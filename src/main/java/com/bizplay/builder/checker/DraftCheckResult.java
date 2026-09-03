package com.bizplay.builder.checker;

import java.util.List;

/**
 * 후보 하나를 저장해도 되나에 대한 답.
 *
 * @param caused 후보를 얹어 <b>새로 생긴</b> 진단. <b>이것만 그 사람 탓이다</b> —
 *               남이 깔아 둔 것은 여기 안 들어온다
 * @param fixed  후보가 <b>없앤</b> 진단. 막는 데는 안 쓰지만 「고쳐졌다」를 보여주면
 *               기획자가 자기가 한 일을 안다
 */
public record DraftCheckResult(Verdict verdict, List<Finding> caused, List<Finding> fixed) {

    public DraftCheckResult {
        caused = caused == null ? List.of() : List.copyOf(caused);
        fixed = fixed == null ? List.of() : List.copyOf(fixed);
    }

    public enum Verdict {
        /** 새로 생긴 것이 없다. 저장해도 된다. */
        GREEN,
        /** 규격을 깨뜨렸다. <b>막는다.</b> */
        RED,
        /**
         * 새로 생긴 것이 <b>확인할 항목뿐</b>이다. 막지 않고 보여준다 —
         * 검사기의 「확인 못 함」은 위반이 아니다.
         */
        REVIEW_REQUIRED,
        /**
         * 판정을 못 냈다. ⛔ <b>막는다.</b> 초록으로 읽으면 검사 없이 저장이 열린다 —
         * 실물에서 이 자리는 {@code npm install} 이 안 돼 있을 때다.
         */
        UNKNOWN
    }

    /**
     * 저장을 열어도 되나.
     *
     * <p>⚠ <b>「막지 않는다」와 「보여주지 않는다」는 다르다.</b> {@link Verdict#REVIEW_REQUIRED}
     * 는 저장을 열지만 {@link #caused()} 를 화면에 내야 한다.
     */
    public boolean canSave() {
        return verdict == Verdict.GREEN || verdict == Verdict.REVIEW_REQUIRED;
    }
}
