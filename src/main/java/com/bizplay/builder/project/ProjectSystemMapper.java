package com.bizplay.builder.project;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL 은 {@code mapper/project/ProjectSystemMapper.xml} 에 있다. */
@Mapper
public interface ProjectSystemMapper {

    List<ProjectSystem> selectByProjectId(String projectId);

    void insert(ProjectSystem system);

    void updateDisplayName(@Param("projectId") String projectId,
                           @Param("systemCode") String systemCode,
                           @Param("displayName") String displayName);

    void deleteByProjectIdAndSystemCode(@Param("projectId") String projectId,
                                       @Param("systemCode") String systemCode);
}
