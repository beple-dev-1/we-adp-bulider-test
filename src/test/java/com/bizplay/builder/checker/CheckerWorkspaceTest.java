package com.bizplay.builder.checker;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.project.ProjectPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>진짜 git 을 쓴다.</b> 워크트리를 따고 치우는 것은 대역으로 재봐야 아무것도 증명이 안 된다 —
 * 「자리가 남아 다음 검사가 막힌다」가 이 장치의 유일한 실패 방식인데 그게 git 의 행동이기 때문이다.
 *
 * <p>검사기(node)만 대역이다. 그건 {@link NodeCheckerCommandTest} 가 따로 잰다.
 */
class CheckerWorkspaceTest {

    private static final String PROJECT_ID = "0000001";

    @TempDir Path dataRoot;

    private RecordingChecker checker;
    private CheckerWorkspace workspaces;
    private ProjectPaths paths;
    private Path clone;

    @BeforeEach
    void setUp() throws IOException {
        BuilderProperties properties = new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                dataRoot, Duration.ofMinutes(10), 4, 50, Duration.ofMinutes(2));
        paths = new ProjectPaths(properties);
        GitCommand git = new GitCommand();
        checker = new RecordingChecker();
        workspaces = new CheckerWorkspace(paths, git, new DraftChecker(checker), properties);

        clone = paths.cloneDir(PROJECT_ID);
        Files.createDirectories(clone.resolve("reqs"));
        Files.writeString(clone.resolve("reqs/RQ-001.md"), "# 원래 있던 것\n");
        git.run(clone, Duration.ofSeconds(30), "init", "-q");
        git.run(clone, Duration.ofSeconds(30), "config", "user.email", "t@example.com");
        git.run(clone, Duration.ofSeconds(30), "config", "user.name", "시험");
        git.run(clone, Duration.ofSeconds(30), "add", ".");
        git.run(clone, Duration.ofSeconds(30), "commit", "-q", "-m", "첫 커밋");
    }

    @Test
    void 검사는_클론이_아니라_따로_딴_자리에서_돈다() {
        workspaces.check(PROJECT_ID, "reqs/RQ-001.md", "# 후보다\n");

        assertThat(checker.rootsSeen).isNotEmpty();
        // ⛔ 클론에서 돌면 두 사람이 동시에 저장할 때 서로의 초안이 섞인다.
        assertThat(checker.rootsSeen).noneMatch(it -> it.equals(clone));
    }

    /**
     * ⚠ 후보가 그 자리에 실제로 얹힌 채로 검사가 돌아야 한다 — 안 그러면 재는 것이 없다.
     *
     * <p>⚠ <b>글자를 정확히 비교하지 않는다.</b> git 이 워크트리로 체크아웃할 때 줄바꿈을
     * 바꿔 놓는다({@code core.autocrlf} — 윈도우 실측). 여기서 재는 것은 <b>얹기 전과 후가
     * 갈렸나</b>이고 줄바꿈은 그 판정과 무관하다.
     */
    @Test
    void 얹은_후보를_검사기가_실제로_본다() {
        workspaces.check(PROJECT_ID, "reqs/RQ-001.md", "# 후보다\n");

        assertThat(checker.contentsSeen).hasSize(2);
        assertThat(checker.contentsSeen.get(0)).contains("원래 있던 것").doesNotContain("후보");
        assertThat(checker.contentsSeen.get(1)).contains("후보");
    }

    /**
     * ⛔ <b>자리를 남기면 다음 검사가 {@code already exists} 로 막힌다</b> —
     * 그러면 그 프로젝트의 저장이 영영 안 된다.
     */
    @Test
    void 검사가_끝나면_자리를_치운다() {
        workspaces.check(PROJECT_ID, "reqs/RQ-001.md", "# 후보다\n");

        assertThat(paths.worktreeRoot(PROJECT_ID).resolve("check")).doesNotExist();
    }

    @Test
    void 두_번_이어서_검사해도_막히지_않는다() {
        workspaces.check(PROJECT_ID, "reqs/RQ-001.md", "# 첫째\n");
        DraftCheckResult second = workspaces.check(PROJECT_ID, "reqs/RQ-001.md", "# 둘째\n");

        assertThat(second.verdict()).isEqualTo(DraftCheckResult.Verdict.GREEN);
        assertThat(checker.rootsSeen).hasSize(4);   // 두 검사 × (얹기 전 + 얹은 뒤)
    }

    /**
     * 서버가 검사 도중에 죽으면 자리가 남는다. 다음 검사가 <b>먼저 치우고 딴다</b>는 것을 잰다 —
     * 이게 없으면 사람이 손으로 지워 줘야 한다.
     */
    @Test
    void 앞_검사가_남긴_자리가_있어도_다시_딴다() throws IOException {
        Path leftover = paths.worktreeRoot(PROJECT_ID).resolve("check");
        Files.createDirectories(leftover);
        Files.writeString(leftover.resolve("찌꺼기.txt"), "앞 검사가 죽으며 남긴 것");

        DraftCheckResult result = workspaces.check(PROJECT_ID, "reqs/RQ-001.md", "# 후보다\n");

        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.GREEN);
        assertThat(leftover).doesNotExist();
    }

    /** ⛔ 클론이 아직 없는 프로젝트를 초록으로 읽으면 검사 없이 저장이 열린다. */
    @Test
    void 클론이_없으면_판정을_못_낸_것으로_둔다() {
        DraftCheckResult result = workspaces.check("0000002", "reqs/RQ-001.md", "x");

        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.UNKNOWN);
        assertThat(result.canSave()).isFalse();
    }

    /** 검사기가 무엇을 봤는지 적어 두는 대역. */
    private static final class RecordingChecker implements CheckerCommand {

        private final List<Path> rootsSeen = new ArrayList<>();
        private final List<String> contentsSeen = new ArrayList<>();

        @Override
        public CheckReport run(Path checkerHome, Path repoRoot) {
            rootsSeen.add(repoRoot);
            Path target = repoRoot.resolve("reqs/RQ-001.md");
            try {
                contentsSeen.add(Files.exists(target) ? Files.readString(target) : null);
            } catch (IOException e) {
                contentsSeen.add(null);
            }
            return new CheckReport(CheckReport.Verdict.CHECKED, List.of());
        }
    }
}
