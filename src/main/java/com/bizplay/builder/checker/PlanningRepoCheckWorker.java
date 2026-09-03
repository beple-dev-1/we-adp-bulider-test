package com.bizplay.builder.checker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 기획 저장소 검사기를 <b>뒤에서</b> 한 번 돌린다 — {@link PlanningRepoCheckCache} 의 손발이다.
 *
 * <p>★ <b>별도 빈이다.</b> 캐시 안에 두면 자기 자신을 부르는 꼴이라 {@code @Async} 가 발동하지 않는다
 * ({@code ScreenMockupWorker}·{@code ScreenTobeDocumentWorker} 와 같은 본보기).
 *
 * <p>⚠ 시험은 프록시 없이 직접 부른다 — 그때는 동기로 돌고 끝난 Future 가 온다. 그것이 시험에 맞는 모양이다.
 */
@Component
public class PlanningRepoCheckWorker {

    private static final Logger log = LoggerFactory.getLogger(PlanningRepoCheckWorker.class);

    private final CheckerCommand checker;

    public PlanningRepoCheckWorker(CheckerCommand checker) {
        this.checker = checker;
    }

    /** ⛔ 던지지 않는다 — 못 돌린 것은 {@link CheckReport#unknown()} 으로 낸다. Future 가 예외로 끝나면 캐시가 영영 「검사 중」이다. */
    @Async("cloneExecutor")
    public CompletableFuture<CheckReport> run(Path checkerHome, Path repoRoot) {
        try {
            return CompletableFuture.completedFuture(checker.run(checkerHome, repoRoot));
        } catch (RuntimeException failure) {
            log.warn("기획 저장소 검사가 예상 못 한 이유로 끝났다 root={}", repoRoot, failure);
            return CompletableFuture.completedFuture(CheckReport.unknown());
        }
    }
}
