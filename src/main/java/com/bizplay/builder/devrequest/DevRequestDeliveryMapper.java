package com.bizplay.builder.devrequest;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DevRequestDeliveryMapper {

    void insert(DevRequestDeliveryAttempt attempt);

    /** 시도 하나를 닫는다. ⚠ 여는 것과 닫는 것을 갈라 둔다 — 여는 순간과 답이 오는 순간이 다르다. */
    int finish(@Param("id") String id, @Param("outcome") DeliveryOutcome outcome,
               @Param("httpStatus") Integer httpStatus, @Param("responseId") String responseId,
               @Param("failure") String failure);

    List<DevRequestDeliveryAttempt> selectByRequestId(String devRequestId);

    /** 목록용 최근 전송 시도. 철회 시도는 몸 지문이 없으므로 제외한다. */
    List<DevRequestDeliveryAttempt> selectLatestHandoffByRequestIds(
            @Param("requestIds") List<String> requestIds);

    /** 개발요청서의 상태를 답에 따라 옮긴다. ⛔ 「전송중」일 때만 옮긴다 — 남의 시도를 밀지 않는다. */
    int moveFromSending(@Param("id") String id, @Param("outcome") DeliveryOutcome outcome);

    /** FRD 로 되돌릴 때 같이 지운다 — 전송 전이라 실패한 시도만 남아 있을 수 있다. */
    int deleteByRequestId(String devRequestId);
}
