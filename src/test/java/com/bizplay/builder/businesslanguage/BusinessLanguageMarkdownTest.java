package com.bizplay.builder.businesslanguage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessLanguageMarkdownTest {

    private final BusinessLanguageMarkdown markdown = new BusinessLanguageMarkdown();

    @Test
    void 정책서_본문은_스크립트를_실행할_수_없는_HTML로_바꾼다() {
        String html = markdown.policyHtml("## 1. 신청 기준\n<script>alert(1)</script>");

        assertThat(html).contains("<h2>1. 신청 기준</h2>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>");
    }

    @Test
    void 표준용어는_네_열의_Markdown_표로_왕복한다() {
        var source = List.of(new StandardTerm("접수", "신청을 받는 일", "신청 받기", ""));

        String document = markdown.termsMarkdown(source);

        assertThat(document).contains("| 표준용어 | 용어 정의 | 동의어·유사어 |");
        assertThat(markdown.terms(document)).containsExactlyElementsOf(source);
    }

    @Test
    void 빈_정책서는_저장하지_않는다() {
        assertThatThrownBy(() -> markdown.normalizePolicy("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정책서 내용을 입력해 주세요.");
    }

    @Test
    void 기존_표_머리의_공백이_달라도_용어로_읽지_않는다() {
        String document = """
                # 표준용어
                |표준용어|뜻|달리 부르는 말|사용하지 않을 표현|
                |---|---|---|---|
                | 접수 | 신청을 받는 일 | | |
                """;

        assertThat(markdown.terms(document)).extracting(StandardTerm::term).containsExactly("접수");
    }

    @Test
    void 기존_비표준_용어_열은_동의어에_합치지_않고_제외한다() {
        String document = """
                # 표준용어
                | 표준용어 | 용어 정의 | 동의어·유사어 | 비표준 용어 |
                | --- | --- | --- | --- |
                | 이용기관 | 서비스를 운영하는 기관 | 발행기관 | usagId, usagCd |
                """;

        assertThat(markdown.terms(document)).containsExactly(
                new StandardTerm("이용기관", "서비스를 운영하는 기관", "발행기관", ""));
    }
}
