package com.bizplay.builder.featurespec;

import java.time.Instant;

/** 화면 하나의 현재 기능명세서 상태와 정상 개정판 포인터. */
public record FeatureSpecCurrent(
        String projectId, String systemCode, String screenId,
        String currentRevisionId, int currentRevisionNo,
        FeatureSpecState state, String generationId, Instant generationStartedAt,
        String requestedSourceFingerprint, String requestedGeneratorVersion,
        String requestedSchemaVersion, String failedReason, Instant retryAfter,
        Instant updatedAt) {

    public boolean hasRevision() {
        return currentRevisionId != null && !currentRevisionId.isBlank();
    }
}
