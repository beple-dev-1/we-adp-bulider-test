package com.bizplay.builder.businesslanguage;

import java.time.Instant;

/** 프로젝트마다 종류별로 한 장만 존재하는 Markdown 문서. */
public record BusinessDocument(
        String projectId,
        BusinessDocumentKind kind,
        String content,
        String sourceRefs,
        Instant updatedAt,
        String updatedBy
) {
}
