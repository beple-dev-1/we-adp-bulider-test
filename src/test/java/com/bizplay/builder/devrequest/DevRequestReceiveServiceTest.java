package com.bizplay.builder.devrequest;

import com.bizplay.builder.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「개발 결과 받기」를 <b>부르는 자리</b>가 무엇을 쥐고 무엇을 안 쥐나.
 *
 * <p>⭐ <b>역류는 두 갈래이고 앉는 자리가 다르다</b> — 화면 파일은 기획 저장소로, 테스트 결과는
 * 빌더 DB 로 간다. 이 시험은 둘이 갈려 있나를 본다.
 * ⛔ <b>거절이면 DB 에도 아무것도 안 놓는다</b> — 반쪽 상태가 가장 나쁘다.
 */
class DevRequestReceiveServiceTest {

    private static final String PROJECT = "project-1";
    private static final String REQUEST = "request-1";

    private final DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
    private final DevelopmentRequestService requestService = mock(DevelopmentRequestService.class);
    private final DevRequestDeliveryWorkspace workspaces = mock(DevRequestDeliveryWorkspace.class);
    private final DevRequestTestResultMapper testResults = mock(DevRequestTestResultMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final DevRequestReceiveService service = new DevRequestReceiveService(
            requests, requestService, workspaces, testResults, projects);

    private final DevelopmentRequest request = mock(DevelopmentRequest.class);

    @BeforeEach
    void setUp() {
        given(request.projectId()).willReturn(PROJECT);
        given(request.label()).willReturn("DR-009");
        given(request.deliveryState()).willReturn(DevelopmentRequest.DeliveryState.SENT);
        given(requests.selectById(REQUEST)).willReturn(request);

        DevelopmentRequestContent content = mock(DevelopmentRequestContent.class);
        given(content.testScenarios()).willReturn(List.of());
        DevelopmentRequestService.View view = mock(DevelopmentRequestService.View.class);
        given(view.content()).willReturn(content);
        given(requestService.read(PROJECT, REQUEST)).willReturn(view);

        given(projects.cloneMaterials(PROJECT))
                .willReturn(new ProjectService.CloneMaterials("main", "https://example/repo.git"));
    }

    /** ⚠ 안 보낸 것을 받을 수는 없다 — 무엇을 기준으로 판정할지가 없다. */
    @Test
    void 아직_넘기지_않았으면_받지_않는다() {
        given(request.deliveryState()).willReturn(DevelopmentRequest.DeliveryState.NOT_SENT);

        assertThatThrownBy(() -> service.receive(PROJECT, REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("넘기지 않은");
        verify(workspaces, never()).receive(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** ⛔ 거절이면 DB 도 안 움직인다 — 화면 파일만 안 놓고 표만 채우면 둘이 어긋난다. */
    @Test
    void 거절이면_테스트_결과도_담지_않는다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(
                        false, List.of("기준 커밋이 다릅니다"), null, null));

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejections()).containsExactly("기준 커밋이 다릅니다");
        assertThat(result.testRows()).isZero();
        verify(testResults, never()).upsert(anyString(), any());
    }

    /**
     * ⭐ <b>테스트 결과 md 는 받은 판에서 읽는다</b> — 기본 브랜치에는 안 놓이는 파일이라
     * 돌려받은 머리 커밋에서 꺼내야 한다. 경로는 개발요청서 이름 밑이다.
     */
    @Test
    void 돌려받은_판에서_테스트_결과를_꺼내_TC_줄로_담는다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(
                        true, List.of(), "새커밋", "돌려받은판"));
        given(workspaces.fileAt(PROJECT, "돌려받은판", "DR-009/return/unit-tests.md")).willReturn("""
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-001 | 빈 값으로 응답한다 | 없음 | 조건 | 행위 | 기대 | 왔다 | 통과 | 로그 |
                """);
        given(workspaces.fileAt(PROJECT, "돌려받은판", "DR-009/return/integration-tests.md")).willReturn("""
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-101 | 가입이 끝난다 | 없음 | 조건 | 행위 | 기대 | 안 됐다 | 실패 | 캡처 |
                """);

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST);

        assertThat(result.accepted()).isTrue();
        assertThat(result.commit()).isEqualTo("새커밋");
        assertThat(result.testRows()).isEqualTo(2);

        ArgumentCaptor<TestResultReader.Result> rows =
                ArgumentCaptor.forClass(TestResultReader.Result.class);
        verify(testResults, org.mockito.Mockito.times(2)).upsert(eq(REQUEST), rows.capture());
        assertThat(rows.getAllValues()).extracting(TestResultReader.Result::tcId,
                        TestResultReader.Result::kind, TestResultReader.Result::verdict)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("TC-001", "UNIT", TestResultReader.PASS),
                        org.assertj.core.groups.Tuple.tuple("TC-101", "INTEGRATION",
                                TestResultReader.FAIL));
    }

    /**
     * ⚠ <b>md 가 없어도 받기는 성립한다</b> — 설계가 「시나리오 없이도 계약은 성립한다」고 했고,
     * 화면 변경만 있는 개발요청서도 있다. 그때는 0줄이다.
     */
    @Test
    void 테스트_결과가_없어도_받기는_성립한다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(
                        true, List.of(), "새커밋", "돌려받은판"));
        given(workspaces.fileAt(any(), any(), any())).willReturn(null);

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST);

        assertThat(result.accepted()).isTrue();
        assertThat(result.testRows()).isZero();
        verify(testResults, never()).upsert(anyString(), any());
    }

    /** ⚠ 남의 프로젝트의 개발요청서를 이름만으로 받아 가지 못한다. */
    @Test
    void 다른_프로젝트의_개발요청서는_받지_않는다() {
        given(request.projectId()).willReturn("project-2");

        assertThatThrownBy(() -> service.receive(PROJECT, REQUEST))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
