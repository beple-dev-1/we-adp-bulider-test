package com.bizplay.builder.frd;

import java.time.Instant;

/** 분석 결과의 완료 기준 또는 확인 필요 항목. */
public record FrdAnalysisNote(String id, String frdId, int seq, Kind kind,
                              String content, Instant createdAt) {
    public enum Kind { ACCEPTANCE_CRITERION, OPEN_ISSUE, WORK_MODE_FAST_TRACK, WORK_MODE_FRD }
}
