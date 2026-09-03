package com.bizplay.builder.frd;

import com.bizplay.builder.solution.SolutionScreenReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrdScreenIaMaterializerTest {

    @TempDir
    Path temp;

    @Test
    void 화면_연결이_미정이어도_개발파일명을_확보하고_완료를_막지_않는다() {
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreenIaPlacementService placements = mock(FrdScreenIaPlacementService.class);
        stubReservation(placements);
        FrdScreenFiles files = mock(FrdScreenFiles.class);
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        FrdScreen screen = newScreen("0000002", "tmp-0000002", null);
        when(screens.selectByFrdId("0000001")).thenReturn(List.of(screen));
        when(solutions.read("0000001")).thenReturn(List.of());
        when(placements.of(screen.id())).thenReturn(placement(screen.id(),
                FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN));
        when(files.document("0000001", "0000001", "bo", screen.screenId()))
                .thenReturn(temp.resolve("tmp-0000002.md"));

        FrdScreenIaMaterializer materializer = new FrdScreenIaMaterializer(
                screens, placements, files, solutions);

        materializer.materialize("0000001", "0000001");

        verify(placements).reserveDevelopmentFileName(
                eq("0000001"), eq(screen), any(FrdScreenIaPlacement.class), anySet());
    }

    @Test
    void 신규_목록과_상세는_시스템_IA와_화면명을_근거로_자동_배치한다() throws Exception {
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreenIaPlacementService placements = mock(FrdScreenIaPlacementService.class);
        stubReservation(placements);
        FrdScreenFiles files = mock(FrdScreenFiles.class);
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        FrdScreen list = newScreen("0000002", "tmp-0000067", "폐업가맹점 목록 조회", "목록");
        FrdScreen detail = newScreen("0000003", "tmp-0000068", "폐업가맹점 상세 조회", "상세");
        Path listDocument = temp.resolve("tmp-0000067.md");
        Path detailDocument = temp.resolve("tmp-0000068.md");
        Path listHtml = temp.resolve("tmp-0000067.html");
        Path detailHtml = temp.resolve("tmp-0000068.html");
        Path iaDocument = temp.resolve("ia.md");
        Files.writeString(listHtml, """
                <html><head></head><body><main data-screen-id="bo-merc-close-list">
                <button id="search" data-element-id="bo-merc-close-list-e01">조회</button>
                <table><tbody>
                <tr data-element-id="bo-merc-close-list-e02" data-nav-target="bo-merc-close-detail"><td>상세</td></tr>
                </tbody></table>
                </main></body></html>
                """);
        Files.writeString(detailHtml, """
                <html><head></head><body><main data-screen-id="tmp-0000068">
                <button id="back" data-nav-target="tmp-0000067">목록</button>
                </main></body></html>
                """);
        Files.writeString(iaDocument, """
                ## 이름표
                - merc: 가맹점 관리
                - merc/master: 마스터 가맹점
                - settle/qr: QR가맹점 미정산 내역

                --- 배치 ---
                - 경로: merc/master / 화면: bo-merc-master-list
                """);
        when(screens.selectByFrdId("0000001")).thenReturn(List.of(list, detail));
        when(solutions.read("0000001")).thenReturn(List.of());
        when(placements.of(list.id())).thenReturn(placement(list.id(),
                FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN));
        when(placements.of(detail.id())).thenReturn(placement(detail.id(),
                FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN));
        when(placements.save(anyString(), any(FrdScreenIaPlacementService.Request.class)))
                .thenAnswer(call -> proposed(call.getArgument(0), call.getArgument(1)));
        when(files.document("0000001", "0000001", "bo", list.screenId())).thenReturn(listDocument);
        when(files.document("0000001", "0000001", "bo", detail.screenId())).thenReturn(detailDocument);
        when(files.targetHtml("0000001", "0000001", "bo", list.screenId())).thenReturn(listHtml);
        when(files.targetHtml("0000001", "0000001", "bo", detail.screenId())).thenReturn(detailHtml);
        when(files.iaDocument("0000001", "0000001", "bo")).thenReturn(iaDocument);

        new FrdScreenIaMaterializer(screens, placements, files, solutions)
                .materialize("0000001", "0000001");

        assertThat(Files.readString(iaDocument))
                .doesNotContain("tmp-0000067", "tmp-0000068");
        assertThat(Files.readString(listDocument)).doesNotContain("--- IA ---");
        assertThat(Files.readString(detailDocument)).doesNotContain("--- IA ---");
        assertThat(Files.readString(listHtml))
                .contains("data-screen-id=\"tmp-0000067\"")
                .contains("data-element-id=\"tmp-0000067-e01\"")
                .contains("data-nav-target=\"tmp-0000068\"")
                .doesNotContain("bo-merc-close-list", "bo-merc-close-detail");
        assertThat(Files.readString(detailHtml))
                .contains("data-screen-id=\"tmp-0000068\"")
                .contains("data-element-id=\"tmp-0000068-e01\"")
                .contains("data-nav-target=\"tmp-0000067\"");
        assertThat(Files.readString(listDocument))
                .contains("앵커: tmp-0000067-e01")
                .contains("앵커: tmp-0000067-e02 / 이동: tmp-0000068")
                .contains("/ 과업: []", "--- 원본 글 ---")
                .doesNotContain("/ 작업: []");
        assertThat(Files.readString(detailDocument))
                .contains("앵커: tmp-0000068-e01 / 이동: tmp-0000067")
                .contains("/ 과업: []", "--- 원본 글 ---")
                .doesNotContain("/ 작업: []");
        verify(placements).save(eq(list.id()), argThat(request ->
                "MENU".equals(request.placementMode()) && "merc".equals(request.menuPathKey())
                        && "AI".equals(request.source())));
        verify(placements).save(eq(detail.id()), argThat(request ->
                "CHILD".equals(request.placementMode())
                        && list.screenId().equals(request.anchorScreenId())
                        && "AI".equals(request.source())));
        verify(placements, never()).confirm(anyString(), any(), any());
    }

    @Test
    void 화면형_신규화면은_상위화면을_MD에_쓰고_개발파일명을_확정한다() throws Exception {
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreenIaPlacementService placements = mock(FrdScreenIaPlacementService.class);
        stubReservation(placements);
        FrdScreenFiles files = mock(FrdScreenFiles.class);
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        FrdScreen anchor = FrdScreen.pickedIn("0000001", "0000001", "bo-merc-list",
                "가맹점 목록", "bo-merc-list", null, null, "bo");
        FrdScreen screen = newScreen("0000002", "tmp-0000002", anchor.screenId());
        FrdScreenIaPlacement placement = placement(screen.id(),
                FrdScreenIaPlacement.PlacementMode.CHILD, anchor.screenId(),
                FrdScreenIaPlacement.ScreenKind.SCREEN);
        Path document = temp.resolve("tmp-0000002.md");
        when(screens.selectByFrdId("0000001")).thenReturn(List.of(anchor, screen));
        when(solutions.read("0000001")).thenReturn(List.of());
        when(placements.of(screen.id())).thenReturn(placement);
        when(files.document("0000001", "0000001", "bo", screen.screenId())).thenReturn(document);

        new FrdScreenIaMaterializer(screens, placements, files, solutions)
                .materialize("0000001", "0000001");

        assertThat(Files.readString(document)).doesNotContain("--- IA ---");
        verify(placements, never()).confirm(anyString(), any(), any());
    }

    @Test
    void 기존_IA_블록만_교체하고_정의는_보존한다() {
        String current = """
                --- 화면명세 ---
                화면명: 예전 화면

                --- IA ---
                - 종류: 화면 / 상위화면: old-list

                --- 정의 ---
                - 구분: 버튼 / 앵커: save
                """;

        String merged = FrdScreenIaMaterializer.mergeIa(current,
                "- 종류: 화면 / 상위화면: new-list");

        assertThat(merged).contains("상위화면: new-list", "--- 정의 ---", "앵커: save")
                .doesNotContain("상위화면: old-list");
    }

    @Test
    void 메뉴_배치는_시스템_IA에_이름표와_배치행을_추가한다() {
        String current = """
                # bo IA 이름표

                ## 이름표
                - merc: 가맹점 관리
                - merc/master: 마스터 가맹점

                --- 배치 ---
                - 경로: merc/master / 화면: bo-merc-master-list
                """;
        FrdScreen screen = newScreen("0000002", "tmp-0000002", null);

        String changed = FrdScreenIaMaterializer.addMenuPlacement(current, screen, "merc/master");

        assertThat(changed)
                .contains("- merc/master/tmp-0000002: 신규 상세")
                .contains("- 경로: merc/master/tmp-0000002 / 화면: tmp-0000002")
                .contains("- 경로: merc/master / 화면: bo-merc-master-list");
    }

    @Test
    void 메뉴형_신규화면은_화면_MD와_시스템_IA를_각각_정본_형식으로_쓴다() throws Exception {
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreenIaPlacementService placements = mock(FrdScreenIaPlacementService.class);
        stubReservation(placements);
        FrdScreenFiles files = mock(FrdScreenFiles.class);
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        FrdScreen screen = newScreen("0000002", "tmp-0000002", null);
        FrdScreenIaPlacement placement = new FrdScreenIaPlacement(
                screen.id(), FrdScreenIaPlacement.PlacementMode.MENU, null, "merc/master", null,
                FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.USER, null, Instant.now(), null);
        Path screenDocument = temp.resolve("tmp-0000002.md");
        Path iaDocument = temp.resolve("ia.md");
        Files.writeString(iaDocument, """
                ## 이름표
                - merc/master: 마스터 가맹점

                --- 배치 ---
                - 경로: merc/master / 화면: bo-merc-master-list
                """);
        when(screens.selectByFrdId("0000001")).thenReturn(List.of(screen));
        when(solutions.read("0000001")).thenReturn(List.of());
        when(placements.of(screen.id())).thenReturn(placement);
        when(files.document("0000001", "0000001", "bo", screen.screenId())).thenReturn(screenDocument);
        when(files.iaDocument("0000001", "0000001", "bo")).thenReturn(iaDocument);

        new FrdScreenIaMaterializer(screens, placements, files, solutions)
                .materialize("0000001", "0000001");

        assertThat(Files.readString(screenDocument)).doesNotContain("--- IA ---", "경로:");
        assertThat(Files.readString(iaDocument))
                .doesNotContain("tmp-0000002")
                .contains("- 경로: merc/master / 화면: bo-merc-master-list");
        verify(placements, never()).confirm(anyString(), any(), any());
    }

    @Test
    void 없는_메뉴에는_배치행을_추가하지_않는다() {
        String current = """
                ## 이름표
                - merc: 가맹점 관리

                --- 배치 ---
                - 경로: merc/master / 화면: bo-merc-master-list
                """;

        assertThatThrownBy(() -> FrdScreenIaMaterializer.addMenuPlacement(
                current, newScreen("0000002", "tmp-0000002", null), "customer/missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메뉴 경로를 찾지 못했습니다");
    }

    @Test
    void 팝업은_여는_화면의_이동관계가_있으면_IA를_쓴다() throws Exception {
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreenIaPlacementService placements = mock(FrdScreenIaPlacementService.class);
        stubReservation(placements);
        FrdScreenFiles files = mock(FrdScreenFiles.class);
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        FrdScreen opener = FrdScreen.pickedIn("0000001", "0000001", "bo-merc-list",
                "가맹점 목록", "bo-merc-list", null, null, "bo");
        FrdScreen popup = newScreen("0000002", "tmp-0000002", opener.screenId());
        FrdScreenIaPlacement placement = placement(popup.id(),
                FrdScreenIaPlacement.PlacementMode.OPENER, opener.screenId(),
                FrdScreenIaPlacement.ScreenKind.POPUP);
        Path openerDocument = temp.resolve("bo-merc-list.md");
        Path popupDocument = temp.resolve("tmp-0000002.md");
        Files.writeString(openerDocument, """
                --- 정의 ---
                - 구분: 버튼 / 앵커: detail / 라벨: 상세 / 이동modal: tmp-0000002
                """);
        when(screens.selectByFrdId("0000001")).thenReturn(List.of(opener, popup));
        when(solutions.read("0000001")).thenReturn(List.of());
        when(placements.of(popup.id())).thenReturn(placement);
        when(files.document("0000001", "0000001", "bo", opener.screenId())).thenReturn(openerDocument);
        when(files.document("0000001", "0000001", "bo", popup.screenId())).thenReturn(popupDocument);

        new FrdScreenIaMaterializer(screens, placements, files, solutions)
                .materialize("0000001", "0000001");

        assertThat(Files.readString(popupDocument)).doesNotContain("--- IA ---");
        verify(placements, never()).confirm(anyString(), any(), any());
    }

    private FrdScreen newScreen(String rowId, String screenId, String baseScreenId) {
        return new FrdScreen(rowId, "0000001", screenId, "신규 상세", baseScreenId,
                null, null, FrdScreen.State.WAITING, null, null, null,
                null, Instant.now(), "bo", "상세", null);
    }

    private FrdScreen newScreen(String rowId, String screenId, String screenName, String screenType) {
        return new FrdScreen(rowId, "0000001", screenId, screenName, null,
                null, null, FrdScreen.State.WAITING, null, null, null,
                null, Instant.now(), "bo", screenType, null);
    }

    private FrdScreenIaPlacement proposed(String rowId, FrdScreenIaPlacementService.Request request) {
        return new FrdScreenIaPlacement(rowId,
                FrdScreenIaPlacement.PlacementMode.valueOf(request.placementMode()), null,
                request.menuPathKey(), request.anchorScreenId(),
                FrdScreenIaPlacement.ScreenKind.valueOf(request.screenKind()),
                FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.valueOf(request.source()), null, Instant.now(), null);
    }

    private FrdScreenIaPlacement placement(String rowId,
                                           FrdScreenIaPlacement.PlacementMode mode,
                                           String anchor,
                                           FrdScreenIaPlacement.ScreenKind kind) {
        return new FrdScreenIaPlacement(rowId, mode, null, null, anchor, kind,
                FrdScreenIaPlacement.Status.PROPOSED, FrdScreenIaPlacement.Source.USER,
                null, Instant.now(), null);
    }

    private void stubReservation(FrdScreenIaPlacementService placements) {
        when(placements.reserveDevelopmentFileName(
                anyString(), any(FrdScreen.class), any(FrdScreenIaPlacement.class), anySet()))
                .thenAnswer(call -> {
                    FrdScreen screen = call.getArgument(1);
                    FrdScreenIaPlacement placement = call.getArgument(2);
                    return new FrdScreenIaPlacement(
                            placement.frdScreenId(), placement.placementMode(), placement.structureId(),
                            placement.menuPathKey(), placement.anchorScreenId(), placement.screenKind(),
                            placement.status(), placement.source(), "bo-screen-" + screen.id(),
                            Instant.now(), placement.updatedBy());
                });
    }
}
