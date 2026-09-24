package com.bizplay.builder.srt;

import com.bizplay.builder.frd.FrdAnalysisNote;

import java.util.List;

/**
 * SRT 원문이 개발 요청으로 성립하는지와 개발요청서에 실을 최소 정의다.
 *
 * <p>⭐ <b>AI 가 채우고 사람은 개발요청서 생성 전에 확인만 한다</b> (2026-09-24 사용자 확정 · 목업 13).
 * 고칠 화면과 정한 것(권장안을 답으로)을 함께 짚는다. SRT 에는 인터뷰가 없어 정한 것은 모두 권장안에서 시작한다.
 *
 * @param screenChange 화면에 보이는 변경이 있나 — 있는데 {@code screens} 가 비었으면 사람이 골라야 생성할 수 있다
 * @param screens      색인의 화면ID 로 짚은 고칠 화면
 * @param decisions    원문으로 정할 수 없던 것과 권장안
 */
public record SrtAiAnalysis(boolean eligible, String rejectionReason, String analysisComment,
                            List<String> requirements, List<String> acceptanceCriteria,
                            boolean screenChange, List<Target> screens,
                            List<FrdAnalysisNote.Decision> decisions) {

    public SrtAiAnalysis {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        screens = screens == null ? List.of() : List.copyOf(screens);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public SrtAiAnalysis(boolean eligible, String rejectionReason, String analysisComment,
                         List<String> requirements, List<String> acceptanceCriteria) {
        this(eligible, rejectionReason, analysisComment, requirements, acceptanceCriteria,
                false, List.of(), List.of());
    }

    /** AI 가 짚은 고칠 화면 하나. 시스템은 받지 않는다 — 색인이 정한다. */
    public record Target(String screenId, String reason) { }
}
