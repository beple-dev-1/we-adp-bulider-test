package com.bizplay.builder.ia;

import java.time.Instant;

/** 메뉴구조도 확정 한 판과 Git 게시 결과. */
public record IaRevision(
        String id,
        String structureId,
        int revision,
        String snapshotContent,
        String snapshotHash,
        State state,
        String publishedCommit,
        String failure,
        Instant createdAt,
        String createdBy,
        Instant publishedAt) {

    public enum State { PUBLISHING, PUBLISHED, FAILED }
}
