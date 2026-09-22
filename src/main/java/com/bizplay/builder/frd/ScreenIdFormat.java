package com.bizplay.builder.frd;

import java.util.regex.Pattern;

/**
 * 화면ID 로 쓸 수 있는 글자 — <b>순수 함수만</b> 있다.
 *
 * <p>⚠ {@link com.bizplay.builder.screenid.StandardScreenIdFormat} 의 <b>표준 화면ID 와 다른 것</b>이다.
 * 이것은 클론의 화면 열쇠({@code EXW-UWV-70-30-10-C} · {@code bo-usag-list})이고 목업 파일 이름이
 * 되는 값이다. 표준 화면ID({@code BP-EXW-WEB-020-X22})는 사람이 대장에서 찾는 번호다.
 *
 * <p>⛔ <b>이 정규식을 다른 곳에 다시 적지 마라</b> — 흩어지면 한쪽만 고쳐진다.
 * {@link TemporaryScreenId} 가 {@code tmp-} 를 한 자리에 둔 것과 같은 이유다.
 * 2026-09-22 에 실제로 네 곳에 흩어져 있었고, 그중 셋만 소문자를 강제해서 화면을 고르고 인터뷰하고
 * 목업까지 만든 뒤 <b>개발요청서에서만</b> 막혔다 — 원인이 제일 안 보이는 자리에서 터졌다.
 *
 * <p>⭐ <b>대소문자를 둘 다 받는다 (2026-09-22).</b> 종전에는 소문자만 받았다. 근거는
 * {@code docs/requests-to-planning-repo.md} §5 의 2026-08 실측({@code bo-usag-list} 꼴)이었는데,
 * 「비플페이」 기획 레포 클론은 {@code core/EXW/pages} 110장이 전부 <b>대문자</b>였다.
 * 화면ID 는 클론이 정하는 값이라 <b>사람이 고칠 수 있는 자리가 아니다</b> — 빌더가 막아도
 * 사용자가 할 수 있는 일이 없어서, 막는 대신 받기로 했다.
 *
 * <p>⚠ <b>어느 표기가 규격인지는 아직 미결이다</b> — {@code docs/requests-to-extractor.md} 끝의
 * 「화면ID 의 표기 규격」 요청에 회신을 기다린다. 회신이 「소문자가 정본」으로 오면 이 관용을
 * 거두는 것이 아니라 <b>추출기가 내보내는 값이 바뀐다</b> — 그때도 대문자 클론이 남아 있으므로
 * 받아 주는 쪽이 안전하다.
 */
public final class ScreenIdFormat {

    /** ⚠ 밑줄·점·슬래시는 받지 않는다 — 이 값이 그대로 파일 이름이 된다. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");

    private ScreenIdFormat() {
    }

    /** 화면ID 로 쓸 수 있는 글자인가. {@code tmp-} 임시 이름도 꼴로는 맞다 — 가려낼 곳에서 따로 본다. */
    public static boolean isValid(String screenId) {
        return screenId != null && VALID.matcher(screenId).matches();
    }

    /** 개발로 넘길 수 있는 확정된 화면ID 인가 — 꼴이 맞고 임시 이름이 아니다. */
    public static boolean isDeliverable(String screenId) {
        return isValid(screenId) && !TemporaryScreenId.isTemporary(screenId);
    }

    /** 아니면 거절한다. 경로를 짓기 직전에 쓴다 — 작업 자리 밖을 가리키는 이름을 막는다. */
    public static void require(String screenId) {
        if (!isValid(screenId)) {
            throw new IllegalArgumentException("화면 ID 형식이 올바르지 않습니다: " + screenId);
        }
    }

    /**
     * 같은 화면인지 견줄 때 쓰는 열쇠.
     *
     * <p>⭐ <b>대소문자만 다른 둘을 같은 것으로 본다.</b> 화면ID 는 파일 이름이고 동시편집 잠금의
     * 열쇠인데, 리눅스는 대소문자를 구분하고 윈도우는 구분하지 않는다. 글자 그대로 견주면
     * 같은 화면이 두 열쇠로 잡혀 <b>상충하는 판본 둘이 개발로 나간다.</b>
     */
    public static String key(String screenId) {
        return screenId == null ? null : screenId.toLowerCase();
    }
}
