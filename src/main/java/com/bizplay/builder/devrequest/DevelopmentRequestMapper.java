package com.bizplay.builder.devrequest;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DevelopmentRequestMapper {
    int allocateNumber(String projectId);
    void insert(DevelopmentRequest request);
    List<DevelopmentRequest> selectByProjectId(String projectId);
    DevelopmentRequest selectById(String id);
    DevelopmentRequest selectByFrdId(String frdId);



    /**
     * 스냅샷 본문을 통째로 다시 쓴다 — <b>테스트 시나리오를 채우는 자리 하나</b>에서만 쓴다.
     * ⛔ 계약 본문(요구사항·화면·완료 조건)을 고치는 데 쓰지 마라 — 스냅샷은 다시 만들지 않는다.
     */
    int updateContent(@Param("id") String id, @Param("contentJson") String contentJson);

    /** 전송 직전 기능정의서를 작업트리에 커밋한 뒤 실제 전달 기준판을 갱신한다. */
    int updateWorkspaceHeadSha(@Param("id") String id, @Param("workspaceHeadSha") String workspaceHeadSha);


    /** 이 개발요청서를 「앞 개발요청서」로 가리키는 다른 개발요청서 수. 0 이 아니면 지울 수 없다. */
    int countReferencing(@Param("id") String id);

    /**
     * FRD 로 되돌리기 — <b>전송 전({@code NOT_SENT})</b>인 것만 지운다 (2026-08-25).
     *
     * @return 지운 줄. 0 이면 그 사이 전송이 시작됐다는 뜻이다
     */
    /**
     * ⚠ <b>옛 전송 시도 줄을 먼저 지운다.</b> 003(2026-09-06)에서 전송을 없앴지만 표
     * {@code adk_builder_dev_request_delivery} 는 죽은 채 남겼고, 그 FK 에는
     * {@code on delete cascade} 가 <b>없다</b>({@code V48}). 003 이전에 쓰인 줄이 남아 있으면
     * {@code deleteNotSent} 가 FK 위반으로 죽어 「FRD 작업 재개」가 500 이 된다.
     * ⛔ 이 표에 새로 쓰는 코드는 없다 — 옛 자료를 치우는 길이다.
     */
    int deleteDeliveryAttempts(@Param("devRequestId") String devRequestId);

    int deleteNotSent(@Param("id") String id);
}
