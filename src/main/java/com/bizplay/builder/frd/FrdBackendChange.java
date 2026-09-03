package com.bizplay.builder.frd;

import java.time.Instant;

/** 분석 결과에서 확인한 백엔드 변경 한 줄. */
public record FrdBackendChange(String id, String frdId, int seq, Integer requirementSeq,
                               Category category, String target, String changeDetail,
                               String evidence, String verification, boolean required,
                               Instant createdAt) {

    /*
     * verification — 「무엇으로 됐다고 하나」.
     *
     * ⭐ 화면은 목업이 완료 조건 노릇을 하는데 화면 외 구현에는 그것이 없다. 이 칸이 비면
     *    개발요청서가 항목별 검수를 못 갈라 준다.
     * ⛔ 전체 완료 조건(ACCEPTANCE_CRITERION)으로 갈음하지 마라 — 그건 FRD 하나에 걸린 것이라
     *    항목 다섯 중 어느 것이 남았는지 못 가른다.
     * ⚠ 널이 정상이다 — 2026-08-24 앞에 분석된 FRD 에는 값이 없다.
     */

    public enum Category {
        API("API"),
        DATA("데이터"),
        PERMISSION("권한"),
        BATCH("배치"),
        NOTIFICATION("알림"),
        OTHER("기타");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public String categoryLabel() {
        return category.label();
    }

    public String stateLabel() {
        return required ? "수정 필요" : "변경 없음";
    }
}
