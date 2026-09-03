package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrdScreenHistoryServiceTest {

    @TempDir Path temp;

    @Test
    void 맵_AI_이력을_복원하면_HTML과_이동관계_MD가_같이_돌아온다() throws Exception {
        FrdMapper frds = mock(FrdMapper.class);
        FrdScreenMapper screens = mock(FrdScreenMapper.class);
        FrdScreenHistoryMapper histories = mock(FrdScreenHistoryMapper.class);
        FrdScreenFiles screenFiles = mock(FrdScreenFiles.class);
        Frd frd = mock(Frd.class);
        FrdScreen screen = mock(FrdScreen.class);
        FrdScreenHistory history = new FrdScreenHistory(7L, "screen-row", "screen-a", "화면 A",
                "<html><head></head><body>이전 화면</body></html>",
                "- 구분: 이동 / 이동: screen-b / 앵커: screen-a-e01\n",
                "화면과 연결을 수정했습니다.", "operation-1", "FRD_CANVAS_AI", Instant.now());
        Path worktree = temp.resolve("worktree");
        Path pages = worktree.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve("screen-a.html"), "현재 화면");
        Files.writeString(pages.resolve("screen-a.md"), "현재 연결");

        when(histories.selectById(7L)).thenReturn(history);
        when(screens.selectById("screen-row")).thenReturn(screen);
        when(screen.frdId()).thenReturn("frd-1");
        when(screen.id()).thenReturn("screen-row");
        when(screen.screenId()).thenReturn("screen-a");
        when(screen.systemCode()).thenReturn("webview");
        when(screen.state()).thenReturn(FrdScreen.State.GENERATED);
        when(frds.selectById("frd-1")).thenReturn(frd);
        when(frd.id()).thenReturn("frd-1");
        when(frd.projectId()).thenReturn("project-1");
        when(screenFiles.existingHtml("project-1", "frd-1", "webview", "screen-a"))
                .thenReturn(pages.resolve("screen-a.html"));
        when(screenFiles.document("project-1", "frd-1", "webview", "screen-a"))
                .thenReturn(pages.resolve("screen-a.md"));

        new FrdScreenHistoryService(frds, screens, histories, screenFiles)
                .restore("project-1", "frd-1", 7L);

        assertThat(Files.readString(pages.resolve("screen-a.html"))).contains("이전 화면");
        assertThat(Files.readString(pages.resolve("screen-a.md"))).contains("이동: screen-b");
        verify(screens).updateGenerated(eq("screen-row"), eq(history.html()), eq(history.changes()), any(Instant.class));
    }
}
