package com.bizplay.builder.businesslanguage;

import java.time.Instant;

/** 정책서 또는 표준용어를 저장한 한 시점의 전체 내용. */
public record BusinessDocumentRevision(
        String projectId,
        BusinessDocumentKind kind,
        int revisionNo,
        String content,
        String sourceRefs,
        BusinessDocumentRevisionType changeType,
        Instant createdAt,
        String createdBy
) {
}
