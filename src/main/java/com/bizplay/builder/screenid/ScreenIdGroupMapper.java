package com.bizplay.builder.screenid;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 업무영역·기능그룹 코드표의 데이터 접근. */
@Mapper
public interface ScreenIdGroupMapper {

    List<ScreenIdGroup> selectByProject(String projectId);

    void insert(ScreenIdGroup row);
}
