package com.bizplay.builder.devrequest;

import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class DevelopmentRequestControllerTest {

    @TempDir Path temp;

    private final DevelopmentRequestService requests = mock(DevelopmentRequestService.class);
    private final ProjectFacetMapper projectFacets = mock(ProjectFacetMapper.class);
    private final ProjectSystemService projectSystems = mock(ProjectSystemService.class);
    private final DevelopmentRequestController controller =
            new DevelopmentRequestController(requests, projectFacets, projectSystems);

    @Test
    void 개발요청서_목록은_요청한_페이지와_목록_크기만_내린다() {
        given(requests.list("project-1"))
                .willReturn(Collections.nCopies(21, (DevelopmentRequestService.Row) null));
        given(projectFacets.selectByProjectId("project-1")).willReturn(List.of());
        SystemLabels systemLabels = new SystemLabels(Map.of("bo", "백오피스"));
        given(projectSystems.labels("project-1")).willReturn(systemLabels);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.list("project-1", "", "", "", "", 2, 20, model);

        assertThat(view).isEqualTo("artifacts/dev-requests");
        assertThat((List<?>) model.getAttribute("rows")).hasSize(1);
        assertThat(model.getAttribute("matchedCount")).isEqualTo(21);
        assertThat(model.getAttribute("page")).isEqualTo(2);
        assertThat(model.getAttribute("pageCount")).isEqualTo(2);
        assertThat(model.getAttribute("pageNumbers")).isEqualTo(List.of(1, 2));
        assertThat(model.getAttribute("pageSize")).isEqualTo(20);
        assertThat(model.getAttribute("pageSizes")).isEqualTo(List.of(10, 20, 50, 100));
        assertThat(model.getAttribute("systemLabels")).isSameAs(systemLabels);
    }

    @Test
    void 잘못된_페이지와_목록_크기는_허용_범위로_보정한다() {
        given(requests.list("project-1"))
                .willReturn(Collections.nCopies(21, (DevelopmentRequestService.Row) null));
        given(projectFacets.selectByProjectId("project-1")).willReturn(List.of());
        ConcurrentModel model = new ConcurrentModel();

        controller.list("project-1", "", "", "", "", 999, 7, model);

        assertThat((List<?>) model.getAttribute("rows")).hasSize(1);
        assertThat(model.getAttribute("page")).isEqualTo(3);
        assertThat(model.getAttribute("pageSize")).isEqualTo(10);
    }

    @Test
    void 개발요청서_목록의_개발_범위는_FRD_작업_범위와_같은_형식으로_표시한다() {
        DevelopmentRequestService.Row row = new DevelopmentRequestService.Row(
                null, null, false, 3, 1, 2, 0, false, null);

        assertThat(row.rangeLabel()).isEqualTo("화면 3개 · 신규 1개 · 수정 2개 · 백엔드 2건");
    }

    @Test
    void 개발요청서_목록의_빈_개발_범위도_FRD_작업_범위와_같이_표시한다() {
        DevelopmentRequestService.Row row = new DevelopmentRequestService.Row(
                null, null, false, 0, 0, 0, 0, false, null);

        assertThat(row.rangeLabel()).isEqualTo("프론트 없음 · 백엔드 없음");
    }


    @Test
    void 개발요청서_번호_업무명과_기준_FRD_번호로_검색한다() {
        var first = row(1, 7, "전자결재 개선", "bo",
                DevelopmentRequest.DeliveryState.NOT_SENT, "김기획");
        var second = row(2, 8, "급여 조회 개선", "wv",
                DevelopmentRequest.DeliveryState.SENT, "이기획");
        given(requests.list("project-1")).willReturn(List.of(first, second));
        given(projectFacets.selectByProjectId("project-1")).willReturn(List.of());
        given(projectSystems.labels("project-1")).willReturn(new SystemLabels(Map.of()));

        ConcurrentModel byTitle = new ConcurrentModel();
        controller.list("project-1", "전자결재", "", "", "", 1, 10, byTitle);
        ConcurrentModel byRequestNumber = new ConcurrentModel();
        controller.list("project-1", "DR-002", "", "", "", 1, 10, byRequestNumber);
        ConcurrentModel byFrdNumber = new ConcurrentModel();
        controller.list("project-1", "FRD-007", "", "", "", 1, 10, byFrdNumber);

        assertThat(rows(byTitle)).containsExactly(first);
        assertThat(rows(byRequestNumber)).containsExactly(second);
        assertThat(rows(byFrdNumber)).containsExactly(first);
    }

    @Test
    void 시스템과_담당자를_함께_골라_개발요청서를_거른다() {
        var matched = row(1, 7, "전자결재 개선", "bo",
                DevelopmentRequest.DeliveryState.SENT, "김기획");
        var other = row(2, 8, "급여 조회 개선", "wv",
                DevelopmentRequest.DeliveryState.NOT_SENT, "이기획");
        given(requests.list("project-1")).willReturn(List.of(matched, other));
        given(projectFacets.selectByProjectId("project-1")).willReturn(List.of());
        given(projectSystems.labels("project-1"))
                .willReturn(new SystemLabels(Map.of("bo", "백오피스", "wv", "웹뷰")));
        ConcurrentModel model = new ConcurrentModel();

        controller.list("project-1", "", "SENT", "김기획", "bo", 1, 10, model);

        assertThat(rows(model)).containsExactly(matched);
        assertThat(model.getAttribute("totalCount")).isEqualTo(2);
        assertThat(model.getAttribute("matchedCount")).isEqualTo(1);
        assertThat(model.getAttribute("stateFilter")).isEqualTo("SENT");
        assertThat(model.getAttribute("ownerFilter")).isEqualTo("김기획");
        assertThat(model.getAttribute("systemFilter")).isEqualTo("bo");
        // ⛔ 2026-09-06(003) — 전송 상태 거르개가 없어져 그 선택지를 화면에 안 담는다.
        //    stateFilter 자체는 링크 왕복에 쓰이므로 남는다.
        assertThat(model.getAttribute("stateOptions")).isNull();
        assertThat((List<?>) model.getAttribute("ownerOptions")).hasSize(2);
        assertThat((List<?>) model.getAttribute("systemOptions")).hasSize(2);
    }

    @Test
    void 개발요청서_목록은_FRD_작업과_같은_검색영역을_쓴다() throws Exception {
        String template = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-requests.html"));

        assertThat(template)
                .contains("role=\"search\"")
                .containsSubsequence("for=\"dev-request-system\"", "for=\"dev-request-owner\"",
                        "for=\"dev-request-search\"")
                .doesNotContain("전달 상태");
        // ⛔ 2026-09-04(003) — 산출물 공유 기능을 빼면서 전송·개발 상태 열과 거르개가 없어졌다.
        //    그 두 값은 밖으로 보내고 되받는 장치가 쥐던 것이라 되살릴 자리가 없다.
        assertThat(template)
                .doesNotContain("for=\"dev-request-state\"", "전송 상태", "개발 상태");
    }

    @Test
    void 상세에_목록의_검색_조건과_페이지를_전달한다() {
        DevelopmentRequestService.View detail = mock(DevelopmentRequestService.View.class);
        DevelopmentRequest request = mock(DevelopmentRequest.class);
        given(detail.request()).willReturn(request);
        given(requests.read("project-1", "request-1")).willReturn(detail);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.detail("project-1", "request-1",
                "전자결재", "SENT", "김기획", "bo", 3, 20, model);

        assertThat(view).isEqualTo("artifacts/dev-request");
        assertThat(model.getAttribute("listQuery")).isEqualTo("전자결재");
        assertThat(model.getAttribute("listState")).isEqualTo("SENT");
        assertThat(model.getAttribute("listOwner")).isEqualTo("김기획");
        assertThat(model.getAttribute("listSystem")).isEqualTo("bo");
        assertThat(model.getAttribute("listPage")).isEqualTo(3);
        assertThat(model.getAttribute("listPageSize")).isEqualTo(20);
    }

    @Test
    void 목록과_상세_사이에_검색_조건을_이어_준다() throws Exception {
        String listTemplate = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-requests.html"));
        String detailTemplate = Files.readString(
                Path.of("src/main/resources/templates/artifacts/dev-request.html"));

        assertThat(listTemplate).contains(
                "r=${row.request().id()},query=${query},state=${stateFilter},owner=${ownerFilter},system=${systemFilter},page=${page},pageSize=${pageSize}");
        assertThat(detailTemplate).contains(
                "query=${listQuery},state=${listState},owner=${listOwner},system=${listSystem},page=${listPage},pageSize=${listPageSize}");
    }

    private static DevelopmentRequestService.Row row(
            int number, int frdNumber, String title, String systemCode,
            DevelopmentRequest.DeliveryState state, String ownerName) {
        DevelopmentRequest request = mock(DevelopmentRequest.class);
        given(request.label()).willReturn("DR-%03d".formatted(number));
        given(request.frdLabel()).willReturn("FRD-%03d".formatted(frdNumber));
        given(request.title()).willReturn(title);
        given(request.systemCode()).willReturn(systemCode);
        given(request.deliveryState()).willReturn(state);
        given(request.deliveryStateLabel()).willReturn(switch (state) {
            case NOT_SENT -> "대기";
            case SENDING -> "전송중";
            case SENT -> "전송완료";
            case WITHDRAWN -> "취소";
        });
        return new DevelopmentRequestService.Row(request, ownerName, false,
                0, 0, 0, 0, false, null);
    }

    @SuppressWarnings("unchecked")
    private static List<DevelopmentRequestService.Row> rows(ConcurrentModel model) {
        return (List<DevelopmentRequestService.Row>) model.getAttribute("rows");
    }
}
