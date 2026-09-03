package com.bizplay.builder.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 두 층으로 본다 — 위쪽은 스프링 없이 생성자만, 아래쪽은 스프링이 **값을 묶을 때**.
 * 앞의 것만 있으면 {@code @Validated}·{@code @NotBlank} 를 지워도 다 통과한다.
 *
 * ⚠ 이 테스트를 「앱을 띄워서 확인하는」 모양으로 되돌리지 마라 — 셋이 한꺼번에 깨진다:
 *   ① 데이터소스 자동설정을 빼면 JPA 리포지토리도 같이 사라져 Task 5 부터 컨텍스트가 안 뜬다
 *   ② 8080 을 세 번 문다 — 개발 서버가 떠 있으면 전부 죽는다
 *   ③ 터진 메시지가 BeanCreationException 으로 덮여 한국어 문구 단언이 안 맞는다
 */
class BuilderPropertiesTest {

    private BuilderProperties build(String key) {
        return new BuilderProperties(
                "admin",
                "firstpass",
                key,
                Path.of(System.getProperty("java.io.tmpdir"), "builder-data"),
                Duration.ofMinutes(10),
                4,
                50,
                Duration.ofMinutes(2));
    }

    @Test
    void 열쇠가_32바이트가_아니면_안_뜬다() {
        assertThatThrownBy(() -> build("c2hvcnQ="))          // "short" = 5바이트
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("열쇠는 32바이트");
    }

    @Test
    void 열쇠가_Base64_가_아니면_안_뜬다() {
        assertThatThrownBy(() -> build("이건 Base64 가 아니다"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64 가 아니다");
    }

    @Test
    void 열쇠가_비면_compact_생성자가_가로채지_않는다() {
        // 빈 값은 @NotBlank 가 잡는다. 여기서 가로채면 「없다」와 「틀렸다」가 한 문구로 뭉개진다.
        assertThatNoException().isThrownBy(() -> build(null));
        assertThatNoException().isThrownBy(() -> build(""));
    }

    @Test
    void 다_있으면_뜬다() {
        BuilderProperties props = build("A".repeat(42) + "g=");
        assertThat(props.aiRunTimeout().toMinutes()).isEqualTo(10);
        assertThat(props.dataRoot()).isNotNull();
    }

    // ── 여기부터는 스프링이 값을 **묶을 때** 무슨 일이 나는지 본다. ──────────────────
    // 위 생성자 시험만 있으면 @Validated 나 @NotBlank 를 지워도 전부 통과한다.
    // 컨텍스트 전체를 띄우지 않고 이 설정 하나만 묶어보는 러너를 쓴다 — DB 도 포트도 안 쓴다.

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @EnableConfigurationProperties(BuilderProperties.class)
    static class PropertiesOnly {
    }

    private String[] values(String... overrides) {
        var defaults = new java.util.ArrayList<>(java.util.List.of(
                "builder.super-account-login-id=admin",
                "builder.super-account-password=firstpass",
                "builder.secret-key-base64=" + "A".repeat(42) + "g=",
                "builder.data-root=" + System.getProperty("java.io.tmpdir"),
                "builder.ai-run-timeout=10m",
                // ⚠ 러너는 application.yml 을 안 읽는다 — 여기 없으면 0 으로 묶여 @Positive 가 잡는다.
                "builder.ai-concurrency=4",
                "builder.ai-queue-capacity=50",
                "builder.check-timeout=2m"));
        defaults.addAll(java.util.List.of(overrides));
        return defaults.toArray(String[]::new);
    }

    @Test
    void 열쇠가_없으면_서버가_안_뜬다() {
        contextRunner.withPropertyValues(values("builder.secret-key-base64="))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 잘못된_열쇠는_묶을_때_터진다() {
        contextRunner.withPropertyValues(values("builder.secret-key-base64=c2hvcnQ="))
                .run(ctx -> assertThat(ctx).getFailure()
                        .hasStackTraceContaining("열쇠는 32바이트"));
    }

    @Test
    void 다_있으면_묶인다() {
        contextRunner.withPropertyValues(values())
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .getBean(BuilderProperties.class)
                        .extracting(BuilderProperties::aiRunTimeout)
                        .isEqualTo(Duration.ofMinutes(10)));
    }
}
