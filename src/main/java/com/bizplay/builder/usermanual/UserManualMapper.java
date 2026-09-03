package com.bizplay.builder.usermanual;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 사용자 매뉴얼의 데이터 접근. 화면 하나에 한 줄이고, 다시 만들면 덮어쓴다. */
@Mapper
public interface UserManualMapper {

    /** 넣거나 덮어쓴다. ⚠ 시각은 DB 가 넣는다 — {@link UserManual#createdAt()} 은 안 보낸다. */
    void upsert(UserManual manual);

    /** 생성 시도를 선점한다. 이미 진행 중이면 0, 비어 있거나 오래된 시도이면 1을 돌려준다. */
    int beginGeneration(@Param("projectId") String projectId,
                        @Param("systemCode") String systemCode,
                        @Param("screenId") String screenId,
                        @Param("generationId") String generationId,
                        @Param("staleBefore") Instant staleBefore);

    /** 다 만들었다. ⚠ 청한 줄이 없으면 0줄이 바뀌고 그것이 맞다 — 그 사이 지워진 것이다. */
    int saveDone(@Param("projectId") String projectId,
                 @Param("systemCode") String systemCode,
                 @Param("screenId") String screenId,
                 @Param("generationId") String generationId,
                 @Param("html") String html,
                 @Param("sourceFingerprint") String sourceFingerprint,
                 @Param("generatorVersion") String generatorVersion,
                 @Param("capture") UserManualCapture capture);

    /** 캡처를 아직 만들지 않는 직접 생성 경로도 같은 완료 조건을 쓴다. */
    default int saveDone(String projectId, String systemCode, String screenId, String generationId,
                         String html, String sourceFingerprint, String generatorVersion) {
        return saveDone(projectId, systemCode, screenId, generationId, html,
                sourceFingerprint, generatorVersion, null);
    }

    /** 같은 생성 시도가 못 만들었을 때만 실패를 남긴다. */
    int markFailed(@Param("projectId") String projectId,
                   @Param("systemCode") String systemCode,
                   @Param("screenId") String screenId,
                   @Param("generationId") String generationId,
                   @Param("reason") String reason);

    Optional<UserManual> selectOne(@Param("projectId") String projectId,
                                   @Param("systemCode") String systemCode,
                                   @Param("screenId") String screenId);

    /** 마지막 정상본의 대표 화면 캡처 포인터. 캡처가 없는 기존 정상본은 빈 값이다. */
    Optional<UserManualCapture> selectCapture(@Param("projectId") String projectId,
                                              @Param("systemCode") String systemCode,
                                              @Param("screenId") String screenId);

    /** 본문과 캡처를 한 문장으로 읽어 서로 다른 생성 세대가 섞이지 않게 한다. */
    Optional<UserManualArtifact> selectArtifact(@Param("projectId") String projectId,
                                                @Param("systemCode") String systemCode,
                                                @Param("screenId") String screenId);

    /** 프로젝트 ZIP에 넣을 정상본과 캡처를 한 문장으로 읽는다. */
    List<UserManualArtifact> selectArtifactsByProject(@Param("projectId") String projectId);

    /**
     * 이 프로젝트의 매뉴얼 전부.
     *
     * <p>⭐ <b>목록이 화면마다 묻지 않고 이것 한 번으로 짝을 맞춘다</b> — 640장이면 640번 묻게 된다.
     */
    List<UserManual> selectByProject(String projectId);
}
