package com.bizplay.builder.screenid;

/**
 * 표준 화면ID 의 업무영역 3글자와 기능그룹 번호 한 줄.
 *
 * <p>⛔ <b>열쇠는 {@code areaKey}·{@code groupKey}(영문 slug)이지 한글 이름이 아니다.</b>
 * 이름표는 기획자가 IA 작업대에서 고칠 수 있고, 이름을 열쇠로 잡으면 이름이 바뀔 때
 * <b>같은 업무영역에 코드를 하나 더 짓는다.</b>
 *
 * @param groupKey IA 경로의 둘째 마디. 마디가 하나뿐인 가지는 <b>빈 문자열</b>이다
 * @param groupNo  기능그룹 번호. {@code groupKey} 가 비면 {@code 0} 이다
 */
public record ScreenIdGroup(
        String id,
        String projectId,
        String systemCode,
        String areaKey,
        String areaCode,
        String areaLabel,
        String groupKey,
        int groupNo,
        String groupLabel) {
}
