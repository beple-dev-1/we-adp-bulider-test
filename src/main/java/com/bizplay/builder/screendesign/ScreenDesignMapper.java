package com.bizplay.builder.screendesign;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 화면설계서 현재 상태와 불변 개정판의 데이터 접근. */
@Mapper
public interface ScreenDesignMapper {

    int beginGeneration(@Param("projectId") String projectId,
                        @Param("systemCode") String systemCode,
                        @Param("screenId") String screenId,
                        @Param("generationId") String generationId,
                        @Param("sourceFingerprint") String sourceFingerprint,
                        @Param("generatorVersion") String generatorVersion,
                        @Param("schemaVersion") String schemaVersion,
                        @Param("staleBefore") Instant staleBefore,
                        @Param("retryNow") Instant retryNow);

    Optional<ScreenDesignCurrent> selectCurrent(@Param("projectId") String projectId,
                                                 @Param("systemCode") String systemCode,
                                                 @Param("screenId") String screenId);

    Optional<ScreenDesignCurrent> lockCurrent(@Param("projectId") String projectId,
                                               @Param("systemCode") String systemCode,
                                               @Param("screenId") String screenId);

    List<ScreenDesignCurrent> selectByProject(@Param("projectId") String projectId);

    List<ScreenDesignRevision> selectCurrentRevisionsByProject(@Param("projectId") String projectId);

    Optional<ScreenDesignRevision> selectRevision(@Param("revisionId") String revisionId);

    int insertRevision(ScreenDesignRevision revision);

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
