package com.bizplay.builder.srt;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SrtMapper {
    int allocateNumber(String projectId);
    void insert(Srt srt);
    Srt selectById(String id);
    Srt selectByIdForUpdate(String id);
    List<Srt> selectByProjectId(String projectId);
    int connectBridge(@Param("id") String id, @Param("bridgeFrdId") String bridgeFrdId);
    int connectRequest(@Param("id") String id, @Param("devRequestId") String devRequestId);
    int updateAnalysisState(@Param("id") String id, @Param("state") Srt.AnalysisState state,
                            @Param("message") String message);
    int updateDirect(@Param("id") String id, @Param("title") String title, @Param("content") String content);
    int deleteUnprepared(@Param("projectId") String projectId, @Param("id") String id);
}
