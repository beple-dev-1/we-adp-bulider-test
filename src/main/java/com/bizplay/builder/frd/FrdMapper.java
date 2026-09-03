package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FrdMapper {

    /**
     * 번호를 집는 유일한 문. ⭐ {@code update ... returning} 이 그 줄을 잠근다.
     * ⛔ {@code select max(number) + 1} 로 바꾸지 마라 — 지운 번호가 되살아나고 동시 실행이 부딪힌다.
     */
    int allocateNumber(String projectId);

    void insert(Frd frd);

    List<Frd> selectByProjectId(String projectId);

    /** SRT 내부 호환 행을 제외하고 사용자가 작업하는 FRD만 읽는다. */
    List<Frd> selectVisibleByProjectId(String projectId);

    List<Frd> selectByState(Frd.State state);

    Frd selectById(String id);

    /**
     * 여러 FRD 를 한 번에 읽는다. ⚠ 목록 화면이 담당을 채우려고 쓴다 —
     * FRD 마다 {@link #selectById} 를 부르면 N+1 이다. ⛔ 빈 목록으로 부르지 마라({@code in ()} 는 문법 오류다).
     */
    List<Frd> selectByIdIn(@Param("ids") List<String> ids);

    /** 짚기가 끝났다. 제목·시스템·화면없음 사유·상태를 <b>한 문장에서</b> 같이 쓴다. */
    void updateAfterPick(@Param("id") String id, @Param("title") String title,
                         @Param("systemCode") String systemCode,
                         @Param("noScreenReason") String noScreenReason,
                         @Param("state") Frd.State state, @Param("failure") String failure);

    void updateState(@Param("id") String id, @Param("state") Frd.State state);

    /** SRT 원문 수정 내용을 내부 호환 행에 함께 반영한다. */
    int updateSrtSource(@Param("id") String id, @Param("title") String title,
                        @Param("sourceText") String sourceText);

    /** 같은 상태에서 출발한 요청 하나만 다음 상태로 보낸다. */
    int transitionState(@Param("id") String id, @Param("expected") Frd.State expected,
                        @Param("state") Frd.State state);

    /** 완료 전 상태인 FRD 한 건만 삭제한다. */
    int deleteIncomplete(@Param("projectId") String projectId, @Param("id") String id);
}
