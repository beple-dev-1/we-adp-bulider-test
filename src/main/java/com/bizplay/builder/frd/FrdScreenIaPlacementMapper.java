package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FrdScreenIaPlacementMapper {

    FrdScreenIaPlacement selectByScreenId(String frdScreenId);

    List<FrdScreenIaPlacement> selectByFrdId(String frdId);

    FrdScreenIaPlacement selectByDevelopmentFileName(@Param("projectId") String projectId,
                                                     @Param("developmentFileName") String developmentFileName);

    void upsert(FrdScreenIaPlacement placement);

    void deleteByScreenId(String frdScreenId);
}
