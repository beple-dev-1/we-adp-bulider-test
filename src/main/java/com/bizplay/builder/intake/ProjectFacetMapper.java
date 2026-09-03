package com.bizplay.builder.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 프로젝트 적용 구분의 데이터 접근 — <b>값의 정본이 앉은 표</b>다.
 * SQL 은 {@code src/main/resources/mapper/intake/ProjectFacetMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다. ⛔ g2c 의 {@code ...ListPage} 접미사는 안 쓴다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> MyBatis 는 {@code jpa.default_schema} 를
 * 안 물려받는다 — 빠뜨리면 「표가 없다」로 죽는다.
 */
@Mapper
public interface ProjectFacetMapper {

    /**
     * 등록 화면이 고를 것을 여기서 얻는다. ⚠ 비어 있으면 그 프로젝트엔 적용 구분 축이 없다.
     *
     * <p>⚠ <b>이름순이다.</b> 넣은 순서는 안 지킨다 — 화면이 이 순서 그대로 그린다.
     */
    List<ProjectFacet> selectByProjectId(String projectId);

    /** 테스트가 「어느 프로젝트에도 한 행도 안 남았다」를 잴 때 쓴다. */
    List<ProjectFacet> selectAll();

    void insert(ProjectFacet facet);

    int updateCode(@Param("projectId") String projectId,
                   @Param("name") String name,
                   @Param("code") String code);

    int updateName(@Param("projectId") String projectId,
                   @Param("code") String code,
                   @Param("name") String name);

    /**
     * 기본키 둘로 한 줄을 지운다.
     *
     * <p>⚠ 인자가 둘인 것은 <b>기본키가 {@code (project_id, name)} 둘</b>이라서다.
     * JPA 때는 이 자리를 위해 {@code ProjectFacet.Key} 라는 복합키 레코드가 있었는데,
     * 그것은 {@code @IdClass} 규약을 맞추려고만 있던 것이라 2026-08-15 에 지웠다.
     * <b>되살리지 마라</b> — 여기 인자 둘이면 족하다.
     *
     * <p>⛔ 「통째로 지웠다 다시 넣기」로 되돌리지 마라 — {@code adk_builder_intake_facet} 이
     * {@code (project_id, name)} 을 통째로 FK 로 걸어서, 접수가 하나라도 걸린 이름을 지우면
     * <b>바뀐 것이 없어도</b> 500 이 난다. 부르는 쪽이 차이만 만지는 까닭이다.
     */
    void deleteByProjectIdAndName(@Param("projectId") String projectId, @Param("name") String name);
}
