package com.bizplay.builder.frd;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * AI 가 요구사항을 쪼갠 항목 하나와 그 판정.
 *
 * <p>⭐ <b>조용한 누락을 드러내는 자리다.</b> 화면 목록만 받으면 「AI 가 못 찾은 것」과
 * 「화면 일이 아닌 것」과 「아직 추출 안 된 화면」이 구별되지 않는다 — 2026-08-18 에
 * 6건짜리 요구사항이 화면 1장으로 끝나고 다섯이 아무 말 없이 사라진 것을 봤다.
 *
 * <p>⛔ <b>다시 짚으면 통째로 갈아 낀다.</b> 화면과 달리 사람이 손보는 것이 아니라
 * AI 가 읽은 것의 사본이다.
 */
public record FrdItem(String id, String frdId, int seq, String requirement, Nature nature,
                      Verdict verdict, String screenIds, String note, Instant createdAt) {

    /**
     * 항목의 <b>성격</b> — 무엇을 바꾸는 일인가.
     *
     * <p>⭐ <b>가르는 질문은 하나다 — 「그 일을 할 기능이 이미 있나」.</b>
     * 화면 md 와 {@code domains/} 가 그 답을 들고 있다.
     *
     * <p>⛔ <b>{@link Verdict} 와 한 칸에 담지 마라 (2026-08-18 실측).</b> 「개발이냐」를
     * 「화면이냐」에 눌러 담았더니 {@code NO_SCREEN} 하나에 뜻이 셋 들어갔고, 반대로
     * 「FAQ 삭제처리」는 이미 있는 삭제 버튼을 찾아 놓고 {@code SCREEN} 을 받았다.
     *
     * <p>⛔ <b>넷째 값을 더하지 마라</b> — 「모르겠다」를 값으로 만들면 AI 가 거기로 도망친다.
     * ⚠ <b>「데이터」와 「설정」을 가르지 마라</b> — 둘 다 「이미 있다」 편이고 차이는 {@code note} 에 적힌다.
     */
    public enum Nature {
        /** 그 일을 할 기능이 <b>없다</b> — 만들거나 고쳐야 한다(화면·로직·배치·API). */
        DEVELOP,
        /** 기능이 <b>이미 있다</b> — 운영자가 자료·콘텐츠·설정을 바꾼다. 개발이 필요 없다. */
        OPERATE,
        /** 이 기획 저장소의 세 시스템 밖이다. 개발은 필요할 수 있으나 <b>여기 것이 아니다.</b> */
        OUTSIDE
    }

    /**
     * 화면을 짚었나. ⚠ <b>{@link Nature#DEVELOP} 인 항목에만 뜻이 있다</b> —
     * 「개발이냐」는 {@link Nature} 가 답한다.
     *
     * <p>⛔ 넷째 값을 더하지 마라 — 「모르겠다」를 값으로 만들면 AI 가 거기로 도망친다.
     */
    public enum Verdict { SCREEN, NO_SCREEN, NOT_INDEXED }

    public static FrdItem of(String id, String frdId, int seq, String requirement,
                             Nature nature, Verdict verdict, List<String> screenIds, String note) {
        return new FrdItem(id, frdId, seq, requirement, nature, verdict,
                screenIds == null || screenIds.isEmpty() ? null : String.join(",", screenIds),
                note, null);
    }

    /** ⚠ 사람이 읽는 근거일 뿐이다 — 작업 단위는 {@link FrdScreen} 이다. 조인하지 마라. */
    public List<String> screenIdList() {
        return screenIds == null || screenIds.isBlank()
                ? List.of() : Arrays.asList(screenIds.split(","));
    }

    /**
     * 화면에 뜨는 말. ⚠ 코드값과 갈라 둔다 — {@link Frd#stateLabel()} 과 같은 규율이다.
     *
     * <p>⚠ <b>성격이 {@link Nature#DEVELOP} 이 아니면 화면 판정은 뜻이 없다</b> —
     * 그때는 {@link #natureLabel()} 만 보여 준다. 「화면 없음」이 「개발이 필요 없다」로
     * 읽히던 것이 2026-08-18 에 사람을 헷갈리게 한 자리다.
     */
    public String verdictLabel() {
        return switch (verdict) {
            case SCREEN -> "화면";
            case NO_SCREEN -> "화면 없음";
            case NOT_INDEXED -> "미추출";
        };
    }

    /** 성격을 사람 말로. ⚠ 목록에서 이것이 <b>앞에</b> 선다 — 사람이 먼저 묻는 것이 「개발이냐」다. */
    public String natureLabel() {
        return switch (nature) {
            case DEVELOP -> "개발";
            case OPERATE -> "운영";
            case OUTSIDE -> "범위 밖";
        };
    }

    /** 화면 판정을 보여 줄 자리인가. ⚠ {@code DEVELOP} 이 아니면 화면 판정은 뜻이 없다. */
    public boolean showsVerdict() {
        return nature == Nature.DEVELOP;
    }
}
