package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import com.bizplay.builder.project.PlanningRepositoryUpdater;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrdDraftingServiceTest {

    private final PlanningRepositoryUpdater updater = mock(PlanningRepositoryUpdater.class);

    /**
     * ⭐ <b>작업을 시작하기 전에 기획 저장소를 원격에 맞춘다.</b> 새 워크트리는 클론 HEAD 에서
     * 따는데, 「개발 결과 받기」는 원격 {@code main} 에 직접 커밋하므로 클론이 뒤처진다 —
     * 맞추지 않으면 받아 놓은 개발 결과가 다음 FRD 에 안 보인다(2026-09-23 실측).
     */
    @Test
    void 시작하기_전에_기획_저장소를_원격에_맞춘다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdWorkspace.Prepared prepared = new FrdWorkspace.Prepared(
                Path.of("clone"), Path.of("worktrees/frd-0000025"), "frd/0000025", true, true);
        when(workspaces.ensure("0000001", "0000025")).thenReturn(prepared);

        new FrdDraftingService(workspaces, mock(FrdService.class), mock(FrdScreenMapper.class), updater)
                .start("0000001", "0000025");

        InOrder order = inOrder(updater, workspaces);
        order.verify(updater).refresh("0000001");
        order.verify(workspaces).ensure("0000001", "0000025");
    }

    /** ⚠ 못 맞춰도 시작은 간다 — 끊긴 망 때문에 기획이 일을 못 하면 안 된다. 전송 전 검증이 알린다. */
    @Test
    void 원격에_못_맞춰도_시작은_간다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdWorkspace.Prepared prepared = new FrdWorkspace.Prepared(
                Path.of("clone"), Path.of("worktrees/frd-0000025"), "frd/0000025", true, true);
        when(workspaces.ensure("0000001", "0000025")).thenReturn(prepared);
        doThrow(new IllegalStateException("원격 기획 저장소의 최신 내용을 받지 못했습니다."))
                .when(updater).refresh("0000001");

        new FrdDraftingService(workspaces, frds, mock(FrdScreenMapper.class), updater)
                .start("0000001", "0000025");

        verify(frds).startDrafting("0000025");
    }

    @Test
    void 워크트리를_준비한_뒤에만_수정_중으로_바꾼다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdWorkspace.Prepared prepared = new FrdWorkspace.Prepared(
                Path.of("clone"), Path.of("worktrees/frd-0000025"), "frd/0000025", true, true);
        when(workspaces.ensure("0000001", "0000025")).thenReturn(prepared);

        new FrdDraftingService(workspaces, frds, screens, updater).start("0000001", "0000025");

        verify(workspaces).ensure("0000001", "0000025");
        verify(frds).startDrafting("0000025");
        verify(workspaces, never()).rollback(prepared);
    }

    @Test
    void 상태_전환이_실패하면_이번에_준비한_자리를_되돌린다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdWorkspace.Prepared prepared = new FrdWorkspace.Prepared(
                Path.of("clone"), Path.of("worktrees/frd-0000025"), "frd/0000025", true, true);
        when(workspaces.ensure("0000001", "0000025")).thenReturn(prepared);
        doThrow(new IllegalStateException("다른 요청이 먼저 시작했다"))
                .when(frds).startDrafting("0000025");

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens, updater)
                .start("0000001", "0000025"))
                .isInstanceOf(IllegalStateException.class);

        verify(workspaces).rollback(prepared);
    }

    @Test
    void Git_실행_오류도_사용자가_다시_시도할_수_있는_메시지로_바꾼다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(workspaces.ensure("0000001", "0000025")).thenThrow(new RuntimeException("git timeout"));

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens, updater)
                .start("0000001", "0000025"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("저장소 상태를 확인한 뒤 다시 시도");

        verify(frds, never()).startDrafting("0000025");
    }

    @Test
    void 작업_초기화는_워크트리를_새로_만든_뒤_화면_상태를_대기로_돌린다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        stubFrdState(frds, Frd.State.DRAFTING);
        when(screens.selectByFrdId("0000025")).thenReturn(List.of());

        new FrdDraftingService(workspaces, frds, screens, updater).reset("0000001", "0000025");

        var ordered = org.mockito.Mockito.inOrder(workspaces, screens);
        ordered.verify(workspaces).reset("0000001", "0000025");
        ordered.verify(screens).resetByFrdId("0000025");
    }

    @Test
    void AI_초안을_만드는_중이면_작업을_초기화하지_않는다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        stubFrdState(frds, Frd.State.DRAFTING);
        FrdScreen generating = mock(FrdScreen.class);
        when(generating.state()).thenReturn(FrdScreen.State.GENERATING);
        when(screens.selectByFrdId("0000025")).thenReturn(List.of(generating));

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens, updater)
                .reset("0000001", "0000025"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("만드는 중");

        verify(workspaces, never()).reset("0000001", "0000025");
        verify(screens, never()).resetByFrdId("0000025");
    }

    @Test
    void 수정_중이_아닌_FRD는_작업을_초기화하지_않는다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        stubFrdState(frds, Frd.State.REVIEW);

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens, updater)
                .reset("0000001", "0000025"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("수정 중인 FRD만");

        verify(workspaces, never()).reset("0000001", "0000025");
        verify(screens, never()).resetByFrdId("0000025");
    }

    private void stubFrdState(FrdService frds, Frd.State state) {
        Frd frd = mock(Frd.class);
        when(frd.state()).thenReturn(state);
        when(frds.of("0000001", "0000025")).thenReturn(frd);
    }
}
