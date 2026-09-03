package com.bizplay.builder.screendesign;

import com.bizplay.builder.screendesign.ScreenDesignMaterialService.Snapshot;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.VariantMaterial;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenCaptureRunnerTest {

    @TempDir Path temporary;

    @Test
    void 사용자_매뉴얼은_default_화면을_대표로_고른다() throws Exception {
        VariantMaterial first = variant("jeju", "제주", "first.html", "<h1>제주</h1>");
        VariantMaterial standard = variant("default", "기본 화면", "default.html", "<h1>기본</h1>");
        Snapshot snapshot = snapshot(first, standard);

        VariantMaterial selected = ScreenCaptureRunner.representativeVariant(snapshot);

        assertThat(selected).isSameAs(standard);
    }

    @Test
    void default_화면이_없으면_첫_변형을_대표로_고른다() throws Exception {
        VariantMaterial first = variant("iksan", "익산", "first.html", "<h1>익산</h1>");
        VariantMaterial second = variant("jeju", "제주", "second.html", "<h1>제주</h1>");

        assertThat(ScreenCaptureRunner.representativeVariant(snapshot(first, second))).isSameAs(first);
        assertThatThrownBy(() -> ScreenCaptureRunner.representativeVariant(snapshot()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("대표 화면");
    }

    @Test
    void 캡처_HTML은_실행_요소를_버리고_로컬_자산_기준을_넣는다() throws Exception {
        Path page = temporary.resolve("core/backoffice/pages/sample.html");
        Files.createDirectories(page.getParent());
        String source = """
                <!doctype html><html><head>
                <meta http-equiv="refresh" content="0;url=https://outside.example">
                <link rel="stylesheet" href="../assets/style.css">
                <script src="https://outside.example/run.js"></script>
                </head><body onload="alert(1)">
                <iframe src="https://outside.example"></iframe>
                <a href="javascript:alert(2)" onclick="alert(3)">열기</a>
                <img src="../assets/sample.png" onerror="alert(4)">
                </body></html>
                """;
        VariantMaterial variant = new VariantMaterial("default", "기본 화면", page, source);

        String captured = ScreenCaptureRunner.captureHtml(temporary.resolve("core"), variant);
        var document = Jsoup.parse(captured);

        assertThat(document.select("script,iframe,meta[http-equiv=refresh],[onload],[onclick],[onerror]")).isEmpty();
        assertThat(document.selectFirst("a").hasAttr("href")).isFalse();
        assertThat(document.selectFirst("base").attr("href"))
                .isEqualTo("http://screen-design.invalid/backoffice/pages/");
        assertThat(document.selectFirst("link").attr("href")).isEqualTo("../assets/style.css");
        assertThat(document.selectFirst("img").attr("src")).isEqualTo("../assets/sample.png");
    }

    @Test
    void 캡처_높이와_파일_크기_상한을_검사한다() throws Exception {
        Path image = temporary.resolve("manual-preview.png");
        Files.write(image, new byte[]{1});

        ScreenCaptureRunner.validateManualHeight(1);
        ScreenCaptureRunner.validateManualFile(image);

        assertThatThrownBy(() -> ScreenCaptureRunner.validateManualHeight(0))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("세로 길이");
        assertThatThrownBy(() -> ScreenCaptureRunner.validateManualHeight(16_001))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("세로 길이");
        Files.write(image, new byte[12_000_001]);
        assertThatThrownBy(() -> ScreenCaptureRunner.validateManualFile(image))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("허용 크기");
    }

    @Test
    void 화면설계서도_과도한_높이와_파일_크기를_거절한다() throws Exception {
        Path image = temporary.resolve("screen.png");
        Files.write(image, new byte[]{1});

        ScreenCaptureRunner.validateScreenHeight(16_000);
        ScreenCaptureRunner.validateScreenFile(image);

        assertThatThrownBy(() -> ScreenCaptureRunner.validateScreenHeight(16_001))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("세로 길이");
        Files.write(image, new byte[12_000_001]);
        assertThatThrownBy(() -> ScreenCaptureRunner.validateScreenFile(image))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("허용 크기");
    }

    private Snapshot snapshot(VariantMaterial... variants) {
        return new Snapshot(null, List.of(variants), "", "", temporary.resolve("core"), "fingerprint");
    }

    private VariantMaterial variant(String code, String label, String file, String html) throws IOException {
        Path path = temporary.resolve("core/backoffice/pages").resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, html);
        return new VariantMaterial(code, label, path, html);
    }
}
