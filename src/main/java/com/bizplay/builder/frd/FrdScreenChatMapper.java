package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FrdScreenChatMapper {

    void insert(FrdScreenChatMessage message);

    void insertCanvas(FrdScreenChatMessage message);

    int selectNextSequence(String frdScreenId);

    List<FrdScreenChatMessage> selectByScreenId(String frdScreenId);

    int selectNextCanvasSequence(String frdId);

    List<FrdScreenChatMessage> selectCanvasByFrdId(String frdId);

    FrdScreenChatMessage selectById(String id);

    FrdScreenChatMessage selectRunningByFrdId(String frdId);

    String selectLatestSessionId(String frdScreenId);

    String selectLatestCanvasSessionId(String frdId);

    void updateDone(@Param("id") String id, @Param("content") String content,
                    @Param("sessionId") String sessionId,
                    @Param("completedAt") Instant completedAt);

    void updateFailed(@Param("id") String id, @Param("failure") String failure,
                      @Param("completedAt") Instant completedAt);

    int failInterrupted(@Param("failure") String failure, @Param("completedAt") Instant completedAt);
}
