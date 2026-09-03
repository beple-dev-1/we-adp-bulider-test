package com.bizplay.builder.ia;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
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

        IaPublisher publisher = new IaPublisher(paths, projects, git);
        String commit = publisher.publish(projectId, "backoffice", 1, "# backoffice IA\n");

        assertThat(commit).hasSize(40);
        assertThat(Files.readString(paths.iaFile(projectId, "backoffice"))).isEqualTo("# backoffice IA\n");
        assertThat(git.run(remote, Duration.ofSeconds(10), "show", "main:core/backoffice/ia.md").stdout())
                .isEqualTo("# backoffice IA\n");
    }

    private BuilderProperties properties() {
        return new BuilderProperties("admin", "password", "A".repeat(42) + "g=", temporary,
                Duration.ofMinutes(10), 2, 10, Duration.ofMinutes(2));
    }

    private void require(GitResult result) {
        assertThat(result.exitCode()).withFailMessage(result.stderr()).isZero();
    }
}
