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
    /** 준비를 못 마친 요청서를 거두기 전에 SRT 와의 연결을 푼다 — SRT 는 생성 전으로 돌아간다. */
    int disconnectRequest(String devRequestId);
    int updateAnalysisState(@Param("id") String id, @Param("state") Srt.AnalysisState state,
                            @Param("message") String message);
    int updateDirect(@Param("id") String id, @Param("title") String title, @Param("content") String content);
    int deleteUnprepared(@Param("projectId") String projectId, @Param("id") String id);
}
