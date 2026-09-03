package com.bizplay.builder.ia;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 확정 스냅샷을 기획 저장소에 쓰고 색인을 갱신한 뒤 커밋·푸시한다. */
@Component
public class IaPublisher {

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(10);

    private final ProjectPaths paths;
    private final ProjectService projects;
    private final GitCommand git;

    public IaPublisher(ProjectPaths paths, ProjectService projects, GitCommand git) {
        this.paths = paths;
        this.projects = projects;
        this.git = git;
    }

    public String publish(String projectId, String systemCode, int revision, String content) {
        Path clone = paths.cloneDir(projectId);
        ensureClean(clone);
        Path ia = paths.iaFile(projectId, systemCode);
        Path index = clone.resolve("index.json");
        boolean hadIa = Files.exists(ia);
        String relativeIa = "core/" + systemCode + "/ia.md";
        boolean committed = false;
        try {
            writeAtomically(ia, content);
            reindexWhenAvailable(clone);

            List<String> add = new ArrayList<>(List.of("add", "--", relativeIa));
            if (Files.exists(index)) {
                add.add("index.json");
            }
            require(git.run(clone, GIT_TIMEOUT, add.toArray(String[]::new)), "게시 파일을 Git에 올리지 못했습니다.");
            GitResult changed = git.run(clone, GIT_TIMEOUT, "diff", "--cached", "--quiet");
            if (changed.exitCode() == 1) {
                require(git.run(clone, GIT_TIMEOUT, "commit", "-m",
                        "docs: " + systemCode + " IA " + revision + "차 확정"), "IA 커밋을 만들지 못했습니다.");
                committed = true;
            } else if (changed.exitCode() != 0) {
                throw new IllegalStateException("게시할 변경을 확인하지 못했습니다.");
            }

            String commit = require(git.run(clone, GIT_TIMEOUT, "rev-parse", "HEAD"),
                    "게시 커밋을 확인하지 못했습니다.").stdout().strip();
            ProjectService.CloneMaterials material = projects.cloneMaterials(projectId);
            require(git.run(clone, PUSH_TIMEOUT, "push", material.authenticatedUrl(),
                    "HEAD:" + material.defaultBranch()), "IA를 기획 저장소에 올리지 못했습니다.");
            return commit;
        } catch (RuntimeException failed) {
            if (!committed) {
                restore(clone, relativeIa, hadIa, Files.exists(index));
            }
            throw failed;
        }
    }

    private void ensureClean(Path clone) {
        GitResult status = require(git.run(clone, GIT_TIMEOUT, "status", "--porcelain", "--untracked-files=no"),
                "기획 저장소 상태를 확인하지 못했습니다.");
        if (!status.stdout().isBlank()) {
            throw new IllegalStateException("기획 저장소에 아직 게시하지 않은 변경이 있어 IA를 올릴 수 없습니다.");
        }
    }

    /** 산출물이 바뀌면 파생 색인도 같은 커밋에서 다시 만든다. 검사기 사본이 없는 저장소는 건너뛴다. */
    public static void reindexWhenAvailable(Path clone) {
        Path script = clone.resolve("verify").resolve("reindex.mjs");
        if (!Files.isRegularFile(script)) {
            return;
        }
        Process process = null;
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("ia-reindex-", ".log");
            process = new ProcessBuilder("node", "verify/reindex.mjs", ".")
                    .directory(clone.toFile()).redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile()).start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("기획 저장소 색인 갱신이 시간 상한을 넘었습니다.");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("기획 저장소 색인을 갱신하지 못했습니다: " + summarize(output));
            }
        } catch (IOException e) {
            throw new IllegalStateException("기획 저장소 색인 갱신을 시작하지 못했습니다.", e);
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기획 저장소 색인 갱신을 기다리다 중단됐습니다.", e);
        } finally {
            if (outputFile != null) {
                try { Files.deleteIfExists(outputFile); } catch (IOException ignored) { }
            }
        }
    }

    private void writeAtomically(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "ia-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("확정한 IA 파일을 쓰지 못했습니다.", e);
        }
    }

    private void restore(Path clone, String relativeIa, boolean hadIa, boolean hadIndex) {
        if (hadIa) {
            git.run(clone, GIT_TIMEOUT, "restore", "--staged", "--worktree", "--", relativeIa);
        } else {
            git.run(clone, GIT_TIMEOUT, "restore", "--staged", "--", relativeIa);
            try {
                Files.deleteIfExists(clone.resolve(relativeIa));
            } catch (IOException ignored) {
                // 실패 상태는 DB에 남는다. 다음 시도 전에 깨끗한지 다시 검사한다.
            }
        }
        if (hadIndex) git.run(clone, GIT_TIMEOUT, "restore", "--staged", "--worktree", "--", "index.json");
    }

    private GitResult require(GitResult result, String message) {
        if (result.exitCode() != 0) {
            throw new IllegalStateException(message + " " + summarize(result.stderr()));
        }
        return result;
    }

    private static String summarize(String output) {
        if (output == null || output.isBlank()) return "";
        String oneLine = GitCommand.mask(output).replaceAll("\\s+", " ").strip();
        return oneLine.substring(0, Math.min(oneLine.length(), 300));
    }
}
