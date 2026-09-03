package com.bizplay.builder.project;

import java.time.Instant;

/** 클론된 기획 저장소의 최근 업데이트 시도. */
public record RepositoryUpdate(
        String projectId,
        RepositoryUpdateState state,
        String fromCommit,
        String currentCommit,
        Boolean changed,
        Instant startedAt,
        Instant finishedAt,
        String failureReason) {
}
