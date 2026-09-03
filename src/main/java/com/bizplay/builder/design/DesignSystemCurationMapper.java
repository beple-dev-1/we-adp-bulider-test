package com.bizplay.builder.design;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DesignSystemCurationMapper {

    DesignSystemCuration select(@Param("projectId") String projectId, @Param("systemId") String systemId);

    DesignSystemCuration selectForUpdate(@Param("projectId") String projectId,
                                         @Param("systemId") String systemId);

    void insert(DesignSystemCuration curation);

    int update(@Param("projectId") String projectId, @Param("systemId") String systemId,
               @Param("contentJson") String contentJson, @Param("expectedVersion") int expectedVersion,
               @Param("updatedBy") String updatedBy);
}
