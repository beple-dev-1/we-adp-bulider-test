package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FrdScreenMapper {

    void insert(FrdScreen screen);

    List<FrdScreen> selectByFrdId(String frdId);

    FrdScreen selectById(String id);

    FrdScreen selectIncludingExcludedById(String id);

    void deleteById(String id);

    int excludeById(String id);

    int restoreExcluded(@Param("frdId") String frdId, @Param("screenId") String screenId);

    void updateState(@Param("id") String id, @Param("state") FrdScreen.State state);

    /**
     * AI 가 목업을 만들면서 고른 기준 화면을 적는다 — 신규 화면은 만들어질 때 비어 있다.
     *
     * <p>⚠ 이미 채워져 있으면 아무것도 안 바꾼다({@code where base_screen_id is null}) —
     * 두 번째 목업이 다른 시작점을 골라 화면이 갈리는 것을 막는다.
     */
    void updateBaseScreenId(@Param("id") String id, @Param("baseScreenId") String baseScreenId);

    /** AI가 분석에서 확인한 시스템은 아직 비어 있는 신규 화면에만 채운다. */
    void updateSystemCodeIfMissing(@Param("id") String id, @Param("systemCode") String systemCode);

    /** 재분석할 때마다 화면별 신규·수정 내용을 최신 분석 결과로 바꾼다. */
    void updateScopeChange(@Param("id") String id, @Param("scopeChange") String scopeChange);

    /** 인터뷰에서 찾은 신규 화면을 기존 화면으로 잘못 저장한 옛 데이터를 TMP 화면으로 바로잡는다. */
    int convertDiscoveredToDraft(@Param("id") String id, @Param("screenId") String screenId,
                                 @Param("screenType") String screenType);

    /** 목업이 나왔다. ⚠ 다시 만들면 <b>덮어쓴다</b> — 이번 판에 버전이 없다. */
    void updateGenerated(@Param("id") String id, @Param("html") String html,
                         @Param("changes") String changes, @Param("generatedAt") Instant generatedAt);

    void updateFailed(@Param("id") String id, @Param("failure") String failure);

    /** 작업 초기화 뒤 화면 작업 결과를 지우고 최초 상태로 되돌린다. */
    void resetByFrdId(String frdId);

    int failInterruptedGenerations(@Param("failure") String failure);
}
