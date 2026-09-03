package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrdAnalysisNoteMapper {
    void insert(FrdAnalysisNote note);
    List<FrdAnalysisNote> selectByFrdId(String frdId);
    void deleteByFrdId(String frdId);
}
