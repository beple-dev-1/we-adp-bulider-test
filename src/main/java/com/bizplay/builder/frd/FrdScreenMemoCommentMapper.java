package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrdScreenMemoCommentMapper {

    void insert(FrdScreenMemoComment comment);

    List<FrdScreenMemoComment> selectByScreenId(String frdScreenId);
}
