package com.bizplay.builder.businesslanguage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLanguageFailureViewTest {

    @Test
    void 실패_코드를_사용자가_할_수_있는_행동으로_설명한다() {
        assertThat(BusinessLanguageController.seedFailureMessage("INVALID_STANDARD_TERMS"))
                .contains("표준용어").contains("다시 만들어 주세요");
        assertThat(BusinessLanguageController.seedFailureMessage("CREDENTIAL_LOST"))
                .contains("다시 연결");
        assertThat(BusinessLanguageController.seedFailureMessage("INCOMPLETE_SOURCE_COVERAGE"))
                .contains("업무 문서와 화면 자료").contains("모두 확인하지 못했습니다");
        assertThat(BusinessLanguageController.seedFailureMessage("QUEUE_REJECTED"))
                .contains("AI 작업이 많습니다").contains("잠시 뒤");
        assertThat(BusinessLanguageController.seedFailureMessage("SERVER_RESTARTED"))
                .contains("서버가 다시 시작").contains("다시 만들어 주세요");
    }

    @Test
    void 실패_화면은_공통_빈_상태와_실제_실패_문구를_쓴다() throws Exception {
        String html = Files.readString(Path.of(
                "src/main/resources/templates/artifacts/business-language.html"));

        assertThat(html)
                .contains("class=\"empty-state business-language-error\"")
                .contains("th:text=\"${seedFailureMessage}\"")
                .contains("aria-labelledby=\"business-language-error-title\"");
    }
}
