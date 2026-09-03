package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevRequestTemplateContractTest {

    @Test
    void 개발_결과_반영은_목록이_아니라_개발요청서_상세에서_실행한다() throws IOException {
        String detail = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);
        String list = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-requests.html"),
                StandardCharsets.UTF_8);

        assertThat(detail)
                .contains("request.canMergeDevelopment()")
                .contains(">개발 결과 반영</button>");
        assertThat(list)
                .contains("class=\"data-table data-table--dense dev-request-table\"")
                .contains("dev-request-row--merge-required")
                .doesNotContain(">개발 결과 반영</button>", "<th scope=\"col\">병합</th>",
                        "병합 필요", "병합 완료");
    }

    @Test
    void 생성_진행은_보내기_전_확인에_표시하고_개발요청은_잠시_막는다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("dev-request-gate--working")
                .contains("변경 예정 기능정의서를 만들고 있습니다.")
                .contains("data-pending=${view.generating()}")
                .contains("!view.generating() and precheck.sendable()")
                .contains("th:disabled=\"${view.generating()}\"")
                .doesNotContain("dev-request-gate__spinner");
    }

    @Test
    void 보내기_전_확인은_전송을_막는_상태만_표시한다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("request.deliveryState().name() != 'SENT' and (view.generating() or !precheck.blocking().isEmpty())")
                .contains("th:each=\"item : ${precheck.blocking()}\"")
                .doesNotContain("th:each=\"item : ${precheck.warnings()}\"")
                .doesNotContain("전송 가능 · 확인")
                .doesNotContain("dev-request-gate__ready");
    }

    @Test
    void 메뉴_위치_미연결은_해당_화면명_옆_안내_아이콘으로_표시한다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("dev-request-menu-notice")
                .contains("screen.menuPath() == null || screen.menuPath().isBlank()")
                .contains("정식 메뉴 위치가 연결되지 않았습니다. 개발요청 전송을 막지 않으며 관리자가 메뉴구조도에서 나중에 연결할 수 있습니다.")
                .contains("th:each=\"note : ${content.openIssues()}\"");
    }

    @Test
    void 화면과_화면_외_구현은_비교할_수_있는_표로_표시한다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("class=\"table-wrap dev-request-table-wrap\"")
                .contains("class=\"data-table dev-request-detail-table dev-request-screen-table\"")
                .contains("class=\"data-table dev-request-detail-table dev-request-backend-table\"")
                .contains("class=\"badge badge--info\"")
                .doesNotContain("screen.deliveryFileName()", ">파일명<")
                .contains("<th scope=\"col\">대상 화면</th>")
                .contains("<th scope=\"col\">화면 식별 정보</th>")
                .contains("<th scope=\"col\">구분</th>")
                .contains("<th scope=\"col\">구현 대상</th>")
                .contains("<th scope=\"col\">변경 내용</th>")
                .contains("<th scope=\"row\" class=\"dev-request-screen-name\"")
                .contains("<th scope=\"row\" th:text=\"${change.target() == null || change.target().isBlank() ? '-' : change.target()}\"")
                .doesNotContain("dev-request-screen-list", "dev-request-backend-list", "dev-request-category");
    }

    @Test
    void 요청_원문과_인터뷰_요구사항_요약을_구분해_표시한다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("요구사항 요약", "요청 원문")
                .contains("content.interviewSummary()")
                .contains("content.summary()")
                .doesNotContain("th:if=\"${content.interviewSummary() != null and !content.interviewSummary().isBlank()}\"");
    }

    @Test
    void 전송을_시작하면_대화창을_닫아_페이지_로딩_표시가_앞에_보인다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("request.deliveryState().name() == 'SENT' and (request.developmentState() == null or request.developmentState().name() == 'INTAKE')")
                .contains("form?.addEventListener('submit'")
                .contains("if (!event.defaultPrevented) dialog.close()");
    }

    @Test
    void 되돌리기와_취소는_상태에_따라_기타_작업에_표시한다() throws IOException {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"),
                StandardCharsets.UTF_8);

        assertThat(template)
                .contains("class=\"pop rq-head__overflow\"")
                .contains("aria-label=\"기타 작업\"")
                .contains("request.deliveryState().name() == 'NOT_SENT'")
                .contains("request.developmentState().name() == 'INTAKE'")
                .contains(">FRD 작업 재개</button>")
                .contains(">개발요청 취소</button>");
    }
}
