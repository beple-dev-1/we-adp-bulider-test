package com.bizplay.builder.screenid;

import java.util.Map;

/**
 * 업무영역의 영문 3글자를 짓는다. <b>표준 화면ID 에서 AI 가 손대는 자리는 여기 하나뿐이다.</b>
 *
 * <p>⛔ <b>일련번호를 AI 에게 맡기지 마라.</b> 두 사람이 같은 시각에 같은 번호를 받는다 —
 * 원자적 채번의 일이다.
 *
 * <p>⚠ <b>못 지으면 지어내지 말고 {@code "XXX"} 를 낸다.</b> 사람이 볼 목록에 남아야 고칠 수 있다.
 */
public interface BusinessAreaCoder {

    /**
     * @param areas IA 경로 첫 마디 slug → 이름표의 한글 (예: {@code merchant → 가맹점})
     * @return slug → 대문자 3글자. <b>{@code areas} 의 열쇠를 하나도 빠뜨리지 않는다</b> —
     *         못 지은 것은 {@code "XXX"} 다
     */
    Map<String, String> codesOf(String projectId, String accountId, Map<String, String> areas);
}
