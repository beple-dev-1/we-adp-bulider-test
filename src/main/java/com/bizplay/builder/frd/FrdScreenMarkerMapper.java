package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FrdScreenMarkerMapper {

    void insert(FrdScreenMarker marker);

    FrdScreenMarker selectById(String id);

    List<FrdScreenMarker> selectByScreenId(String frdScreenId);

    int selectNextMarkerNo(String frdScreenId);

    int updateDescription(String id, String description, Instant updatedAt);

    int deleteById(String id);
}
