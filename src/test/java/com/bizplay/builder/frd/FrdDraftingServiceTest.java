package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrdDraftingServiceTest {

    @Test
    void 워크트리를_준비한_뒤에만_수정_중으로_바꾼다() {
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        FrdService frds = mock(FrdService.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdWorkspace.Prepared prepared = new FrdWorkspace.Prepared(
                Path.of("clone"), Path.of("worktrees/frd-0000025"), "frd/0000025", true, true);
        when(workspaces.ensure("0000001", "0000025")).thenReturn(prepared);

        new FrdDraftingService(workspaces, frds, screens).start("0000001", "0000025");

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

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens)
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

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens)
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

        new FrdDraftingService(workspaces, frds, screens).reset("0000001", "0000025");

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

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens)
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

        assertThatThrownBy(() -> new FrdDraftingService(workspaces, frds, screens)
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
