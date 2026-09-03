package com.bizplay.builder.screenid;

import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.project.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ⛔ <b>이 스위치의 유일한 실패 방식은 둘 다 뜨는 것이다</b> — {@code BusinessAreaCoder} 가 둘이면
 * {@code ScreenStandardIdService} 주입이 애매해져 <b>앱이 아예 안 뜬다.</b> 그래서 개수를 잰다.
 */
class BusinessAreaCoderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ClaudeCredentialRunner.class, () -> mock(ClaudeCredentialRunner.class))
            .withBean(ProjectPaths.class, () -> mock(ProjectPaths.class))
            .withUserConfiguration(ClaudeBusinessAreaCoder.class);

    @Test
    void 설정과_관계없이_Claude_하나만_뜬다() {
        runner.withPropertyValues("builder.screen-id.business-area-coder=gemini")
                .run(context -> assertThat(context.getBeansOfType(BusinessAreaCoder.class).values())
                        .hasSize(1)
                        .allMatch(ClaudeBusinessAreaCoder.class::isInstance));
    }
}
