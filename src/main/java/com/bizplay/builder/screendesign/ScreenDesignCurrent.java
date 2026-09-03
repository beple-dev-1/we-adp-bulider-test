package com.bizplay.builder.screendesign;

import java.time.Instant;

/** 화면 하나의 현재 화면설계서 생성 상태와 정상 개정판 포인터. */
public record ScreenDesignCurrent(
        String projectId, String systemCode, String screenId,
        String currentRevisionId, int currentRevisionNo,
        ScreenDesignState state, String generationId, Instant generationStartedAt,
        String requestedSourceFingerprint, String requestedGeneratorVersion,
        String requestedSchemaVersion, String failedReason, Instant retryAfter,
        Instant updatedAt) {

    public boolean hasRevision() {
        return currentRevisionId != null && !currentRevisionId.isBlank();
    }
}
