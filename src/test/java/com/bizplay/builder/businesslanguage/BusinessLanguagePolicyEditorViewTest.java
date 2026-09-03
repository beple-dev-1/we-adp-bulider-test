package com.bizplay.builder.businesslanguage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLanguagePolicyEditorViewTest {

    @Test
    void 편집_화면에서_정책_항목을_추가하고_왼쪽_목록을_갱신한다() throws Exception {
        String html = Files.readString(Path.of(
                "src/main/resources/templates/artifacts/business-language.html"));
        String javascript = Files.readString(Path.of(
                "src/main/resources/static/js/business-language.js"));

        assertThat(html)
                .contains("type=\"button\" data-add-policy-section")
                .contains("data-policy-index-link");
        assertThat(javascript)
                .contains("document.createElement('h2')")
                .contains("policyEditor.append(heading, content)")
                .contains("refreshPolicyIndex()")
                .contains("range.selectNodeContents(heading)");
    }
}
