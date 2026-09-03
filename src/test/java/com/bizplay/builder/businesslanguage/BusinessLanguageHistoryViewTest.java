package com.bizplay.builder.businesslanguage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLanguageHistoryViewTest {

    @Test
    void 수정이력_화면에_개정목록_변경비교_복원기능이_있다() throws Exception {
        String html = Files.readString(Path.of(
                "src/main/resources/templates/artifacts/business-language-history.html"));

        assertThat(html)
                .contains("정책·표준용어 수정이력")
                .contains("개정 목록")
                .contains("이전 내용", "변경 내용")
                .contains("이 버전으로 복원")
                .contains("revision-detail__actions", "로 돌아가기")
                .contains("history/restore");
    }

    @Test
    void 두_문서의_기본_화면에서_수정이력으로_갈_수_있다() throws Exception {
        String html = Files.readString(Path.of(
                "src/main/resources/templates/artifacts/business-language.html"));

        assertThat(html).contains(
                "aria-label=\"정책서 수정이력\"",
                "aria-label=\"표준용어 수정이력\"",
                "tab='policy'",
                "tab='terms'");
    }
}
