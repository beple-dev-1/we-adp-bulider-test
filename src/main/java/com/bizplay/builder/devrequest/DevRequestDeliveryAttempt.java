package com.bizplay.builder.devrequest;

import java.time.Instant;

/**
 * 전송 시도 한 건.
 *
 * <p>⛔ <b>상태 한 줄로 뭉치지 마라.</b> 두 번째 시도가 첫 번째를 지우면, 첫 시도가 「전송중」인 채로
 * 두 번째가 성공했을 때 <b>둘 다 알아야 하는데 담을 자리가 없다.</b>
 *
 * @param bodyFingerprint 보낸 꾸러미의 지문. 「받았다」가 어느 판을 받은 것인지 묶는다.
 *                        ⚠ <b>철회 시도는 널이다</b> — 몸을 안 보내므로 지문이 없는 것이 참이다
 *                        (2026-08-25 실물에서 not null 로 터졌다)
 * @param httpStatus      돌아온 상태코드 그대로. ⚠ 우리 해석이 아니라 <b>원값</b>이다 —
 *                        나중에 갈래 표를 고칠 때 근거가 된다
 */
public record DevRequestDeliveryAttempt(String id, String devRequestId, String deliveryKey,
                                        String bodyFingerprint, DeliveryOutcome outcome,
                                        Integer httpStatus, String responseId, String failure,
                                        String requestedBy, Instant startedAt,
                                        Instant finishedAt) {
}
