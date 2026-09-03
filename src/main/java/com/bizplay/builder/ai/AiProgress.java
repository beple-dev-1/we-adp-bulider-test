package com.bizplay.builder.ai;

import com.bizplay.builder.ai.ClaudeRunner.Progress;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 도는 중인 AI 실행이 <b>지금 무엇을 하는지</b>. 화면이 이것을 읽어 보여 준다.
 *
 * <p>⭐ <b>AI 자리 넷이 같이 쓴다</b> — 화면 짚기·요구사항 분석·목업 만들기가 저마다 보관함을
 * 만들지 않는다. 열쇠는 부르는 쪽이 정한다({@code "frd:0000005"} 꼴).
 *
 * <p>⚠ <b>메모리에 둔다 — DB 에 두지 않는다.</b> 진행은 상태가 아니라 곁가지다. 상태의 정본은
 * 각 표의 {@code state} 열이고, 서버가 재기동하면 <b>실행 자체가 죽으므로</b> 진행이 같이
 * 사라지는 것이 옳다. ⛔ 설계서의 「기다림을 메모리로 붙잡으면 새로고침 한 번에 사라진다」는
 * <b>마법사 상태</b>를 두고 한 말이다 — 이것과 섞지 마라. 새로고침해도 이건 안 사라진다.
 *
 * <p>⛔ <b>끝나면 {@link #clear} 를 불러라.</b> 안 지우면 실행마다 한 칸씩 영원히 남는다.
 */
@Component
public class AiProgress {

    /** ⚠ 한 판이 수십 걸음이다 — 최근 것만 남긴다. */
    private static final int KEEP = 20;

    private final Map<String, Deque<Progress>> steps = new ConcurrentHashMap<>();

    /**
     * 한 걸음 더한다. ⚠ <b>같은 말이 잇달아 오면 한 번만</b> 남는다 — 모델이 같은 파일을
     * 연달아 읽는 일이 있다.
     */
    public void add(String key, Progress step) {
        steps.compute(key, (ignored, kept) -> {
            Deque<Progress> queue = kept == null ? new ArrayDeque<>() : kept;
            synchronized (queue) {
                if (queue.peekFirst() == null || !queue.peekFirst().text().equals(step.text())) {
                    queue.addFirst(step);
                    while (queue.size() > KEEP) {
                        queue.removeLast();
                    }
                }
            }
            return queue;
        });
    }

    /** 최근 것이 먼저다 — 사람이 「지금 무엇을 하는 중」을 맨 먼저 읽는다. */
    public List<Progress> of(String key) {
        Deque<Progress> queue = steps.get(key);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    public void clear(String key) {
        steps.remove(key);
    }
}
