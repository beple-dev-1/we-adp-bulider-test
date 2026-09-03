package com.bizplay.builder.frd;

import java.time.Instant;

/**
 * FRD 작업 한 줄. <b>산출물 사슬 넷과 외래키로 묶이지 않는다.</b>
 *
 * <p>⛔ <b>{@code sourceRef} 로 조인하지 마라</b> — 글자 도장이다. 사슬 넷이 나중에 별도
 * 시스템으로 나갈 수 있어서 일부러 관계를 안 걸었다(2026-08-18 병주 확정).
 */
public record Frd(String id, String projectId, int number, String title, String systemCode,
                  SourceKind sourceKind, String sourceRef, String sourceText,
                  Instant sourceImportedAt, String noScreenReason,
                  State state, String failure, String ownerAccountId,
                  Instant createdAt, Instant updatedAt, Instant completedAt) {

    /** 완료일시 열을 추가하기 전 생성 코드를 위한 호환 생성자다. */
    public Frd(String id, String projectId, int number, String title, String systemCode,
               SourceKind sourceKind, String sourceRef, String sourceText,
               Instant sourceImportedAt, String noScreenReason,
               State state, String failure, String ownerAccountId,
               Instant createdAt, Instant updatedAt) {
        this(id, projectId, number, title, systemCode, sourceKind, sourceRef, sourceText,
                sourceImportedAt, noScreenReason, state, failure, ownerAccountId,
                createdAt, updatedAt, null);
    }

    /** 요구사항이 어디서 왔나. */
    public enum SourceKind { PASTED, REQUIREMENT, BRD, SRT }

    /** 작업 상태. ⚠ 화면에 뜨는 말은 {@link #stateLabel()} 이다 — 코드값과 갈라 둔다. */
    public enum State { ANALYZING, WAITING_ANSWER, ANALYSIS_FAILED, PICKED, SCOPE_REVIEW, DRAFTING, REVIEW, DONE }

    /**
     * 붙여넣기로 여는 FRD. ⚠ 번호는 부르는 쪽이 {@code allocateNumber} 로 집어 넘긴다.
     *
     * <p>⭐ <b>{@code ownerAccountId} 를 반드시 넣는다.</b> {@link com.bizplay.builder.frd.ScreenPickWorker}
     * 가 이 사람의 Claude 자격으로 화면 짚기를 돌린다 — 널이면 자격 검사에서 바로 실패한다.
     */
    public static Frd pasted(String id, String projectId, int number, String title, String sourceText,
                             String ownerAccountId) {
        return new Frd(id, projectId, number, title, null,
                SourceKind.PASTED, null, sourceText, null, null,
                State.ANALYZING, null, ownerAccountId, null, null, null);
    }

    /** 화면에 뜨는 말. */
    public String stateLabel() {
        return switch (state) {
            case ANALYZING -> "요구사항 분석 중";
            case WAITING_ANSWER -> "답변 필요";
            case ANALYSIS_FAILED -> "분석 오류";
            case PICKED -> "분석 결과 확인";
            case SCOPE_REVIEW -> "개발 범위 확인";
            case DRAFTING -> "수정 중";
            case REVIEW -> "완료";
            case DONE -> "완료";
        };
    }

    /** 화면에서 완료로 표시되기 전까지만 FRD 작업을 삭제할 수 있다. */
    public boolean canDelete() {
        return state != State.REVIEW && state != State.DONE;
    }

    /** 화면에 뜨는 {@code FRD-003}. ⚠ 정렬에 쓰지 마라 — 정렬은 {@link #number()} 로 한다. */
    public String label() {
        return "FRD-%03d".formatted(number);
    }

    /** 생성 방식 칸에 뜨는 말. 사용자가 직접 입력한 요구사항은 직접 등록으로 표시한다. */
    public String sourceLabel() {
        return sourceRef == null || sourceRef.isBlank() ? "직접 등록" : sourceRef;
    }

    /**
     * 「업무 · 시스템」 칸에 뜨는 말. ⚠ {@code systemCode} 는 널일 수 있다 —
     * Task 4 의 짚기가 채우기 전까지는 제목만 적는다({@code · } 를 안 붙인다).
     */
    public String businessLabel() {
        return systemCode == null || systemCode.isBlank() ? title : title + " · " + systemCode;
    }
}
