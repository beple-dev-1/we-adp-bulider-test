package com.bizplay.builder.screenid;

/**
 * 표준 화면ID 여섯 마디를 조립한다 — <b>순수 함수만</b> 있다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-20-screen-standard-id-design.md} §2·§4.
 *
 * <p>⛔ <b>여기에 되돌리는 함수(파싱)를 만들지 마라.</b> 표준 ID 에서 업무영역·유형을 읽어 내는
 * 코드가 생기는 순간, 클론 직후의 IA 로 박혀 <b>어긋난 채 굳은 값</b>이 분류로 쓰인다.
 * 분류는 언제나 IA 와 {@code IaScreenProfile} 을 본다.
 */
public final class StandardScreenIdFormat {

    private StandardScreenIdFormat() {
    }

    /**
     * 유형 한 글자.
     *
     * <p><b>종류가 먼저다.</b> 보스 예시의 {@code L01}·{@code D01}·{@code P01} 은 한 글자에
     * 축 둘을 섞었다 — {@code L}·{@code D} 는 화면유형이고 {@code P} 는 화면 종류다.
     *
     * <p>⚠ <b>등록이 {@code C}(Create) 가 아니라 {@code R}, 수정이 {@code E} 가 아니라 {@code U} 다.</b>
     * 여섯째 마디가 {@code S}/{@code N}/{@code C} 라서 {@code C} 가 두 자리에서 다른 뜻으로 쓰이면
     * 사람이 반드시 헷갈린다. 자리가 달라 기계는 안 헷갈리지만 <b>이 ID 를 읽는 것은 사람이다.</b>
     *
     * @param kind       {@code IaScreenProfile.ScreenKind} 의 한글 라벨 — 화면·팝업·모달
     * @param screenType {@code IaScreenProfile.ScreenType} 의 한글 라벨 — 목록·상세·등록·수정·안내·미분류
     */
    public static String letterOf(String kind, String screenType) {
        if ("팝업".equals(kind)) return "P";
        if ("모달".equals(kind)) return "M";
        if (screenType == null) return "X";
        return switch (screenType) {
            case "목록" -> "L";
            case "상세" -> "D";
            case "등록" -> "R";
            case "수정" -> "U";
            case "안내" -> "G";
            default -> "X";
        };
    }

    /**
     * 저장하는 <b>5마디</b>. 상태 마디는 여기 없다.
     *
     * <p>⚠ 일련번호는 2자리 0채움이고 99 를 넘으면 자연히 3자리가 된다 —
     * <b>그래서 이 문자열로 정렬하면 안 된다</b>({@code sort_no} 열이 그 몫이다).
     */
    public static String core(String platform, String systemCode2, String areaCode,
                              int groupNo, String letter, int seq) {
        return "%s-%s-%s-%03d-%s%02d".formatted(platform, systemCode2, areaCode, groupNo, letter, seq);
    }

    /**
     * 사람에게 보이는 <b>6마디</b>. 상태 마디를 그때 붙인다.
     *
     * <p>⛳ <b>{@code C}(변경)는 아직 없다</b>(2026-08-20 병주 지시). 나중에 받을 때는
     * 「태생이 S 인데 이번 사업에서 BRD/FRD 가 이 화면을 잡았으면 C」 한 줄을 늘리면 되고,
     * <b>5마디만 저장하므로 그때 마이그레이션이 없다.</b>
     */
    public static String display(String core, ScreenStandardId.Origin origin) {
        return core + "-" + origin.name();
    }
}
