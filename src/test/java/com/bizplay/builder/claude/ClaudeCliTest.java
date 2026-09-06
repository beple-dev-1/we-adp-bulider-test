package com.bizplay.builder.claude;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI 를 어떻게 띄울지 정하는 자리를 재는 단위 시험이다. <b>프로세스를 띄우지 않는다.</b>
 *
 * <p>⚠ 「배치를 지나면 여러 줄 지시문이 첫 줄에서 잘린다」는 사실 자체는 <b>실측으로 확인한 것</b>이고
 * 여기서 다시 재지 않는다(자식 프로세스가 필요하다). 여기서 지키는 것은 <b>그 사실에서 나온 갈림</b>이다 —
 * 고정 낱말은 {@code cmd /c}, 자유형 인자는 실물 실행파일.
 */
class ClaudeCliTest {

    /** ⛔ 찾은 값을 캐시하므로 시험 사이에 비운다 — 안 비우면 뒤 시험이 앞 시험의 자리를 본다. */
    @AfterEach
    void forgetResolvedLauncher() {
        ClaudeCli.forgetCache();
    }

    @Test
    void 윈도우의_고정_낱말_자리는_cmd_를_앞에_붙인다() {
        assertThat(ClaudeCli.fixedCommand("Windows 11", List.of("auth", "status", "--json")))
                .containsExactly("cmd", "/c", "claude", "auth", "status", "--json");
    }

    @Test
    void 리눅스와_맥은_claude_를_그대로_부른다() {
        assertThat(ClaudeCli.fixedCommand("Linux", List.of("auth", "status", "--json")))
                .containsExactly("claude", "auth", "status", "--json");
        assertThat(ClaudeCli.launcher("Mac OS X")).isEqualTo("claude");
        assertThat(ClaudeCli.launcher(null)).isEqualTo("claude");
    }

    /**
     * ⛔ <b>자유형 인자가 가는 자리는 {@code cmd} 를 안 끼운다.</b> 끼우면 여러 줄 지시문이
     * 첫 줄에서 잘리고, 오류가 안 나 아무도 못 알아챈다.
     */
    @Test
    void 자유형_인자_자리는_셸을_끼우지_않는다() {
        assertThat(ClaudeCli.launcher("Windows 11")).doesNotStartWith("cmd");
    }

    /**
     * 배치가 <b>스스로 적어 둔</b> 실행파일을 꺼내는지. ⚠ 경로를 우리가 짓지 않는다 —
     * npm 설치 꼴이 바뀌어도 배치에 적힌 것을 읽을 뿐이다.
     */
    @Test
    void 배치가_가리키는_실행파일을_꺼낸다(@TempDir Path home) throws IOException {
        Path bin = Files.createDirectories(home.resolve("node_modules/pkg/bin"));
        Path exe = Files.writeString(bin.resolve("claude.exe"), "실물인 척");
        Path shim = Files.writeString(home.resolve("claude.cmd"), """
                @ECHO off
                SETLOCAL
                CALL :find_dp0
                "%dp0%\\node_modules\\pkg\\bin\\claude.exe"   %*
                """, StandardCharsets.UTF_8);

        assertThat(ClaudeCli.executableDeclaredBy(shim)).contains(exe.toAbsolutePath().normalize());
    }

    /** ⛔ 지어내지 않는다 — 배치가 안 가리키면 빈 값이다. */
    @Test
    void 배치가_아무것도_안_가리키면_빈_값이다(@TempDir Path home) throws IOException {
        Path shim = Files.writeString(home.resolve("claude.cmd"), "@ECHO off\nnode index.js %*\n");
        assertThat(ClaudeCli.executableDeclaredBy(shim)).isEmpty();
    }

    /** 배치가 가리키는 파일이 실제로 없으면 그것도 빈 값이다. */
    @Test
    void 배치가_가리키는_파일이_없으면_빈_값이다(@TempDir Path home) throws IOException {
        Path shim = Files.writeString(home.resolve("claude.cmd"),
                "@ECHO off\n\"%dp0%\\없는자리\\claude.exe\" %*\n", StandardCharsets.UTF_8);
        assertThat(ClaudeCli.executableDeclaredBy(shim)).isEmpty();
    }
}
