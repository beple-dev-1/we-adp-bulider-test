package com.bizplay.builder.devrequest;

/**
 * 나가는 창구 — <b>빌더가 개발 API 를 한 번 부른다.</b>
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-handoff-to-dev-design.md} ·
 * 합의할 것은 {@code docs/requests-to-dev.md}.
 *
 * <p>⛔ <b>여기가 g2c 를 아는 자리가 아니다.</b> 빌더가 아는 것은 <b>보낼 주소와 형식</b>뿐이다.
 * 개발 저장소를 들여다보거나 화면을 뽑아오기 시작하면 선을 넘는 것이다.
 *
 * <p>⛔ <b>구현이 몰래 재시도하지 마라.</b> 두 번 갔는지 안 갔는지 아무도 모르게 된다.
 * ⚠ 정책만으로는 안 지켜진다 — HTTP 클라이언트와 사내 프록시는 <b>연결 끊김·502 에 기본으로
 * 다시 쏘는 것들이 있다.</b> 구현에서 <b>꺼야</b> 이 문장이 참이 된다.
 *
 * <p>⛔ <b>리다이렉트 자동 따라가기를 끈다.</b> POST 에 302 가 오면 많은 클라이언트가
 * <b>GET 으로 바꿔 따라가서 몸을 통째로 버리고</b> 최종 2xx 를 받는다. 그러면 빌더는
 * <b>아무것도 안 보내 놓고 「전송완료」로 확정</b>한다 — 두 번 가는 것보다 나쁘다(아무도 모른다).
 */
public interface DevHandoffGateway {

    /**
     * 꾸러미 하나를 개발에 한 번 보낸다.
     *
     * <p>⛔ <b>던지지 말고 {@link DeliveryOutcome#SENDING} 으로 낸다.</b> 시간 상한·연결 끊김은
     * <b>상대가 이미 받았을 수 있다</b> — 그것을 실패로 뭉치면 다시 누를 때 두 번 간다.
     *
     * @param built       보낼 꾸러미
     * @param deliveryKey 이 개발요청서의 전송 키. <b>다시 보내면 같은 키다</b>
     */
    Receipt send(DevRequestPackage built, String deliveryKey);

    /**
     * 이미 보낸 것을 <b>철회</b>한다 (2026-08-25 병주 지시).
     *
     * <p>⭐ <b>라벨이 {@code intake} 일 때만 된다.</b> 그 라벨은 우리가 붙인 것이고
     * <b>「개발이 인수 전」</b>이라는 뜻이다 — 개발이 집어가면 그쪽 워크플로가 라벨을 바꾼다.
     * 그래서 <b>라벨이 그대로인지 보는 것만으로 「아직 아무도 손 안 댔다」가 판정된다</b> —
     * 개발과 따로 합의할 것이 없다.
     *
     * <p>⛔ <b>라벨이 바뀌었으면 거절한다.</b> 남이 하고 있는 일을 우리가 무를 수 없다.
     *
     * @return 철회했으면 {@link DeliveryOutcome#WITHDRAWN}. 못 하면 그 까닭이 담긴
     *         {@link DeliveryOutcome#SENT}(그대로 두라는 뜻)
     */
    default Receipt withdraw(String projectId, String deliveryKey, String reason) {
        return new Receipt(DeliveryOutcome.SENT, null, null,
                "이 창구는 개발요청 취소를 지원하지 않습니다.");
    }

    /**
     * 창구가 돌려준 것.
     *
     * @param outcome    이 답이 어느 상태로 가나
     * @param httpStatus 돌아온 상태코드 그대로. 안 왔으면 널
     * @param responseId 개발이 준 응답 식별자. 없으면 널
     * @param failure    사람이 읽을 까닭. 없으면 널
     */
    record Receipt(DeliveryOutcome outcome, Integer httpStatus, String responseId, String failure) {

        public static Receipt sending(String failure) {
            return new Receipt(DeliveryOutcome.SENDING, null, null, failure);
        }
    }
}
