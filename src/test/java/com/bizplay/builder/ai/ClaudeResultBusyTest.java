package com.bizplay.builder.ai;

import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「저쪽이 붐빈 것」과 「그 밖의 실패」를 가르는 자리 (2026-09-01 실측).
 *
 * <p>⭐ FRD 0000069 가 204초를 다 쓰고 마지막에 529 로 버려졌다. 붐빔만 다시 돌린다 —
 * 그 밖의 실패는 다시 돌려도 같은 이유로 또 죽는다.
 */
class ClaudeResultBusyTest {

    private static ClaudeResult apiError(Integer status, String body) {
        return new ClaudeResult(1, true, "api_error", status, body);
    }

    @Test
    void 과부하_529_는_붐빔이다() {
        assertThat(apiError(529, "API Error: 529 Overloaded").busy()).isTrue();
    }

    @Test
    void 한도_429_와_그밖의_5xx_도_붐빔이다() {
        ClaudeResult rateLimited = apiError(429, "rate limit");
        assertThat(rateLimited.busy()).isTrue();
        assertThat(rateLimited.rateLimited()).isTrue();
        assertThat(apiError(500, "internal").busy()).isTrue();
        assertThat(apiError(503, "unavailable").busy()).isTrue();
        assertThat(apiError(529, "overloaded").rateLimited()).isFalse();
    }

    @Test
    void 요청이_틀린_4xx_는_붐빔이_아니다() {
        assertThat(apiError(400, "bad request").busy()).isFalse();
        assertThat(apiError(401, "unauthorized").busy()).isFalse();
    }

    /** ⛔ 상태 없는 실패를 붐빔으로 읽으면 자격끊김과 시간초과를 덮어쓴다. */
    @Test
    void 자격끊김과_시간초과는_붐빔이_아니다() {
        ClaudeResult lost = apiError(null, "Not logged in");
        assertThat(lost.credentialLost()).isTrue();
        assertThat(lost.busy()).isFalse();
        assertThat(ClaudeResult.timedOut().busy()).isFalse();
    }

    @Test
    void 성공한_판은_붐빔이_아니다() {
        assertThat(new ClaudeResult(0, false, "completed", null, "{}").busy()).isFalse();
    }
}
