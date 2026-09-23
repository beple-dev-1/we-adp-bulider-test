package com.bizplay.builder.devrequest;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 개발이 돌려보낸 테스트 결과 — <b>TC 번호마다 한 줄</b>.
 *
 * <p>⭐ md 통째로 담지 않는 까닭은 <b>화면이 통과·실패를 세야</b> 하기 때문이다.
 * 그린존의 단위·통합테스트 화면이 이 줄들을 읽는다.
 *
 * <p>⭐ <b>다시 받으면 그 줄을 갈아 낀다</b> — 같은 TC 가 두 판 남으면 어느 것이 마지막인지 모른다.
 */
@Mapper
public interface DevRequestTestResultMapper {

    /** 한 줄 넣거나 갈아 낀다. 열쇠는 (개발요청서, TC 번호)다. */
    int upsert(@Param("devRequestId") String devRequestId,
               @Param("result") TestResultReader.Result result);

    List<DevRequestTestResult> selectByRequestId(@Param("devRequestId") String devRequestId);

    /** ⚠ 개발요청서를 지우기 전에 이 줄들을 먼저 치운다 — FK 가 걸려 있다. */
    int deleteByRequestId(@Param("devRequestId") String devRequestId);
}
