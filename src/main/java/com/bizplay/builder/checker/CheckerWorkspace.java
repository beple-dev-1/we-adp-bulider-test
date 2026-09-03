package com.bizplay.builder.checker;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 검사에만 쓰는 레포 사본 한 채를 딴다.
 *
 * <p>⛔ <b>공용 클론에서 검사하지 마라.</b> 클론은 그 프로젝트의 모든 기획자가 나눠 쓴다 —
 * 거기에 후보 파일을 얹으면 두 사람이 동시에 저장할 때 <b>서로의 초안이 섞이고,
 * 「얹기 전」이 남의 초안을 포함한 상태가 되어 차이 판정이 통째로 거짓이 된다.</b>
 *
 * <p>⚠ <b>이것은 계획 2 Task 6(워크트리) 전체가 아니다.</b> BRD 작업용 워크트리는 브랜치를 갖고
 * 커밋·밀기까지 가지만, 여기 것은 <b>읽고 버리는 것</b>이라 브랜치도 안 만든다({@code --detach}).
 * Task 6 이 서면 그쪽 장치로 옮겨 붙일 수 있다 — <b>지금 그것을 미리 만들지 않는다.</b>
 *
 * <p>⚠ 워크트리는 오브젝트를 클론과 <b>공유</b>하므로 148MB 를 다시 받지 않는다.
 */
@Component
public class CheckerWorkspace {

    private static final Logger log = LoggerFactory.getLogger(CheckerWorkspace.class);

    /** 워크트리 뿌리 아래 이 이름 하나를 쓴다. ⚠ 자리 글자를 만드는 곳은 {@link ProjectPaths} 다. */
    private static final String DIR_NAME = "check";

    private final ProjectPaths paths;
    private final GitCommand git;
    private final DraftChecker drafts;
    private final BuilderProperties properties;

    public CheckerWorkspace(ProjectPaths paths, GitCommand git, DraftChecker drafts,
                            BuilderProperties properties) {
        this.paths = paths;
        this.git = git;
        this.drafts = drafts;
        this.properties = properties;
    }

    /**
     * 후보 하나를 검사한다 — <b>자리를 따고 · 재고 · 치운다.</b>
     *
     * <p>⛔ <b>자리를 남기지 마라.</b> 남은 워크트리는 다음 검사에서
     * {@code already exists} 로 걸려 <b>그 프로젝트의 저장이 영영 막힌다.</b> 그래서 지우기를
     * {@code finally} 에 두고, 딸 때도 <b>먼저 치우고 딴다.</b>
     */
    public DraftCheckResult check(String projectId, String repoRelativePath, String content) {
        Path clone = paths.cloneDir(projectId);
        Path workspace = paths.worktreeRoot(projectId).resolve(DIR_NAME);
        if (!Files.isDirectory(clone.resolve(".git"))) {
            // 아직 클론이 안 끝났거나 실패한 프로젝트다. ⛔ 초록으로 읽지 않는다.
            log.warn("검사할 클론이 없다 projectId={}", projectId);
            return new DraftCheckResult(DraftCheckResult.Verdict.UNKNOWN, null, null);
        }
        try {
            open(clone, workspace);
            // ⭐ 검사기는 **클론에 깔린 것**을 쓰고, 검사할 자리는 워크트리다 (2026-08-14 실측).
            //    워크트리에는 node_modules 가 안 딸려오므로 여기를 뒤집으면 검사마다 npm install 을 한다.
            return drafts.check(clone, workspace, repoRelativePath, content);
        } finally {
            remove(clone, workspace);
        }
    }

    /**
     * ⚠ <b>먼저 치우고 딴다.</b> 앞 검사가 서버 강제 종료로 끊겼으면 자리가 남아 있고,
     * 그 상태로 {@code add} 하면 실패한다. 치우기는 <b>여러 번 불러도 되게</b> 만들어 뒀다.
     *
     * <p>⚠ {@code --detach} 다 — 브랜치를 만들지 않는다. 읽고 버릴 자리에 브랜치를 만들면
     * 그 이름이 남아 <b>다음에 같은 이름으로 못 딴다.</b>
     */
    private void open(Path clone, Path workspace) {
        remove(clone, workspace);
        GitResult result = git.run(clone, properties.checkTimeout(),
                "worktree", "add", "--detach", workspace.toString(), "HEAD");
        if (!result.succeeded()) {
            throw new IllegalStateException(
                    "검사할 자리를 따지 못했다: " + GitCommand.mask(result.stderr()).strip());
        }
    }

    /**
     * 멱등이다 — 없어도 조용히 지나간다. ⛔ 여기서 던지면 검사 결과를 삼킨다.
     *
     * <p>⛔ <b>{@code git worktree remove} 하나로는 모자란다 (2026-08-14 실측).</b> 서버가 검사 도중에
     * 죽으면 <b>등록되지 않은 맨 폴더</b>가 남는데, git 은 그걸 「워크트리가 아니다」로 거절한다.
     * 그 상태로 {@code add} 하면 <b>{@code already exists} 로 막혀 그 프로젝트의 저장이 영영 안 된다.</b>
     * 그래서 git 에게 맡긴 뒤 <b>남은 폴더는 손으로 지운다.</b>
     */
    private void remove(Path clone, Path workspace) {
        try {
            git.run(clone, properties.checkTimeout(),
                    "worktree", "remove", "--force", workspace.toString());
        } catch (RuntimeException e) {
            // 등록된 워크트리가 아니었다 — 아래에서 폴더를 직접 지운다.
            log.debug("등록된 워크트리가 아니다 workspace={}", workspace);
        }
        try {
            // ⚠ 자리는 지워졌는데 등록만 남는 경우가 있다 — 그걸 털어야 다음 add 가 산다.
            git.run(clone, properties.checkTimeout(), "worktree", "prune");
            // ⚠ 여기까지 와도 남아 있으면 git 이 모르는 폴더다. 이것이 실측으로 잡힌 자리다.
            FileSystemUtils.deleteRecursively(workspace);
        } catch (IOException | RuntimeException e) {
            log.warn("검사 자리를 치우지 못했다 — 다음 검사가 먼저 치운다 workspace={}", workspace);
        }
    }
}
