package com.bizplay.builder.checker;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 기획 저장소 검사 결과를 <b>미리 재어 두고 화면은 읽기만</b> 하게 한다 (2026-08-25 실측).
 *
 * <p><b>무슨 일이 있었나.</b> 개발요청서 상세가 열릴 때마다 {@link CheckerCommand} 를 <b>동기로</b> 돌렸다 —
 * 워크트리 검사 6초 + 기저 검사 5초. 브라우저는 응답이 올 때까지 앞 화면(FRD 작업)에 머물러
 * 「작업 완료 로딩바가 오래 돈다」로 보였고, 「생성중」 15초 자동 새로고침이 그 11초를 반복했다.
 *
 * <p>⭐ <b>열쇠는 검사기 자리의 HEAD + 검사 대상의 HEAD 다.</b> 둘이 같으면 결과가 같으니 다시 안 돈다.
 * 클론이 새 판으로 올라가거나(검사기 갱신) 워크트리에 커밋이 서면 열쇠가 바뀌어 저절로 다시 잰다.
 *
 * <p>⚠ <b>서버 1대라 메모리로 족하다.</b> DB 에 두면 낡은 결과가 재시작 뒤에도 남는다 — 검사기가 6초라
 * 재시작 뒤 한 번 다시 도는 값이 싸다.
 *
 * <p>⚠ <b>{@code UNKNOWN} 은 오래 붙들지 않는다.</b> 사내 TLS·npm 같은 일시 장애가 HEAD 가 바뀔 때까지
 * 굳으면 안 된다 — 유효 기간이 지나면 다음 조회가 다시 건다.
 */
@Component
public class PlanningRepoCheckCache {

    private static final int MAX_ENTRIES = 200;

    private record Entry(CompletableFuture<CheckReport> result, Instant startedAt) {}

    private final PlanningRepoCheckWorker worker;
    private final CheckerCommand checker;
    private final GitCommand git;
    private final Duration gitTimeout;
    private final Duration unknownTtl;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public PlanningRepoCheckCache(PlanningRepoCheckWorker worker, CheckerCommand checker, GitCommand git,
                                  BuilderProperties properties) {
        this(worker, checker, git, properties.checkTimeout(), Duration.ofMinutes(10));
    }

    /** 시험용 — 유효 기간을 직접 준다. */
    public PlanningRepoCheckCache(PlanningRepoCheckWorker worker, CheckerCommand checker, GitCommand git,
                                  Duration gitTimeout, Duration unknownTtl) {
        this.worker = worker;
        this.checker = checker;
        this.git = git;
        this.gitTimeout = gitTimeout;
        this.unknownTtl = unknownTtl;
    }

    /**
     * 결과가 있으면 준다. 없으면 <b>뒤에서 검사를 걸고 빈 값</b>을 준다 — 부르는 쪽은 「검사 중」으로 읽는다.
     *
     * <p>⚠ 조회는 git {@code rev-parse} 둘 값이다(수 ms). 검사기는 여기서 절대 동기로 돌지 않는다.
     */
    public Optional<CheckReport> lookup(Path checkerHome, Path repoRoot) {
        String key = key(checkerHome, repoRoot);
        if (key == null) {
            // ⚠ HEAD 를 못 읽는 자리(git 저장소가 아니다)는 열쇠가 없어 캐시할 수 없다 — 실제 클론·워크트리에는
            //    없는 경우고, 시험의 빈 폴더가 그렇다. 검사기가 verify 자리를 못 찾아 바로 UNKNOWN 을 내니 동기로 돈다.
            return Optional.of(checker.run(checkerHome, repoRoot));
        }
        Entry entry = entries.get(key);
        if (entry != null && entry.result().isDone()) {
            CheckReport report = reportOf(entry);
            if (!report.isUnknown() || entry.startedAt().plus(unknownTtl).isAfter(Instant.now())) {
                return Optional.of(report);
            }
            entries.remove(key, entry);
            entry = null;
        }
        if (entry == null) {
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
            entry = entries.computeIfAbsent(key,
                    k -> new Entry(worker.run(checkerHome, repoRoot), Instant.now()));
        }
        return entry.result().isDone() ? Optional.of(reportOf(entry)) : Optional.empty();
    }

    /**
     * 결과가 날 때까지 <b>기다려서</b> 준다 — 「개발요청 전송」을 누른 자리에서 쓴다 (2026-08-25 병주 지시).
     *
     * <p>⭐ <b>상세 화면은 {@link #lookup} 도 부르지 않는다.</b> 열 때마다 검사를 걸면 결과가 만료될 때마다
     * 같은 화면이 다르게 보인다. 검사기는 사람이 전송을 누른 <b>그 한 번</b>만 돈다 — 그때는 기다릴 각오가 된 자리다.
     *
     * <p>⚠ 캐시에 있으면 그것을 준다. 없으면 뒤에서 돌던 것을 기다리거나 새로 걸어 기다린다(실물 6초).
     */
    public CheckReport await(Path checkerHome, Path repoRoot) {
        Optional<CheckReport> cached = lookup(checkerHome, repoRoot);
        if (cached.isPresent()) {
            return cached.get();
        }
        Entry entry = entries.get(key(checkerHome, repoRoot));
        return entry == null ? checker.run(checkerHome, repoRoot) : reportOf(entry);
    }

    private static CheckReport reportOf(Entry entry) {
        try {
            return entry.result().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CheckReport.unknown();
        } catch (ExecutionException failed) {
            return CheckReport.unknown();
        }
    }

    /** @return 둘 중 하나라도 HEAD 를 못 읽으면 널 — 열쇠가 없다 */
    private String key(Path checkerHome, Path repoRoot) {
        String home = head(checkerHome);
        String root = head(repoRoot);
        if (home == null || root == null) {
            return null;
        }
        return home + "|" + root + "|" + repoRoot.toAbsolutePath().normalize();
    }

    private String head(Path dir) {
        GitResult result = git.run(dir, gitTimeout, "rev-parse", "HEAD");
        return result.succeeded() && !result.stdout().isBlank() ? result.stdout().strip() : null;
    }
}
