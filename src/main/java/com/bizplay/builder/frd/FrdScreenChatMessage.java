package com.bizplay.builder.frd;

import java.time.Instant;

/** FRD 작업대에서 화면 한 장을 기준으로 주고받은 수정 대화 한 줄. */
public record FrdScreenChatMessage(String id, String frdId, String frdScreenId, Integer sequenceNo,
                                   Role role, State state, String content, String failure,
                                   String sessionId, Instant createdAt, Instant completedAt) {

    public enum Role { USER, AI }

    public enum State { DONE, RUNNING, FAILED }
}
