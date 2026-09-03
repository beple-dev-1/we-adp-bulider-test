package com.bizplay.builder.businesslanguage;

import java.time.Instant;

/** 프로젝트별 최초 초안 생성 상태. */
public record BusinessDocumentSeed(
        String projectId,
        BusinessDocumentSeedState state,
        String accountId,
        Instant startedAt,
        Instant finishedAt,
        String failedReason
) {
}
