package com.bizplay.builder.businesslanguage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/** 정책서와 표준용어 문서의 데이터 접근. */
@Mapper
public interface BusinessDocumentMapper {

    Optional<BusinessDocument> selectOne(@Param("projectId") String projectId,
                                         @Param("kind") BusinessDocumentKind kind);

    Optional<BusinessDocument> selectOneForUpdate(@Param("projectId") String projectId,
                                                  @Param("kind") BusinessDocumentKind kind);

    List<BusinessDocument> selectByProjectId(String projectId);

    void upsert(@Param("projectId") String projectId,
                @Param("kind") BusinessDocumentKind kind,
                @Param("content") String content,
                @Param("sourceRefs") String sourceRefs,
                @Param("updatedBy") String updatedBy);

    int updateContent(@Param("projectId") String projectId,
                      @Param("kind") BusinessDocumentKind kind,
                      @Param("content") String content,
                      @Param("updatedBy") String updatedBy);

    int updateDocument(@Param("projectId") String projectId,
                       @Param("kind") BusinessDocumentKind kind,
                       @Param("content") String content,
                       @Param("sourceRefs") String sourceRefs,
                       @Param("updatedBy") String updatedBy);
}
