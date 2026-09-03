package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신규 화면 자리표시자가 실제 화면ID 로 바뀌나.
 *
 * <p>⭐ <b>실물에서 발견 (2026-08-25 DR-012).</b> 프롬프트는 「{@code {{draftKey}}} 를 적어라」였고 치환기는
 * {@code {{<실제 키>}}} 만 찾았다 — AI 는 시키는 대로 글자 {@code {{draftKey}}} 를 적었고 그것이 그대로
 * 남아 신규 화면마다 검사기 A-2(파일명과 {@code data-screen-id} 불일치)가 떴다.
 */
class FrdCanvasChatWorkerTest {

    private static final Map<String, String> NEW_IDS = Map.of("complete", "tmp-0000051", "detail", "tmp-0000052");

    @Test
    void 자기_키를_감싼_자리표시자는_그_화면ID_로_바뀐다() {
        String html = "<body data-screen-id=\"{{complete}}\"><a href=\"{{detail}}.html\">";

        assertThat(FrdCanvasChatWorker.substituteDraftIds(html, "complete", NEW_IDS))
                .isEqualTo("<body data-screen-id=\"tmp-0000051\"><a href=\"tmp-0000052.html\">");
    }

    @Test
    void 글자_그대로의_draftKey_는_그_파일_자신의_화면ID_로_읽는다() {
        // AI 가 프롬프트의 예시 글자를 그대로 적은 경우 — 자기 파일 안에서는 뜻이 하나뿐이다.
        String html = "<body data-screen-id=\"{{draftKey}}\">";

        assertThat(FrdCanvasChatWorker.substituteDraftIds(html, "complete", NEW_IDS))
                .isEqualTo("<body data-screen-id=\"tmp-0000051\">");
    }

    @Test
    void 남의_파일에_남은_글자_그대로의_draftKey_는_건드리지_않는다() {
        // 기존 화면 md 에 남은 {{draftKey}} 는 어느 신규 화면인지 알 수 없다 — 지어내지 않는다.
        String md = "이동: {{draftKey}}";

        assertThat(FrdCanvasChatWorker.substituteDraftIds(md, null, NEW_IDS)).isEqualTo(md);
    }
}
