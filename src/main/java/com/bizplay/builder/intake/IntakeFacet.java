package com.bizplay.builder.intake;

/**
 * 이 접수가 어느 적용 구분에 걸리나. 적용 구분이 있는 프로젝트에서는 <b>하나 이상</b>이다.
 * 표는 {@code builder.adk_builder_intake_facet} 이고 기본키는 {@code (intake_id, name)} 둘이다.
 *
 * <p>받은 문서 하나가 여러 적용 구분에 공통이면 해당하는 것을 <b>모두</b> 담는다 —
 * 「공통」이라는 값을 만들지 않고, 미선택을 공통으로 해석하지도 않는다(→ {@code facet-axis}).
 *
 * <p>⛔ <b>{@code projectId} 는 중복이 아니다.</b> {@code (project_id, name)} 을 통째로
 * {@link ProjectFacet} 에 FK 로 걸기 위한 열이다 — 목록에 없는 값과
 * <b>남의 프로젝트 적용 구분</b>을 DB 가 막는다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 * 그때 복합키 {@code Key} 레코드를 지웠다 — 그것은 JPA 의 {@code @IdClass} 가 요구해서만
 * 있던 것이라 (무인자 생성자 · {@code equals} · {@code hashCode} 가 전부 그 규약이었다)
 * 걷어내니 쓸 데가 남지 않았다. <b>되살리지 마라</b> — 키 둘로 부르는 매퍼 메서드가 인자 둘을 받는다.
 */
public class IntakeFacet {

    private final String intakeId;
    private final String projectId;
    private final String name;

    /** MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}). */
    private IntakeFacet(String intakeId, String projectId, String name) {
        this.intakeId = intakeId;
        this.projectId = projectId;
        this.name = name;
    }

    public static IntakeFacet create(String intakeId, String projectId, String name) {
        return new IntakeFacet(intakeId, projectId, name);
    }

    public String intakeId() {
        return intakeId;
    }

    public String projectId() {
        return projectId;
    }

    public String name() {
        return name;
    }
}
