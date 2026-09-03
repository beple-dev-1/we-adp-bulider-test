package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FrdInterviewMessageMapper {
    void insert(FrdInterviewMessage message);
    List<FrdInterviewMessage> selectByFrdId(String frdId);
    FrdInterviewMessage selectLatestQuestion(String frdId);
    int selectNextSeq(String frdId);
}
