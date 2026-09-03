package com.bizplay.builder.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 접수 적용 구분의 데이터 접근.
 * SQL 은 {@code src/main/resources/mapper/intake/IntakeFacetMapper.xml} 에 있다.
 *
 * <p>⚠ <b>이름 규칙</b> — SQL 종류를 접두사로 쓴다. ⛔ g2c 의 {@code ...ListPage} 접미사는 안 쓴다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> MyBatis 는 {@code jpa.default_schema} 를
 * 안 물려받는다 — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>⚠ <b>고치는 문이 없다.</b> 적용 구분은 붙이거나 안 붙이는 것이지 이름을 바꿔 다는 것이 아니다.
 */
@Mapper
public interface IntakeFacetMapper {

    List<IntakeFacet> selectByIntakeId(String intakeId);

    /**
     * 목록 화면이 접수 여럿의 적용 구분을 한 번에 끌어온다.
     *
     * <p>⛔ <b>빈 목록으로 부르지 마라</b> — {@code in ()} 이 되어 SQL 이 깨진다.
     */
    List<IntakeFacet> selectByIntakeIdIn(@Param("intakeIds") List<String> intakeIds);

    /**
     * 그 프로젝트에서 지금 쓰이는 적용 구분 중 이름이 목록에 든 것만 — 「지울 수 있나」를 묻는 자리다.
     *
     * <p>⛔ <b>빈 목록으로 부르지 마라</b> — {@code in ()} 이 되어 SQL 이 깨진다.
     * 부르는 쪽({@code ProjectService.replaceFacets})이 「지울 것이 있을 때만」 부른다.
     */
    List<IntakeFacet> selectByProjectIdAndNameIn(@Param("projectId") String projectId,
                                                 @Param("names") List<String> names);

    void insert(IntakeFacet facet);
}
