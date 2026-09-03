package com.bizplay.builder.project;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface RepositoryUpdateMapper {

    Optional<RepositoryUpdate> selectByProjectId(String projectId);

    /** 이미 실행 중이면 0, 새 시도를 잡았으면 1이다. */
    int tryStart(String projectId);

    int updateSucceeded(@Param("projectId") String projectId,
                        @Param("fromCommit") String fromCommit,
                        @Param("currentCommit") String currentCommit,
                        @Param("changed") boolean changed);

    int updateFailed(@Param("projectId") String projectId,
                     @Param("failureReason") String failureReason);
}
