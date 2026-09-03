package com.bizplay.builder.ia;

import java.time.Instant;

/** 프로젝트·시스템별 메뉴구조도 정본. */
public record IaStructure(
        String id,
        String projectId,
        String systemCode,
        State state,
        int currentRevision,
        int version,
        String importedHash,
        Instant importedAt,
        Instant confirmedAt,
        String confirmedBy,
        String publishedCommit,
        String publishFailure,
        Instant updatedAt,
        String updatedBy) {

    public enum State { DRAFT, CONFIRMED, PUBLISH_FAILED }
}
