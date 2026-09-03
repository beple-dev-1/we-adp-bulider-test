package com.bizplay.builder.featurespec;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 기능명세서 현재 상태와 불변 개정판의 데이터 접근. */
@Mapper
public interface FeatureSpecMapper {

    int beginGeneration(@Param("projectId") String projectId,
                        @Param("systemCode") String systemCode,
                        @Param("screenId") String screenId,
                        @Param("generationId") String generationId,
                        @Param("sourceFingerprint") String sourceFingerprint,
                        @Param("generatorVersion") String generatorVersion,
                        @Param("schemaVersion") String schemaVersion,
                        @Param("staleBefore") Instant staleBefore,
                        @Param("retryNow") Instant retryNow);

    Optional<FeatureSpecCurrent> selectCurrent(@Param("projectId") String projectId,
                                               @Param("systemCode") String systemCode,
                                               @Param("screenId") String screenId);

    Optional<FeatureSpecCurrent> lockCurrent(@Param("projectId") String projectId,
                                             @Param("systemCode") String systemCode,
                                             @Param("screenId") String screenId);

    List<FeatureSpecCurrent> selectByProject(@Param("projectId") String projectId);

    List<FeatureSpecRevision> selectCurrentRevisionsByProject(@Param("projectId") String projectId);

    Optional<FeatureSpecRevision> selectRevision(@Param("revisionId") String revisionId);

    int insertRevision(FeatureSpecRevision revision);

    int promote(@Param("projectId") String projectId,
                @Param("systemCode") String systemCode,
                @Param("screenId") String screenId,
                @Param("generationId") String generationId,
                @Param("revisionId") String revisionId,
                @Param("revisionNo") int revisionNo);

    int markFailed(@Param("projectId") String projectId,
                   @Param("systemCode") String systemCode,
                   @Param("screenId") String screenId,
                   @Param("generationId") String generationId,
                   @Param("reason") String reason,
                   @Param("retryAfter") Instant retryAfter);
}
