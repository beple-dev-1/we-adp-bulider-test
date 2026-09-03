package com.bizplay.builder.srt;

import java.time.Instant;

/** 간단한 변경 원문과 개발요청서의 연결을 보존하는 SRT 작업이다. */
public record Srt(String id, String projectId, int number, SourceKind sourceKind,
                  String flowTaskNumber, String title, String content, String sourceJson,
                  String ownerAccountId, String bridgeFrdId, String devRequestId,
                  AnalysisState analysisState, String analysisMessage,
                  Instant createdAt, Instant updatedAt) {

    public enum SourceKind { DIRECT, FLOW }
    public enum AnalysisState { READY, ANALYZING, COMPLETE, REJECTED, FAILED }

    public String label() {
        return "SRT-%03d".formatted(number);
    }

    public String sourceLabel() {
        return sourceKind == SourceKind.FLOW ? "플로우 등록" : "직접 입력";
    }

    public String stateLabel() {
        if (devRequestId != null) return "완료";
        return switch (analysisState) {
            case READY, ANALYZING -> "분석 중";
            case COMPLETE -> "생성 대기";
            case REJECTED, FAILED -> "확인 필요";
        };
    }

    public boolean canSend() {
        return devRequestId != null;
    }
}
