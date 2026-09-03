package com.bizplay.builder.srt;

import java.util.List;

/** SRT 원문이 개발 요청으로 성립하는지와 개발요청서에 실을 최소 정의다. */
public record SrtAiAnalysis(boolean eligible, String rejectionReason, String analysisComment,
                            List<String> requirements, List<String> acceptanceCriteria) {
}
