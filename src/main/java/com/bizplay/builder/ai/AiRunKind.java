package com.bizplay.builder.ai;

/**
 * 무엇을 시키는 실행인가. <b>넷이다.</b>
 *
 * <p>⛔ <b>「고치기」를 넣지 않는다</b>(2026-08-14 병주 결정). 산출물 사슬 재설계가 「고치기」를 폐기했고
 * {@code CLAUDE.md} 가 그것을 정본으로 적는다. <b>옛 계획서에 다섯으로 적혀 있던 것을 넷으로 줄인 것이다.</b>
 *
 * <p>⛔ <b>새 종류가 생겨도 장치를 새로 만들지 않는다</b> — 이 값만 는다.
 */
public enum AiRunKind {

    /** 받은 문서에서 요구사항을 뽑는다. */
    EXTRACT_REQUIREMENTS("요구사항 뽑기"),
    /** 요구사항 하나를 요구사항정의서로 푼다. */
    WRITE_DEFINITION("정의서 쓰기"),
    /** BRD 초안을 만든다 — 작업 하나의 단위다. */
    DRAFT_BRD("BRD 초안"),
    /** 개발요청서를 쓴다. */
    WRITE_DEV_REQUEST("개발요청서 쓰기");

    private final String label;

    AiRunKind(String label) {
        this.label = label;
    }

    /** 화면에 뜨는 말. ⛔ 코드값(이름)과 갈라 둔다. */
    public String label() {
        return label;
    }
}
