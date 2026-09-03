package com.bizplay.builder.screendesign;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 화면설계서 목록·상세의 정적 화면 계약. */
class ScreenDesignTemplateContractTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void 목록은_공통_목록_순서와_접근성_계약을_지킨다() throws IOException {
        String html = read("templates/artifacts/screen-designs.html");

        assertThat(html)
                .containsSubsequence("page-head list-page-head", "filter-bar filter-bar--screen-design",
                        "table-wrap artifact-list-table-wrap", "list-table-foot")
                .contains("<h1 class=\"page-head__title\">화면설계서</h1>")
                .contains("state-panel list-empty-state", "조회된 내용이 없습니다.")
                .contains("for=\"screen-design-system\"", "for=\"screen-design-state\"",
                        "for=\"screen-design-search\"")
                .containsSubsequence("<th scope=\"col\">화면명</th>",
                        "<th scope=\"col\">화면관리번호</th>",
                        "<th scope=\"col\">화면 ID</th>",
                        "<th scope=\"col\">IA 메뉴 경로</th>",
                        "<th scope=\"col\">시스템</th>",
                        "<th scope=\"col\">생성여부</th>",
                        "<th scope=\"col\">운영 화면 수정일</th>",
                        "<th scope=\"col\">문서 작성일</th>")
                .contains("screen-design-table-wrap", "status-badge", "selectedSystem", "selectedScreen",
                        "th:if=\"${layerOpen}\"", "artifacts/screen-design :: layer")
                .contains("aria-current=${number == page} ? 'page' : null")
                .doesNotContain("summary-strip", "전체 화면", "확인 가능", "확인 필요", "화면 최종 수정",
                        "문서 상태", "일괄 생성", "다시 만들기", "설계서 만들기", "checkbox");
    }

    @Test
    void 상세는_한_문서_상태와_제출용_문서를_낸다() throws IOException {
        String html = read("templates/artifacts/screen-design.html");

        assertThat(html)
                .contains("data-screen-design-poll", "화면설계서를 작성하고 있습니다",
                        "artifact-generation", "artifact-generation-paper",
                        "화면설계서를 만들지 못했습니다", "dialog dialog--layer",
                        "id=\"screen-design-dialog\"", "aria-modal=\"true\"",
                        "id=\"screen-design-dialog-title\" tabindex=\"-1\"",
                        "data-screen-design-close", "class=\"screen-design-paper official-document\"",
                        "class=\"official-document__head\"",
                        "class=\"official-document__kind\">화면설계서</p>",
                        "class=\"official-document__title\"",
                        "class=\"official-document__meta\"",
                        "class=\"official-document__body screen-design-paper__body\"",
                        "class=\"official-document__foot\"",
                        "${systemLabel} + ' · ' + ${managementNumber}",
                        "화면 HTML · 화면 명세 · IA 기준", "화면설계서 ' + document.revisionNo + '차 · '",
                        "1. 화면 개요", "2. 화면 구성",
                        "screenDesignContent.purpose", "screenDesignContent.menuPath",
                        "documentBody", "capture.imageUrl", "capture.label")
                .contains(">닫기</button>")
                .doesNotContain("solution-detail screen-design-detail", "rq-head", "목록으로",
                        "rq-meta", "preview-stage screen-design-preview", "screen-design-official",
                        "page-head__title", "일괄 생성",
                        "화면설계서 만들기", "화면설계서 내려받기", "downloadUrl",
                        ">AI<", "해시", "sourceSpecification", "${content.");
    }

    @Test
    void 모바일은_페이지가_아니라_표와_문서_내부만_가로로_스크롤한다() throws IOException {
        String css = read("static/css/screen-design.css");

        assertThat(css)
                .contains(".screen-design-table-wrap", "overflow-x: auto", "min-width: 1265px",
                        ".screen-design-col-management")
                .contains(".screen-design-capture-canvas {\n  max-width: 100%;\n  overflow: hidden",
                        ".screen-design-capture-canvas img {\n  width: 100%",
                        "max-width: 100%;\n  height: auto")
                .contains("@media (max-width: 600px)", "calc(100vw - 32px)",
                        "margin: var(--space-8) 0 var(--space-3)",
                        ".screen-design-navigation > h2", ".screen-design-variants > h2",
                        "grid-template-columns: 160px minmax(0, 1fr)",
                        "min-width: 860px", "line-height: 1.55")
                .doesNotContain("font-size: 16px", "overflow-x: auto;\n  }\n\n  body",
                        "min-width: 840px", "min-width: min(960px, 100%)",
                        "screen-design-paper__cover", "screen-design-paper__foot");
    }

    @Test
    void 산출물_생성_상태는_실제_간격_토큰으로_카드와_문서_사이를_띄운다() throws IOException {
        String css = read("static/css/components.css");

        assertThat(css)
                .contains(".artifact-generation-layout {", "gap: 20px",
                        "padding: var(--space-6) var(--space-8)",
                        ".artifact-generation__track {", "margin-top: 20px")
                .doesNotContain(".artifact-generation { padding: var(--space-5)",
                        "padding: var(--space-6) var(--space-7)");
    }

    @Test
    void 목록은_화면관리번호를_조회하고_화면_ID_순으로_열_개씩_나눈다() throws IOException {
        String controller = Files.readString(Path.of("src", "main", "java", "com", "bizplay", "builder",
                "screendesign", "ScreenDesignController.java"), StandardCharsets.UTF_8);

        assertThat(controller)
                .contains("List.of(10, 20, 50, 100)", "defaultValue = \"10\"",
                        "standardIds.selectByProject(projectId)", "StandardScreenIdFormat.display",
                        "managementNumbers.getOrDefault(screen.screenId(), \"—\")",
                        "model.addAttribute(\"managementNumber\", managementNumber(projectId, screenId))",
                        ".sorted(Comparator.comparing(row -> row.screen().screenId()))",
                        "lower(row.managementNumber()).contains(needle)")
                .contains("if (current == null) return \"미생성\";",
                        "revision == null ? \"준비 중\" : \"업데이트 중\"",
                        "revision == null ? \"생성 실패\" : \"업데이트 실패\"",
                        "revision == null ? \"미생성\" : \"완료\"")
                .doesNotContain("return \"작성 전\"", "? \"작성 중\" : \"갱신 중\"",
                        "최신 자료 반영 실패");
    }

    @Test
    void 생성_상태는_완료될_때만_화면을_새로_읽는다() throws IOException {
        String script = read("static/js/screen-design.js");

        assertThat(script)
                .contains("dataset.screenDesignPoll", "Accept: 'application/json'", ".complete",
                        "window.location.reload()", "2500", "screen-design-dialog",
                        "data-screen-design-close", "dialog.showModal()", "selectedSystem", "selectedScreen",
                        "dialog.addEventListener('cancel'", "event.target === dialog")
                .doesNotContain("location.reload();\n  window.setInterval");
    }

    @Test
    void 이전_상세_주소는_목록_레이어_주소로_전환한다() throws IOException {
        String controller = Files.readString(Path.of("src", "main", "java", "com", "bizplay", "builder",
                "screendesign", "ScreenDesignController.java"), StandardCharsets.UTF_8);

        assertThat(controller)
                .contains("RedirectAttributes redirect", "redirect.addAttribute(\"selectedSystem\", systemCode)",
                        "redirect.addAttribute(\"selectedScreen\", screenId)",
                        "populateLayer(projectId, selectedSystem, selectedScreen, model)",
                        "redirect:/projects/\" + projectId + \"/artifacts/screen-designs");
    }

    @Test
    void 캡처와_PDF는_화면의_현재본이_아니라_표시한_개정판을_가리킨다() throws IOException {
        String controller = Files.readString(Path.of("src", "main", "java", "com", "bizplay", "builder",
                "screendesign", "ScreenDesignController.java"), StandardCharsets.UTF_8);

        assertThat(controller)
                .contains("/revisions/{revisionId}/captures/{name}",
                        "/revisions/{revisionId}/download", "view.revision().revisionId()",
                        "current.state() != ScreenDesignState.DONE",
                        "!revisionId.equals(current.currentRevisionId())",
                        "name.toLowerCase(Locale.ROOT).endsWith(\".png\")",
                        "name.equals(item.imageFile())")
                .doesNotContain("/{systemCode}/{screenId}/captures/{name}");
    }

    private String read(String relative) throws IOException {
        return Files.readString(RESOURCES.resolve(relative), StandardCharsets.UTF_8);
    }
}
