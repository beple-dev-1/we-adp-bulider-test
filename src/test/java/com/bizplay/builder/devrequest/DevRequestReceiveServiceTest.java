package com.bizplay.builder.devrequest;

import com.bizplay.builder.project.PlanningRepositoryUpdater;
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
import static org.mockito.Mockito.doThrow;
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
    private final PlanningRepositoryUpdater updater = mock(PlanningRepositoryUpdater.class);
    private final DevRequestReceiptMapper receipts = mock(DevRequestReceiptMapper.class);
    private final DevRequestReceiveService service = new DevRequestReceiveService(
            requests, requestService, workspaces, testResults, projects, updater, receipts);

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

        assertThatThrownBy(() -> service.receive(PROJECT, REQUEST, "account-1"))
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

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST, "account-1");

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

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST, "account-1");

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

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST, "account-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.testRows()).isZero();
        verify(testResults, never()).upsert(anyString(), any());
    }

    /**
     * ⛔ <b>목록 밖 TC 는 커밋 전에 거절한다</b> — 판정 자리에서 막아야 화면 파일도 안 놓인다.
     * 커밋 뒤에 표를 담을 때 걸러 내면 화면은 들어가고 테스트만 빠지는 반쪽이 된다.
     */
    @Test
    void 보낸_적_없는_TC_는_판정에서_거절한다() {
        DevelopmentRequestContent content = requestService.read(PROJECT, REQUEST).content();
        given(content.testScenarios()).willReturn(List.of(
                new DevelopmentRequestContent.TestScenario("UNIT", 1, "TC-001",
                        "무엇", "없음", "조건", "행위", "기대")));
        ArgumentCaptor<DevRequestDeliveryWorkspace.Judge> judge =
                ArgumentCaptor.forClass(DevRequestDeliveryWorkspace.Judge.class);
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), judge.capture(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(false, List.of("x"), null, null));
        service.receive(PROJECT, REQUEST, "account-1");

        String unit = """
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-001 | 무엇 | 없음 | 조건 | 행위 | 기대 | 실제 | 통과 | 근거 |
                | TC-777 | 지어낸 것 | 없음 | 조건 | 행위 | 기대 | 실제 | 통과 | 근거 |
                """;
        ReturnBatch.Verdict verdict = judge.getValue().judge(
                "{\"dr\": \"DR-009\", \"base\": \"b\", \"screens\": []}",
                new ReturnBatch.MainHistory() {
                    @Override
                    public boolean contains(String commit) {
                        return "b".equals(commit);
                    }

                    @Override
                    public java.util.Set<String> changedSince(String commit) {
                        return java.util.Set.of();
                    }
                },
                path -> path.equals("DR-009/" + ReturnBatch.UNIT_TESTS) ? unit : null);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("TC-777"));
        assertThat(verdict.filesToTake()).isEmpty();
    }

    /**
     * ⭐ <b>받아 놓은 뒤 클론을 원격에 맞춘다.</b> 받기는 원격 {@code main} 에 직접 커밋하므로
     * 클론이 한 판 뒤처지고, 그린존 문서(기능명세서·화면설계서·사용자 매뉴얼)는 클론의
     * {@code core/} 를 재료로 읽는다 — 맞추지 않으면 받은 개발 결과가 문서에 안 담긴다.
     */
    @Test
    void 받아_놓은_뒤_클론을_원격에_맞춘다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(true, List.of(), "새커밋", "돌려받은판"));

        service.receive(PROJECT, REQUEST, "account-1");

        verify(updater).refresh(PROJECT);
    }

    /** ⚠ 거절이면 원격이 안 움직였으니 맞출 것도 없다. */
    @Test
    void 거절이면_클론을_건드리지_않는다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(false, List.of("x"), null, null));

        service.receive(PROJECT, REQUEST, "account-1");

        verify(updater, never()).refresh(any());
    }

    /**
     * ⚠ <b>못 맞춰도 받기는 성공이다</b> — 원격에는 이미 들어갔다. 실패로 알리면 사람이 다시 누르고,
     * 두 번째는 「바뀐 것 없음」이 된다. 클론은 다음 FRD 작업하기나 IA 게시가 맞춘다.
     */
    @Test
    void 클론을_못_맞춰도_받기는_성공이다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(true, List.of(), "새커밋", "돌려받은판"));
        doThrow(new IllegalStateException("기획 저장소에 커밋되지 않은 변경이 있어 최신 내용을 받을 수 없습니다."))
                .when(updater).refresh(PROJECT);

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST, "account-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.commit()).isEqualTo("새커밋");
    }

    /** ⭐ 받으면 수신 이력에 받은 판 · 받기 커밋 · 담은 줄 수 · 누른 사람을 남긴다. */
    @Test
    void 받으면_수신_이력을_남긴다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(true, List.of(), "새커밋", "돌려받은판"));

        service.receive(PROJECT, REQUEST, "account-1");

        ArgumentCaptor<DevRequestReceipt> row = ArgumentCaptor.forClass(DevRequestReceipt.class);
        verify(receipts).insert(row.capture());
        assertThat(row.getValue().outcome()).isEqualTo(DevRequestReceipt.ACCEPTED);
        assertThat(row.getValue().devRequestId()).isEqualTo(REQUEST);
        assertThat(row.getValue().returnedHead()).isEqualTo("돌려받은판");
        assertThat(row.getValue().receiveCommit()).isEqualTo("새커밋");
        assertThat(row.getValue().accountId()).isEqualTo("account-1");
    }

    /** ⭐ 거절도 사유와 함께 남긴다 — 무엇 때문에 몇 번 떨어졌는지가 개발과 말을 맞출 근거다. */
    @Test
    void 거절도_사유와_함께_남긴다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(false, List.of("회신서가 없습니다"), null, "판"));

        service.receive(PROJECT, REQUEST, "account-1");

        ArgumentCaptor<DevRequestReceipt> row = ArgumentCaptor.forClass(DevRequestReceipt.class);
        verify(receipts).insert(row.capture());
        assertThat(row.getValue().outcome()).isEqualTo(DevRequestReceipt.REJECTED);
        assertThat(row.getValue().rejectionList()).containsExactly("회신서가 없습니다");
    }

    /**
     * ⭐ <b>이미 받은 판이면 git 은 건너뛰고 테스트 결과만 다시 담는다.</b> 밀고 나서 담기가 실패했을 때
     * 다시 누르는 길이 이것이다 — 같은 TC 는 갈아 끼우므로 두 번 담아도 한 벌이다.
     */
    @Test
    void 이미_받은_판이면_테스트_결과만_다시_담는다() {
        given(workspaces.receive(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new DevRequestDeliveryWorkspace.Received(true, List.of(), "앞커밋", "돌려받은판", true));
        given(workspaces.fileAt(PROJECT, "돌려받은판", "DR-009/return/integration-tests.md")).willReturn("""
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-101 | 열린다 | 없음 | 조건 | 행위 | 기대 | 열렸다 | 통과 | 녹화 |
                """);

        DevRequestReceiveService.Result result = service.receive(PROJECT, REQUEST, "account-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.alreadyReceived()).isTrue();
        assertThat(result.testRows()).isEqualTo(1);
        // ⚠ 앞 받기에서 클론 맞추기가 실패했을 수 있다 — 다시 누른 이때 맞춘다.
        verify(updater).refresh(PROJECT);
        ArgumentCaptor<DevRequestReceipt> row = ArgumentCaptor.forClass(DevRequestReceipt.class);
        verify(receipts).insert(row.capture());
        assertThat(row.getValue().outcome()).isEqualTo(DevRequestReceipt.ALREADY);
    }

    /** ⚠ 남의 프로젝트의 개발요청서를 이름만으로 받아 가지 못한다. */
    @Test
    void 다른_프로젝트의_개발요청서는_받지_않는다() {
        given(request.projectId()).willReturn("project-2");

        assertThatThrownBy(() -> service.receive(PROJECT, REQUEST, "account-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
