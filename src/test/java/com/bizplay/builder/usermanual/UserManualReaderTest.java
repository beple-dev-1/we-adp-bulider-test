package com.bizplay.builder.usermanual;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserManualReaderTest {

    private final UserManualReader reader = new UserManualReader(new ObjectMapper());

    @Test
    void 구조화_응답을_검증하고_안전한_본문으로_만든다() throws Exception {
        String output = """
                ```json
                {
                  "title": "지급 <내역>",
                  "overview": "지급 & 반려 상태를 확인합니다.",
                  "overviewEvidence": "md:payment-overview",
                  "openingSteps": [{
                    "text": "왼쪽 메뉴에서 지급 관리를 선택합니다.",
                    "evidence": "ia:payment-menu"
                  }],
                  "tasks": [{
                    "title": "지급 내역 확인",
                    "steps": ["조회 조건을 입력합니다.", "조회 버튼을 선택합니다."],
                    "result": "조건에 맞는 지급 내역이 표시됩니다.",
                    "evidence": "html:btnSearch"
                  }],
                  "fields": [{
                    "name": "지급 상태",
                    "description": "현재 처리 상태입니다.",
                    "evidence": "md:payment-status"
                  }],
                  "nextScreens": [{
                    "name": "지급 상세",
                    "description": "선택한 지급 건을 확인합니다.",
                    "evidence": "ia:payment-detail"
                  }]
                }
                ```
                """;

        String html = reader.read(output);

        assertThat(html).startsWith("<h1>지급 &lt;내역&gt;</h1>")
                .contains("<h2>화면 개요</h2>")
                .contains("지급 &amp; 반려 상태를 확인합니다.")
                .contains("<h2>화면 접근 경로</h2>")
                .contains("<h2>주요 사용 방법</h2>")
                .contains("<h2>화면 항목</h2>")
                .contains("<h2>연관 화면</h2>")
                .doesNotContain("<script", "onclick=", "href=");
    }

    @Test
    void 필수_내용이_비거나_형식이_다르면_거절한다() {
        String missingEvidence = """
                {
                  "title":"지급 내역",
                  "overview":"지급 내역을 확인합니다.",
                  "overviewEvidence":"md:payment-overview",
                  "openingSteps":[],
                  "tasks":[{"title":"조회","steps":["조회합니다."],"result":"목록이 보입니다.","evidence":"html"}],
                  "fields":[],
                  "nextScreens":[]
                }
                """;

        assertThatThrownBy(() -> reader.read(missingEvidence))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("근거");
        assertThatThrownBy(() -> reader.read("{\"title\":\"제목\",\"overview\":[]}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("개요");
        assertThatThrownBy(() -> reader.read("{잘못된 JSON}"))
                .isInstanceOf(IOException.class)
                .hasMessage("사용자 매뉴얼 응답 JSON을 읽을 수 없습니다.");
    }

    @Test
    void 기존_HTML은_위험한_요소와_속성_외부_주소를_버리고_감싼다() {
        String legacy = """
                <h1 onclick="alert(1)">기존 매뉴얼</h1>
                <style>body{display:none}</style><script>alert(1)</script>
                <iframe src="https://outside.example"></iframe>
                <img src="data:image/png;base64,iVBORw0KGgo=">
                <form action="https://outside.example"><p>확인할 내용</p><button>전송</button></form>
                <p><a href="https://outside.example">외부 도움말</a></p>
                """;

        String html = reader.renderStandalone(legacy);

        assertThat(html).startsWith("<!doctype html>")
                .contains("<html lang=\"ko\">")
                .contains("font: 14px/1.55")
                .contains("max-width: 1120px")
                .contains("background: var(--workspace)")
                .contains("border-top: 4px solid var(--ink)")
                .contains("border-bottom: 2px solid var(--ink)")
                .contains("img-src data:")
                .contains("@page { size: A4")
                .contains("기존 매뉴얼", "확인할 내용", "외부 도움말")
                .doesNotContain("onclick", "outside.example", "<iframe", "<form", "<button", "<img", "alert(1)",
                        "border-left: 4px solid #62517d");

        var body = Jsoup.parse(html).body();
        assertThat(body.select("script, style, iframe, form, a, button, img, [onclick], [href], [src]")).isEmpty();
    }

    @Test
    void 검증된_캡처를_문서_정보와_개요_뒤에_한_장만_넣는다() {
        String body = """
                <h1>배송 &lt;상세&gt;</h1>
                <h2>개요</h2><p>배송 건을 확인합니다.</p>
                <h2>화면 여는 순서</h2><ol><li>배송 목록에서 선택합니다.</li></ol>
                <h2>할 수 있는 일</h2><h3>반송 처리</h3><ol><li>반송을 선택합니다.</li></ol><p><strong>결과: </strong>반송됩니다.</p>
                <h2>항목 설명</h2><dl><dt>배송 상태</dt><dd>현재 상태입니다.</dd></dl>
                <h2>이어지는 화면</h2><ul><li>배송 목록</li></ul>
                """;
        var meta = new UserManualReader.StandaloneMeta("백오피스 <관리>", "PS-BO-MRC-010-D01-S",
                "bo-delivery-<detail>",
                LocalDate.of(2026, 8, 27));
        var capture = new UserManualReader.VerifiedCapture(pngDataUrl(), 1440, 1000, "제주 <기관>");

        String html = reader.renderStandalone(body, meta, capture);
        var document = Jsoup.parse(html);
        var main = document.selectFirst("main");

        assertThat(main).isNotNull();
        assertThat(main.select("header.manual-head > .manual-kicker").text()).isEqualTo("사용자 매뉴얼");
        assertThat(main.select("header.manual-head > h1").text()).isEqualTo("배송 <상세>");
        assertThat(main.select(".manual-meta dd").eachText())
                .containsExactly("백오피스 <관리>", "PS-BO-MRC-010-D01-S",
                        "bo-delivery-<detail>", "2026-08-27");
        assertThat(main.children().eachText())
                .startsWith("사용자 매뉴얼 배송 <상세> 시스템 백오피스 <관리> 관리번호 PS-BO-MRC-010-D01-S 화면 ID bo-delivery-<detail> 작성일 2026-08-27",
                        "개요", "배송 건을 확인합니다.", "화면 이미지 실제 화면 · 제주 <기관>", "화면 여는 순서");

        var image = main.selectFirst("figure.manual-capture img");
        assertThat(image).isNotNull();
        assertThat(image.attr("src")).isEqualTo(pngDataUrl());
        assertThat(image.attr("alt")).isEqualTo("배송 <상세> · 제주 <기관> 실제 화면");
        assertThat(image.attr("width")).isEqualTo("1440");
        assertThat(image.attr("height")).isEqualTo("1000");
        assertThat(main.select("figure.manual-capture")).hasSize(1);
        assertThat(html).contains("max-width: 1120px", "@page { size: A4", "counter-increment: manual-section",
                        "max-width: 100%", "height: auto", "img-src data:",
                        "class=\"manual-foot\"", "운영 화면 · 화면 명세 기준", "사용자 매뉴얼 · 2026-08-27")
                .doesNotContain("백오피스 <관리>", "bo-delivery-<detail>", "제주 <기관>");
    }

    @Test
    void 안전하지_않은_캡처_주소와_PNG가_아닌_내용은_거절한다() {
        var meta = new UserManualReader.StandaloneMeta("백오피스", "PS-BO-MRC-010-D01-S",
                "bo-detail", LocalDate.of(2026, 8, 27));
        String body = "<h1>배송 상세</h1><h2>개요</h2><p>배송을 확인합니다.</p>";

        assertThatThrownBy(() -> reader.renderStandalone(body, meta,
                new UserManualReader.VerifiedCapture("https://outside.example/screen.png", 1440, 1000, "기본 화면")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PNG");
        assertThatThrownBy(() -> reader.renderStandalone(body, meta,
                new UserManualReader.VerifiedCapture("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=", 1440, 1000,
                        "기본 화면")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PNG");
        assertThatThrownBy(() -> reader.renderStandalone(body, meta,
                new UserManualReader.VerifiedCapture("data:image/png;base64,PHNjcmlwdD5ubzwvc2NyaXB0Pg==", 1440,
                        1000, "기본 화면")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PNG");
    }

    @Test
    void 빈_기존_본문도_읽을_수_있는_문서로_만든다() {
        String html = reader.renderStandalone("<script>나쁜 내용</script>");

        assertThat(html).contains("<h1>사용자 매뉴얼</h1>")
                .contains("매뉴얼 내용이 없습니다.")
                .doesNotContain("나쁜 내용");
    }

    @Test
    void 기존_호출에는_문서_정보와_캡처를_추가하지_않는다() {
        String html = reader.renderStandalone("<h1>기존 매뉴얼</h1><p>기존 내용</p>");

        assertThat(Jsoup.parse(html).body().select(".manual-meta, .manual-capture")).isEmpty();
    }

    @Test
    void 캡처가_없는_과거_정상본에도_표준_문서_정보를_넣는다() {
        var meta = new UserManualReader.StandaloneMeta("백오피스", "PS-BO-MRC-010-D01-S",
                "bo-delivery-detail", LocalDate.of(2026, 8, 27));

        String html = reader.renderStandalone("<h1>기존 매뉴얼</h1><p>기존 내용</p>", meta);
        var document = Jsoup.parse(html);

        assertThat(document.select(".manual-meta dd").eachText())
                .containsExactly("백오피스", "PS-BO-MRC-010-D01-S", "bo-delivery-detail", "2026-08-27");
        assertThat(document.select(".manual-capture")).isEmpty();
        assertThat(document.select("footer.manual-foot")).hasSize(1);
    }

    private String pngDataUrl() {
        return "data:image/png;base64,"
                + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    }
}
