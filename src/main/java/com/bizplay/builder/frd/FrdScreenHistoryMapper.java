package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.Instant;

@Mapper
public interface FrdScreenHistoryMapper {

    long insert(@Param("frdScreenId") String frdScreenId, @Param("html") String html,
                @Param("changes") String changes);

    long insertCanvas(@Param("frdScreenId") String frdScreenId, @Param("html") String html,
                      @Param("md") String md, @Param("changes") String changes,
                      @Param("operationId") String operationId);

    List<FrdScreenHistory> selectByFrdId(String frdId);

    FrdScreenHistory selectLatestByScreenId(String frdScreenId);

    FrdScreenHistory selectById(long id);

    /**
     * 이미 있는 이력 줄에 <b>기능정의서 md 만</b> 채운다.
     *
     * <p>⛔ <b>새 이력 줄을 만들지 마라.</b> 화면 html 은 그대로인데 판이 하나 더 생기면
     * 되돌리기 목록에 <b>아무것도 안 바뀐 지점</b>이 끼어든다.
     *
     * <p>⚠ <b>이미 값이 있으면 안 덮는다</b>({@code md is null} 조건). 캔버스 AI 가 사람과
     * 대화로 만든 것이 더 세다.
     */
    int fillMd(@Param("id") long id, @Param("md") String md);

    TobeDocumentStatus selectTobeDocumentStatus(long id);

    int updateTobeDocumentStatus(@Param("id") long id, @Param("state") String state,
                                 @Param("failure") String failure);

    /** 변경 예정 기능정의서 생성 상태. 실패 원문 대신 제한된 이유 코드만 저장한다. */
    record TobeDocumentStatus(String state, String failure, Instant updatedAt) {
        public boolean isGenerating() {
            return "REQUESTED".equals(state) || "RUNNING".equals(state);
        }
    }
}
