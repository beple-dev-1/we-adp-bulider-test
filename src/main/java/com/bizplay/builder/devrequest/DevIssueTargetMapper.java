package com.bizplay.builder.devrequest;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DevIssueTargetMapper {

    DevIssueTarget selectByProjectId(String projectId);

    /** 있으면 갈아 낀다 — 자리는 프로젝트마다 하나다. */
    void upsert(DevIssueTarget target);
}
