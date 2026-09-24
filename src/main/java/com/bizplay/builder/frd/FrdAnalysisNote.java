package com.bizplay.builder.frd;

import java.time.Instant;

/**
 * 분석 결과의 완료 기준 · 정한 것 · 진행 방식.
 *
 * <p>⭐ <b>인터뷰는 확인 필요를 남기지 않는다</b> (2026-09-24 사용자 확정). 남은 것은 인터뷰가 선택지로 다 묻고,
 * 일찍 정리하면 AI 권장안으로 정한다. 그래서 새 결과는 {@code DECISION_*} 로만 담기고
 * {@link Kind#OPEN_ISSUE} 는 이미 저장된 옛 결과를 읽을 때만 나온다.
 */
public record FrdAnalysisNote(String id, String frdId, int seq, Kind kind,
                              String content, Instant createdAt) {
    public enum Kind {
        ACCEPTANCE_CRITERION, OPEN_ISSUE, WORK_MODE_FAST_TRACK, WORK_MODE_FRD,
        /** 인터뷰에서 사람이 답해 정한 것. */
        DECISION_INTERVIEW,
        /** 인터뷰를 일찍 정리해 AI 권장안으로 정한 것. */
        DECISION_RECOMMENDED;

        public boolean decision() {
            return this == DECISION_INTERVIEW || this == DECISION_RECOMMENDED;
        }
    }

    /** 정한 것 하나. 저장은 {@link #content()} 한 칸에 「질문 줄바꿈 답」으로 한다. */
    public record Decision(String question, String answer, boolean recommended) {
        public Decision {
            question = question == null ? "" : question.strip();
            answer = answer == null ? "" : answer.strip();
        }

        public String content() {
            return question + "\n" + answer;
        }

        public Kind kind() {
            return recommended ? Kind.DECISION_RECOMMENDED : Kind.DECISION_INTERVIEW;
        }

        /** 저장된 칸을 되읽는다 — 줄바꿈이 없으면 질문 없이 답만 있는 것으로 본다. */
        public static Decision parse(String content, boolean recommended) {
            String text = content == null ? "" : content.strip();
            int newline = text.indexOf('\n');
            return newline < 0
                    ? new Decision("", text, recommended)
                    : new Decision(text.substring(0, newline), text.substring(newline + 1), recommended);
        }
    }

    /** 정한 것이면 질문과 답으로 풀어 준다. 아니면 {@code null}. */
    public Decision decision() {
        return kind != null && kind.decision()
                ? Decision.parse(content, kind == Kind.DECISION_RECOMMENDED) : null;
    }
}
