package com.bizplay.builder.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * 설치할 때 사람이 채우는 값들. 없으면 서버가 안 뜬다.
 *
 * <p>세 문서가 한 자리를 가리켰다 — {@code project-setup} 이 「빌더를 올릴 때 설정에
 * 아이디·비밀번호가 적혀 있다」, {@code planner-account} 가 「푸는 열쇠는 설치 설정에 있고
 * 저장소에는 없다」, {@code ai-run} 이 「시간 상한 값은 설치 설정에 적는다」고 했다.
 *
 * @param aiConcurrency   AI 실행을 <b>동시에 몇 개</b>까지 돌리나.
 *                        ⛔ <b>1 로 두지 마라</b> — 잠기는 것은 「한 일」이지 서버 전체가 아니다.
 *                        1 이면 다른 BRD·다른 사람이 앞사람 10분을 한 줄로 기다린다
 * @param aiQueueCapacity 자리가 다 찼을 때 <b>줄에 세울 수 있는 수</b>. 넘으면 제출이 거절되고
 *                        그 실행은 실패로 닫힌다 — ⛔ 비동기로 넘긴다고 자원 상한이 없어지지 않는다
 * @param checkTimeout    기획 레포 검사기 한 번의 상한. <b>전체 검사가 ~0.95초</b>였고(263화면 실측)
 *                        저장마다 두 번 돈다. ⚠ 첫 회에는 {@code npm install} 이 여기 같이 들어가므로
 *                        검사 자체보다 넉넉해야 한다 — ⛔ 클론의 30분을 물려받지 마라
 */
@Validated
@ConfigurationProperties(prefix = "builder")
public record BuilderProperties(
        @NotBlank String superAccountLoginId,
        @NotBlank String superAccountPassword,
        @NotBlank String secretKeyBase64,
        @NotNull Path dataRoot,
        @NotNull Duration aiRunTimeout,
        @Positive int aiConcurrency,
        @PositiveOrZero int aiQueueCapacity,
        @NotNull Duration checkTimeout) {

    /**
     * record 의 compact 생성자다 — 스프링이 값을 묶어 이 객체를 만드는 순간 돈다.
     *
     * <p>⚠ 이것을 {@code @PostConstruct} 메서드로 되돌리지 마라. 그러면 이 검사를 확인하려고
     * 테스트가 스프링 컨텍스트를 통째로 띄워야 하고, 터졌을 때 나오는 메시지가
     * {@code BeanCreationException: Invocation of init method failed} 로 덮여 한국어 문구가 사라진다.
     * 빈 값은 여기서 가로채지 않는다 — 그 자리는 {@code @NotBlank} 가 맡는다.
     */
    public BuilderProperties {
        if (secretKeyBase64 != null && !secretKeyBase64.isBlank()) {
            byte[] key;
            try {
                key = Base64.getDecoder().decode(secretKeyBase64);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("builder.secret-key-base64 가 Base64 가 아니다", e);
            }
            if (key.length != 32) {
                throw new IllegalStateException(
                        "builder.secret-key-base64: 열쇠는 32바이트여야 한다 — 지금 " + key.length + "바이트");
            }
        }
    }
}
