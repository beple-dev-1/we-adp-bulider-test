package com.bizplay.builder.project;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.screenid.ScreenStandardIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryUpdateWorkerTest {

    @TempDir Path temp;

    @Test
    void 깨끗한_클론은_fast_forward로_업데이트한다() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        ProjectPaths paths = mock(ProjectPaths.class);
        GitCommand git = mock(GitCommand.class);
        Path clone = temp.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        when(paths.cloneDir("0000001")).thenReturn(clone);
        when(projects.cloneMaterials("0000001"))
                .thenReturn(new ProjectService.CloneMaterials("main", "https://oauth2:t@host/x.git"));

        AtomicInteger revParse = new AtomicInteger();
        when(git.run(eq(clone), any(), any(String[].class))).thenAnswer(call -> {
            String command = call.getArgument(2);
            return switch (command) {
                case "status", "fetch", "merge" -> new GitResult(0, "", "");
                case "symbolic-ref" -> new GitResult(0, "main\n", "");
                case "rev-parse" -> new GitResult(0,
                        revParse.getAndIncrement() == 0 ? "old\n" : "new\n", "");
                default -> new GitResult(1, "", "unexpected");
            };
        });

        ScreenStandardIdWorker screenIds = mock(ScreenStandardIdWorker.class);
        new RepositoryUpdateWorker(projects, paths, git, screenIds, mock(ProjectSystemService.class),
                new ProjectRepositoryLocks()).update("0000001", "acc-1");

        verify(projects).repositoryUpdateSucceeded("0000001", "old", "new", true);
        verify(projects, never()).repositoryUpdateFailed(eq("0000001"), anyString());
    }

    @Test
    void 커밋하지_않은_변경이_있으면_현재_클론을_건드리지_않는다() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        ProjectPaths paths = mock(ProjectPaths.class);
        GitCommand git = mock(GitCommand.class);
        Path clone = temp.resolve("dirty-clone");
        Files.createDirectories(clone.resolve(".git"));
        when(paths.cloneDir("0000002")).thenReturn(clone);
        when(projects.cloneMaterials("0000002"))
                .thenReturn(new ProjectService.CloneMaterials("main", "https://oauth2:t@host/x.git"));
        when(git.run(eq(clone), any(), any(String[].class)))
                .thenReturn(new GitResult(0, " M index.json\n", ""));

        ScreenStandardIdWorker screenIds = mock(ScreenStandardIdWorker.class);
        new RepositoryUpdateWorker(projects, paths, git, screenIds, mock(ProjectSystemService.class),
                new ProjectRepositoryLocks()).update("0000002", "acc-1");

        verify(projects).repositoryUpdateFailed(eq("0000002"),
                eq("기획 저장소에 커밋되지 않은 변경이 있어 업데이트하지 않았습니다."));
        verify(projects, never()).repositoryUpdateSucceeded(anyString(), anyString(), anyString(), anyBoolean());
    }

    /**
     * ★ 픽스라운드 2(2026-08-20) — {@code @Async} 제출 자체가 거절되면 그 예외는
     * {@code assignQuietly} 본문이 시작되기도 전에 <b>부르는 쪽 스레드에서 동기로</b> 던져진다.
     * {@code RepositoryUpdateWorker} 가 그것을 감싸지 않으면 이미 성공한 업데이트가 실패로
     * 뒤집힌다 — 이 시험이 바로 그 뒤집힘이 안 일어나는지를 잰다.
     */
    @Test
    void 채번_제출이_거절돼도_이미_성공한_업데이트는_실패로_안_바뀐다() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        ProjectPaths paths = mock(ProjectPaths.class);
        GitCommand git = mock(GitCommand.class);
        Path clone = temp.resolve("clone-guarded");
        Files.createDirectories(clone.resolve(".git"));
        when(paths.cloneDir("0000003")).thenReturn(clone);
        when(projects.cloneMaterials("0000003"))
                .thenReturn(new ProjectService.CloneMaterials("main", "https://oauth2:t@host/x.git"));

        AtomicInteger revParse = new AtomicInteger();
        when(git.run(eq(clone), any(), any(String[].class))).thenAnswer(call -> {
            String command = call.getArgument(2);
            return switch (command) {
                case "status", "fetch", "merge" -> new GitResult(0, "", "");
                case "symbolic-ref" -> new GitResult(0, "main\n", "");
                case "rev-parse" -> new GitResult(0,
                        revParse.getAndIncrement() == 0 ? "old\n" : "new\n", "");
                default -> new GitResult(1, "", "unexpected");
            };
        });

        ScreenStandardIdWorker screenIds = mock(ScreenStandardIdWorker.class);
        doThrow(new TaskRejectedException("대기열이 찼다")).when(screenIds).assignQuietly(any(), any());

        new RepositoryUpdateWorker(projects, paths, git, screenIds, mock(ProjectSystemService.class),
                new ProjectRepositoryLocks())
                .update("0000003", "acc-1");

        verify(projects).repositoryUpdateSucceeded("0000003", "old", "new", true);
        verify(projects, never()).repositoryUpdateFailed(anyString(), anyString());
    }
}
