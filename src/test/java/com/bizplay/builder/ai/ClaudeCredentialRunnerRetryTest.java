package com.bizplay.builder.ai;

import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.claude.ClaudeAccountLocks;
import com.bizplay.builder.claude.ClaudeCredentialFile;
import com.bizplay.builder.claude.ClaudeCredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 같은 계정의 실행이 동시에 돌다 <b>OAuth 갱신이 겹쳐</b> 한쪽이 자격 오류로 끝나는 자리 (2026-08-27 병주 확정 ②).
 *
 * <p>겹친 쪽은 이미 쓰인 갱신 토큰을 들고 있어 {@code Not logged in} 으로 죽는다. 그때 <b>DB 자격이 내가
 * 시작할 때와 달라졌으면</b>(다른 실행이 갱신해 저장했으면) 새 자격으로 <b>한 번만</b> 다시 돈다.
 * DB 가 그대로면 진짜 로그아웃이라 종전처럼 자격끊김으로 닫는다.
 */
class ClaudeCredentialRunnerRetryTest {

    @TempDir Path temp;

    private static final ClaudeResult LOST = new ClaudeResult(1, true, "api_error", null, "Not logged in");
    private static final ClaudeResult OK = new ClaudeResult(0, false, null, null, "ok");

    /** 실행마다 자격 파일 내용을 기억하고 정해진 답을 차례로 내는 가짜. */
    private static class ScriptedRunner implements ClaudeRunner {
        final List<String> credentialSeen = new ArrayList<>();
        final List<ClaudeResult> answers;

        ScriptedRunner(ClaudeResult... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                String instruction, Consumer<Process> onStarted) {
            return run(credentialDir, workDir, timeout, List.of(), instruction, onStarted, null);
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout, List<String> extraArgs,
                                String instruction, Consumer<Process> onStarted, Consumer<Progress> onProgress) {
            try {
                credentialSeen.add(Files.readString(credentialDir.resolve(".credentials.json"),
                        StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return answers.remove(0);
        }
    }

    private ClaudeCredentialRunner runnerWith(ScriptedRunner scripted, AtomicReference<String> db) {
        ClaudeCredentialService credentials = mock(ClaudeCredentialService.class);
        when(credentials.tokenOf(anyString())).thenAnswer(invocation -> Optional.of(db.get()));
        ClaudeCredentialFile file = mock(ClaudeCredentialFile.class);
        when(file.extractOAuthBlock(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ClaudeCredentialRunner(scripted, credentials, file, new ClaudeAccountLocks(3));
    }

    @Test
    void 자격_오류로_끝났는데_그_사이_DB_자격이_바뀌었으면_새_자격으로_한_번_다시_돈다() throws IOException {
        AtomicReference<String> db = new AtomicReference<>("T0");
        ScriptedRunner scripted = new ScriptedRunner(LOST, OK) {
            @Override
            public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout, List<String> extraArgs,
                                    String instruction, Consumer<Process> onStarted, Consumer<Progress> onProgress) {
                ClaudeResult answer = super.run(credentialDir, workDir, timeout, extraArgs, instruction,
                        onStarted, onProgress);
                // 첫 실행이 도는 동안 다른 실행이 갱신 자격을 DB 에 저장했다.
                if (credentialSeen.size() == 1) db.set("T1");
                return answer;
            }
        };

        Optional<ClaudeResult> result = runnerWith(scripted, db).run("acct", temp.resolve("c"), temp,
                Duration.ofSeconds(5), List.of(), "일", process -> { }, null);

        assertThat(result).isPresent();
        assertThat(result.get().exitCode()).isZero();
        assertThat(scripted.credentialSeen).as("두 번째는 새 자격 T1 으로 돌았다").containsExactly("T0", "T1");
    }

    @Test
    void DB_자격이_그대로면_진짜_로그아웃이라_다시_돌지_않는다() throws IOException {
        AtomicReference<String> db = new AtomicReference<>("T0");
        ScriptedRunner scripted = new ScriptedRunner(LOST, OK);

        Optional<ClaudeResult> result = runnerWith(scripted, db).run("acct", temp.resolve("c"), temp,
                Duration.ofSeconds(5), List.of(), "일", process -> { }, null);

        assertThat(result).isPresent();
        assertThat(result.get().credentialLost()).isTrue();
        assertThat(scripted.credentialSeen).as("한 번만 돌았다").containsExactly("T0");
    }

    @Test
    void 자격_오류가_아닌_실패는_DB_가_바뀌었어도_다시_돌지_않는다() throws IOException {
        AtomicReference<String> db = new AtomicReference<>("T0");
        ClaudeResult otherFailure = new ClaudeResult(1, true, "api_error", 500, "server error");
        ScriptedRunner scripted = new ScriptedRunner(otherFailure, OK) {
            @Override
            public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout, List<String> extraArgs,
                                    String instruction, Consumer<Process> onStarted, Consumer<Progress> onProgress) {
                ClaudeResult answer = super.run(credentialDir, workDir, timeout, extraArgs, instruction,
                        onStarted, onProgress);
                db.set("T1");
                return answer;
            }
        };

        Optional<ClaudeResult> result = runnerWith(scripted, db).run("acct", temp.resolve("c"), temp,
                Duration.ofSeconds(5), List.of(), "일", process -> { }, null);

        assertThat(result.get().exitCode()).isEqualTo(1);
        assertThat(scripted.credentialSeen).containsExactly("T0");
    }
}
