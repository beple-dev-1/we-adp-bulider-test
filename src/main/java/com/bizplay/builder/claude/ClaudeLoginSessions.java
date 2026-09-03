package com.bizplay.builder.claude;

import com.bizplay.builder.config.BuilderProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 살아 있는 `claude auth login` 자식 프로세스를 쥐고 있는 자리.
 *
 * <p><b>왜 있나</b> — 스파이크(2026-08-08)가 확정했다: <b>주소를 내는 프로세스와 코드를 넣는 프로세스가
 * 같아야 한다.</b> PKCE 의 `code_challenge` 와 `state` 를 그 프로세스가 메모리에 쥐기 때문이다.
 * 끊고 다시 부르면 코드가 안 맞는다. 그래서 <b>사람이 브라우저에서 승인하는 동안</b> 자식이 떠 있어야 하고,
 * 떠 있는 것을 관리할 자리가 필요하다. <b>설계에 없던 면적이다.</b>
 *
 * <p>셋을 떠안는다 —
 * <ul>
 *   <li><b>시간 상한</b> — 승인을 안 끝내면 스스로 죽는다 (`builder.claude-login-timeout`, 기본 10분)</li>
 *   <li><b>중도 취소</b> — {@link #discard(String)}. 화면을 다시 열거나 실패하면 컨트롤러가 부른다</li>
 *   <li><b>부팅 청소</b> — 지난 판이 남긴 자리를 통째로 지운다</li>
 * </ul>
 *
 * <p>시계는 <b>데몬 스레드 하나</b>다. Spring 의 `@EnableScheduling` 에 기대지 않는다 —
 * 그것은 Task 10 에서야 선다.
 */
@Component
public class ClaudeLoginSessions {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLoginSessions.class);

    /** 사람이 브라우저에서 승인하는 동안 자식이 기다려 주는 시간. 넘으면 죽인다. */
    private final Duration approvalTimeout;
    private final Path root;

    private final ConcurrentHashMap<String, LiveLogin> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "claude-login-reaper");
        t.setDaemon(true);   // 서버가 내려가는 것을 붙잡지 않는다
        return t;
    });

    public ClaudeLoginSessions(
            BuilderProperties properties,
            @Value("${builder.claude-login-timeout:10m}") Duration approvalTimeout) {
        // 기본값이 있는 값이라 BuilderProperties(전부 필수)에 넣지 않았다.
        this.root = properties.dataRoot().resolve("claude-login");
        this.approvalTimeout = approvalTimeout;
    }

    /** 살아 있는 로그인 하나. `dir` 이 그 로그인만 쓰는 `CLAUDE_CONFIG_DIR` 이다. */
    public record LiveLogin(String handle, Path dir, Process process, BufferedReader stdout) {
    }

    public Duration approvalTimeout() {
        return approvalTimeout;
    }

    /** 이 로그인만 쓰는 빈 자리를 판다. `CLAUDE_CONFIG_DIR` 로 줄 자리다. */
    public Path makeDir(String handle) throws IOException {
        Path dir = root.resolve(handle);
        Files.createDirectories(dir);
        return dir;
    }

    /** 뜬 프로세스를 맡긴다. 이 순간부터 상한 시계가 돈다. */
    public void keep(String handle, Path dir, Process process, BufferedReader stdout, Duration timeout) {
        live.put(handle, new LiveLogin(handle, dir, process, stdout));
        resetTimeout(handle, timeout);
    }

    /**
     * 걸린 시계를 물리고 다시 건다.
     * 주소를 받아낸 뒤 「주소가 나오기를 기다리는 짧은 상한」에서 「사람이 승인하는 긴 상한」으로 늘릴 때 쓴다.
     */
    public void resetTimeout(String handle, Duration timeout) {
        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            log.warn("Claude 로그인이 {} 안에 안 끝나 버린다. handle={}", timeout, handle);
            discard(handle);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = timers.put(handle, scheduled);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** 맡긴 것을 찾는다. 빈 것이면 상한에 걸려 죽었거나 서버가 그 사이 다시 떴다는 뜻이다. */
    public Optional<LiveLogin> find(String handle) {
        return Optional.ofNullable(live.get(handle));
    }

    /**
     * 프로세스를 죽이고 그 자리를 지운다. <b>여러 번 불러도 된다(멱등).</b>
     *
     * <p>그 자리에는 방금 앉은 `.credentials.json` 이 있다 —
     * <b>봉인해 DB 에 넣었으면 디스크에 남기지 않는다.</b>
     */
    public void discard(String handle) {
        ScheduledFuture<?> timer = timers.remove(handle);
        if (timer != null) {
            timer.cancel(false);
        }
        LiveLogin it = live.remove(handle);
        if (it == null) {
            return;
        }
        it.process().destroy();
        try {
            if (!it.process().waitFor(5, TimeUnit.SECONDS)) {
                it.process().destroyForcibly();
            }
        } catch (InterruptedException e) {
            it.process().destroyForcibly();
            Thread.currentThread().interrupt();
        }
        deleteTree(it.dir());
    }

    /** 서버가 뜰 때 — 지난 판이 남긴 자리를 통째로 치운다. 자격 조각이 디스크에 굴러다니지 않게. */
    @PostConstruct
    void cleanPreviousRun() {
        deleteTree(root);
    }

    /**
     * 서버가 내려갈 때 — 살아 있는 것을 전부 죽인다.
     *
     * <p>서버가 하드킬 되면 이것도 안 돈다. 그때 자식은 <b>stdin 파이프가 닫혀 EOF 를 읽고 스스로 끝나고</b>,
     * 남은 자리는 다음 부팅의 {@link #cleanPreviousRun()} 가 치운다.
     */
    @PreDestroy
    void discardAll() {
        live.keySet().forEach(this::discard);
        scheduler.shutdownNow();
    }

    private void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("로그인 자리의 한 조각을 못 지웠다 {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("로그인 자리를 못 치웠다 {}", dir, e);
        }
    }
}
