package com.bizplay.builder.frd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FRD에서 AI가 선택한 대기·실패 화면에 초안 만들기를 <b>한꺼번에</b> 건다.
 *
 * <p>⭐ <b>2026-08-26 에 「한 장씩 순차」에서 「화면마다 비동기 제출」로 바꿨다.</b> 종전에는 이 스레드가
 * {@code for} 로 한 장이 끝나야 다음 장을 시작해 화면 5장이 5배 걸렸다. 지금은 화면마다
 * {@link ScreenMockupWorker#generate} 를 던지고, 같은 계정의 동시 실행 수는
 * {@code ClaudeAccountLocks}({@code builder.ai-account-concurrency}) 가 잡는다.
 *
 * <p>⚠ <b>던지기 전에 「만드는 중」으로 먼저 표시한다.</b> 제출은 순간에 끝나므로, 표시가 없으면 사람이
 * 버튼을 두 번 눌렀을 때 두 번째 일괄이 같은 화면을 또 고른다. 종전에는 {@code runningFrds} 가 그 구멍을
 * 막았는데 이제 그것은 「제출 중」이라는 짧은 순간만 막는다.
 */
@Component
public class ScreenMockupBatchWorker {

    private static final Logger log = LoggerFactory.getLogger(ScreenMockupBatchWorker.class);

    private final FrdScreenMapper screens;
    private final ScreenMockupWorker screenWorker;
    private final ScreenMockupService mockups;
    private final Set<String> runningFrds = ConcurrentHashMap.newKeySet();

    public ScreenMockupBatchWorker(FrdScreenMapper screens, ScreenMockupWorker screenWorker,
                                   ScreenMockupService mockups) {
        this.screens = screens;
        this.screenWorker = screenWorker;
        this.mockups = mockups;
    }

    @Async("aiExecutor")
    public void generate(String frdId) {
        if (!runningFrds.add(frdId)) {
            log.info("FRD 일괄 초안 만들기가 이미 진행 중이다 frdId={}", frdId);
            return;
        }
        try {
            var targets = screens.selectByFrdId(frdId).stream()
                    .filter(FrdScreen::canGenerateDraft)
                    .toList();
            log.info("FRD 일괄 초안 만들기 제출 frdId={} 대상={}개 — 화면마다 따로 돈다", frdId, targets.size());
            for (FrdScreen target : targets) {
                mockups.markGenerating(target.id());
                screenWorker.generate(target.id());
            }
        } finally {
            runningFrds.remove(frdId);
        }
    }
}
