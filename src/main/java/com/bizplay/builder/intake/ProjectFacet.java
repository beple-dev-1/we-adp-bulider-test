package com.bizplay.builder.intake;

/**
 * 그 프로젝트에 어떤 적용 구분이 있나 — <b>값의 정본</b>이다(→ {@code data-model} §6).
 * 표는 {@code builder.adk_builder_project_facet} 이고 기본키는 {@code (project_id, name)} 둘이다.
 *
 * <p>익산·제주처럼 한 시스템 안에서 요구사항이 갈리는 기준이다. 프로젝트 등록에서 사람이 넣는다.
 *
 * <p>⚠ <b>0행이 정상이다.</b> 그러면 그 프로젝트엔 적용 구분 축이 아예 없고
 * 화면에 필터도 입력도 안 뜬다. 「공통」이라는 값을 따로 만들지 않는다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 * 그때 복합키 {@code Key} 레코드를 지웠다 — 그것은 JPA 의 {@code @IdClass} 가 요구해서만
 * 있던 것이라 (무인자 생성자 · {@code equals} · {@code hashCode} 가 전부 그 규약이었다)
 * 걷어내니 쓸 데가 남지 않았다. <b>되살리지 마라</b> —
 * 지우는 자리는 {@link ProjectFacetMapper#deleteByProjectIdAndName} 이고 인자 둘을 받는다.
 */
public class ProjectFacet {

    private final String projectId;
    private final String code;
    private final String name;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     * ⚠ 여기서는 다듬지 않는다 — DB 에 앉은 값을 그대로 받는 자리다. 다듬는 것은 {@link #create} 다.
     */
    private ProjectFacet(String projectId, String code, String name) {
        this.projectId = projectId;
        this.code = code;
        this.name = name;
    }

    /** ⚠ 앞뒤 빈 칸을 다듬어 앉힌다 — DB {@code CHECK (name = btrim(name))} 가 같은 것을 지킨다. */
    public static ProjectFacet create(String projectId, String name) {
        String trimmed = name.strip();
        return new ProjectFacet(projectId, trimmed, trimmed);
    }

    /** 추출기 식별자와 사용자 표시 이름을 분리해 앉힌다. */
    public static ProjectFacet create(String projectId, String code, String name) {
        return new ProjectFacet(projectId, code.strip(), name.strip());
    }

    public String projectId() {
        return projectId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }
}
