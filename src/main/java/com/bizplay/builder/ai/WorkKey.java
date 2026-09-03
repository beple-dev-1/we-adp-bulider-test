package com.bizplay.builder.ai;

/**
 * 다섯 자리의 AI 실행이 공유하는 <b>일 하나</b>를 가리키는 열쇠.
 *
 * <p>⛔ <b>실행을 산출물 하나에 매지 않는다</b>({@code decided-facts} 7번). 잠기는 것은 지시 칸이 아니라
 * <b>그 일</b>이다 — 같은 일을 두 탭에서 열어도 서버가 거절한다.
 *
 * <p>⚠ <b>번호가 {@code Long} 이 아니라 글자이고, 프로젝트가 열쇠에 들어간다.</b> 까닭 셋 —
 * <ul>
 *   <li>번호는 <b>프로젝트마다 1번부터</b>라({@code data-model} §4) 프로젝트가 없으면
 *       남의 사업 {@code BRD-003} 과 같은 열쇠가 된다</li>
 *   <li><b>메뉴구조도는 번호가 없다</b> — 대신 시스템 이름이 들어간다({@code MENU_STRUCTURE:webview}).
 *       {@code NULL} 을 넣으면 PostgreSQL 의 유일 인덱스가 여러 건을 허용해
 *       <b>「한 일에 하나」가 조용히 죽는다</b></li>
 *   <li>{@link #kind} 를 글자 앞에 붙여야 {@code BRD:12} 와 {@code INTAKE:12} 가 안 부딪힌다</li>
 * </ul>
 */
public record WorkKey(String projectId, String kind, String number) {

    /** 접수 — 받은 문서에서 요구사항까지. */
    public static final String INTAKE = "INTAKE";
    /** BRD — 작업 하나의 단위다. */
    public static final String BRD = "BRD";
    /** 메뉴구조도 — 번호가 없어 {@link #number} 자리에 시스템 이름이 들어간다. */
    public static final String MENU_STRUCTURE = "MENU_STRUCTURE";

    public WorkKey {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("일 열쇠에 프로젝트가 없다");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("일 열쇠에 갈래가 없다");
        }
        // ⛔ 번호가 비면 열쇠가 「BRD:」 가 되어 그 프로젝트의 모든 BRD 가 한 일로 뭉친다.
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("일 열쇠에 번호가 없다 — 번호가 없는 갈래는 시스템 이름을 쓴다");
        }
    }

    /**
     * DB 에 앉는 열쇠 글자. {@code "BRD:0000012"} 꼴이다.
     *
     * <p>⛔ <b>글자를 만드는 자리는 여기 하나뿐이다</b>({@code decided-facts} 6번과 같은 처방).
     * 부르는 쪽에서 이어 붙이면 <b>잠금이 짝을 못 찾는다.</b>
     *
     * <p>⚠ 프로젝트는 여기 안 들어간다 — 표에서 {@code project_id} 가 따로 열을 가지고,
     * 유일 인덱스가 <b>둘을 묶어</b> 잰다.
     */
    public String text() {
        return kind + ":" + number;
    }
}
