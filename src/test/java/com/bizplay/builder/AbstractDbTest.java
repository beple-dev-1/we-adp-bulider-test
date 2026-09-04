package com.bizplay.builder;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 테스트는 zonky 가 띄우는 빈 PostgreSQL 을 쓴다. Docker 가 필요 없다.
 * 스키마는 Flyway 가 만든다 — 빈 DB 라서 테스트에서 강제로 켠다.
 *
 * <p>⚠ {@code @Transactional} 을 지우지 마라. zonky 는 DB 를 <b>컨텍스트마다 한 번</b> 띄우고
 * 테스트 사이에 되돌려주지 않는다. 이게 없으면 한 테스트가 바꾼 계정·프로젝트가
 * 다음 테스트로 새어 <b>실행 순서에 따라 통과와 실패가 갈린다</b>(찾기 아주 어렵다).
 * 이웃 저장소 we-adk-admin 도 DB 테스트 전부에 같은 것을 달아 뒀다.
 *
 * <p>부팅에서 서는 슈퍼계정(Task 5)은 컨텍스트 시작 때 커밋되므로 롤백에 안 지워진다 — 그게 맞다.
 *
 * <h2>⛔ 디스크도 컨텍스트마다 따로 쓴다 (2026-09-04 · 작업 002-2)</h2>
 *
 * <p><b>왜.</b> zonky 는 <b>스프링 컨텍스트마다 빈 DB 를 새로 띄운다</b> — 전수 한 번에 18개를
 * 실측했다. DB 가 따로면 <b>시퀀스도 따로</b>라 프로젝트 번호가 컨텍스트마다 {@code 0000001}
 * 부터 다시 난다. 그런데 종전에는 디스크가 {@code %TEMP%/builder-test} <b>하나뿐</b>이라
 * 다른 컨텍스트의 {@code 0000005} 와 이번 {@code 0000005} 가 <b>같은 폴더를 가리켰다.</b>
 * 앞 실행이 남긴 것까지 그대로 얹혀서, 판정이 <b>실행 순서와 잔재에 흔들렸다.</b>
 *
 * <p>⚠ <b>「롤백에 번호가 되돌아가서」가 아니다.</b> {@code IdSequence} 가 적었듯 시퀀스는
 * 트랜잭션을 안 타서 롤백해도 번호가 안 돌아온다 — <b>DB 자체가 다른 것</b>이 원인이다.
 *
 * <p><b>실측 (2026-09-04).</b> 공용 폴더를 지우고 전수 = 빨강 23. 지우지 않고 전수 = 빨강 24
 * (늘어난 것은 {@code FrdCanvasScreenTest.전체_캔버스에서_현재_화면을_독립된_신규_화면으로_복제한다}).
 * 종전에 001·002 에서 터진 자리도 같은 꼴이었고 <b>터지는 클래스는 그때그때 달랐다</b> —
 * 시험이 늘어 번호 배분이 밀리면 부딪히는 짝이 바뀌기 때문이다.
 *
 * <p>⛔ <b>이 격리를 걷어내지 마라.</b> 걷어내면 초록·빨강이 다시 실행 순서를 탄다.
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Transactional
@AutoConfigureEmbeddedDatabase(
        provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY,
        type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
public abstract class AbstractDbTest {

    /** 모든 시험 임시 폴더의 지붕. 이 아래에 실행마다 하나씩 판다. */
    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "builder-test");

    /** 이번 실행의 자리. 프로세스 번호와 시각을 붙여 다른 실행과 절대 안 겹치게 한다. */
    private static final Path RUN_ROOT = TEST_ROOT.resolve(
            "run-%d-%d".formatted(ProcessHandle.current().pid(), System.currentTimeMillis()));

    /** 컨텍스트마다 하나씩 늘려 폴더 이름을 가른다. */
    private static final AtomicInteger CONTEXT_NUMBER = new AtomicInteger();

    /**
     * ⚠ 죽은 실행이 남긴 자리를 치울 여유. 전수가 이보다 오래 걸리는 일은 없으므로,
     * 이보다 오래된 것은 <b>끝난 실행의 찌꺼기</b>다. 강제 종료로 아래 갈고리가 못 돌았을 때를 받는다.
     */
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    static {
        sweepStale();
        // ⚠ 갈고리가 못 돌 수도 있다(강제 종료). 그래서 위 sweepStale 이 다음 실행에서 한 번 더 받는다.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(RUN_ROOT), "builder-test-cleanup"));
    }

    /**
     * 엔티티를 손으로 만드는 테스트가 기본키를 받아 갈 자리.
     *
     * <p>⚠ 번호를 글자로 박지 마라({@code "0000001"}). 그 표를 쓰는 다른 테스트와 <b>부딪힌다</b> —
     * 부팅 슈퍼계정은 롤백에 안 지워져 계정 {@code '0000001'} 이 이미 나가 있다.
     */
    @org.springframework.beans.factory.annotation.Autowired
    protected com.bizplay.builder.id.IdSequence ids;

    /** 뒤의 모든 통합 테스트가 이 설치 설정을 쓴다. */
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("builder.super-account-login-id", () -> "admin");
        registry.add("builder.super-account-password", () -> "firstpass");
        registry.add("builder.secret-key-base64", () -> "A".repeat(42) + "g=");
        // Global Constraints: 경로는 Path 로만 만든다. "/" 를 문자열로 이어붙이지 않는다.
        // ⚠ 값을 먼저 굳혀서 넘긴다 — 람다 안에서 번호를 올리면 그 람다가 다시 불릴 때마다
        //   자리가 바뀌어, 같은 컨텍스트가 두 폴더를 보게 된다.
        Path dataRoot = RUN_ROOT.resolve("ctx-" + CONTEXT_NUMBER.incrementAndGet());
        registry.add("builder.data-root", dataRoot::toString);
        registry.add("builder.ai-run-timeout", () -> "10m");
    }

    /**
     * 화면 HTML 에서 <b>CSRF 토큰 값만</b> 지운다.
     *
     * <p>⚠ <b>왜 있나 (2026-09-04 · 작업 002-2).</b> 토큰은 요청마다 새로 나는 난수 글자다.
     * 짧은 낱말을 금지어로 두고 페이지 전체를 훑으면 <b>그 낱말이 토큰 안에 우연히 들어가</b>
     * 시험이 확률적으로 깨진다. 실제로 {@code "AI"} 두 글자가 토큰(
     * {@code …QksjiAIwwgd…})에 섞여 <b>전수 다섯 번에 한 번</b> 터졌다 —
     * 100자 남짓에 64글자 알파벳이면 두 글자가 들어갈 확률이 한 번에 2~3%다.
     *
     * <p>⛔ <b>금지어 목록을 줄이는 것이 아니다.</b> 볼 것은 그대로 보고 <b>난수만</b> 걷어낸다.
     * 금지어에서 낱말을 빼면 그 낱말이 정말로 샜을 때 아무도 못 잡는다.
     *
     * <p>⚠ 세 글자짜리(예: {@code "999"})는 만 번에 네 번쯤이라 거의 안 터지지만 같은 병이다 —
     * 렌더된 화면 전체를 훑는 자리라면 이것을 지나게 한다.
     */
    protected static String withoutCsrfTokens(String html) {
        return html.replaceAll("(name=\"_csrf\"[^>]*?(?:value|content)=\")[^\"]*\"", "$1\"");
    }

    /** 앞 실행이 남긴 자리를 치운다. 지금 도는 다른 실행을 건드리지 않으려고 나이로 가른다. */
    private static void sweepStale() {
        if (!Files.isDirectory(TEST_ROOT)) {
            return;
        }
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        try (Stream<Path> entries = Files.list(TEST_ROOT)) {
            entries.filter(entry -> !entry.equals(RUN_ROOT))
                    .filter(entry -> olderThan(entry, cutoff))
                    .forEach(AbstractDbTest::deleteTree);
        } catch (IOException unreadable) {
            // 못 치워도 시험은 돌아야 한다. 다만 조용히 넘기지 않는다 — 쌓이면 다음 사람이 봐야 한다.
            System.err.println("[builder-test] 지붕 폴더를 못 읽어 묵은 자리를 못 치웠다: " + unreadable);
        }
    }

    private static boolean olderThan(Path entry, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * 통째로 지운다.
     *
     * <p>⚠ <b>읽기 전용을 먼저 푼다</b> — 클론 안 {@code .git/objects} 는 읽기 전용이라
     * 그냥 지우면 윈도우에서 {@code AccessDenied} 가 난다. 002 에서 그 자리를 밟았다.
     */
    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true, false);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException stuck) {
                    System.err.println("[builder-test] 못 지운 자리: " + path + " — " + stuck);
                }
            });
        } catch (IOException unreadable) {
            System.err.println("[builder-test] 못 훑은 자리: " + root + " — " + unreadable);
        }
    }
}
