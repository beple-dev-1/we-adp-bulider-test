package com.bizplay.builder.solution;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 「실물과 다름」 표시의 데이터 접근.
 * SQL 은 {@code src/main/resources/mapper/solution/MockupMismatchMapper.xml} 에 있다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> MyBatis 는 {@code default_schema} 를
 * 안 물려받는다 — 빠뜨리면 「표가 없다」로 죽는다.
 *
 * <p>⛔ <b>{@code delete} 를 만들지 마라.</b> 「아니었다」도 기록이다 — 무를 자리를 만드는 것은
 * 이 계획 밖이고, 그때도 지우는 것이 아니라 무른 것으로 표시한다.
 */
@Mapper
public interface MockupMismatchMapper {

    /** ⚠ {@code id} 와 {@code created_at} 은 넣지 않는다 — DB 의 default 가 채운다. */
    void insert(@Param("projectId") String projectId,
                @Param("screenId") String screenId,
                @Param("reason") String reason,
                @Param("reporterId") String reporterId);

    /** 한 프로젝트의 표시 전부. <b>새것이 앞</b>이다. 목록의 건수 세기와 상세가 같이 쓴다. */
    List<MockupMismatch> selectByProjectId(@Param("projectId") String projectId);
}
