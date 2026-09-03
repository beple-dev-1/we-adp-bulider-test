package com.bizplay.builder.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 문서 처리 시도의 데이터 접근.
 * SQL 은 {@code src/main/resources/mapper/intake/DocumentProcessingRunMapper.xml} 에 있다.
 *
 * <p>⛔ <b>{@code delete} 를 만들지 마라.</b> 시도 이력이 남는 것이 이 표의 존재 이유다 —
 * 재시도가 앞의 실패를 지우면 무엇이 왜 안 됐는지 사라진다.
 *
 * <p>⚠ <b>표 이름에 {@code builder.} 를 빠뜨리지 마라.</b> MyBatis 는 {@code default_schema} 를
 * 안 물려받는다 — 빠뜨리면 「표가 없다」로 죽는다.
 */
@Mapper
public interface DocumentProcessingRunMapper {

    void insert(DocumentProcessingRun run);

    /** 돌기 시작했다고 적는다. {@code started_at} 이 이때 찬다. */
    int updateRunning(@Param("runId") String runId, @Param("startedAt") Instant startedAt);

    /**
     * 끝났다고 적는다.
     *
     * <p>⛔ 실패 사유를 화면에 그대로 내지 마라 — 개발자가 보는 원문이다.
     * 부르는 쪽이 {@code GitCommand.mask} 를 지나 넘긴다(자격이 섞여 나올 수 있다).
     */
    int updateFinished(@Param("runId") String runId,
                       @Param("state") DocumentProcessingRun.State state,
                       @Param("errorMessage") String errorMessage,
                       @Param("finishedAt") Instant finishedAt);

    /** 한 문서의 시도를 새것부터. 상세 화면이 「왜 안 됐나」를 보여줄 때 쓴다. */
    List<DocumentProcessingRun> selectByDocumentId(@Param("documentId") String documentId);

    /**
     * 아직 안 끝난 시도 전부.
     *
     * <p>⛔ <b>재기동 청소가 이것을 닫는다</b>({@link DocumentProcessingService#closeStuckRuns}).
     * 안 닫으면 부분 유일 인덱스 {@code ..._one_live} 에 걸려 그 문서가
     * <b>영영 다시 시도할 수 없다.</b>
     */
    List<DocumentProcessingRun> selectLive();
}
