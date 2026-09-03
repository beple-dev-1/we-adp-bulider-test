package com.bizplay.builder.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCommandTest {

    private final GitCommand git = new GitCommand();

    @Test
    void git_이_돌고_결과를_준다(@TempDir Path dir) {
        GitResult result = git.run(dir, Duration.ofSeconds(10), "--version");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("git version");
    }

    @Test
    void 실패하면_종료코드가_0이_아니다(@TempDir Path dir) {
        GitResult result = git.run(dir, Duration.ofSeconds(10), "그런명령없다");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void 토큰을_URL_에_끼운다() {
        String url = git.authenticatedUrl("https://gitlab.co/we/expense.git", "glpat-비밀");
        assertThat(url).isEqualTo("https://oauth2:glpat-%EB%B9%84%EB%B0%80@gitlab.co/we/expense.git");
    }

    @Test
    void 가리면_토큰이_안_보인다() {
        String text = "fatal: https://oauth2:glpat-비밀@gitlab.co/we/expense.git 에 못 닿는다";
        assertThat(GitCommand.mask(text))
                .doesNotContain("glpat-비밀")
                .contains("https://***@gitlab.co/we/expense.git");
    }

    @Test
    void 상한을_넘으면_죽이고_예외를_던진다(@TempDir Path dir) {
        assertThatThrownBy(() ->
                git.run(dir, Duration.ofMillis(1), "clone", "https://example.invalid/x.git"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("시간 상한");
    }

    /**
     * ⛔ {@code GitException} 메시지는 실패한 명령을 {@code String.join(" ", command)} 로 그대로
     * 담는다. 그 명령 안에 {@link #authenticatedUrl} 이 만든 인증된 URL 이 있으면 토큰이 그대로
     * 실린다 — 그 예외가 화면(RepoProbe → AdminProjectController → error 모델)과 로그(CloneWorker)
     * 로 새어 나간다. {@code mask(...)} 를 안 거치면 여기서 이미 새 것이다.
     */
    @Test
    void 예외_메시지에도_토큰이_안_남는다(@TempDir Path dir) {
        String url = git.authenticatedUrl("https://gitlab.co/we/expense.git", "glpat-secrettoken123");

        assertThatThrownBy(() ->
                git.run(dir, Duration.ofMillis(1), "clone", url, "x"))
                .isInstanceOf(GitException.class)
                .hasMessageNotContaining("glpat-secrettoken123")
                .hasMessageContaining("***");
    }

    /**
     * ⛔ 이 시험을 지우지 마라 (2026-08-27). 도우미를 켠 채로 두었더니 인증에 성공한 뒤
     * {@code git credential-manager store} 가 죽으면서 클론 전체가 종료코드 128 로 끝났다.
     * 화면에는 사유가 {@code Cloning into …} 한 줄로만 남아 원인을 못 찾았다.
     */
    @Test
    void 자격_도우미를_끈_채로_git_을_부른다() {
        assertThat(GitCommand.command("clone", "http://host/x.git"))
                .containsExactly("git", "-c", "credential.helper=", "clone", "http://host/x.git");
    }

    /** ⚠ 빈 값이어야 한다 — 이름을 넣으면 「그 도우미를 찾아라」가 되어 도리어 실패한다. */
    @Test
    void 도우미를_끄는_값은_이름이_아니라_빈_값이다() {
        assertThat(GitCommand.command("status")).doesNotContain("credential.helper=none");
        assertThat(GitCommand.command("status")).contains("credential.helper=");
    }
}
