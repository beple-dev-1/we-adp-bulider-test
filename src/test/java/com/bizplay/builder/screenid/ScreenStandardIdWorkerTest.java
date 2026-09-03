package com.bizplay.builder.screenid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ⚠ 2026-08-20 픽스라운드 1 — 프록시(빈 주입)로 부르면 {@code @Async} 메서드는 안이 어떻든
 * 호출부에서 절대 던지지 않는다(예외는 {@code AsyncUncaughtExceptionHandler} 로 간다). 그래서
 * 시험은 전부 {@code new ScreenStandardIdWorker(...)} 로 직접 만들어 <b>동기로</b> 부른다 —
 * 실패할 수 있는 시험만 남긴다.
 */
class ScreenStandardIdWorkerTest {

    @Test
    void 채번이_할_일이_없어도_대역을_그대로_부르고_인자를_그대로_넘긴다() {
        RecordingAssigner assigner = new RecordingAssigner(0);
        ScreenStandardIdWorker worker = new ScreenStandardIdWorker(assigner);

        assertThatCode(() -> worker.assignQuietly("9999999", "acc-1")).doesNotThrowAnyException();

        assertThat(assigner.calls).hasSize(1);
        assertThat(assigner.calls.get(0)).containsExactly("9999999", "acc-1");
    }

    @Test
    void 채번이_터져도_삼키고_로그만_남긴다() {
        ScreenStandardIdWorker exploding = new ScreenStandardIdWorker((projectId, accountId) -> {
            throw new IllegalStateException("일부러 터뜨린다");
        });

        assertThatCode(() -> exploding.assignQuietly("0000001", null)).doesNotThrowAnyException();
    }

    /** ⚠ 넘어온 {@code projectId}·{@code accountId} 가 그대로 들어왔는지 재려고 인자를 적어 둔다. */
    private static final class RecordingAssigner implements ScreenStandardIdWorker.Assigner {
        private final int result;
        private final java.util.List<String[]> calls = new java.util.ArrayList<>();

        RecordingAssigner(int result) {
            this.result = result;
        }

        @Override
        public int assign(String projectId, String accountId) {
            calls.add(new String[] {projectId, accountId});
            return result;
        }
    }
}
