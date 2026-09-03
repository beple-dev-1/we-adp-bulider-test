package com.bizplay.builder.screenid;

/**
 * 화면 한 장의 채번 재료. <b>IA 경로를 얻은 화면만</b> 이 꼴이 된다.
 *
 * @param systemCode  기획 레포의 시스템 코드({@code backoffice} 꼴). 코드표(group)의 열쇠로 쓴다
 * @param systemCode2 {@code manifest.json} 의 {@code systems[].prefix} 를 <b>이미 대문자로 올린</b> 것
 *                    ({@code bo} → {@code BO}) — 표준 화면ID 의 시스템 마디다. 이 material 이 났다는
 *                    것 자체가 그 시스템의 prefix 를 manifest 에서 읾었다는 뜻이라 <b>여기는 절대
 *                    비지 않는다</b>({@code ScreenIdMaterialReader} 가 못 읽은 시스템은 material 을
 *                    안 낸다) — 그래서 서비스 쪽에 다시 매핑표를 두지 않는다
 * @param areaKey   IA 경로 첫 마디 slug. <b>코드표의 열쇠다</b>
 * @param groupKey  IA 경로 둘째 마디 slug. 마디가 하나뿐이면 빈 문자열
 * @param letter    유형 한 글자 ({@code StandardScreenIdFormat.letterOf})
 * @param pathKey   IA 경로 전체. <b>결정적 정렬의 첫 열쇠다</b>
 */
public record ScreenIdMaterial(
        String screenId,
        String systemCode,
        String systemCode2,
        String areaKey,
        String areaLabel,
        String groupKey,
        String groupLabel,
        String letter,
        String pathKey) {
}
