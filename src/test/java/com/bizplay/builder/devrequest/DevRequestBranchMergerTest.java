package com.bizplay.builder.devrequest;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectRepositoryLocks;
import com.bizplay.builder.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevRequestBranchMergerTest {

    @TempDir Path temp;

    @Test
    void FRD_전달_기준_커밋을_기본_브랜치에_병합하고_재실행해도_중복_병합하지_않는다() throws Exception {
        GitCommand git = new GitCommand();
        BuilderProperties properties = properties();
        ProjectPaths paths = new ProjectPaths(properties);
        Path remote = temp.resolve("remote.git");
        Path seed = temp.resolve("seed");
        Path clone = paths.cloneDir("0000001");

        run(git, temp, "init", "--bare", remote.toString());
        run(git, seed, "init");
        identity(git, seed);
        Files.writeString(seed.resolve("README.md"), "기준\n");
        run(git, seed, "add", ".");
        run(git, seed, "commit", "-m", "기준");
        run(git, seed, "branch", "-M", "main");
        run(git, seed, "remote", "add", "origin", remote.toString());
        run(git, seed, "push", "-u", "origin", "main");
        run(git, temp, "clone", "-b", "main", remote.toString(), clone.toString());

        Path frd = paths.frdWorktree("0000001", "0000002");
        run(git, clone, "worktree", "add", "-b", "frd/0000002", frd.toString());
        identity(git, frd);
        Files.writeString(frd.resolve("change.md"), "개발 대상\n");
        run(git, frd, "add", ".");
        run(git, frd, "commit", "-m", "docs: FRD-002 작업 완료");
        String expected = output(git, frd, "rev-parse", "HEAD");

        ProjectService projects = mock(ProjectService.class);
        when(projects.cloneMaterials("0000001"))
                .thenReturn(new ProjectService.CloneMaterials("main", remote.toString()));
        DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
        when(requests.isCurrentDevelopmentStatusCandidate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        DevRequestBranchMerger merger = new DevRequestBranchMerger(projects, requests, paths,
                new ProjectRepositoryLocks(), git, properties);
        DevelopmentStatusCandidate candidate = new DevelopmentStatusCandidate(
                "0000003", "0000001", "0000002", "DR-003", "0000004",
                "DRK-0000003", "http://gitlab.test/issues/3",
                DevelopmentState.DONE, expected);

        DevRequestBranchMerger.Result first = merger.merge(candidate);
        DevRequestBranchMerger.Result second = merger.merge(candidate);

        assertThat(first.succeeded()).isTrue();
        assertThat(second.succeeded()).isTrue();
        assertThat(second.commitSha()).isEqualTo(first.commitSha());
        run(git, clone, "fetch", remote.toString(), "main");
        assertThat(git.run(clone, Duration.ofSeconds(10),
                "merge-base", "--is-ancestor", expected, "FETCH_HEAD").succeeded()).isTrue();
        assertThat(paths.devRequestMergeWorktree("0000001", "0000003")).doesNotExist();
    }

    private BuilderProperties properties() {
        return new BuilderProperties("admin", "password", "A".repeat(42) + "g=",
                temp.resolve("data"), Duration.ofMinutes(1), 2, 10, Duration.ofSeconds(10));
    }

    private void identity(GitCommand git, Path dir) {
        run(git, dir, "config", "user.name", "테스트");
        run(git, dir, "config", "user.email", "test@example.com");
    }

    private void run(GitCommand git, Path dir, String... args) {
        GitResult result = git.run(dir, Duration.ofSeconds(10), args);
        assertThat(result.succeeded()).as(String.join(" ", args) + " " + result.stderr()).isTrue();
    }

    private String output(GitCommand git, Path dir, String... args) {
        GitResult result = git.run(dir, Duration.ofSeconds(10), args);
        assertThat(result.succeeded()).isTrue();
        return result.stdout().strip();
    }
}
