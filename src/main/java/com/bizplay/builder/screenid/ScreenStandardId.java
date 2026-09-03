package com.bizplay.builder.screenid;

/**
 * 기획 레포의 화면ID 에 붙인 표준 화면ID 한 줄.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-20-screen-standard-id-design.md}.
 *
 * <p>⛔ <b>{@code standardId} 로 화면을 찾지 마라.</b> 조회·잠금·경로·조인은 언제나
 * {@code screenId} 로 한다. 두 이름으로 같은 것을 찾기 시작하면 한쪽이 낡았을 때
 * <b>조용히 다른 화면을 집는다.</b>
 *
 * <p>⛔ <b>{@code standardId} 를 파싱해 업무영역·유형을 알아내지 마라.</b> 클론 직후의 IA 로
 * 박히기 때문에 기획자가 메뉴구조도를 정리하면 <b>어긋난 채로 굳는다.</b> 그래도 기능이 안 깨지는
 * 것은 아무도 이것을 파싱하지 않기 때문이다.
 *
 * @param standardId 상태 마디를 뺀 <b>5마디</b>({@code PS-BO-MRC-010-L01})
 */
public record ScreenStandardId(
        String id,
        String projectId,
        String screenId,
        String standardId,
        Origin origin,
        int sortNo) {

    /** 이 화면이 어디서 났나. ⚠ 화면 속성이라 불변이다 — 「이번에 고쳤나」와 섞지 마라. */
    public enum Origin { S, N }
}
