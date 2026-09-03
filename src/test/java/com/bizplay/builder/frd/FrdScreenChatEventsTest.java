package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class FrdScreenChatEventsTest {

    @Test
    void 대화_이벤트는_시간_제한_없는_SSE_연결을_만든다() {
        FrdScreenChatEvents events = new FrdScreenChatEvents();
        try {
            var emitter = events.subscribe("0000001");

            assertThat(emitter.getTimeout()).isZero();
            events.publish("0000001");
            emitter.complete();
        } finally {
            events.close();
        }
    }

    @Test
    void Windows_한국어_연결_중단은_정상적인_브라우저_종료로_판별한다() {
        IOException disconnected = new IOException(
                "현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다");
        IOException unrelated = new IOException("화면 파일을 읽지 못했습니다");

        assertThat(FrdScreenChatEvents.isClientDisconnect(disconnected)).isTrue();
        assertThat(FrdScreenChatEvents.isClientDisconnect(unrelated)).isFalse();
    }
}
