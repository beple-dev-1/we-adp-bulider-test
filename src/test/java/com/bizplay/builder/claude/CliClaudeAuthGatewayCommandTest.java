package com.bizplay.builder.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI 를 띄우는 명령줄만 재는 단위 시험이다. <b>프로세스를 실제로 띄우지 않는다</b> —
 * 그것은 사람이 붙어 있어야 하는 대화형 로그인이라 러너가 갈 수 있는 자리가 아니다.
 *
 * <p>⚠ 004-1(2026-09-06)에 <b>「승인 화면 열기」가 윈도우에서 500</b> 이 났다. 까닭은
 * {@code ProcessBuilder} 가 윈도우에서 {@code .exe} 만 찾는데 npm 이 까는 것은
 * {@code claude.cmd} 라는 것이었다({@code CreateProcess error=2}).
 * 그 판단이 여기 한 자리에 모여 있으므로 여기를 지킨다.
 */
class CliClaudeAuthGatewayCommandTest {

    @Test
    void 윈도우에서는_cmd_를_앞에_붙여_claude_를_찾게_한다() {
        assertThat(CliClaudeAuthGateway.loginCommand("Windows 11"))
                .containsExactly("cmd", "/c", "claude", "auth", "login", "--claudeai");
    }

    @Test
    void 윈도우_이름이_어떻게_적히든_같게_본다() {
        assertThat(CliClaudeAuthGateway.loginCommand("windows server 2019"))
                .isEqualTo(CliClaudeAuthGateway.loginCommand("Windows 11"));
    }

    @Test
    void 리눅스와_맥에서는_claude_를_그대로_부른다() {
        assertThat(CliClaudeAuthGateway.loginCommand("Linux"))
                .containsExactly("claude", "auth", "login", "--claudeai");
        assertThat(CliClaudeAuthGateway.loginCommand("Mac OS X"))
                .containsExactly("claude", "auth", "login", "--claudeai");
    }

    /** 운영체제 이름을 못 읽는 자리에서도 터지지 않고 종전 길로 간다. */
    @Test
    void 운영체제_이름이_없으면_종전대로_간다() {
        assertThat(CliClaudeAuthGateway.loginCommand(null))
                .containsExactly("claude", "auth", "login", "--claudeai");
    }

    /**
     * ⛔ 이 명령줄에 사람이 준 값을 더하지 마라 — 윈도우에서는 셸을 한 번 더 지나므로
     * 그 순간 「인자가 전부 고정 낱말이라 안전하다」는 근거가 무너진다.
     *
     * <p>⚠ 이 단정이 그것을 <b>막지는 못한다</b> — 영문자로만 된 값은 지나간다.
     * 실질 방어는 위의 {@code containsExactly} 이고, 이것은 <b>거드는 쪽</b>이다.
     */
    @Test
    void 명령줄에_붙는_인자는_고정_낱말뿐이다() {
        assertThat(CliClaudeAuthGateway.loginCommand("Windows 11"))
                .allSatisfy(part -> assertThat(part).matches("[A-Za-z/-]+"));
    }
}
