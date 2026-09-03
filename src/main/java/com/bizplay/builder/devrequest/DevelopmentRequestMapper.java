package com.bizplay.builder.devrequest;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDate;

@Mapper
public interface DevelopmentRequestMapper {
    int allocateNumber(String projectId);
    void insert(DevelopmentRequest request);
    List<DevelopmentRequest> selectByProjectId(String projectId);
    DevelopmentRequest selectById(String id);
    DevelopmentRequest selectByFrdId(String frdId);
    List<DevelopmentStatusCandidate> selectDevelopmentStatusCandidates();
    DevelopmentStatusCandidate selectDevelopmentStatusCandidate(@Param("projectId") String projectId,
                                                                 @Param("requestId") String requestId);

    /** 상태는 INTAKE → PROGRESS → DONE으로만 전진한다. */
    int isCurrentDevelopmentStatusCandidate(@Param("candidate") DevelopmentStatusCandidate candidate);
    int advanceDevelopmentState(@Param("candidate") DevelopmentStatusCandidate candidate,
                                @Param("state") DevelopmentState state);
    int recordDevelopmentSyncFailure(@Param("candidate") DevelopmentStatusCandidate candidate,
                                     @Param("failure") String failure);
    int markDevelopmentMerged(@Param("candidate") DevelopmentStatusCandidate candidate,
                              @Param("commitSha") String commitSha);

    /**
     * 같은 화면을 건드린 <b>앞선</b> 개발요청서. ⛔ 이것은 <b>후보</b>이지 답이 아니다 —
     * 화면이 겹치는 것과 같은 업무인 것은 다르므로 사람이 고른다.
     */
    List<DevelopmentRequest> selectPreviousCandidates(@Param("projectId") String projectId,
                                                      @Param("requestId") String requestId);
    int updatePlannerComment(@Param("id") String id, @Param("plannerComment") String plannerComment);

    /** 전송을 누른 순간에 잰 「전송 전 확인」 결과를 굳힌다. ⚠ 되돌리면 널로 지운다. */
    int updatePrecheck(@Param("id") String id, @Param("precheckJson") String precheckJson);

    /**
     * 스냅샷 본문을 통째로 다시 쓴다 — <b>테스트 시나리오를 채우는 자리 하나</b>에서만 쓴다.
     * ⛔ 계약 본문(요구사항·화면·완료 조건)을 고치는 데 쓰지 마라 — 스냅샷은 다시 만들지 않는다.
     */
    int updateContent(@Param("id") String id, @Param("contentJson") String contentJson);

    /** 전송 직전 기능정의서를 작업트리에 커밋한 뒤 실제 전달 기준판을 갱신한다. */
    int updateWorkspaceHeadSha(@Param("id") String id, @Param("workspaceHeadSha") String workspaceHeadSha);
    int requestDelivery(@Param("id") String id,
                        @Param("plannerComment") String plannerComment,
                        @Param("attachmentName") String attachmentName,
                        @Param("attachmentPath") String attachmentPath,
                        @Param("attachmentSize") Long attachmentSize,
                        @Param("developmentCompletedOn") LocalDate developmentCompletedOn,
                        @Param("deploymentOn") LocalDate deploymentOn,
                        @Param("previousRequestId") String previousRequestId,
                        @Param("precheckJson") String precheckJson);

    /**
     * 철회 — 「전송완료」에서만 나간다 (2026-08-25).
     *
     * <p>⛔ <b>{@code NOT_SENT} 로 되돌리지 않는다.</b> 이슈는 살아 있는데 상태가 「대기」면
     * 다시 눌러 두 번째 이슈가 열린다.
     *
     * @return 바뀐 줄. 0 이면 그 사이 남이 상태를 옮겼다는 뜻이다
     */
    int withdrawDelivery(@Param("id") String id);

    /** 이 개발요청서를 「앞 개발요청서」로 가리키는 다른 개발요청서 수. 0 이 아니면 지울 수 없다. */
    int countReferencing(@Param("id") String id);

    /**
     * FRD 로 되돌리기 — <b>전송 전({@code NOT_SENT})</b>인 것만 지운다 (2026-08-25).
     *
     * @return 지운 줄. 0 이면 그 사이 전송이 시작됐다는 뜻이다
     */
    int deleteNotSent(@Param("id") String id);
}
