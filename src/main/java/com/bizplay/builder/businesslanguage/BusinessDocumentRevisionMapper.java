package com.bizplay.builder.businesslanguage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/** 정책서와 표준용어 개정본의 데이터 접근. */
@Mapper
public interface BusinessDocumentRevisionMapper {

    List<BusinessDocumentRevision> selectAll(@Param("projectId") String projectId,
                                              @Param("kind") BusinessDocumentKind kind);

    Optional<BusinessDocumentRevision> selectOne(@Param("projectId") String projectId,
                                                  @Param("kind") BusinessDocumentKind kind,
                                                  @Param("revisionNo") int revisionNo);

    int nextRevisionNo(@Param("projectId") String projectId,
                       @Param("kind") BusinessDocumentKind kind);

    void insert(@Param("projectId") String projectId,
                @Param("kind") BusinessDocumentKind kind,
                @Param("revisionNo") int revisionNo,
                @Param("content") String content,
                @Param("sourceRefs") String sourceRefs,
                @Param("changeType") BusinessDocumentRevisionType changeType,
                @Param("createdBy") String createdBy);
}
