package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrdBackendChangeMapper {
    void insert(FrdBackendChange change);
    List<FrdBackendChange> selectByFrdId(String frdId);
    void deleteByFrdId(String frdId);
}
