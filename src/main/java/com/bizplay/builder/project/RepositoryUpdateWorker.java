package com.bizplay.builder.project;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitException;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.screenid.ScreenStandardIdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 기존 클론을 지우지 않고 기본 브랜치만 fast-forward로 최신화한다. */
@Component
public class RepositoryUpdateWorker {

    private static final Logger log = LoggerFactory.getLogger(RepositoryUpdateWorker.class);
    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(30);

    private final ProjectService projects;
    private final ProjectPaths paths;
    private final GitCommand git;
    private final ScreenStandardIdWorker screenIds;
    private final ProjectSystemService projectSystems;
    private final ProjectRepositoryLocks repositoryLocks;

    public RepositoryUpdateWorker(ProjectService projects, ProjectPaths paths, GitCommand git,
                                  ScreenStandardIdWorker screenIds, ProjectSystemService projectSystems,
                                  ProjectRepositoryLocks repositoryLocks) {
        this.projects = projects;
        this.paths = paths;
        this.git = git;
        this.screenIds = screenIds;
        this.projectSystems = projectSystems;
        this.repositoryLocks = repositoryLocks;
    }

    @Async("cloneExecutor")
    public void update(String projectId, String accountId) {
        repositoryLocks.run(projectId, () -> updateLocked(projectId, accountId));
    }

    private void updateLocked(String projectId, String accountId) {
        try {
            ProjectService.CloneMaterials materials = projects.cloneMaterials(projectId);
            Path clone = paths.cloneDir(projectId);
            if (!Files.isDirectory(clone.resolve(".git"))) {
                fail(projectId, "받아 둔 기획 저장소가 없어 업데이트하지 못했습니다. 저장소를 다시 받아 주세요.");
                return;
            }

            GitResult status = git.run(clone, UPDATE_TIMEOUT, "status", "--porcelain");
            if (!status.succeeded()) {
                fail(projectId, "기획 저장소 상태를 확인하지 못했습니다.");
                return;
            }
            if (!status.stdout().isBlank()) {
                fail(projectId, "기획 저장소에 커밋되지 않은 변경이 있어 업데이트하지 않았습니다.");
                return;
            }

            String checkedOutBranch = outputOf(clone, "symbolic-ref", "--short", "HEAD");
            if (!materials.defaultBranch().equals(checkedOutBranch)) {
                fail(projectId, "기획 저장소가 기본 브랜치에 있지 않아 업데이트하지 않았습니다.");
                return;
            }

            String before = outputOf(clone, "rev-parse", "HEAD");
            GitResult fetched = git.run(clone, UPDATE_TIMEOUT,
                    "fetch", materials.authenticatedUrl(), materials.defaultBranch());
            if (!fetched.succeeded()) {
                fail(projectId, message("원격 저장소의 변경을 받지 못했습니다.", fetched.stderr()));
                return;
            }

            GitResult merged = git.run(clone, UPDATE_TIMEOUT, "merge", "--ff-only", "FETCH_HEAD");
            if (!merged.succeeded()) {
                fail(projectId, "원격 변경을 바로 반영할 수 없어 업데이트하지 않았습니다. 저장소 상태를 확인해 주세요.");
                return;
            }

            String after = outputOf(clone, "rev-parse", "HEAD");
            projects.repositoryUpdateSucceeded(projectId, before, after, !before.equals(after));
            // 레포가 시스템을 늘렸거나 줄였을 수 있다. 이미 넣은 표시 이름은 건드리지 않는다.
            // ⛔ 삼킨다 — 여기서 터져도 업데이트는 이미 성공이고, 다음 업데이트가 재시도다.
            projectSystems.syncQuietly(projectId);
            // 새 화면이 들어왔을 수 있다. 없는 것만 채우므로 안 바뀌었어도 돌려서 손해가 없다 —
            // 지난번에 실패한 채번의 재시도이기도 하다.
            // ⛔ 제출 자체를 따로 감싼다. @Async 의 거절(TaskRejectedException)은 프록시가
            //    부르는 쪽 스레드에서 동기로 던진다 — assignQuietly 안의 try/catch 는 아직
            //    시작도 안 했으므로 그것을 못 잡는다. 감싸지 않으면 바로 아래 catch 가 fail 을
            //    불러 이미 성공한 업데이트를 실패로 뒤집는다.
            try {
                screenIds.assignQuietly(projectId, accountId);
            } catch (RuntimeException rejected) {
                log.warn("표준 화면ID 채번을 시작하지 못했다. 다음 저장소 업데이트가 재시도다 projectId={}",
                        projectId, rejected);
            }
        } catch (RuntimeException failure) {
            log.warn("기획 저장소 업데이트 실패 projectId={}", projectId, failure);
            fail(projectId, message("기획 저장소를 업데이트하지 못했습니다.", failure.getMessage()));
        }
    }

    private String outputOf(Path clone, String... args) {
        GitResult result = git.run(clone, UPDATE_TIMEOUT, args);
        if (!result.succeeded() || result.stdout().isBlank()) {
            throw new GitException("기획 저장소의 현재 커밋을 확인하지 못했습니다.");
        }
        return result.stdout().strip();
    }

    private void fail(String projectId, String reason) {
        projects.repositoryUpdateFailed(projectId, GitCommand.mask(reason));
    }

    private String message(String lead, String detail) {
        String safe = GitCommand.mask(detail == null ? "" : detail.strip());
        return safe.isBlank() ? lead : lead + " " + safe;
    }
}
