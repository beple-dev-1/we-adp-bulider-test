package com.bizplay.builder.devrequest;

/**
 * 창구의 답을 <b>상태 셋 중 하나로</b> 옮긴다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-handoff-to-dev-design.md}
 * 「어느 답이 어느 상태로 가나」.
 *
 * <p>⭐ <b>기준 하나다: 「상대가 처리 안 했다」가 증명되는 것만 「대기」이고, 모르면 「전송중」이다.</b>
 *
 * <p>⛔ <b>「4xx 면 대기」로 뭉치지 마라.</b> {@code 408}·{@code 429} 는 「지금 말고 나중에」이고
 * {@code 409} 는 오히려 「이미 받았다」일 수 있다.
 */
public enum DeliveryOutcome {

    /** 아직 안 갔다. 다시 누르면 처음부터다. */
    NOT_SENT,
    /** 보냈는데 <b>갔는지 안 갔는지 모른다.</b> ⛔ 다시 누르는 것이 막힌다. */
    SENDING,
    /** 개발이 받았다는 증거가 있다. */
    SENT,
    /**
     * 보냈다가 <b>철회했다</b> (2026-08-25 병주 지시).
     *
     * <p>⭐ <b>「취소」가 아니다.</b> 이미 나간 것은 없던 일로 못 만든다 — 개발에게 알림이 갔고
     * 읽음이 남는다. 우리가 하는 것은 <b>그쪽 언어로 「무릅니다」를 알리는 것</b>뿐이다:
     * 라벨을 {@code intake} 에서 빼고 이슈를 닫는다.
     *
     * <p>⛔ <b>{@link #NOT_SENT} 로 되돌리지 마라.</b> 이슈는 살아 있는데 상태가 「대기」면
     * 다시 눌러 <b>두 번째 이슈가 열린다</b>. 그래서 되돌리기가 아니라 새 상태다.
     *
     * <p>⚠ <b>여기서 다시 보낼 수 있다</b> — 고쳐서 다시 보내려고 무르는 것이다.
     * 다만 <b>새 전송 키</b>를 쓴다: 옛 키로 찾으면 방금 닫은 그 이슈를 재활용해 버린다.
     */
    WITHDRAWN;

    /**
     * 상태코드를 상태로 옮긴다.
     *
     * <p>⚠ <b>{@code echoed} 가 참일 때만 2xx 가 「전송완료」다.</b> 사내 프록시와 로그인 페이지도
     * {@code 200} 을 주기 때문에, 응답 몸에 우리 전송 키가 되울려 오지 않으면
     * 「2xx = 받았다」를 확인할 길이 없다.
     *
     * <p>⚠ <b>{@code 202} 는 「접수했다」이지 「처리했다」가 아니다.</b>
     * ⚠ <b>{@code 409} 는 함정이다</b> — 「같은 것 이미 받았다」면 실은 전송완료이고 일반 충돌이면
     * 실패다. <b>상태코드만 보고 정하지 않는다.</b> 개발에 뜻을 물어 정한 뒤 이 표를 고친다
     * ({@code docs/requests-to-dev.md} 가 2026-08-10 에 물었고 답이 안 왔다).
     *
     * @param echoed 응답 몸에 우리 전송 키가 되울려 왔나
     */
    public static DeliveryOutcome of(int httpStatus, boolean echoed) {
        if (httpStatus == 200 || httpStatus == 201) {
            return echoed ? SENT : SENDING;
        }
        if (httpStatus == 202) {
            return SENDING;
        }
        // 요청 자체를 안 받아들였다 — 몸이 상대 처리에 닿지 않았다는 증거다.
        if (httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404
                || httpStatus == 413 || httpStatus == 415 || httpStatus == 422) {
            return NOT_SENT;
        }
        // 「지금 말고 나중에」 — 처리 여부가 안 갈린다.
        if (httpStatus == 408 || httpStatus == 429) {
            return SENDING;
        }
        // ⚠ 3xx 는 주소가 옮겨졌다는 신호이지 처리를 안 했다는 증거가 아니다.
        //    5xx 도 마찬가지다 — 502·504 는 뒤에 넘겨 놓고 기다리다 포기한 것이다.
        // ⛔ 그 밖의 2xx 도 여기로 온다 — 되울림 없는 2xx 는 받았다는 증거가 없다.
        return SENDING;
    }

    /**
     * 몸이 나가기도 전에 끝난 것.
     *
     * <p>⭐ DNS 실패 · 연결 거부 · TLS 실패는 <b>「대기」다</b> — 상대에게 닿지 않았음이 증명된다.
     * ⛔ <b>시간 상한과 응답 전 끊김을 여기 넣지 마라</b> — 그건 이미 받았을 수 있어 「전송중」이다.
     */
    public static DeliveryOutcome beforeBody() {
        return NOT_SENT;
    }
}
