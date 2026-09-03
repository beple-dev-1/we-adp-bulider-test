package com.bizplay.builder.screendesign;

import com.bizplay.builder.screendesign.ScreenDesignMaterialService.Snapshot;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.VariantMaterial;
import com.bizplay.builder.solution.SolutionScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 서버에 별도로 설치한 Chromium까지 포함해 실제 화면설계서 묶음을 확인한다. */
@EnabledIfEnvironmentVariable(named = "BUILDER_BROWSER_TEST", matches = "true")
class ScreenCaptureRunnerBrowserTest {

    @TempDir Path temporary;

    @Test
    void 화면을_PNG와_한글_PDF로_생성한다() throws Exception {
        Path core = temporary.resolve("core");
        Path page = core.resolve("backoffice/pages/sample.html");
        Files.createDirectories(page.getParent());
        String html = """
                <!doctype html>
                <html lang="ko"><head><meta charset="utf-8"><title>결재 요청</title></head>
                <body><main><h1>결재 요청</h1>
                <label for="title">제목</label><input id="title" placeholder="제목 입력">
                <button type="button">임시 저장</button><a href="#content">내용 확인</a>
                <section id="content"><p>화면설계서 브라우저 검증</p></section>
                </main></body></html>
                """;
        Files.writeString(page, html, StandardCharsets.UTF_8);
        VariantMaterial variant = new VariantMaterial("default", "기본 화면", page, html);
        SolutionScreen screen = mock(SolutionScreen.class);
        when(screen.screenName()).thenReturn("결재 요청");
        when(screen.screenId()).thenReturn("EXP-REQ-001");
        when(screen.menuPath()).thenReturn("경비 > 결재 요청");
        when(screen.applicationSummary()).thenReturn("전체 적용");
        Snapshot snapshot = new Snapshot(screen, List.of(variant), "# 결재 요청\n- 제목은 필수입니다.",
                "{}", core.toRealPath(), "fingerprint");
        Path output = temporary.resolve("bundle");

        ScreenCaptureRunner.CaptureResult result = new ScreenCaptureRunner().capture(snapshot, output);

        assertThat(result.captures()).hasSize(1);
        assertThat(result.captures().get(0).width()).isEqualTo(1600);
        assertThat(result.captures().get(0).callouts()).hasSize(3);
        assertThat(Files.readAllBytes(output.resolve("screen-1.png")))
                .startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertThat(Files.readString(output.resolve("screen-design.pdf"), StandardCharsets.ISO_8859_1))
                .startsWith("%PDF");
        assertThat(result.manifestJson()).contains("screen-1.png", "screen-design.pdf");
    }
}
