package com.bizplay.builder.frd;

import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrdCanvasServiceTest {

    @TempDir Path temp;

    @Test
    void 전체_맵의_작업_화면은_상세_화면과_같은_추가_순서로_나온다() {
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(temp.resolve("clone-order"));
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(temp.resolve("worktree-order"));
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-z", "먼저 추가한 화면",
                        "screen-z", null, null, "webview"),
                FrdScreen.pickedIn("0000004", "0000002", "screen-a", "나중에 추가한 화면",
                        "screen-a", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.nodes()).extracting(FrdCanvasService.CanvasNode::screenId)
                .containsExactly("screen-z", "screen-a");
    }

    @Test
    void 화면_탐색기에_신규_여부와_관리번호와_수정_표시를_제공한다() {
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(temp.resolve("clone-meta"));
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(temp.resolve("worktree-meta"));
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreen existing = new FrdScreen("0000003", "0000002", "screen-a", "화면 A",
                "screen-a", null, null, FrdScreen.State.GENERATED, null, "문구 수정",
                null, null, null, "webview", null, null);
        FrdScreen added = FrdScreen.drafted("0000004", "0000002", "tmp-0000004",
                "신규 화면", "상세", null, "webview");
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(existing, added));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());
        FrdScreenIaPlacementService placements = mock(FrdScreenIaPlacementService.class);
        when(placements.all("0000002")).thenReturn(List.of());
        ScreenStandardIdMapper standardIds = mock(ScreenStandardIdMapper.class);
        when(standardIds.selectByProject("0000001")).thenReturn(List.of(
                new ScreenStandardId("0000005", "0000001", "screen-a",
                        "PS-WV-MRC-010-L01", ScreenStandardId.Origin.S, 1)));

        FrdCanvasService.Canvas canvas = new FrdCanvasService(
                paths, screens, solutions, placements, standardIds)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.nodes()).filteredOn(node -> node.screenId().equals("screen-a"))
                .singleElement().satisfies(node -> {
                    assertThat(node.newScreen()).isFalse();
                    assertThat(node.managementNumberLabel()).isEqualTo("PS-WV-MRC-010-L01-S");
                    assertThat(node.modified()).isTrue();
                });
        assertThat(canvas.nodes()).filteredOn(FrdCanvasService.CanvasNode::newScreen)
                .singleElement().satisfies(node -> {
                    assertThat(node.managementNumberLabel()).isEqualTo("미채번");
                    assertThat(node.modified()).isFalse();
                });
    }

    @Test
    void 기준_저장소와_워크트리_MD를_비교해_현재_추가_삭제_연결을_만든다() throws Exception {
        Path clone = temp.resolve("clone");
        Path worktree = temp.resolve("worktree");
        page(clone, "screen-a", """
                화면명: 화면 A
                - 구분: 이동 / 라벨: 조회 / 이동: screen-b / 앵커: screen-a-e01
                - 구분: 이동 / 라벨: 삭제 / 이동: screen-b / 앵커: screen-a-e02
                """);
        page(clone, "screen-b", "화면명: 화면 B\n");
        page(worktree, "screen-a", """
                화면명: 화면 A
                - 구분: 이동 / 라벨: 조회 / 이동: screen-b / 앵커: screen-a-e01
                - 구분: 이동 / 라벨: 저장 / 이동: screen-b / 앵커: screen-a-e03
                """);
        page(worktree, "screen-b", "화면명: 화면 B\n");

        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(worktree);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-a", "화면 A", "screen-a", null, null, "webview"),
                FrdScreen.pickedIn("0000004", "0000002", "screen-b", "화면 B", "screen-b", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.nodes()).extracting(FrdCanvasService.CanvasNode::screenId)
                .containsExactly("screen-a", "screen-b");
        assertThat(canvas.relations()).extracting(FrdCanvasService.CanvasRelation::state)
                .containsExactlyInAnyOrder(FrdCanvasService.State.CURRENT,
                        FrdCanvasService.State.ADDED, FrdCanvasService.State.REMOVED);
        assertThat(canvas.relations()).anySatisfy(link -> {
            assertThat(link.anchor()).isEqualTo("screen-a-e03");
            assertThat(link.label()).isEqualTo("저장");
            assertThat(link.state()).isEqualTo(FrdCanvasService.State.ADDED);
        });
    }

    @Test
    void 연결선_이름은_긴_구현_설명을_빼고_업무_행동만_보여준다() throws Exception {
        Path clone = temp.resolve("clone-label");
        page(clone, "screen-a", "- 구분: 이동 / 이동: screen-b / 앵커: a-e01 / 해설: 상세검색 필터 열기 (onclick과 라우트 설명)\n");
        page(clone, "screen-b", "화면명: 화면 B\n");
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(temp.resolve("missing-label"));
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-a", "화면 A", "screen-a", null, null, "webview"),
                FrdScreen.pickedIn("0000004", "0000002", "screen-b", "화면 B", "screen-b", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.relations()).singleElement()
                .extracting(FrdCanvasService.CanvasRelation::label).isEqualTo("상세검색 필터 열기");
    }

    @Test
    void 신규_화면_HTML의_이동_대상도_캔버스_연결로_읽는다() throws Exception {
        Path clone = temp.resolve("clone-html-link");
        Path worktree = temp.resolve("worktree-html-link");
        page(clone, "screen-detail", "화면명: 상세 화면\n");
        page(worktree, "screen-detail", "화면명: 상세 화면\n");
        html(worktree, "screen-new", """
                <!doctype html>
                <html><body>
                  <button data-element-id="screen-new-e09"
                          data-nav-target="screen-detail">상세 보기</button>
                  <button data-element-id="screen-new-e10"
                          data-nav-target="screen-detail">다른 행 상세 보기</button>
                </body></html>
                """);

        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(worktree);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-new", "신규 화면", null, null, null, "webview"),
                FrdScreen.pickedIn("0000004", "0000002", "screen-detail", "상세 화면", "screen-detail", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.relations()).singleElement().satisfies(link -> {
            assertThat(link.sourceScreenId()).isEqualTo("screen-new");
            assertThat(link.targetScreenId()).isEqualTo("screen-detail");
            assertThat(link.anchor()).isEqualTo("screen-new-e09");
            assertThat(link.label()).isEqualTo("상세 보기");
            assertThat(link.state()).isEqualTo(FrdCanvasService.State.ADDED);
        });
    }

    @Test
    void 신규_화면은_MD가_없어도_HTML의_클릭_요소를_연결_후보로_보여준다() throws Exception {
        Path clone = temp.resolve("clone-click-elements");
        Path worktree = temp.resolve("worktree-click-elements");
        html(worktree, "screen-new", """
                <!doctype html>
                <html><body>
                  <button data-element-id="screen-new-e01">조회</button>
                  <a id="screen-new-e02" href="#">신규 등록</a>
                  <input data-element-id="screen-new-e03" aria-label="검색어" type="text">
                </body></html>
                """);

        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(worktree);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-new", "신규 화면",
                        null, null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.nodes()).singleElement().satisfies(node ->
                assertThat(node.clickableElements())
                        .extracting(FrdCanvasService.CanvasElement::anchor,
                                FrdCanvasService.CanvasElement::label,
                                FrdCanvasService.CanvasElement::kind)
                        .containsExactlyInAnyOrder(
                                org.assertj.core.groups.Tuple.tuple("screen-new-e01", "조회", "버튼"),
                                org.assertj.core.groups.Tuple.tuple("screen-new-e02", "신규 등록", "링크"),
                                org.assertj.core.groups.Tuple.tuple("screen-new-e03", "검색어", "입력")));
    }

    @Test
    void MD가_있는_화면은_HTML의_이동_정보를_다시_읽지_않는다() throws Exception {
        Path clone = temp.resolve("clone-md-contract");
        Path worktree = temp.resolve("worktree-md-contract");
        page(clone, "screen-detail", "화면명: 상세 화면\n");
        page(worktree, "screen-detail", "화면명: 상세 화면\n");
        page(worktree, "screen-new", "화면명: 신규 화면\n--- 정의 ---\n");
        html(worktree, "screen-new", """
                <!doctype html>
                <html><body>
                  <button data-element-id="screen-new-e09"
                          data-nav-target="screen-detail">상세 보기</button>
                </body></html>
                """);

        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(worktree);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-new", "신규 화면", null, null, null, "webview"),
                FrdScreen.pickedIn("0000004", "0000002", "screen-detail", "상세 화면", "screen-detail", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.relations()).isEmpty();
    }

    @Test
    void FRD_주변_범위는_작업화면과_직접_연결된_화면만_남긴다() throws Exception {
        Path clone = temp.resolve("clone-neighbor");
        page(clone, "screen-a", "- 구분: 이동 / 이동: screen-b / 앵커: a-e01\n");
        page(clone, "screen-b", "- 구분: 이동 / 이동: screen-c / 앵커: b-e01\n");
        page(clone, "screen-c", "화면명: 화면 C\n");
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(temp.resolve("missing"));
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-a", "화면 A", "screen-a", null, null, "webview"),
                FrdScreen.pickedIn("0000004", "0000002", "screen-b", "화면 B", "screen-b", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of());

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.nodes()).extracting(FrdCanvasService.CanvasNode::screenId)
                .containsExactly("screen-a", "screen-b");
        assertThat(canvas.relations()).hasSize(1);
    }

    @Test
    void 작업화면_주변에서는_관련화면끼리의_연결선을_숨긴다() throws Exception {
        Path clone = temp.resolve("clone-direct-lines");
        page(clone, "screen-work", """
                - 구분: 이동 / 이동: screen-b / 앵커: work-e01
                - 구분: 이동 / 이동: screen-c / 앵커: work-e02
                """);
        page(clone, "screen-b", "- 구분: 이동 / 이동: screen-c / 앵커: b-e01\n");
        page(clone, "screen-c", "화면명: 화면 C\n");
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(temp.resolve("missing-direct-lines"));
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of(
                FrdScreen.pickedIn("0000003", "0000002", "screen-work", "작업 화면", "screen-work", null, null, "webview")));
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of(
                solution("screen-work"), solution("screen-b"), solution("screen-c")));

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", false, null);

        assertThat(canvas.relations())
                .extracting(FrdCanvasService.CanvasRelation::sourceScreenId,
                        FrdCanvasService.CanvasRelation::targetScreenId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("screen-work", "screen-b"),
                        org.assertj.core.groups.Tuple.tuple("screen-work", "screen-c"));
    }

    @Test
    void 프로젝트_전체_범위는_연결이_없는_솔루션_화면도_보여준다() {
        ProjectPaths paths = mock(ProjectPaths.class);
        when(paths.cloneDir("0000001")).thenReturn(temp.resolve("empty-clone"));
        when(paths.frdWorktree("0000001", "0000002")).thenReturn(temp.resolve("missing"));
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        when(screens.selectByFrdId("0000002")).thenReturn(List.of());
        SolutionScreenReader solutions = mock(SolutionScreenReader.class);
        when(solutions.read("0000001")).thenReturn(List.of(
                new com.bizplay.builder.solution.SolutionScreen(
                        "screen-alone", "독립 화면", "webview", "화면", null, null,
                        null, null, null, null, List.of(), List.of(), null, List.of(), false, null)));

        FrdCanvasService.Canvas canvas = new FrdCanvasService(paths, screens, solutions)
                .read("0000001", "0000002", true, null);

        assertThat(canvas.nodes()).extracting(FrdCanvasService.CanvasNode::screenId)
                .containsExactly("screen-alone");
    }

    private void page(Path repository, String screenId, String md) throws Exception {
        Path pages = repository.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve(screenId + ".md"), md);
    }

    private void html(Path repository, String screenId, String html) throws Exception {
        Path pages = repository.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve(screenId + ".html"), html);
    }

    private com.bizplay.builder.solution.SolutionScreen solution(String screenId) {
        return new com.bizplay.builder.solution.SolutionScreen(
                screenId, screenId, "webview", "화면", null, null,
                null, null, null, null, List.of(), List.of(), null, List.of(), false, null);
    }
}
