package com.bizplay.builder.ia;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.PlanningRepositoryUpdater;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectRepositoryLocks;
import com.bizplay.builder.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IaPublisherTest {

    @TempDir Path temporary;

    @Test
    void 확정_스냅샷을_한_커밋으로_만들어_기본_브랜치에_게시한다() throws Exception {
        ProjectPaths paths = new ProjectPaths(properties());
        GitCommand git = new GitCommand();
        ProjectService projects = mock(ProjectService.class);
        String projectId = "0000001";
        Path clone = paths.cloneDir(projectId);
        Path remote = temporary.resolve("remote.git");
        Files.createDirectories(clone);
        require(git.run(temporary, Duration.ofSeconds(10), "init", "--bare", remote.toString()));
        require(git.run(clone, Duration.ofSeconds(10), "init", "-b", "main"));
        require(git.run(clone, Duration.ofSeconds(10), "config", "user.name", "Builder 시험"));
        require(git.run(clone, Duration.ofSeconds(10), "config", "user.email", "builder@example.com"));
        Files.writeString(clone.resolve("README.md"), "seed\n");
        require(git.run(clone, Duration.ofSeconds(10), "add", "README.md"));
        require(git.run(clone, Duration.ofSeconds(10), "commit", "-m", "seed"));
        require(git.run(clone, Duration.ofSeconds(10), "push", remote.toString(), "main"));
        when(projects.cloneMaterials(projectId)).thenReturn(new ProjectService.CloneMaterials("main", remote.toString()));

        IaPublisher publisher = publisher(paths, projects, git);
        String commit = publisher.publish(projectId, "backoffice", 1, "# backoffice IA\n");

        assertThat(commit).hasSize(40);
        assertThat(Files.readString(paths.iaFile(projectId, "backoffice"))).isEqualTo("# backoffice IA\n");
        assertThat(git.run(remote, Duration.ofSeconds(10), "show", "main:core/backoffice/ia.md").stdout())
                .isEqualTo("# backoffice IA\n");
    }

    /**
     * ⭐ <b>원격이 앞서 있어도 받아서 그 위에 게시한다.</b> 빌더의 「개발 결과 받기」와 사람이 원격
     * {@code main} 에 직접 커밋한다 — 로컬 클론에만 커밋해 밀면 「원격이 앞서 있다」로 거절된다
     * (2026-09-23 실측: 로컬 {@code 370cd63} · 원격 {@code 896b7e6}).
     */
    @Test
    void 원격이_앞서_있어도_받아서_그_위에_게시한다() throws Exception {
        ProjectPaths paths = new ProjectPaths(properties());
        GitCommand git = new GitCommand();
        ProjectService projects = mock(ProjectService.class);
        Path clone = seededClone(paths, git, projects);
        Path remote = temporary.resolve("remote.git");
        commitElsewhere(git, remote, "RECEIVED.md", "개발 결과 받기가 원격에 직접 올린 것\n");

        String commit = publisher(paths, projects, git).publish("0000001", "backoffice", 1, "# backoffice IA\n");

        assertThat(git.run(remote, Duration.ofSeconds(10), "rev-parse", "main").stdout().strip())
                .isEqualTo(commit);
        assertThat(git.run(remote, Duration.ofSeconds(10), "show", "main:RECEIVED.md").stdout())
                .contains("개발 결과 받기가 원격에 직접 올린 것");
        assertThat(Files.readString(clone.resolve("RECEIVED.md"))).contains("개발 결과 받기");
    }

    /**
     * ⛔ <b>올리지 못하면 로컬 커밋을 되돌린다.</b> 남겨 두면 로컬과 원격이 갈라져, 다음 FRD 완료의
     * 최신화(앞으로 감기)까지 「바로 반영할 수 없습니다」로 막힌다.
     */
    @Test
    void 올리지_못하면_로컬_커밋을_되돌린다() throws Exception {
        ProjectPaths paths = new ProjectPaths(properties());
        GitCommand git = new GitCommand();
        ProjectService projects = mock(ProjectService.class);
        Path clone = seededClone(paths, git, projects);
        Path hook = temporary.resolve("remote.git").resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/bin/sh\necho reject >&2\nexit 1\n");
        hook.toFile().setExecutable(true);
        String before = git.run(clone, Duration.ofSeconds(10), "rev-parse", "HEAD").stdout().strip();

        assertThatThrownBy(() -> publisher(paths, projects, git)
                .publish("0000001", "backoffice", 1, "# backoffice IA\n"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(git.run(clone, Duration.ofSeconds(10), "rev-parse", "HEAD").stdout().strip())
                .isEqualTo(before);
        assertThat(git.run(clone, Duration.ofSeconds(10), "status", "--porcelain").stdout()).isBlank();
    }

    private IaPublisher publisher(ProjectPaths paths, ProjectService projects, GitCommand git) {
        return new IaPublisher(paths, projects, git,
                new PlanningRepositoryUpdater(projects, paths, git, new ProjectRepositoryLocks()));
    }

    /** 원격과 같은 판으로 시작하는 클론 하나. */
    private Path seededClone(ProjectPaths paths, GitCommand git, ProjectService projects) throws Exception {
        Path clone = paths.cloneDir("0000001");
        Path remote = temporary.resolve("remote.git");
        Files.createDirectories(clone);
        require(git.run(temporary, Duration.ofSeconds(10), "init", "--bare", "-b", "main", remote.toString()));
        require(git.run(clone, Duration.ofSeconds(10), "init", "-b", "main"));
        require(git.run(clone, Duration.ofSeconds(10), "config", "user.name", "Builder 시험"));
        require(git.run(clone, Duration.ofSeconds(10), "config", "user.email", "builder@example.com"));
        Files.writeString(clone.resolve("README.md"), "seed\n");
        require(git.run(clone, Duration.ofSeconds(10), "add", "README.md"));
        require(git.run(clone, Duration.ofSeconds(10), "commit", "-m", "seed"));
        require(git.run(clone, Duration.ofSeconds(10), "push", remote.toString(), "main"));
        when(projects.cloneMaterials("0000001"))
                .thenReturn(new ProjectService.CloneMaterials("main", remote.toString()));
        return clone;
    }

    /** 빌더 클론 밖에서 원격 main 에 커밋 하나를 더한다. */
    private void commitElsewhere(GitCommand git, Path remote, String file, String content) throws Exception {
        Path other = temporary.resolve("other");
        require(git.run(temporary, Duration.ofSeconds(10), "clone", "-q", "-b", "main",
                remote.toString(), other.toString()));
        require(git.run(other, Duration.ofSeconds(10), "config", "user.name", "다른 이"));
        require(git.run(other, Duration.ofSeconds(10), "config", "user.email", "other@example.com"));
        Files.writeString(other.resolve(file), content);
        require(git.run(other, Duration.ofSeconds(10), "add", file));
        require(git.run(other, Duration.ofSeconds(10), "commit", "-m", "밖에서 올린 것"));
        require(git.run(other, Duration.ofSeconds(10), "push", "origin", "main"));
    }

    private BuilderProperties properties() {
        return new BuilderProperties("admin", "password", "A".repeat(42) + "g=", temporary,
                Duration.ofMinutes(10), 2, 10, Duration.ofMinutes(2));
    }

    private void require(GitResult result) {
        assertThat(result.exitCode()).withFailMessage(result.stderr()).isZero();
    }
}
