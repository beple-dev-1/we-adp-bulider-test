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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 같은 계정의 실행이 <b>동시에</b> 돌 때 갱신 자격 되쓰기가 겹치는 자리를 재현한다
 * (2026-08-26 코덱스 적대 검증이 짚은 결함).
 *
 * <p>둘이 같은 원본 T0 을 들고 시작해 각자 T1·T2 로 갱신했다. 되쓰기가 「DB 읽기 → 비교 → 저장」을
 * 한 덩어리로 잠그지 않으면 둘 다 「DB 는 아직 T0」을 읽고 둘 다 저장한다 — 뒤에 쓴 것이 이긴다.
 * 잠그면 두 번째는 DB 가 이미 바뀐 것을 보고 물러난다.
 *
 * <p>⚠ 재현 장치: 되쓰기 단계의 DB 읽기에서 두 스레드를 <b>차단기</b>로 만나게 한다. 잠금이 있으면
 * 첫 스레드가 차단기에서 <b>혼자</b> 기다리다 시간이 지나 저장하고, 그제야 둘째가 읽는다 —
 * 그래서 잠금이 있을 때는 차단기 시간 초과가 <b>정상</b>이다.
 */
class ClaudeCredentialRunnerRaceTest {

    @TempDir Path temp;

    @Test
    void 동시_실행_둘이_같은_원본을_들고_끝나도_갱신_자격은_한_번만_저장된다() throws Exception {
        AtomicReference<String> db = new AtomicReference<>("T0");
        AtomicInteger stores = new AtomicInteger();
        AtomicInteger persistReads = new AtomicInteger();
        CyclicBarrier bothReading = new CyclicBarrier(2);

        ClaudeCredentialService credentials = mock(ClaudeCredentialService.class);
        when(credentials.tokenOf(anyString())).thenAnswer(invocation -> {
            // 실행 시작 때의 읽기 둘(1·2번째)은 그대로 두고, 되쓰기 단계의 읽기(3번째부터)에서 둘을 맞닥뜨리게 한다.
            if (persistReads.incrementAndGet() > 2) {
                try {
                    bothReading.await(700, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | BrokenBarrierException lockedOut) {
                    // 잠금이 있으면 여기로 온다 — 둘째가 못 들어와서 혼자 기다린 것이다.
                }
            }
            return Optional.of(db.get());
        });
        doAnswer(invocation -> {
            stores.incrementAndGet();
            db.set(invocation.getArgument(1));
            return null;
        }).when(credentials).store(anyString(), anyString());

        ClaudeCredentialFile file = mock(ClaudeCredentialFile.class);
        when(file.extractOAuthBlock(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        CyclicBarrier bothStarted = new CyclicBarrier(2);
        ClaudeRunner refreshingRunner = new ClaudeRunner() {
            @Override
            public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                    String instruction, Consumer<Process> onStarted) {
                return run(credentialDir, workDir, timeout, List.of(), instruction, onStarted, null);
            }

            @Override
            public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout, List<String> extraArgs,
                                    String instruction, Consumer<Process> onStarted, Consumer<Progress> onProgress) {
                try {
                    // 둘 다 T0 을 파일에 받은 뒤에야 갱신한다 — 「같은 원본을 들고 시작」을 보장한다.
                    bothStarted.await(3, TimeUnit.SECONDS);
                    Files.writeString(credentialDir.resolve(".credentials.json"), instruction,
                            StandardCharsets.UTF_8);
                } catch (IOException | InterruptedException | BrokenBarrierException | TimeoutException e) {
                    throw new IllegalStateException(e);
                }
                return new ClaudeResult(0, false, null, null, "ok");
            }
        };

        ClaudeCredentialRunner runner = new ClaudeCredentialRunner(
                refreshingRunner, credentials, file, new ClaudeAccountLocks(2));

        Thread first = new Thread(() -> run(runner, temp.resolve("a"), "T1"));
        Thread second = new Thread(() -> run(runner, temp.resolve("b"), "T2"));
        first.start();
        second.start();
        first.join(10_000);
        second.join(10_000);

        assertThat(stores.get())
                .as("둘 중 하나만 저장돼야 한다 — 둘 다 저장되면 뒤의 낡은 토큰이 앞의 것을 덮는다")
                .isEqualTo(1);
        assertThat(db.get()).isIn("T1", "T2");
    }

    private static void run(ClaudeCredentialRunner runner, Path credentialDir, String refreshedTo) {
        try {
            runner.run("acct", credentialDir, credentialDir, Duration.ofSeconds(5),
                    List.of(), refreshedTo, process -> { }, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
