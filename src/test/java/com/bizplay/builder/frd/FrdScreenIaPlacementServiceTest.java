package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrdScreenIaPlacementServiceTest {

    private final FrdScreenIaPlacementMapper mapper = mock(FrdScreenIaPlacementMapper.class);
    private final FrdScreenIaPlacementService service = new FrdScreenIaPlacementService(mapper);

    @Test
    void 위치를_정하지_않은_신규_화면도_미정으로_추적한다() {
        service.save("0000042", new FrdScreenIaPlacementService.Request(
                null, null, null, null, null));

        var captured = forClass(FrdScreenIaPlacement.class);
        verify(mapper).upsert(captured.capture());
        assertThat(captured.getValue().placementMode()).isEqualTo(FrdScreenIaPlacement.PlacementMode.UNRESOLVED);
        assertThat(captured.getValue().status()).isEqualTo(FrdScreenIaPlacement.Status.PROPOSED);
        assertThat(captured.getValue().screenKind()).isEqualTo(FrdScreenIaPlacement.ScreenKind.SCREEN);
    }

    @Test
    void 기준_화면이_있는_일반_화면은_하위_배치로_기록한다() {
        service.save("0000043", new FrdScreenIaPlacementService.Request(
                "CHILD", "screen-list", null, "화면", "AI"));

        var captured = forClass(FrdScreenIaPlacement.class);
        verify(mapper).upsert(captured.capture());
        assertThat(captured.getValue().placementMode()).isEqualTo(FrdScreenIaPlacement.PlacementMode.CHILD);
        assertThat(captured.getValue().anchorScreenId()).isEqualTo("screen-list");
        assertThat(captured.getValue().source()).isEqualTo(FrdScreenIaPlacement.Source.AI);
    }

    @Test
    void 팝업은_상위화면이_아니라_여는_화면을_요구한다() {
        service.save("0000044", new FrdScreenIaPlacementService.Request(
                "OPENER", "screen-detail", null, "팝업", "USER"));

        var captured = forClass(FrdScreenIaPlacement.class);
        verify(mapper).upsert(captured.capture());
        assertThat(captured.getValue().screenKind()).isEqualTo(FrdScreenIaPlacement.ScreenKind.POPUP);
        assertThat(captured.getValue().placementMode()).isEqualTo(FrdScreenIaPlacement.PlacementMode.OPENER);
    }

    @Test
    void 일반_화면에_여는화면_배치를_적으면_거절한다() {
        assertThatThrownBy(() -> service.save("0000045", new FrdScreenIaPlacementService.Request(
                "OPENER", "screen-detail", null, "화면", "USER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("팝업·모달");
    }

    @Test
    void 팝업을_메뉴에_직접_배치하면_거절한다() {
        assertThatThrownBy(() -> service.save("0000046", new FrdScreenIaPlacementService.Request(
                "MENU", null, "merc/master", "팝업", "USER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("여는 화면");
    }

    @Test
    void IA_위치가_미정이어도_개발파일명을_예약한다() {
        FrdScreen screen = new FrdScreen("0000067", "0000039", "tmp-0000067", "폐업가맹점 목록 조회", null,
                null, null, FrdScreen.State.WAITING, null, null, null,
                null, Instant.now(), "backoffice", "목록", null);
        FrdScreenIaPlacement placement = new FrdScreenIaPlacement(
                screen.id(), FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null, null, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.AI, null, Instant.now(), null);
        when(mapper.selectByDevelopmentFileName("0000001", "backoffice-list-67")).thenReturn(null);

        FrdScreenIaPlacement reserved = service.reserveDevelopmentFileName("0000001", screen, placement);

        assertThat(reserved.developmentFileName()).isEqualTo("backoffice-list-67");
        assertThat(reserved.placementMode()).isEqualTo(FrdScreenIaPlacement.PlacementMode.UNRESOLVED);
        assertThat(reserved.status()).isEqualTo(FrdScreenIaPlacement.Status.PROPOSED);
        verify(mapper).upsert(reserved);
    }

    @Test
    void 기존_기획화면과_같은_개발화면ID는_다음_이름으로_피한다() {
        FrdScreen screen = new FrdScreen("0000067", "0000039", "tmp-0000067", "폐업가맹점 목록 조회", null,
                null, null, FrdScreen.State.WAITING, null, null, null,
                null, Instant.now(), "backoffice", "목록", null);
        FrdScreenIaPlacement placement = new FrdScreenIaPlacement(
                screen.id(), FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null, null, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.AI, null, Instant.now(), null);
        when(mapper.selectByDevelopmentFileName("0000001", "backoffice-list-67-2")).thenReturn(null);

        FrdScreenIaPlacement reserved = service.reserveDevelopmentFileName(
                "0000001", screen, placement, Set.of("backoffice-list-67"));

        assertThat(reserved.developmentFileName()).isEqualTo("backoffice-list-67-2");
    }

    @Test
    void 연결_위치를_바꿔도_예약된_개발화면ID는_유지한다() {
        FrdScreen screen = new FrdScreen("0000068", "0000039", "tmp-0000068", "폐업가맹점 상세 조회", null,
                null, null, FrdScreen.State.WAITING, null, null, null,
                null, Instant.now(), "backoffice", "상세", null);
        FrdScreenIaPlacement existing = new FrdScreenIaPlacement(
                screen.id(), FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null, null, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.AI, "backoffice-detail-68", Instant.now(), null);
        when(mapper.selectByScreenId(screen.id())).thenReturn(existing);

        FrdScreenIaPlacement changed = service.save(screen.id(), new FrdScreenIaPlacementService.Request(
                "CHILD", "bo-merchant-list", null, "SCREEN", "USER"));

        assertThat(changed.developmentFileName()).isEqualTo("backoffice-detail-68");
    }
}
