package com.bizplay.builder.frd;

import com.bizplay.builder.devrequest.DevelopmentRequest;
import com.bizplay.builder.devrequest.DevelopmentRequestService;
import com.bizplay.builder.project.PlanningRepositoryUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * FRD 작업 완료가 「변경 예정 기능정의서」 만들기를 <b>스스로 건다</b> — 병주 지시 2026-08-25.
 *
 * <p>⭐ 완료 시점은 목업이 커밋되어 더 안 바뀌는 순간이라 재료 셋이 확정된다. 사람이 상세 화면에서
 * 버튼을 찾아 누르지 않아도 개발요청서에 to-be.md 가 채워져 있어야 한다.
 *
 * <p>⚠ 만들기가 실패해도 완료는 성립한다 — 그 화면의 경고로만 남고 버튼으로 다시 돌린다.
 */
class FrdCompletionTobeDocumentTest {

    private static final String PROJECT = "0000001";
    private static final String FRD = "0000036";
    private static final String REQUEST = "0000003";

    private FrdService frds;
    private FrdScreenMapper screens;
    private FrdScreenChatService screenChats;
    private FrdAnalysisNoteMapper notes;
    private FrdWorkspace workspaces;
    private FrdScreenIaMaterializer iaMaterializer;
    private DevelopmentRequestService developmentRequests;
    private PlanningRepositoryUpdater repositoryUpdater;
    private FrdCompletionService completion;

    @BeforeEach
    void setUp() {
        frds = mock(FrdService.class);
        screens = mock(FrdScreenMapper.class);
        screenChats = mock(FrdScreenChatService.class);
        workspaces = mock(FrdWorkspace.class);
        iaMaterializer = mock(FrdScreenIaMaterializer.class);
        developmentRequests = mock(DevelopmentRequestService.class);
        repositoryUpdater = mock(PlanningRepositoryUpdater.class);
        notes = mock(FrdAnalysisNoteMapper.class);
        completion = new FrdCompletionService(frds, screens, screenChats,
                notes, iaMaterializer, workspaces, developmentRequests,
                repositoryUpdater);

        when(frds.of(PROJECT, FRD)).thenReturn(frd(Frd.State.DRAFTING));
        when(screens.selectByFrdId(FRD)).thenReturn(List.of());
        when(workspaces.commitChanges(any(), any(), any()))
                .thenReturn(new FrdWorkspace.Commit(Path.of("wt"), "a", "b"));
        when(developmentRequests.createFromCompletedFrd(PROJECT, FRD, "a", "b")).thenReturn(request());
        when(repositoryUpdater.withLatest(anyString(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(workspaces.syncWithCloneDetails(PROJECT, FRD)).thenReturn(
                new FrdWorkspace.SyncResult(FrdWorkspace.Sync.UP_TO_DATE, "base", List.of()));
    }

    @Test
    void 작업을_완료하기_전에_워크트리를_기획_저장소_최신에_맞춘다() {
        when(workspaces.syncWithCloneDetails(PROJECT, FRD)).thenReturn(
                new FrdWorkspace.SyncResult(FrdWorkspace.Sync.MERGED, "base", List.of("index.json")));

        completion.complete(PROJECT, FRD);

        // ⭐ 맞춘 뒤에 커밋한다 — 그래야 개발요청서의 검사기가 낡은 manifest 에 걸리지 않는다.
        InOrder order = inOrder(iaMaterializer, repositoryUpdater, workspaces);
        order.verify(iaMaterializer).materialize(PROJECT, FRD);
        order.verify(repositoryUpdater).withLatest(anyString(), any());
        order.verify(workspaces).syncWithCloneDetails(PROJECT, FRD);
        order.verify(workspaces).refreshDerivedFiles(PROJECT, FRD);
        order.verify(workspaces).commitChanges(any(), any(), any());
    }

    @Test
    void 최신_내용과_충돌하면_작업물을_커밋하지_않는다() {
        when(workspaces.syncWithCloneDetails(PROJECT, FRD)).thenReturn(
                new FrdWorkspace.SyncResult(FrdWorkspace.Sync.CONFLICT, "base", List.of("README.md")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.complete(PROJECT, FRD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("작업물은 그대로 보존");
        verify(workspaces, never()).commitChanges(any(), any(), any());
    }

    @Test
    void 공통_CSS가_최신화되면_화면_확인_전에는_완료하지_않는다() {
        FrdScreen screen = FrdScreen.pickedIn("0000082", FRD, "screen-list", "목록",
                "screen-list", null, "수정", "backoffice");
        when(screens.selectByFrdId(FRD)).thenReturn(List.of(screen));
        when(workspaces.syncWithCloneDetails(PROJECT, FRD)).thenReturn(
                new FrdWorkspace.SyncResult(FrdWorkspace.Sync.MERGED, "new-head",
                        List.of("design-guide/styles/backoffice.css")));
        when(workspaces.pendingLatestReview(PROJECT, FRD))
                .thenReturn(new FrdWorkspace.PendingReview("new-head", screen.id()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.complete(PROJECT, FRD))
                .isInstanceOf(FrdCompletionService.LatestReviewRequired.class);
        verify(workspaces).requireLatestReview(PROJECT, FRD, "new-head", screen.id());
        verify(workspaces, never()).commitChanges(any(), any(), any());
    }

    @Test
    void 변경된_화면을_확인하면_같은_최신_HEAD로_FRD를_완료한다() {
        when(workspaces.pendingLatestReview(PROJECT, FRD))
                .thenReturn(new FrdWorkspace.PendingReview("new-head", "0000082"));
        when(workspaces.confirmLatestReview(PROJECT, FRD, "new-head")).thenReturn(true);

        String requestId = completion.complete(PROJECT, FRD, "new-head");

        org.assertj.core.api.Assertions.assertThat(requestId).isEqualTo(REQUEST);
        verify(workspaces).confirmLatestReview(PROJECT, FRD, "new-head");
        verify(workspaces).commitChanges(PROJECT, FRD, FrdWorkspace.completionMessage("FRD-036"));
    }

    @Test
    void 개발요청서에서_돌아온_완료_커밋이_남았으면_먼저_풀고_다시_완료한다() {
        String message = FrdWorkspace.completionMessage("FRD-036");
        when(workspaces.hasCompletionToReopen(PROJECT, FRD, message)).thenReturn(true);
        when(workspaces.uncommitCompletion(PROJECT, FRD, message)).thenReturn(true);

        completion.complete(PROJECT, FRD);

        InOrder order = inOrder(workspaces, iaMaterializer);
        order.verify(workspaces).uncommitCompletion(PROJECT, FRD, message);
        order.verify(iaMaterializer).materialize(PROJECT, FRD);
        order.verify(workspaces).commitChanges(PROJECT, FRD, message);
    }

    @Test
    void 검토_중인_FRD를_다시_완료하면_미전송_개발요청서를_정리하고_완료한다() {
        when(frds.of(PROJECT, FRD)).thenReturn(frd(Frd.State.REVIEW), frd(Frd.State.DRAFTING));
        when(developmentRequests.findNotSentByFrd(PROJECT, FRD)).thenReturn(request());

        completion.complete(PROJECT, FRD);

        InOrder order = inOrder(developmentRequests, iaMaterializer, workspaces);
        order.verify(developmentRequests).returnToFrd(PROJECT, REQUEST);
        order.verify(iaMaterializer).materialize(PROJECT, FRD);
        order.verify(workspaces).commitChanges(PROJECT, FRD, FrdWorkspace.completionMessage("FRD-036"));
        order.verify(developmentRequests).createFromCompletedFrd(PROJECT, FRD, "a", "b");
    }

    @Test
    void 작업을_완료하면_변경_예정_기능정의서_만들기를_스스로_건다() {
        String created = completion.complete(PROJECT, FRD);

        assertThat(created).isEqualTo(REQUEST);
        InOrder order = inOrder(developmentRequests);
        order.verify(developmentRequests).createFromCompletedFrd(PROJECT, FRD, "a", "b");
        order.verify(developmentRequests).requestTobeDocuments(PROJECT, REQUEST);
    }

    @Test
    void 기능정의서_만들기가_실패해도_완료는_성립한다() {
        doThrow(new IllegalStateException("AI 자리가 없다"))
                .when(developmentRequests).requestTobeDocuments(anyString(), anyString());

        assertThat(completion.complete(PROJECT, FRD)).isEqualTo(REQUEST);
        // ⛔ 개발요청서는 이미 만들어졌다 — 커밋을 되돌리면 안 된다.
        verify(workspaces, never()).rollbackCommit(any());
    }

    /**
     * ⭐ <b>테스트 시나리오도 여기서 건다 (병주 지시 2026-08-27).</b> 종전에는 상세 화면을 열 때
     * 청했는데, 그 자리가 {@code readOnly = true} 트랜잭션이라 만들기가 스냅샷을 저장하는 순간
     * 트랜잭션이 통째로 중단되고 <b>완료의 도착 화면이 500 으로 죽었다.</b> 재료(완료 조건·화면 외 구현)가
     * 확정되는 시점도 여기라 자리로도 맞다.
     */
    @Test
    void 작업을_완료하면_테스트_시나리오_만들기도_스스로_건다() {
        completion.complete(PROJECT, FRD);

        InOrder order = inOrder(developmentRequests);
        order.verify(developmentRequests).createFromCompletedFrd(PROJECT, FRD, "a", "b");
        order.verify(developmentRequests).requestTestScenarios(PROJECT, REQUEST);
    }

    @Test
    void 테스트_시나리오_만들기가_실패해도_완료는_성립한다() {
        doThrow(new IllegalStateException("AI 대기줄이 찼다"))
                .when(developmentRequests).requestTestScenarios(anyString(), anyString());

        assertThat(completion.complete(PROJECT, FRD)).isEqualTo(REQUEST);
        // ⛔ 개발요청서는 이미 만들어졌다 — 여기서 던지면 성립한 완료가 무너진다.
        verify(workspaces, never()).rollbackCommit(any());
    }

    @Test
    void 기능정의서가_실패해도_테스트_시나리오는_그대로_건다() {
        doThrow(new IllegalStateException("AI 자리가 없다"))
                .when(developmentRequests).requestTobeDocuments(anyString(), anyString());

        assertThat(completion.complete(PROJECT, FRD)).isEqualTo(REQUEST);
        verify(developmentRequests).requestTestScenarios(PROJECT, REQUEST);
    }

    @Test
    void 화면_작업이_없는_FRD를_완료해도_테스트_시나리오를_건다() {
        when(developmentRequests.createFromCompletedFrd(PROJECT, FRD)).thenReturn(request());

        assertThat(completion.completeWithoutScreenWork(PROJECT, FRD)).isEqualTo(REQUEST);
        verify(developmentRequests).requestTestScenarios(PROJECT, REQUEST);
    }

    /**
     * ⭐ <b>화면이 하나라도 있으면 빠른 진행이 닫힌다 (2026-09-02 병주 확정).</b>
     * AI 권장(WORK_MODE_FAST_TRACK)이 있어도 같다 — 간단 화면 변경은 SRT 몫이고,
     * FRD 에서 화면 작업은 FRD 작업대에서 구체화한다.
     */
    @Test
    void 화면이_있으면_AI가_빠른_진행을_권장했어도_거절한다() {
        when(frds.of(PROJECT, FRD)).thenReturn(frd(Frd.State.SCOPE_REVIEW));
        when(notes.selectByFrdId(FRD)).thenReturn(List.of(new FrdAnalysisNote(
                "0000001", FRD, 1, FrdAnalysisNote.Kind.WORK_MODE_FAST_TRACK,
                "기존 화면 한 장의 문구만 변경합니다.", java.time.Instant.now())));
        when(screens.selectByFrdId(FRD)).thenReturn(List.of(FrdScreen.pickedIn(
                "0000082", FRD, "wv-appr-write", "결재 문서 작성",
                "wv-appr-write", null, "수정", "webview")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.completeFastTrack(PROJECT, FRD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FRD 작업");
        verify(developmentRequests, never()).createFromConfirmedScope(anyString(), anyString());
    }

    @Test
    void 빠른_진행도_개발요청서를_만든_뒤_공통_AI_준비를_건다() {
        when(frds.of(PROJECT, FRD)).thenReturn(frd(Frd.State.SCOPE_REVIEW));
        when(developmentRequests.createFromConfirmedScope(PROJECT, FRD)).thenReturn(request());

        assertThat(completion.completeFastTrack(PROJECT, FRD)).isEqualTo(REQUEST);
        InOrder order = inOrder(developmentRequests);
        order.verify(developmentRequests).createFromConfirmedScope(PROJECT, FRD);
        order.verify(developmentRequests).requestTobeDocuments(PROJECT, REQUEST);
        order.verify(developmentRequests).requestTestScenarios(PROJECT, REQUEST);
    }

    @Test
    void 개발요청서를_못_만들면_테스트_시나리오도_걸지_않는다() {
        when(developmentRequests.createFromCompletedFrd(PROJECT, FRD, "a", "b"))
                .thenThrow(new IllegalStateException("다른 요청이 먼저 바꿨다"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.complete(PROJECT, FRD))
                .isInstanceOf(IllegalStateException.class);
        verify(developmentRequests, never()).requestTestScenarios(anyString(), anyString());
    }

    @Test
    void 개발요청서를_못_만들면_기능정의서도_걸지_않는다() {
        when(developmentRequests.createFromCompletedFrd(PROJECT, FRD, "a", "b"))
                .thenThrow(new IllegalStateException("다른 요청이 먼저 바꿨다"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.complete(PROJECT, FRD))
                .isInstanceOf(IllegalStateException.class);
        verify(developmentRequests, never()).requestTobeDocuments(anyString(), anyString());
    }

    private static Frd frd(Frd.State state) {
        return new Frd(FRD, PROJECT, 36, "웹뷰 이용내역 상세조회 기간 안내 문구 수정", "webview",
                null, null, null, null, null, state, null, "acct", Instant.now(), Instant.now());
    }

    private static DevelopmentRequest request() {
        return new DevelopmentRequest(REQUEST, PROJECT, 3, FRD, 36, "제목", "webview", null,
                "{}", DevelopmentRequest.DeliveryState.NOT_SENT, null, null, null, null, null, null,
                null, null, null, null, Instant.now(), Instant.now());
    }
}
