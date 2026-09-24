package com.bizplay.builder.srt;

import com.bizplay.builder.devrequest.DevRequestLink;
import org.springframework.stereotype.Component;

/** SRT 가 가리키는 개발요청서를 거둘 때 연결부터 푼다 — SRT 는 「개발요청서 생성」 전으로 돌아간다. */
@Component
class SrtRequestLink implements DevRequestLink {

    private final SrtMapper srts;

    SrtRequestLink(SrtMapper srts) {
        this.srts = srts;
    }

    @Override
    public void release(String requestId) {
        srts.disconnectRequest(requestId);
    }
}
