package com.bizplay.builder.screendesign;

import com.bizplay.builder.solution.ScreenHistory;
import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreenDesignMaterialServiceTest {

    @TempDir Path temp;

    @Test
    void 다른_화면은_지문을_바꾸지_않고_참조한_CSS는_지문을_바꾼다() throws Exception {
        Path clone = temp.resolve("clone");
        Path core = clone.resolve("core");
        Path pages = core.resolve("bo/pages");
        Path assets = core.resolve("bo/assets");
        Files.createDirectories(pages);
        Files.createDirectories(assets);
        Files.writeString(pages.resolve("target.md"), "# 대상 화면", StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("target.html"),
                "<link rel=stylesheet href=\"../assets/style.css\"><button>저장</button>", StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("other.html"), "다른 화면 1", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("style.css"), "button{color:green}", StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);

        SolutionScreen screen = new SolutionScreen("target", "대상 화면", "bo", "목록", "기본", "근거",
                "업무 > 대상", "", null, null, List.of(), List.of(), null, List.of(), false,
                ScreenHistory.EMPTY);
        SolutionMockupService solutions = mock(SolutionMockupService.class);
        SolutionScreenReader reader = mock(SolutionScreenReader.class);
        when(solutions.screens("P1")).thenReturn(List.of(screen));
        when(reader.coreRoot("P1")).thenReturn(core);
        when(reader.fileInClone("P1", "bo/pages/target.md")).thenReturn(pages.resolve("target.md"));
        when(reader.fileInClone("P1", "bo/pages/target.html")).thenReturn(pages.resolve("target.html"));
        ScreenDesignMaterialService service = new ScreenDesignMaterialService(solutions, reader, new ObjectMapper());

        String first = service.snapshot("P1", "bo", "target").fingerprint();
        Files.writeString(pages.resolve("other.html"), "다른 화면 2", StandardCharsets.UTF_8);
        assertThat(service.snapshot("P1", "bo", "target").fingerprint()).isEqualTo(first);
        Files.writeString(assets.resolve("style.css"), "button{color:blue}", StandardCharsets.UTF_8);
        assertThat(service.snapshot("P1", "bo", "target").fingerprint()).isNotEqualTo(first);
    }

    @Test
    void 루트_기준으로_참조한_자산도_지문에_넣는다() throws Exception {
        Path clone = temp.resolve("absolute-clone");
        Path core = clone.resolve("core");
        Path pages = core.resolve("bo/pages");
        Path assets = core.resolve("assets");
        Files.createDirectories(pages);
        Files.createDirectories(assets);
        Files.writeString(pages.resolve("target.md"), "# 대상 화면", StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("target.html"),
                "<link rel=stylesheet href=\"/assets/style.css\"><button>저장</button>", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("style.css"), "@import \"theme.css\";button{color:green}", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("theme.css"), "body{color:black}", StandardCharsets.UTF_8);

        SolutionScreen screen = new SolutionScreen("target", "대상 화면", "bo", "목록", "기본", "근거",
                "업무 > 대상", "", null, null, List.of(), List.of(), null, List.of(), false,
                ScreenHistory.EMPTY);
        SolutionMockupService solutions = mock(SolutionMockupService.class);
        SolutionScreenReader reader = mock(SolutionScreenReader.class);
        when(solutions.screens("P1")).thenReturn(List.of(screen));
        when(reader.coreRoot("P1")).thenReturn(core);
        when(reader.fileInClone("P1", "bo/pages/target.md")).thenReturn(pages.resolve("target.md"));
        when(reader.fileInClone("P1", "bo/pages/target.html")).thenReturn(pages.resolve("target.html"));
        ScreenDesignMaterialService service = new ScreenDesignMaterialService(solutions, reader, new ObjectMapper());

        String first = service.snapshot("P1", "bo", "target").fingerprint();
        Files.writeString(assets.resolve("theme.css"), "body{color:blue}", StandardCharsets.UTF_8);

        assertThat(service.snapshot("P1", "bo", "target").fingerprint()).isNotEqualTo(first);
    }
}
