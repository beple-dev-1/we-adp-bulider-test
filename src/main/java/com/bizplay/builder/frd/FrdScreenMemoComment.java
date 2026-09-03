package com.bizplay.builder.frd;

import java.time.Instant;

/** FRD 화면에 작성한 댓글형 메모 한 건. */
public record FrdScreenMemoComment(String id, String frdScreenId, String authorAccountId,
                                   String authorName, String content, Instant createdAt) {
}
