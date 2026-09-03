package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FrdScreenMarkerHistoryMapper {

    void snapshot(@Param("historyId") long historyId, @Param("frdScreenId") String frdScreenId);

    int deleteByHistoryId(long historyId);

    List<FrdScreenMarker> selectByHistoryId(long historyId);
}
