package com.bizplay.builder.frd;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Git으로 FRD 워크트리의 생성·재사용·복구 계약을 잰다. */
class FrdWorkspaceTest {

    private static final String PROJECT_ID = "0000001";
    private static final String FRD_ID = "0000025";

    @TempDir Path dataRoot;

    private GitCommand git;
    private ProjectPaths paths;
    private FrdWorkspace workspaces;
    private Path clone;

    @BeforeEach
    void setUp() throws IOException {
        BuilderProperties properties = new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                dataRoot, Duration.ofMinutes(10), 4, 50, Duration.ofMinutes(2));
        git = new GitCommand();
        paths = new ProjectPaths(properties);
        workspaces = new FrdWorkspace(paths, git, properties);

        clone = paths.cloneDir(PROJECT_ID);
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("README.md"), "# 기획 저장소\n");
        run(clone, "init", "-q");
        run(clone, "config", "user.email", "t@example.com");
        run(clone, "config", "user.name", "시험");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "첫 커밋");
    }

    @Test
    void FRD_브랜치와_워크트리를_클론_HEAD에서_만든다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);

        assertThat(prepared.workspaceCreated()).isTrue();
        assertThat(prepared.branchCreated()).isTrue();
        assertThat(prepared.path()).isEqualTo(paths.frdWorktree(PROJECT_ID, FRD_ID).toAbsolutePath());
        assertThat(Files.readString(prepared.path().resolve("README.md"))).contains("기획 저장소");
        assertThat(run(prepared.path(), "branch", "--show-current").stdout().strip())
                .isEqualTo("frd/" + FRD_ID);
    }

    @Test
    void FRD를_삭제하면_전용_워크트리와_브랜치를_함께_정리한다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("작업중.md"), "저장하지 않은 변경");

        workspaces.discard(PROJECT_ID, FRD_ID);

        assertThat(prepared.path()).doesNotExist();
        assertThat(git.run(clone, Duration.ofSeconds(30),
                "show-ref", "--verify", "--quiet", "refs/heads/frd/" + FRD_ID).succeeded()).isFalse();
    }

    @Test
    void 다시_시작하면_사용자_변경이_있는_기존_워크트리를_그대로_쓴다() throws IOException {
        FrdWorkspace.Prepared first = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(first.path().resolve("기획중.md"), "저장하지 않은 변경");

        FrdWorkspace.Prepared second = workspaces.ensure(PROJECT_ID, FRD_ID);

        assertThat(second.workspaceCreated()).isFalse();
        assertThat(second.branchCreated()).isFalse();
        assertThat(Files.readString(second.path().resolve("기획중.md"))).isEqualTo("저장하지 않은 변경");
    }

    @Test
    void 작업을_초기화하면_기존_변경과_브랜치를_버리고_HEAD에서_다시_만든다() throws IOException {
        FrdWorkspace.Prepared first = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(first.path().resolve("기획중.md"), "버릴 변경");
        Files.writeString(first.path().resolve("README.md"), "수정된 내용");
        run(first.path(), "add", ".");
        run(first.path(), "commit", "-q", "-m", "버릴 커밋");

        FrdWorkspace.Prepared reset = workspaces.reset(PROJECT_ID, FRD_ID);

        assertThat(reset.path().resolve("기획중.md")).doesNotExist();
        assertThat(Files.readString(reset.path().resolve("README.md"))).contains("기획 저장소");
        assertThat(run(reset.path(), "rev-parse", "HEAD").stdout().strip())
                .isEqualTo(run(clone, "rev-parse", "HEAD").stdout().strip());
    }

    @Test
    void 작업_초기화도_다른_폴더가_같은_자리를_차지하면_삭제하지_않는다() throws IOException {
        Path collision = paths.frdWorktree(PROJECT_ID, FRD_ID);
        Files.createDirectories(collision);
        Files.writeString(collision.resolve("보존.txt"), "사용자 파일");

        assertThatThrownBy(() -> workspaces.reset(PROJECT_ID, FRD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다른 Git 작업");
        assertThat(Files.readString(collision.resolve("보존.txt"))).isEqualTo("사용자 파일");
    }

    @Test
    void 브랜치만_남았으면_그_브랜치로_워크트리를_다시_연결한다() {
        run(clone, "branch", "frd/" + FRD_ID, "HEAD");

        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);

        assertThat(prepared.workspaceCreated()).isTrue();
        assertThat(prepared.branchCreated()).isFalse();
        assertThat(run(prepared.path(), "branch", "--show-current").stdout().strip())
                .isEqualTo("frd/" + FRD_ID);
    }

    @Test
    void 모르는_폴더가_같은_자리를_차지하면_지우지_않고_거절한다() throws IOException {
        Path collision = paths.frdWorktree(PROJECT_ID, FRD_ID);
        Files.createDirectories(collision);
        Files.writeString(collision.resolve("보존.txt"), "사용자 파일");

        assertThatThrownBy(() -> workspaces.ensure(PROJECT_ID, FRD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다른 Git 작업");
        assertThat(Files.readString(collision.resolve("보존.txt"))).isEqualTo("사용자 파일");
    }

    @Test
    void 상태_전환이_실패하면_이번에_만든_워크트리와_브랜치를_치운다() {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);

        workspaces.rollback(prepared);

        assertThat(prepared.path()).doesNotExist();
        assertThat(git.run(clone, Duration.ofSeconds(30),
                "show-ref", "--verify", "--quiet", "refs/heads/" + prepared.branch()).succeeded()).isFalse();
    }

    @Test
    void 클론이_없으면_상태를_바꾸기_전에_거절한다() throws IOException {
        Path otherClone = paths.cloneDir("0000002");
        Files.createDirectories(otherClone);

        assertThatThrownBy(() -> workspaces.ensure("0000002", FRD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기획 저장소가 준비되지 않아");
        assertThat(paths.frdWorktree("0000002", FRD_ID)).doesNotExist();
    }

    @Test
    void 작업공간의_파일이_바뀌었을_때만_변경됨으로_판단한다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);

        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isFalse();

        Files.writeString(prepared.path().resolve("새 화면.html"), "<main>수정</main>");

        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isTrue();
    }

    @Test
    void 기존_화면을_제외하면_HTML과_MD를_HEAD_원본으로_되돌린다() throws IOException {
        Path pages = clone.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve("screen-a.html"), "<main>운영 화면</main>");
        Files.writeString(pages.resolve("screen-a.md"), "화면명: 운영 화면");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "화면 추가");
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path workPages = prepared.path().resolve("core/webview/pages");
        Files.writeString(workPages.resolve("screen-a.html"), "<main>FRD 수정안</main>");
        Files.writeString(workPages.resolve("screen-a.md"), "화면명: FRD 수정안");

        workspaces.discardScreenFiles(PROJECT_ID, FRD_ID, "webview", "screen-a");

        assertThat(Files.readString(workPages.resolve("screen-a.html"))).isEqualTo("<main>운영 화면</main>");
        assertThat(Files.readString(workPages.resolve("screen-a.md"))).isEqualTo("화면명: 운영 화면");
        assertThat(run(prepared.path(), "status", "--porcelain").stdout()).isBlank();
    }

    @Test
    void 신규_화면을_제외하면_워크트리의_HTML과_MD를_삭제한다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path pages = prepared.path().resolve("core/webview/pages");
        Files.createDirectories(pages);
        Path html = pages.resolve("tmp-0000049.html");
        Path markdown = pages.resolve("tmp-0000049.md");
        Files.writeString(html, "<main>신규 화면</main>");
        Files.writeString(markdown, "화면명: 신규 화면");
        run(prepared.path(), "add", ".");

        workspaces.discardScreenFiles(PROJECT_ID, FRD_ID, "webview", "tmp-0000049");

        assertThat(html).doesNotExist();
        assertThat(markdown).doesNotExist();
        assertThat(run(prepared.path(), "status", "--porcelain").stdout()).isBlank();
    }

    @Test
    void 신규_화면을_제외하면_다른_화면의_이동과_상위화면_참조도_지운다() throws IOException {
        Path pages = clone.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve("screen-a.md"), """
                --- IA ---
                - 종류: 화면

                --- 정의 ---
                - 구분: 버튼 / 앵커: detail / 라벨: 상세
                """);
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "화면 관계 추가");
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path workPages = prepared.path().resolve("core/webview/pages");
        Path source = workPages.resolve("screen-a.md");
        Files.writeString(source, Files.readString(source)
                .replace("- 종류: 화면", "- 종류: 화면 / 상위화면: tmp-0000049")
                .replace("라벨: 상세", "라벨: 상세 / 이동modal: tmp-0000049"));
        Files.writeString(workPages.resolve("tmp-0000049.html"), "<main>신규 화면</main>");
        Files.writeString(workPages.resolve("tmp-0000049.md"), "화면명: 신규 화면");

        workspaces.discardScreenFiles(PROJECT_ID, FRD_ID, "webview", "tmp-0000049");

        assertThat(Files.readString(source))
                .contains("- 종류: 화면", "구분: 버튼", "앵커: detail", "라벨: 상세")
                .doesNotContain("상위화면: tmp-0000049", "이동modal: tmp-0000049");
        assertThat(run(prepared.path(), "status", "--porcelain").stdout()).isBlank();
    }

    @Test
    void 작업공간_생성과_변경_확인은_동시에_실행하지_않는다() throws NoSuchMethodException {
        int modifiers = FrdWorkspace.class
                .getMethod("hasChanges", String.class, String.class)
                .getModifiers();

        assertThat(Modifier.isSynchronized(modifiers)).isTrue();
    }

    @Test
    void FRD_작업을_커밋하고_되돌려도_파일_변경은_보존한다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("새 화면.html"), "<main>수정</main>");

        FrdWorkspace.Commit commit = workspaces.commitChanges(PROJECT_ID, FRD_ID,
                "docs: FRD-025 작업 완료");

        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isFalse();
        assertThat(run(prepared.path(), "log", "-1", "--pretty=%s").stdout().strip())
                .isEqualTo("docs: FRD-025 작업 완료");

        workspaces.rollbackCommit(commit);

        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isTrue();
        assertThat(prepared.path().resolve("새 화면.html")).exists();
    }

    @Test
    void 작업_완료_커밋_전에_색인을_갱신하고_그_결과를_같은_커밋에_넣는다() throws IOException {
        Path verify = clone.resolve("verify");
        Files.createDirectories(verify);
        Files.writeString(verify.resolve("reindex.mjs"), """
                import { writeFileSync } from 'node:fs';
                writeFileSync('index.json', '{"indexed":true}\\n');
                writeFileSync('reindex-ran.txt', '실행됨\\n');
                """);
        Files.writeString(clone.resolve("index.json"), "{\"indexed\":false}\n");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "색인 도구 추가");
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("screen-new.html"), "<main>신규 화면</main>");

        workspaces.commitChanges(PROJECT_ID, FRD_ID, "docs: FRD-025 작업 완료");

        assertThat(Files.readString(prepared.path().resolve("reindex-ran.txt"))).isEqualTo("실행됨\n");
        assertThat(run(prepared.path(), "show", "HEAD:index.json").stdout()).isEqualTo("{\"indexed\":true}\n");
        assertThat(run(prepared.path(), "show", "HEAD:reindex-ran.txt").stdout()).isEqualTo("실행됨\n");
        assertThat(run(prepared.path(), "show", "--pretty=", "--name-only", "HEAD").stdout())
                .contains("index.json", "reindex-ran.txt", "screen-new.html");
    }

    @Test
    void 색인_스크립트가_없어도_작업_완료_커밋은_성공한다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("화면.html"), "<main>수정 화면</main>");

        FrdWorkspace.Commit commit = workspaces.commitChanges(PROJECT_ID, FRD_ID,
                "docs: FRD-025 작업 완료");

        assertThat(commit.after()).isNotEqualTo(commit.before());
        assertThat(run(prepared.path(), "show", "HEAD:화면.html").stdout()).isEqualTo("<main>수정 화면</main>");
    }

    @Test
    void 변경_예정_기능정의서를_작업트리에_쓰고_별도_커밋으로_확정한다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path page = prepared.path().resolve("core/webview/pages/wv-appr-write.md");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "현재 기능정의서");
        workspaces.commitChanges(PROJECT_ID, FRD_ID, FrdWorkspace.completionMessage("FRD-025"));

        FrdWorkspace.Commit commit = workspaces.materializeTobeDocuments(PROJECT_ID, FRD_ID, "DR-001",
                List.of(new FrdWorkspace.TobeDocument(
                        "webview", "wv-appr-write", "변경 예정 기능정의서")));

        assertThat(Files.readString(page)).isEqualTo("변경 예정 기능정의서");
        assertThat(run(prepared.path(), "rev-parse", "HEAD").stdout().strip()).isEqualTo(commit.after());
        assertThat(run(prepared.path(), "log", "-1", "--pretty=%s").stdout().strip())
                .isEqualTo("docs: DR-001 기능정의서 확정");
        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isFalse();
    }

    @Test
    void 전달_화면_ID로_HTML과_MD를_옮기고_임시_파일을_같은_커밋에서_지운다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path pages = prepared.path().resolve("core/backoffice/pages");
        Files.createDirectories(pages);
        Path sourceHtml = pages.resolve("tmp-0000067.html");
        Path sourceMd = pages.resolve("tmp-0000067.md");
        Files.writeString(sourceHtml, "<a href=\"tmp-0000068.html\">tmp-0000067</a>");
        Files.writeString(sourceMd, "old tmp document");
        workspaces.commitChanges(PROJECT_ID, FRD_ID, FrdWorkspace.completionMessage("FRD-039"));

        FrdWorkspace.Commit commit = workspaces.materializeTobeDocuments(PROJECT_ID, FRD_ID, "DR-039",
                List.of(
                        new FrdWorkspace.TobeDocument("backoffice", "tmp-0000067",
                                "backoffice-list-67", "new backoffice-list-67 document"),
                        new FrdWorkspace.TobeDocument("backoffice", "tmp-0000068",
                                "backoffice-detail-68", "new backoffice-detail-68 document")));

        assertThat(sourceHtml).doesNotExist();
        assertThat(sourceMd).doesNotExist();
        assertThat(pages.resolve("backoffice-list-67.html")).hasContent(
                "<a href=\"backoffice-detail-68.html\">backoffice-list-67</a>");
        assertThat(pages.resolve("backoffice-list-67.md")).hasContent("new backoffice-list-67 document");
        assertThat(run(prepared.path(), "show", "--pretty=", "--name-status", commit.after()).stdout())
                .contains("backoffice-list-67.html", "backoffice-list-67.md", "tmp-0000067");
    }

    @Test
    void 개발요청_전_검사가_막히면_전달용_커밋과_파일을_함께_되돌린다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path pages = prepared.path().resolve("core/backoffice/pages");
        Files.createDirectories(pages);
        Path sourceHtml = pages.resolve("tmp-0000067.html");
        Files.writeString(sourceHtml, "<main>tmp-0000067</main>");
        workspaces.commitChanges(PROJECT_ID, FRD_ID, FrdWorkspace.completionMessage("FRD-039"));

        FrdWorkspace.Commit commit = workspaces.materializeTobeDocuments(PROJECT_ID, FRD_ID, "DR-039",
                List.of(new FrdWorkspace.TobeDocument("backoffice", "tmp-0000067",
                        "backoffice-list-67", "new document")));
        workspaces.rollbackMaterialization(commit);

        assertThat(run(prepared.path(), "rev-parse", "HEAD").stdout().strip()).isEqualTo(commit.before());
        assertThat(sourceHtml).exists();
        assertThat(pages.resolve("backoffice-list-67.html")).doesNotExist();
        assertThat(pages.resolve("backoffice-list-67.md")).doesNotExist();
        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isFalse();
    }

    @Test
    void 기능정의서_확정_커밋까지_생겼어도_FRD로_되돌리면_두_커밋을_함께_푼다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Path page = prepared.path().resolve("core/webview/pages/wv-appr-write.md");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "현재 기능정의서");
        String completion = FrdWorkspace.completionMessage("FRD-025");
        workspaces.commitChanges(PROJECT_ID, FRD_ID, completion);
        workspaces.materializeTobeDocuments(PROJECT_ID, FRD_ID, "DR-001",
                List.of(new FrdWorkspace.TobeDocument(
                        "webview", "wv-appr-write", "변경 예정 기능정의서")));

        assertThat(workspaces.uncommitCompletion(PROJECT_ID, FRD_ID, completion)).isTrue();

        assertThat(run(prepared.path(), "log", "-1", "--pretty=%s").stdout().strip()).isEqualTo("첫 커밋");
        assertThat(Files.readString(page)).isEqualTo("변경 예정 기능정의서");
        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isTrue();
    }

    @Test
    void 색인_갱신이_실패해도_작업_완료_커밋은_성공한다() throws IOException {
        Path verify = clone.resolve("verify");
        Files.createDirectories(verify);
        Files.writeString(verify.resolve("reindex.mjs"), "process.exit(1);\n");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "실패하는 색인 도구 추가");
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("화면.html"), "<main>수정 화면</main>");

        FrdWorkspace.Commit commit = workspaces.commitChanges(PROJECT_ID, FRD_ID,
                "docs: FRD-025 작업 완료");

        assertThat(commit.after()).isNotEqualTo(commit.before());
        assertThat(run(prepared.path(), "show", "HEAD:화면.html").stdout()).isEqualTo("<main>수정 화면</main>");
    }

    @Test
    void 완료_커밋을_풀면_파일_변경이_다시_수정_중으로_돌아온다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("새 화면.html"), "<main>modified</main>");
        String message = FrdWorkspace.completionMessage("FRD-025");
        workspaces.commitChanges(PROJECT_ID, FRD_ID, message);
        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isFalse();
        assertThat(workspaces.hasCompletionToReopen(PROJECT_ID, FRD_ID, message)).isTrue();

        // ⭐ 「FRD 로 되돌리기」가 부른다 — 완료가 만든 커밋을 풀어야 「작업 완료」 버튼이 다시 켜진다.
        assertThat(workspaces.uncommitCompletion(PROJECT_ID, FRD_ID, message)).isTrue();

        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isTrue();
        assertThat(workspaces.hasCompletionToReopen(PROJECT_ID, FRD_ID, message)).isFalse();
        assertThat(prepared.path().resolve("새 화면.html")).hasContent("<main>modified</main>");
        assertThat(run(prepared.path(), "log", "-1", "--pretty=%s").stdout().strip()).isEqualTo("첫 커밋");
    }

    @Test
    void HEAD_가_완료_커밋이_아니면_풀지_않는다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("메모.md"), "손으로 커밋한 것");
        run(prepared.path(), "add", ".");
        run(prepared.path(), "commit", "-q", "-m", "docs: 손으로 넣은 커밋");

        // ⛔ 남이 만든 커밋을 풀면 안 된다 — 메시지가 다르면 손대지 않는다.
        assertThat(workspaces.uncommitCompletion(PROJECT_ID, FRD_ID,
                FrdWorkspace.completionMessage("FRD-025"))).isFalse();
        assertThat(run(prepared.path(), "log", "-1", "--pretty=%s").stdout().strip())
                .isEqualTo("docs: 손으로 넣은 커밋");
        assertThat(workspaces.hasChanges(PROJECT_ID, FRD_ID)).isFalse();
    }

    @Test
    void 워크트리가_없으면_풀_것이_없다() {
        assertThat(workspaces.uncommitCompletion(PROJECT_ID, FRD_ID,
                FrdWorkspace.completionMessage("FRD-025"))).isFalse();
    }

    // ── 기획 저장소 최신 반영 (2026-08-25 실측: 워크트리 manifest 가 /4, 클론 검사기가 /5 라 검사기가 첫 관문에서 멈췄다) ──

    @Test
    void 클론이_앞서_가면_워크트리에_병합해_최신으로_맞춘다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("새 화면.html"), "<main>수정 중</main>");
        Files.writeString(clone.resolve("manifest.json"), "{\"toolchain\":\"we-adk-toolchain/5\"}");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "chore: 판 /5");
        assertThat(workspaces.isBehindClone(PROJECT_ID, FRD_ID)).isTrue();

        assertThat(workspaces.syncWithClone(PROJECT_ID, FRD_ID)).isEqualTo(FrdWorkspace.Sync.MERGED);

        assertThat(prepared.path().resolve("manifest.json")).hasContent("{\"toolchain\":\"we-adk-toolchain/5\"}");
        // ⭐ 수정 중인 파일은 그대로다 — 병합이 사람 작업을 건드리지 않는다.
        assertThat(prepared.path().resolve("새 화면.html")).hasContent("<main>수정 중</main>");
        assertThat(workspaces.isBehindClone(PROJECT_ID, FRD_ID)).isFalse();
    }

    @Test
    void 최신_반영_결과에_기본_브랜치에서_바뀐_파일을_돌려준다() throws IOException {
        workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.createDirectories(clone.resolve("design-guide/styles"));
        Files.writeString(clone.resolve("design-guide/styles/backoffice.css"), "body{font-size:14px}");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "design: 공통 CSS 갱신");

        FrdWorkspace.SyncResult result = workspaces.syncWithCloneDetails(PROJECT_ID, FRD_ID);

        assertThat(result.state()).isEqualTo(FrdWorkspace.Sync.MERGED);
        assertThat(result.changedPaths()).contains("design-guide/styles/backoffice.css");
    }

    @Test
    void 최신_화면_확인_표식은_같은_클론_HEAD를_확인해야_지워진다() {
        workspaces.ensure(PROJECT_ID, FRD_ID);
        String cloneHead = run(clone, "rev-parse", "HEAD").stdout().strip();
        workspaces.requireLatestReview(PROJECT_ID, FRD_ID, cloneHead, "0000082");

        assertThat(workspaces.pendingLatestReview(PROJECT_ID, FRD_ID))
                .isEqualTo(new FrdWorkspace.PendingReview(cloneHead, "0000082"));
        assertThat(workspaces.confirmLatestReview(PROJECT_ID, FRD_ID, "old-head")).isFalse();
        assertThat(workspaces.confirmLatestReview(PROJECT_ID, FRD_ID, cloneHead)).isTrue();
        assertThat(workspaces.pendingLatestReview(PROJECT_ID, FRD_ID)).isNull();
    }

    @Test
    void 이미_최신이면_손대지_않는다() {
        workspaces.ensure(PROJECT_ID, FRD_ID);

        assertThat(workspaces.isBehindClone(PROJECT_ID, FRD_ID)).isFalse();
        assertThat(workspaces.syncWithClone(PROJECT_ID, FRD_ID)).isEqualTo(FrdWorkspace.Sync.UP_TO_DATE);
    }

    @Test
    void 수정_중인_파일과_겹치면_병합하지_않고_알린다() throws IOException {
        FrdWorkspace.Prepared prepared = workspaces.ensure(PROJECT_ID, FRD_ID);
        Files.writeString(prepared.path().resolve("README.md"), "# 사람이 고치는 중\n");
        Files.writeString(clone.resolve("README.md"), "# 기획 저장소 (새 판)\n");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "docs: README 갱신");

        assertThat(workspaces.syncWithClone(PROJECT_ID, FRD_ID)).isEqualTo(FrdWorkspace.Sync.CONFLICT);

        // ⛔ 사람 작업이 이긴다 — 덮지도, 병합 중 상태로 남기지도 않는다.
        assertThat(prepared.path().resolve("README.md")).hasContent("# 사람이 고치는 중\n");
        assertThat(prepared.path().resolve(".git")).isRegularFile();
        assertThat(git.run(prepared.path(), Duration.ofSeconds(30), "rev-parse", "--verify", "--quiet", "MERGE_HEAD")
                .succeeded()).as("병합 중 상태가 남지 않는다").isFalse();
        assertThat(workspaces.isBehindClone(PROJECT_ID, FRD_ID)).isTrue();
    }

    @Test
    void 워크트리가_없으면_맞출_것이_없다() {
        assertThat(workspaces.syncWithClone(PROJECT_ID, FRD_ID)).isEqualTo(FrdWorkspace.Sync.NO_WORKTREE);
        assertThat(workspaces.isBehindClone(PROJECT_ID, FRD_ID)).isFalse();
    }

    private GitResult run(Path directory, String... args) {
        GitResult result = git.run(directory, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
        return result;
    }
}
