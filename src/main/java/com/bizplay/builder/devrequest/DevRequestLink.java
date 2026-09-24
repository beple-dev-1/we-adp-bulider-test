package com.bizplay.builder.devrequest;

/**
 * 개발요청서를 가리키는 다른 자리 — 요청서를 거두기 전에 그 연결부터 푼다.
 *
 * <p>⭐ SRT 가 그렇다 — {@code adk_builder_srt.dev_request_id} 가 FK 로 요청서를 가리켜, 풀지 않으면 지울 수 없다.
 * ⛔ 이 패키지가 SRT 를 알지 않게 하려고 둔 틈이다. 구현은 가리키는 쪽이 둔다.
 */
public interface DevRequestLink {

    /** 이 요청서를 가리키는 연결을 푼다. 없으면 아무것도 안 한다. */
    void release(String requestId);
}
