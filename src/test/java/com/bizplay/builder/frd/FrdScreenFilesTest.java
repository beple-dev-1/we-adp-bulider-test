package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrdScreenFilesTest {

    @TempDir Path temp;

    @Test
    void 제주_갈래_화면은_pages가_아니라_variants_jeju를_수정한다() throws Exception {
        Path variant = screen("core/webview/variants-jeju/wv-main-home.html");

        assertThat(FrdScreenFiles.resolveHtml(temp, "webview", "wv-main-home",
                List.of("jeju"), false)).isEqualTo(variant.toRealPath());
    }

    @Test
    void 신규_화면은_pages에_만든다() throws Exception {
        Files.createDirectories(temp.resolve("core/webview/pages"));

        assertThat(FrdScreenFiles.resolveHtml(temp, "webview", "tmp-0000001",
                List.of("jeju"), true))
                .isEqualTo(temp.resolve("core/webview/pages/tmp-0000001.html").toAbsolutePath());
    }

    @Test
    void 공통_화면과_기관별_화면이_겹치면_임의로_고르지_않는다() throws Exception {
        screen("core/webview/pages/wv-main-home.html");
        screen("core/webview/variants-jeju/wv-main-home.html");

        assertThatThrownBy(() -> FrdScreenFiles.resolveHtml(temp, "webview", "wv-main-home",
                List.of("jeju"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공통 화면과 기관별 화면");
    }

    @Test
    void 상위_경로를_뜻하는_화면ID는_거절한다() throws Exception {
        Files.createDirectories(temp.resolve("core/webview/pages"));

        assertThatThrownBy(() -> FrdScreenFiles.resolveHtml(temp, "webview", "..",
                List.of("jeju"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("화면ID");
    }

    @Test
    void 기관별_화면의_이동_링크에서_신규_화면의_상위화면을_찾는다() throws Exception {
        Path system = temp.resolve("core/webview");
        Path parent = system.resolve("variants-jeju/wv-main-home.html");
        Files.createDirectories(parent.getParent());
        Files.writeString(parent, "<a data-nav-target=\"tmp-0000066\">먹깨비</a>");
        screen("core/webview/pages/tmp-0000066.html");

        assertThat(FrdScreenFiles.findInboundParent(system, "tmp-0000066"))
                .isEqualTo("wv-main-home");
    }

    private Path screen(String relative) throws Exception {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "화면");
        return file;
    }
}
