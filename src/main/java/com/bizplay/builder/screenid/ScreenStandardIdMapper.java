package com.bizplay.builder.screenid;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 표준 화면ID 매핑표의 데이터 접근. */
@Mapper
public interface ScreenStandardIdMapper {

    List<ScreenStandardId> selectByProject(String projectId);

    void insert(ScreenStandardId row);
}
