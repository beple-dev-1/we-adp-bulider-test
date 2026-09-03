package com.bizplay.builder.checker;

import com.bizplay.builder.git.GitCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기획 저장소 검사 결과를 <b>미리 재어 두고 화면은 읽기만</b> 한다 (2026-08-25 실측: 개발요청서 상세가
 * 열릴 때마다 검사기를 동기로 6초 + 기저 5초 돌려 페이지가 11초 넘게 걸렸고, 15초 자동 새로고침이 그것을 반복했다).
 *
 * <p>열쇠는 <b>검사기 자리의 HEAD + 검사 대상의 HEAD</b> 다 — 같으면 결과가 같으니 다시 안 돈다.
 * 서버 1대라 메모리로 족하다.
 */
class PlanningRepoCheckCacheTest {

    @TempDir Path root;

    private final GitCommand git = new GitCommand();
    private Path clone;
    private Path worktree;
    private int runs;

    @BeforeEach
    void setUp() throws IOException {
        clone = repo("clone");
        worktree = repo("worktree");
    }

    @Test
    void 첫_조회는_비어_있고_검사를_뒤에서_건다() {
        CompletableFuture<CheckReport> pending = new CompletableFuture<>();
        PlanningRepoCheckCache cache = cache(pending);

        assertThat(cache.lookup(clone, worktree)).isEmpty();
        assertThat(runs).isEqualTo(1);

        // 도는 중에 다시 물어도 두 번 걸지 않는다.
        assertThat(cache.lookup(clone, worktree)).isEmpty();
        assertThat(runs).isEqualTo(1);

        pending.complete(report(2));
        assertThat(cache.lookup(clone, worktree)).hasValueSatisfying(r -> assertThat(r.redCount()).isEqualTo(2));
        assertThat(runs).isEqualTo(1);
    }

    @Test
    void 대상의_HEAD_가_바뀌면_다시_잰다() throws IOException {
        PlanningRepoCheckCache cache = cache(null);
        cache.lookup(clone, worktree);
        assertThat(runs).isEqualTo(1);

        commit(worktree, "고침.md", "새 내용");

        assertThat(cache.lookup(clone, worktree)).isPresent();
        assertThat(runs).isEqualTo(2);
    }

    @Test
    void 검사기_자리의_HEAD_가_바뀌어도_다시_잰다() throws IOException {
        PlanningRepoCheckCache cache = cache(null);
        cache.lookup(clone, worktree);

        // ⭐ 클론이 새 판(검사기 갱신)으로 올라가면 같은 워크트리도 다시 재야 한다.
        commit(clone, "verify/run.mjs", "// 새 판");

        cache.lookup(clone, worktree);
        assertThat(runs).isEqualTo(2);
    }

    @Test
    void 같은_HEAD_면_한_번만_잰다() {
        PlanningRepoCheckCache cache = cache(null);
        cache.lookup(clone, worktree);
        cache.lookup(clone, worktree);
        cache.lookup(clone, worktree);
        assertThat(runs).isEqualTo(1);
    }

    @Test
    void git_저장소가_아닌_자리는_캐시하지_않고_바로_돌린다() throws IOException {
        Path plain = Files.createDirectories(root.resolve("plain"));
        PlanningRepoCheckCache cache = cache(new CompletableFuture<>());

        // 열쇠가 없어 뒤에 걸 수 없다 — 검사기가 verify 자리를 못 찾아 즉시 끝나니 동기로 돌고 결과를 바로 준다.
        assertThat(cache.lookup(clone, plain)).isPresent();
        assertThat(runs).isEqualTo(1);
    }

    @Test
    void 못_돌린_결과는_오래_붙들지_않는다() {
        PlanningRepoCheckCache cache = cache(null, CheckReport.unknown(), Duration.ZERO);
        assertThat(cache.lookup(clone, worktree)).hasValueSatisfying(r -> assertThat(r.isUnknown()).isTrue());

        // 유효 기간 0 — 다음 조회가 다시 건다. 사내 TLS 같은 일시 장애가 HEAD 가 바뀔 때까지 굳지 않게.
        cache.lookup(clone, worktree);
        assertThat(runs).isEqualTo(2);
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    /** {@code pending} 이 널이면 일꾼이 즉시 끝난다(프록시 없는 동기 호출과 같다). */
    private PlanningRepoCheckCache cache(CompletableFuture<CheckReport> pending) {
        return cache(pending, report(0), Duration.ofMinutes(10));
    }

    private PlanningRepoCheckCache cache(CompletableFuture<CheckReport> pending, CheckReport result,
                                         Duration unknownTtl) {
        CheckerCommand checker = (home, repoRoot) -> { runs++; return result; };
        PlanningRepoCheckWorker worker = new PlanningRepoCheckWorker(checker) {
            @Override
            public CompletableFuture<CheckReport> run(Path home, Path repoRoot) {
                if (pending != null) {
                    runs++;
                    return pending;
                }
                return super.run(home, repoRoot);
            }
        };
        return new PlanningRepoCheckCache(worker, checker, git, Duration.ofSeconds(30), unknownTtl);
    }

    private static CheckReport report(int reds) {
        List<Finding> findings = java.util.stream.IntStream.range(0, reds)
                .mapToObj(i -> new Finding("f" + i + ".md", 1, "GATE", Finding.Level.RED, "위반 " + i, "고쳐라"))
                .toList();
        return new CheckReport(CheckReport.Verdict.CHECKED, findings);
    }

    private Path repo(String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        run(dir, "init", "-q");
        run(dir, "config", "user.email", "t@example.com");
        run(dir, "config", "user.name", "시험");
        commit(dir, "README.md", "# " + name);
        return dir;
    }

    private void commit(Path dir, String file, String content) throws IOException {
        Path target = dir.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        run(dir, "add", ".");
        run(dir, "commit", "-q", "-m", "docs: " + file);
    }

    private void run(Path dir, String... args) {
        var result = git.run(dir, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
    }
}
