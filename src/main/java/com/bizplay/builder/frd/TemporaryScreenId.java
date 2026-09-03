package com.bizplay.builder.frd;

import java.util.regex.Pattern;

/**
 * 신규 화면의 <b>임시</b> 화면ID — {@code tmp-} 에 {@link FrdScreen} 의 기본키를 붙인 것.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-new-screen-id-design.md}.
 *
 * <p>⭐ <b>새 채번기가 0개다.</b> 기본키는 {@code IdSequence.Kind.FRD_SCREEN} 이 이미 일곱 자리로
 * 내주므로 중복이 <b>구조적으로 불가능</b>하고, 기준 화면의 메뉴 자리도 유형도 안 본다.
 *
 * <p>⭐ <b>뜻이 없는 것이 의도다.</b> 뜻을 담으면 그 뜻이 틀렸을 때 이름이 거짓말이 되고, 그때
 * 이름을 다시 지어야 한다 — 목업 파일 경로 · 화면 이력 · 화면별 대화가 전부 화면ID 에 붙어 있다.
 *
 * <p>⭐ <b>기본키는 행을 앉힐 때만 난다.</b> 그래서 <b>누구도 미리 이름을 가질 수 없다</b> —
 * 사람도, AI 도, 미리보기도. 구조가 아래 못 둘을 대신 지킨다.
 *
 * <p>⛔ <b>이름을 미리 내주는 API 를 만들지 마라.</b> 사람에게는 「화면 추가」 레이어가 지어진
 * 화면ID 를 보여 주지 않는다(2026-08-22 병주 지시 — 확인할 재료가 없다). AI 에게도 주지 않는다 —
 * AI 호출은 재시도가 기본이라 채번 같은 부작용을 거기 두면 <b>버려진 번호가 쌓이고</b>, 출력은
 * 아직 사람이 검증하기 전이다. AI 는 {@code {isNew, screenName, baseScreenId}} 까지만 낸다.
 *
 * <p>⛔ <b>기본키로 파일 경로를 짓지 마라.</b> 같은 신규 화면을 다음 FRD 가 이어 작업하면 그쪽
 * 행의 기본키는 다르지만 화면ID 는 그대로다 — 경로는 언제나 {@code screenId} 로 짓는다.
 */
public final class TemporaryScreenId {

    /** ⚠ 이 글자를 다른 곳에 다시 적지 마라 — 흩어지면 한쪽만 고쳐진다. */
    private static final String PREFIX = "tmp-";

    /** {@code IdSequence} 가 내는 꼴. ⚠ 일곱 자리를 늘리려면 이 자리도 같이 본다. */
    private static final Pattern PRIMARY_KEY = Pattern.compile("^[0-9]{7}$");

    private TemporaryScreenId() {
    }

    /** {@code "0000042"} → {@code "tmp-0000042"}. */
    public static String of(String frdScreenPrimaryKey) {
        if (frdScreenPrimaryKey == null || !PRIMARY_KEY.matcher(frdScreenPrimaryKey).matches()) {
            throw new IllegalArgumentException(
                    "FRD 화면의 기본키는 일곱 자리 숫자여야 합니다: " + frdScreenPrimaryKey);
        }
        return PREFIX + frdScreenPrimaryKey;
    }

    /** 빌더가 지은 임시 이름인가 — 기획 저장소에서 온 이름과 가른다. */
    public static boolean isTemporary(String screenId) {
        return screenId != null && screenId.startsWith(PREFIX);
    }
}
