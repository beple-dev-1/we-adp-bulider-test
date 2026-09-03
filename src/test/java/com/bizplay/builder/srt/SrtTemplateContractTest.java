package com.bizplay.builder.srt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SrtTemplateContractTest {

    @Test
    void 목록은_공통_위계와_표_컨테이너를_사용한다() throws IOException {
        String template = read("src/main/resources/templates/artifacts/srts.html");

        assertThat(template)
                .containsSubsequence("page-head list-page-head", "filter-bar", "table-wrap", "list-table-foot")
                .contains("class=\"data-table data-table--dense srt-list-table\"")
                .contains("filter-bar--srt filter-bar--with-action", "<button class=\"button\" type=\"submit\">검색</button>")
                .contains("tabindex=\"0\"", "aria-label=\"SRT 목록 표\"")
                .contains("<th scope=\"col\">SRT</th>")
                .contains("th:if=\"${showFacets}\">적용 대상</th>", "row.facetNames", "facet-badges")
                .contains("<th scope=\"col\">상태</th>", ">상태</label>", ">상태 전체</option>", "yyyy-MM-dd HH:mm")
                .contains("<th scope=\"col\">담당자</th>", ">담당자</label>", ">담당자 전체</option>")
                .containsSubsequence("for=\"srt-owner\"", "for=\"srt-state\"", "for=\"srt-search\"")
                .doesNotContain("처리 결과", "<th scope=\"col\">연결 작업</th>")
                .doesNotContain("<h2>목록</h2>", "<h2>검색 결과</h2>");
    }

    @Test
    void 등록은_직접_입력과_플로우_업무번호를_한_레이어에서_선택한다() throws IOException {
        String template = read("src/main/resources/templates/artifacts/srt.html");
        String script = read("src/main/resources/static/js/srt.js");

        assertThat(template)
                .contains("id=\"srt-register-dialog\"")
                .contains("for=\"srt-direct-title\"", "for=\"srt-direct-content\"")
                .contains("for=\"srt-flow-task-number\"")
                .contains("name=\"source\"", "name=\"title\"", "name=\"content\"", "name=\"flowTaskNumber\"")
                .contains("<legend>적용 구분</legend>", "name=\"facet\"", "value=\"__ALL__\"")
                .contains("data-srt-facet-all", "data-srt-facet", "availableFacets", "typedFacets")
                .contains("data-srt-register-loading", "등록 및 분석 중")
                .contains("분석이 끝나면 SRT 상세로 전환됩니다.")
                .doesNotContain("첨부파일, 댓글");
        assertThat(script)
                .contains("registerForm.addEventListener(\"submit\"")
                .contains("[data-srt-facet]", "[data-srt-facet-all]")
                .contains("showRegistrationLoading()", "pollRegistrationAnalysis")
                .contains("registrationStatusUrl", "registrationDetailUrl")
                .contains("window.location.assign(status.detailUrl || registrationDetailUrl)");
    }

    @Test
    void 상세는_원문과_개발요청서_생성_수정_삭제_행동을_제공한다() throws IOException {
        String template = read("src/main/resources/templates/artifacts/srt.html");
        String script = read("src/main/resources/static/js/srt.js");
        String shellScript = read("src/main/resources/static/js/shell.js");

        assertThat(template)
                .contains("id=\"srt-detail-dialog\"")
                .contains("detail.attachments", "attachment.displayName(attachmentStat.count)")
                .contains("data-srt-detail-view", "data-srt-edit-view", "id=\"srt-delete-dialog\"")
                .contains("class=\"dialog__notice\"", "button--danger-outline")
                .contains("srt-board--flow")
                .contains("srt-analysis__comment", "정리된 요구사항", "완료 조건")
                .contains("srt-review-panel", "검토 필요", "개발 변경 내용을 확인하기 어렵습니다.")
                .contains("플로우 원문에 개발할 대상과 변경 내용을 보완한 뒤 새 SRT로 등록해 주세요.")
                .contains("AI 분석을 완료하지 못했습니다.")
                .contains("<h3>요청 내용</h3>")
                .containsSubsequence("<h3>요청 내용</h3>", "class=\"srt-board__attachments\"", "<h3>AI 분석</h3>")
                .contains("srt-file-meta", "srt-file-action", "#numbers.formatDecimal")
                .doesNotContain("th:text=\"${attachment.size + ' bytes'}\"")
                .doesNotContain("detail.comments", "srt-comment-list", "srt-comment-meta")
                .contains("개발요청서 생성", ">수정</button>", ">삭제</button>")
                .contains("<button class=\"button\" type=\"button\" data-srt-detail-close th:if=\"${detail.request != null}\">닫기</button>")
                .doesNotContain(">개발요청서 확인</a>")
                .contains("detail.srt.sourceKind.name() == 'DIRECT'", "개발요청서 생성 전까지 삭제할 수 있습니다.")
                .doesNotContain("id=\"srt-edit-flow-task-number\"")
                .doesNotContain("개발요청서를 만들고 있습니다")
                .doesNotContain("AI가 요청을 분석하는 중", ">AI 분석 중</button>")
                .contains("srtAnalysisStatus.state.name() == 'COMPLETE'")
                .contains("id=\"srt-create-request-form\"", "data-page-loading=\"false\"")
                .contains("개발요청서 생성 전까지 수정하거나 삭제할 수 있습니다.")
                .contains("/dev-request", "/update", "/delete")
                .doesNotContain("개발요청 보내기", "/send", "<h3>내용</h3>");
        assertThat(script)
                .contains("showMode(\"edit\")", "showMode(\"detail\")")
                .contains("showWithoutInitialFocus(deleteDialog)")
                .contains("showWithoutInitialFocus(registerDialog)")
                .contains("showWithoutInitialFocus(detailDialog)")
                .contains("document.activeElement.blur()")
                .contains("url.searchParams.delete(\"register\")", "url.searchParams.delete(\"selected\")")
                .contains("window.history.replaceState")
                .containsSubsequence(
                        "const status = await response.json();",
                        "if (!detailDialog.open) return;",
                        "if (status.state === \"COMPLETE\"")
                .contains("if (detailDialog.open) window.setTimeout(pollDevelopmentRequest, 1500);")
                .contains("requestForm.addEventListener(\"submit\"")
                .contains("event.preventDefault()", "showAnalysisState()")
                .contains("headers: { Accept: \"application/json\" }")
                .contains("let shouldAutoNavigate = false")
                .contains("shouldAutoNavigate = true", "shouldAutoNavigate = false")
                .contains("if (shouldAutoNavigate && detailDialog.open)");
        assertThat(shellScript).contains("form.dataset.pageLoading !== \"false\"");
        assertThat(template)
                .doesNotContain("id=\"srt-register-title\" tabindex=\"-1\"")
                .doesNotContain("id=\"srt-detail-title\" tabindex=\"-1\"");
    }

    @Test
    void 개발요청서_생성_중에는_버튼_로딩만_표시한다() throws IOException {
        String template = read("src/main/resources/templates/artifacts/srt.html");

        assertThat(template)
                .contains("data-srt-idle-action", "data-srt-analyzing-action")
                .contains("is-submit-loading", "data-submit-loading=\"개발요청서 준비 중\"")
                .doesNotContain("data-srt-generation-status", "개발요청서를 만들고 있습니다")
                .contains("data-srt-request-error");
    }

    @Test
    void 모바일에서는_표_컨테이너만_가로로_스크롤한다() throws IOException {
        String style = read("src/main/resources/static/css/srt.css");

        assertThat(style)
                .contains("@media (max-width: 720px)")
                .contains(".srt-list-table { min-width:")
                .contains(".srt-detail-dialog[open]", "overflow-y: auto", "scrollbar-gutter: stable")
                .doesNotContain("body { overflow-x: auto", ".app-main { overflow-x: auto");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
