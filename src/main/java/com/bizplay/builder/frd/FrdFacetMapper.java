package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** FRD 분석에 적용할 대상의 데이터 접근. */
@Mapper
public interface FrdFacetMapper {

    List<FrdFacet> selectByFrdId(String frdId);

    List<FrdFacet> selectByProjectId(String projectId);

    void insert(FrdFacet facet);
}
