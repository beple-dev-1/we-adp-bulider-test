package com.bizplay.builder.solution;

import com.bizplay.builder.project.PlanningManifestReader.ManifestSystem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 시점 기관 스킨 치환 — <b>「제주로 보자」가 실제로 제주 css 를 부르나.</b>
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-preview-skin-design.md}.
 * 계약의 출처는 추출기 회신 #5(추출기 {@code 80530f9} · 기획 레포 {@code 568586b}) —
 * <b>마크업이 갈리면 추출기(갈래 목업), 스타일만 갈리면 빌더(렌더 시점 치환)</b>다.
 *
 * <p>⛔ <b>여기에 {@code iks}·{@code tnj} 를 아는 코드를 두지 마라.</b> 그 글자는 시험이
 * 지어낸 씨앗일 뿐이고, 실물에서는 {@code manifest.json} 의 {@code systems[].skins} 가
 * 유일한 출처다 — 빌더가 그 매핑을 쥐면 g2c 를 아는 것이 된다.
 */
class SkinRewriterTest {

    /**
     * g2c 실물과 같은 모양의 선언이다. 방향이 시스템마다 반대인 것까지 그대로 뒀다.
     *
     * <p>⚠ 뒤 두 칸({@code styleguide}·{@code shell})은 <b>이 시험과 무관해서</b> {@code null} 이다 —
     * 스킨 치환은 css 경로만 본다. 빠뜨린 값이 아니다.
     */
    private static final List<ManifestSystem> SYSTEMS = List.of(
            new ManifestSystem("webview", "wv", Map.of(
                    "iksan", "core/webview/assets/css/iks",
                    "jeju", "core/webview/assets/css/tnj"), null, null),
            new ManifestSystem("portal", "pt", Map.of(
                    "jeju", "core/portal/assets/css/tnj"), null, null),
            new ManifestSystem("backoffice", "bo", Map.of(), null, null));

    // ── 갈리는 것 ─────────────────────────────────────────────────────────

    @Test
    void 익산_목업을_제주로_그리면_스킨_폴더만_갈린다() {
        String html = """
                <link rel="stylesheet" href="../assets/css/iks/ui.base.css">
                """;

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", "jeju", html);

        assertThat(drawn).contains("href=\"../assets/css/tnj/ui.base.css\"");
    }

    /**
     * ⚠ 실물 목업이 {@code ?ver=3.4} 를 달고 부른다. 떼고 판단하되 <b>도로 붙여야</b> 한다 —
     * 안 그러면 브라우저 캐시가 갈린 판을 안 받는다.
     */
    @Test
    void 질의문자열은_판단에서만_빼고_링크에는_살려_둔다() {
        String html = "<link href=\"../assets/css/iks/ui.theme.css?ver=3.4\">";

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", "jeju", html);

        assertThat(drawn).contains("../assets/css/tnj/ui.theme.css?ver=3.4");
    }

    /**
     * 갈래 목업은 폴더가 한 마디 다른 자리에 산다. <b>기준 폴더가 바뀌어도 같은 답</b>이라야
     * 「갈래 화면은 치환이 필요 없다」(그쪽 {@code SKIN-2})가 성립한다.
     */
    @Test
    void 갈래_폴더에서_불러도_상대경로가_제자리를_찾는다() {
        String html = "<link href=\"../assets/css/iks/ui.base.css\">";

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/webview/variants-jeju", "jeju", html);

        assertThat(drawn).contains("../assets/css/tnj/ui.base.css");
    }

    // ── 안 갈리는 것 ──────────────────────────────────────────────────────

    /**
     * ⛔ <b>이 레포의 사실이 아닌 주소는 손대지 않는다.</b> 절대주소·프로토콜·{@code tel:} 은
     * 치환의 대상이 아니고, 우리가 끼운 {@code <base href>} 도 여기에 걸려 안전하다.
     */
    @Test
    void 밖으로_나가는_주소는_안_건드린다() {
        String html = """
                <base href="/projects/0000001/artifacts/solution-mockups/files/webview/pages/">
                <link href="https://cdn.example.com/css/iks/x.css">
                <script src="//cdn.example.com/iks/a.js"></script>
                <a href="tel:1600-3971">전화</a>
                <img src="data:image/svg+xml;base64,AAAA">
                """;

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", "jeju", html);

        assertThat(drawn).isEqualTo(html);
    }

    /**
     * ⭐ <b>SKIN-3 두 장이 여기서 굳는다.</b> 공통 목업이 스킨 폴더 <b>밖에서</b> 기관 그림을
     * 직접 부르는 자리다({@code tnj-card-1.png}). css 를 갈아도 그 그림은 안 갈린다 —
     * 소스가 박아 둔 것이고, <b>우리가 이름으로 짐작해 고치면 지어내는 것</b>이 된다.
     */
    @Test
    void 스킨_폴더_밖의_기관_자산은_안_갈린다() {
        String html = "<img src=\"../assets/images/card/tnj-card-1.png\">";

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", "iksan", html);

        assertThat(drawn).isEqualTo(html);
    }

    /** ⛔ 없는 스킨을 지어내지 않는다 — {@code portal} 은 제주만 실재한다. */
    @Test
    void 대상_기관의_스킨이_없으면_원문_그대로다() {
        String html = "<link href=\"../assets/css/tnj/ui.base.css\">";

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/portal/pages", "iksan", html);

        assertThat(drawn).isEqualTo(html);
    }

    @Test
    void 스킨을_선언하지_않은_시스템은_원문_그대로다() {
        String html = "<link href=\"../assets/css/style.css\">";

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/backoffice/pages", "jeju", html);

        assertThat(drawn).isEqualTo(html);
    }

    @Test
    void 이미_그_기관이면_아무것도_안_한다() {
        String html = "<link href=\"../assets/css/iks/ui.base.css\">";

        String drawn = SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", "iksan", html);

        assertThat(drawn).isEqualTo(html);
    }

    /**
     * ⚠ <b>못 정했으면 안 갈아낀다</b>(설계 §3 의 넷째 칸). 기본 기관을 지어내면
     * {@code online-pg} 8장이 통째로 틀린다 — 방향이 시스템마다 반대다.
     */
    @Test
    void 기관을_못_정하면_손대지_않는다() {
        String html = "<link href=\"../assets/css/iks/ui.base.css\">";

        assertThat(SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", null, html)).isEqualTo(html);
        assertThat(SkinRewriter.rewrite(SYSTEMS, "core/webview/pages", " ", html)).isEqualTo(html);
    }

    @Test
    void 선언이_없으면_원문_그대로다() {
        String html = "<link href=\"../assets/css/iks/ui.base.css\">";

        assertThat(SkinRewriter.rewrite(List.of(), "core/webview/pages", "jeju", html)).isEqualTo(html);
    }
}
