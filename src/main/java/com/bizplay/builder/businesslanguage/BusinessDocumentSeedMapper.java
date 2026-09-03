package com.bizplay.builder.businesslanguage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/** 최초 초안 생성을 원자적으로 선점하고 닫는 데이터 접근. */
@Mapper
public interface BusinessDocumentSeedMapper {

    int begin(@Param("projectId") String projectId, @Param("accountId") String accountId);

    int finish(String projectId);

    int fail(@Param("projectId") String projectId, @Param("reason") String reason);

    Optional<BusinessDocumentSeed> selectOne(String projectId);

    List<BusinessDocumentSeed> selectRunning();
}
