package com.bizplay.builder.frd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 목업 만들기의 <b>DB 토막</b>. ⛔ 여기에 프로세스를 띄우는 코드를 넣지 마라 —
 * {@link ScreenPickService} 와 같은 본보기다.
 */
@Service
public class ScreenMockupService {

    private static final Logger log = LoggerFactory.getLogger(ScreenMockupService.class);

    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final FrdScreenMarkerHistoryMapper markerHistories;

    public ScreenMockupService(FrdScreenMapper screens, FrdScreenHistoryMapper histories,
                               FrdScreenMarkerHistoryMapper markerHistories) {
        this.screens = screens;
        this.histories = histories;
        this.markerHistories = markerHistories;
    }

    /** 만들기 시작 — 화면이 「AI 초안 만드는 중」이 된다. */
    @Transactional
    public void markGenerating(String frdScreenId) {
        screens.updateState(frdScreenId, FrdScreen.State.GENERATING);
    }

    /** ⚠ 다시 만들면 <b>덮어쓴다</b> — 이번 판에 버전이 없다. */
    @Transactional
    public long markGenerated(String frdScreenId, ScreenMockupReader.Mockup mockup) {
        String changes = joinChanges(mockup.changes());
        screens.updateGenerated(frdScreenId, mockup.html(), changes, Instant.now());
        long historyId = histories.insert(frdScreenId, mockup.html(), changes);
        markerHistories.snapshot(historyId, frdScreenId);
        return historyId;
    }

    /** 맵 AI가 HTML과 화면 관계 MD를 함께 바꾼 시점을 화면별 이력으로 저장한다. */
    @Transactional
    public long markCanvasGenerated(String frdScreenId, ScreenMockupReader.Mockup mockup,
                                    String md, String operationId) {
        String changes = joinChanges(mockup.changes());
        screens.updateGenerated(frdScreenId, mockup.html(), changes, Instant.now());
        long historyId = histories.insertCanvas(frdScreenId, mockup.html(), md, changes, operationId);
        markerHistories.snapshot(historyId, frdScreenId);
        return historyId;
    }

    @Transactional
    public void markFailed(String frdScreenId, String failure) {
        screens.updateFailed(frdScreenId, failure);
    }

    /** 서버가 다시 뜨면 이전 프로세스의 실행은 살아 있지 않다 — 영원한 「만드는 중」을 실패로 닫는다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedGenerations() {
        int recovered = screens.failInterruptedGenerations("서버가 다시 시작되어 AI 초안 만들기가 중단되었습니다.");
        if (recovered > 0) {
            log.warn("중단된 AI 초안 만들기를 실패로 전환했다 화면={}개", recovered);
        }
    }

    /** ⚠ {@code changes} 열은 문자열 하나다 — 줄바꿈으로 이어 붙여 화면에서 다시 나눈다. */
    private String joinChanges(List<String> changes) {
        return changes == null || changes.isEmpty() ? null : String.join("\n", changes);
    }
}
