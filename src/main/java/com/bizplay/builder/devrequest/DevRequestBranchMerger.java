package com.bizplay.builder.devrequest;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectRepositoryLocks;
import com.bizplay.builder.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** 개발 완료 FRD의 전달 기준 커밋을 기획 저장소 기본 브랜치에 반영한다. */
@Component
public class DevRequestBranchMerger {

    private static final Logger log = LoggerFactory.getLogger(DevRequestBranchMerger.class);

    private final ProjectService projects;
    private final DevelopmentRequestMapper requests;
    private final ProjectPaths paths;
    private final ProjectRepositoryLocks repositoryLocks;
    private final GitCommand git;
    private final BuilderProperties properties;

    public DevRequestBranchMerger(ProjectService projects, DevelopmentRequestMapper requests,
                                  ProjectPaths paths,
                                  ProjectRepositoryLocks repositoryLocks,
                                  GitCommand git, BuilderProperties properties) {
        this.projects = projects;
        this.requests = requests;
        this.paths = paths;
        this.repositoryLocks = repositoryLocks;
        this.git = git;
        this.properties = properties;
    }

    public Result merge(DevelopmentStatusCandidate candidate) {
        return repositoryLocks.withLock(candidate.projectId(), () -> mergeLocked(candidate));
    }

    private Result mergeLocked(DevelopmentStatusCandidate candidate) {
        if (requests.isCurrentDevelopmentStatusCandidate(candidate) != 1) {
            return Result.failed("개발요청서의 전송 세대가 바뀌어 지난 완료 결과를 병합하지 않았습니다.");
        }
        String expected = candidate.workspaceHeadSha();
        if (expected == null || !expected.matches("^[0-9a-fA-F]{40,64}$")) {
            return Result.failed("개발요청서의 FRD 전달 기준 커밋을 확인할 수 없습니다.");
        }
        Path clone = paths.cloneDir(candidate.projectId()).toAbsolutePath().normalize();
        Path mergeRoot = paths.worktreeRoot(candidate.projectId()).toAbsolutePath().normalize();
        Path workspace = paths.devRequestMergeWorktree(candidate.projectId(), candidate.requestId())
                .toAbsolutePath().normalize();
        if (!workspace.startsWith(mergeRoot)) {
            return Result.failed("개발 완료 병합 작업공간의 위치가 올바르지 않습니다.");
        }
        if (!Files.isDirectory(clone.resolve(".git"))) {
            return Result.failed("받아 둔 기획 저장소가 없어 개발 완료 내용을 병합하지 못했습니다.");
        }

        ProjectService.CloneMaterials materials = projects.cloneMaterials(candidate.projectId());
        String remoteRef = "refs/builder/dev-request/" + candidate.requestId();
        boolean added = false;
        try {
            GitResult fetched = git.run(clone, properties.checkTimeout(), "fetch",
                    materials.authenticatedUrl(),
                    "+refs/heads/" + materials.defaultBranch() + ":" + remoteRef);
            if (!fetched.succeeded()) {
                return Result.failed(message("기획 저장소 기본 브랜치를 받지 못했습니다.", fetched));
            }
            String remoteHead = output(clone, "기획 저장소 기본 브랜치 커밋을 확인하지 못했습니다.",
                    "rev-parse", remoteRef);
            if (!git.run(clone, properties.checkTimeout(), "cat-file", "-e", expected + "^{commit}").succeeded()) {
                return Result.failed("개발요청서의 FRD 전달 기준 커밋이 기획 저장소에 없습니다.");
            }
            if (isAncestor(clone, expected, remoteHead)) {
                return Result.succeeded(remoteHead);
            }

            if (Files.exists(workspace)) {
                GitResult removed = git.run(clone, properties.checkTimeout(),
                        "worktree", "remove", "--force", workspace.toString());
                if (!removed.succeeded() || Files.exists(workspace)) {
                    return Result.failed("지난 개발 완료 병합 작업공간이 남아 있어 다시 시작하지 못했습니다.");
                }
            }
            GitResult pruned = git.run(clone, properties.checkTimeout(),
                    "worktree", "prune", "--expire", "now");
            if (!pruned.succeeded()) {
                return Result.failed(message("지난 개발 완료 병합 작업공간 등록을 정리하지 못했습니다.", pruned));
            }
            GitResult created = git.run(clone, properties.checkTimeout(),
                    "worktree", "add", "--detach", workspace.toString(), remoteHead);
            if (!created.succeeded()) {
                return Result.failed(message("개발 완료 병합 작업공간을 만들지 못했습니다.", created));
            }
            added = true;

            GitResult merged = git.run(workspace, properties.checkTimeout(),
                    "-c", "user.name=빌더 개발완료",
                    "-c", "user.email=builder@localhost",
                    "merge", "--no-ff", "--no-edit", "-m",
                    "merge: " + candidate.requestLabel() + " 개발 완료", expected);
            if (!merged.succeeded()) {
                git.run(workspace, properties.checkTimeout(), "merge", "--abort");
                return Result.failed(message("개발 완료 내용을 기본 브랜치에 병합하지 못했습니다.", merged));
            }

            GitResult pushed = git.run(workspace, properties.checkTimeout(), "push",
                    materials.authenticatedUrl(), "HEAD:refs/heads/" + materials.defaultBranch());
            if (!pushed.succeeded()) {
                return Result.failed(message("개발 완료 병합 결과를 기획 저장소에 올리지 못했습니다.", pushed));
            }

            GitResult verified = git.run(clone, properties.checkTimeout(), "fetch",
                    materials.authenticatedUrl(),
                    "+refs/heads/" + materials.defaultBranch() + ":" + remoteRef);
            if (!verified.succeeded()) {
                return Result.failed(message("올린 개발 완료 결과를 다시 확인하지 못했습니다.", verified));
            }
            String pushedHead = output(clone, "올린 기본 브랜치 커밋을 확인하지 못했습니다.",
                    "rev-parse", remoteRef);
            if (!isAncestor(clone, expected, pushedHead)) {
                return Result.failed("기획 저장소 기본 브랜치에서 개발 완료 커밋을 확인하지 못했습니다.");
            }
            return Result.succeeded(pushedHead);
        } catch (RuntimeException failure) {
            return Result.failed("개발 완료 내용을 병합하지 못했습니다: "
                    + GitCommand.mask(String.valueOf(failure.getMessage())));
        } finally {
            if (added) {
                GitResult removed = git.run(clone, properties.checkTimeout(),
                        "worktree", "remove", "--force", workspace.toString());
                if (!removed.succeeded()) {
                    log.warn("개발 완료 임시 작업공간을 정리하지 못했다 requestId={} reason={}",
                            candidate.requestId(), message("", removed));
                }
            }
            git.run(clone, properties.checkTimeout(), "worktree", "prune", "--expire", "now");
            git.run(clone, properties.checkTimeout(), "update-ref", "-d", remoteRef);
        }
    }

    private boolean isAncestor(Path clone, String ancestor, String descendant) {
        return git.run(clone, properties.checkTimeout(),
                "merge-base", "--is-ancestor", ancestor, descendant).succeeded();
    }

    private String output(Path clone, String failure, String... args) {
        GitResult result = git.run(clone, properties.checkTimeout(), args);
        if (!result.succeeded() || result.stdout().isBlank()) {
            throw new IllegalStateException(failure + " " + message("", result));
        }
        return result.stdout().strip();
    }

    private String message(String lead, GitResult result) {
        String detail = result.stderr() == null || result.stderr().isBlank()
                ? result.stdout() : result.stderr();
        String safe = GitCommand.mask(detail == null ? "" : detail.strip());
        return safe.isBlank() ? lead : (lead + " " + safe).strip();
    }

    public record Result(boolean succeeded, String commitSha, String failure) {
        static Result succeeded(String commitSha) {
            return new Result(true, commitSha, null);
        }

        static Result failed(String failure) {
            return new Result(false, null, failure);
        }
    }
}
