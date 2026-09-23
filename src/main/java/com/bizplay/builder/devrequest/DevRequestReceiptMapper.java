package com.bizplay.builder.devrequest;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 수신 이력 — 「개발 결과 받기」를 누를 때마다 한 줄.
 *
 * <p>⚠ <b>고치지 않고 더하기만 한다.</b> 이력은 지나간 사실이다.
 */
@Mapper
public interface DevRequestReceiptMapper {

    int insert(@Param("receipt") DevRequestReceipt receipt);

    /** 새것이 위다. */
    List<DevRequestReceipt> selectByRequestId(@Param("devRequestId") String devRequestId);
}
