package com.bizplay.builder.devrequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 창구 계약이 오기 전까지 쓰는 구현 — <b>꾸러미를 자리에 남기고 「전송중」에 둔다.</b>
 *
 * <p>⛔ <b>여기에 HTTP 호출을 넣지 마라.</b> {@code docs/requests-to-dev.md} 가 2026-08-10 에
 * 물은 것들이 하나도 안 왔다 — <b>주소 · 인증 · 몸의 모양 · 상태코드표 · 전송 키 되울림.</b>
 * 주소를 모르는 채 쏘면 사내 프록시나 로그인 페이지가 {@code 200} 을 주고, 빌더는
 * <b>아무것도 안 보내 놓고 「전송완료」로 확정</b>한다.
 *
 * <p>⭐ <b>「전송중」인 것이 옳다.</b> 「대기」로 두면 다시 누를 때마다 처음부터 보내는 것이 되고,
 * 「전송완료」로 두면 <b>안 나갔는데 나갔다고 거짓말한다.</b> 상태 셋의 뜻이 그대로 산다 —
 * 「보냈는데 답을 못 받았다」가 지금 우리 형편과 정확히 같다.
 *
 * <p>⚠ 여기서 나오는 문은 <b>슈퍼계정이 개발에 확인하고 고르는 것</b>이다(전송 설계). 그 문은
 * 이 계획 밖이다 — ⛔ <b>「그냥 초기화」 같은 버튼을 만들지 마라.</b>
 */
@Component
public class LoggingDevHandoffGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingDevHandoffGateway.class);

    /**
     * ⚠ <b>{@link DevHandoffGateway} 를 구현하지 않는다 — 빈이 둘이 되면 스프링이 못 고른다.</b>
     * {@link DevIssueGateway} 가 <b>설정이 없을 때</b> 이것으로 되돌아간다. 그것이 되돌릴 길이다.
     */
    public DevHandoffGateway.Receipt send(DevRequestPackage built, String deliveryKey) {
        log.warn("개발 창구가 아직 없어 꾸러미를 보내지 않았다 — 자리에 남긴다."
                        + " 전송키={} 자리={} ZIP={} 파일={}장 {}바이트 지문={}",
                deliveryKey, built.root(), built.archive(), built.entries().size(), built.totalBytes(),
                built.fingerprint());
        return DevHandoffGateway.Receipt.sending("개발 창구 주소가 아직 설정되지 않았습니다. 꾸러미는 만들어 두었습니다.");
    }
}
