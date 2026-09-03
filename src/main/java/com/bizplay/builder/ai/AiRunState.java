package com.bizplay.builder.ai;

/**
 * 실행 상태. <b>값 여섯이고 그중 「끝」은 다섯이다.</b>
 *
 * <p>⛔ <b>「한도」({@code RATE_LIMITED})를 넣지 않는다.</b> {@code decided-facts} 10번이
 * 「한도 초과를 값으로 더하지 않는다」로 못 박았고, 2026-08-14 스파이크가 <b>한도를 갈라낼 수 있는지 못 쟀다.</b>
 * {@code api_error_status == 429} 는 <b>추정</b>이다 — 추정으로 분기를 만들면 아무도 안 걸리거나 엉뚱한 것이 걸린다.
 * 못 가르는 것은 {@link #FAILED} 다.
 *
 * <p>⚠ 저장소 안에 어긋남이 하나 있다 — {@code ai-run} 설계는 끝을 <b>여섯</b>(한도 포함)이라 하고
 * {@code decided-facts} 10번은 <b>다섯</b>이라 한다. <b>여기는 다섯으로 간다</b>(실측이 없는 쪽을 안 만든다).
 * 어느 쪽이 정본인지는 병주가 정할 일이고, 한도가 갈리는 것이 실측되면 그때 더한다.
 */
public enum AiRunState {

    /** 돌고 있다. ⚠ 「그만두는 중」은 값이 아니라 이 값 + 취소 요청 시각 <b>둘</b>로 화면이 만든다. */
    RUNNING("돌고 있다"),
    SUCCEEDED("다 됐다"),
    FAILED("실패했다"),
    TIMED_OUT("시간 상한을 넘겼다"),
    CANCELLED("그만뒀다"),
    CREDENTIAL_LOST("Claude 연결이 끊겼다");

    private final String label;

    AiRunState(String label) {
        this.label = label;
    }

    /** 화면에 뜨는 말. ⛔ 코드값(이름)과 갈라 둔다 — 문구를 다듬는 데 DB 가 안 움직인다. */
    public String label() {
        return label;
    }

    /** 끝난 것인가. 다섯이 참이고 {@link #RUNNING} 만 거짓이다. */
    public boolean isFinished() {
        return this != RUNNING;
    }
}
