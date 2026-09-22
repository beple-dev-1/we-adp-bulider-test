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
     * ⚠ <b>2026-09-22 에 이 표가 다시 살았다</b> — 전송을 git 통로로 되살리면서 시도마다 한 줄을
     * 여기 남긴다(아래 {@code insertDeliveryAttempt}). 「이 표에 새로 쓰는 코드는 없다」는
     * 003 시절의 말이라 더는 참이 아니다. 이 메서드는 그대로 <b>옛 자료를 치우는 길</b>이다.
     */
    int deleteDeliveryAttempts(@Param("devRequestId") String devRequestId);

    int deleteNotSent(@Param("id") String id);

    /** 전송 상태를 옮긴다 — 대기 · 전송중 · 전송완료 · 철회. */
    int updateDeliveryState(@Param("id") String id, @Param("deliveryState") String deliveryState);

    /**
     * 이 개발요청서의 <b>전송 키</b>. 없으면 {@code null}.
     *
     * <p>⭐ <b>다시 보내면 같은 키다</b>(설계). 번호는 프로젝트마다 1번부터라 개발 쪽에서 보면
     * 서로 다른 사업의 {@code DR-001} 이 여럿이 된다 — 키가 그것을 가른다.
     */
    String selectDeliveryKey(@Param("devRequestId") String devRequestId);

    /** 보내기 <b>전</b>에 한 줄 남긴다 — 「먼저 남겨 확정하고 보낸다」(설계). */
    int insertDeliveryAttempt(@Param("id") String id,
                              @Param("devRequestId") String devRequestId,
                              @Param("deliveryKey") String deliveryKey,
                              @Param("requestedBy") String requestedBy);

    /**
     * 시도 한 줄을 끝낸다.
     *
     * @param responseId      git 통로에서는 <b>꾸러미 커밋 SHA</b> 다 — 「보냈다」의 증거
     * @param bodyFingerprint 보낸 몸의 지문(sha256). 「받았다」가 어느 판인지 묶는 값
     */
    int finishDeliveryAttempt(@Param("id") String id, @Param("outcome") String outcome,
                              @Param("responseId") String responseId,
                              @Param("bodyFingerprint") String bodyFingerprint,
                              @Param("failure") String failure);
}
