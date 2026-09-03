package com.bizplay.builder.project;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/** FRD 완료처럼 기본 브랜치 최신화가 선행되어야 하는 작업을 한 잠금 안에서 실행한다. */
@Component
public class PlanningRepositoryUpdater {

    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(30);

    private final ProjectService projects;
    private final ProjectPaths paths;
    private final GitCommand git;
    private final ProjectRepositoryLocks locks;

    public PlanningRepositoryUpdater(ProjectService projects, ProjectPaths paths,
                                     GitCommand git, ProjectRepositoryLocks locks) {
        this.projects = projects;
        this.paths = paths;
        this.git = git;
        this.locks = locks;
    }

    /** 원격 기본 브랜치를 fast-forward로 받은 뒤 같은 잠금 안에서 후속 작업을 실행한다. */
    public <T> T withLatest(String projectId, Supplier<T> work) {
        return locks.withLock(projectId, () -> {
            updateLocked(projectId);
            return work.get();
        });
    }

    private void updateLocked(String projectId) {
        ProjectService.CloneMaterials materials = projects.cloneMaterials(projectId);
        Path clone = paths.cloneDir(projectId).toAbsolutePath().normalize();
        if (!Files.isDirectory(clone.resolve(".git"))) {
            throw new IllegalStateException("기획 저장소가 준비되지 않아 최신 내용을 확인하지 못했습니다.");
        }
        GitResult status = git.run(clone, UPDATE_TIMEOUT, "status", "--porcelain");
        require(status, "기획 저장소 상태를 확인하지 못했습니다.");
        if (!status.stdout().isBlank()) {
            throw new IllegalStateException("기획 저장소에 커밋되지 않은 변경이 있어 최신 내용을 받을 수 없습니다.");
        }
        GitResult branch = git.run(clone, UPDATE_TIMEOUT, "symbolic-ref", "--short", "HEAD");
        require(branch, "기획 저장소의 현재 브랜치를 확인하지 못했습니다.");
        if (!materials.defaultBranch().equals(branch.stdout().strip())) {
            throw new IllegalStateException("기획 저장소가 기본 브랜치에 있지 않아 최신 내용을 받을 수 없습니다.");
        }
        GitResult fetched = git.run(clone, UPDATE_TIMEOUT,
                "fetch", materials.authenticatedUrl(), materials.defaultBranch());
        require(fetched, "원격 기획 저장소의 최신 내용을 받지 못했습니다.");
        GitResult merged = git.run(clone, UPDATE_TIMEOUT, "merge", "--ff-only", "FETCH_HEAD");
        require(merged, "원격 변경을 기본 브랜치에 바로 반영할 수 없습니다.");
    }

    private static void require(GitResult result, String message) {
        if (!result.succeeded()) {
            String detail = GitCommand.mask(result.stderr()).strip();
            throw new IllegalStateException(detail.isBlank() ? message : message + " " + detail);
        }
    }
}
