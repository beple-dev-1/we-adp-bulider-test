package com.bizplay.builder.project;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningRepositoryUpdaterTest {

    @TempDir Path root;

    @Test
    void 후속_작업_전에_원격_기본_브랜치를_fast_forward한다() throws IOException {
        GitCommand git = new GitCommand();
        Path remote = root.resolve("remote.git");
        Path seed = root.resolve("seed");
        run(git, root, "init", "--bare", remote.toString());
        Files.createDirectories(seed);
        run(git, seed, "init", "-b", "main");
        run(git, seed, "config", "user.email", "test@example.com");
        run(git, seed, "config", "user.name", "시험");
        Files.writeString(seed.resolve("screen.html"), "이전 화면");
        run(git, seed, "add", ".");
        run(git, seed, "commit", "-m", "첫 화면");
        run(git, seed, "remote", "add", "origin", remote.toString());
        run(git, seed, "push", "-u", "origin", "main");

        BuilderProperties properties = new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                root.resolve("data"), Duration.ofMinutes(10), 4, 50, Duration.ofMinutes(2));
        ProjectPaths paths = new ProjectPaths(properties);
        Path clone = paths.cloneDir("0000001");
        Files.createDirectories(clone.getParent());
        run(git, clone.getParent(), "clone", "-b", "main", remote.toString(), clone.toString());

        Files.writeString(seed.resolve("screen.html"), "최신 화면");
        run(git, seed, "add", ".");
        run(git, seed, "commit", "-m", "화면 갱신");
        run(git, seed, "push", "origin", "main");

        ProjectService projects = mock(ProjectService.class);
        when(projects.cloneMaterials("0000001"))
                .thenReturn(new ProjectService.CloneMaterials("main", remote.toString()));
        PlanningRepositoryUpdater updater = new PlanningRepositoryUpdater(
                projects, paths, git, new ProjectRepositoryLocks());

        String content = updater.withLatest("0000001", () -> {
            try {
                return Files.readString(clone.resolve("screen.html"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });

        assertThat(content).isEqualTo("최신 화면");
    }

    private static void run(GitCommand git, Path directory, String... args) {
        GitResult result = git.run(directory, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
    }
}
