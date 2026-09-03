package com.bizplay.builder.intake;

import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ReceivedDocument.ContentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 문서 처리의 <b>DB 토막</b>. 프로세스를 띄우거나 밖에 말을 거는 일은 일꾼들이 한다
 * ({@link DocumentProcessingWorker} · {@link RequirementAnalysisWorker}).
 *
 * <p>⛔ <b>여기에 프로세스를 띄우거나 HTTP 를 부르는 코드를 넣지 마라.</b> 트랜잭션을 연 채로
 * 몇 분짜리 일을 하면 커넥션을 그동안 물고 있는다 — {@code AiRunWorker} 주석이 같은 함정을 이미 적어 뒀다.
 * <b>DB 는 짧은 토막으로만 만진다</b>: 재료 읽기 → (바깥일) → 결과 쓰기.
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final IntakeMapper intakes;
    private final ReceivedDocumentMapper documents;
    private final IntakeFacetMapper intakeFacets;
    private final DocumentProcessingRunMapper runs;
    private final IdSequence ids;

    public DocumentProcessingService(IntakeMapper intakes, ReceivedDocumentMapper documents,
                                     IntakeFacetMapper intakeFacets,
                                     DocumentProcessingRunMapper runs, IdSequence ids) {
        this.intakes = intakes;
        this.documents = documents;
        this.intakeFacets = intakeFacets;
        this.runs = runs;
        this.ids = ids;
    }

    /**
     * 일꾼이 돌기 전에 한 번에 떠 가는 재료.
     *
     * @param sourcePath 읽을 파일. 직접 입력만 있으면 {@code null} 이다
     */
    public record Materials(String documentId, String projectId, String accountId,
                            String title, ReceivedDocument.DocumentType documentType,
                            List<String> facets, Path sourcePath, String documentContent,
                            ContentState state) {
    }

    @Transactional(readOnly = true)
    public Materials materials(String intakeId) {
        Intake intake = intakes.selectById(intakeId)
                .orElseThrow(() -> new IllegalStateException("그런 접수가 없다: " + intakeId));
        ReceivedDocument document = documents.selectByIntakeId(intakeId)
                .orElseThrow(() -> new IllegalStateException("받은 문서가 없는 접수다: " + intakeId));
        return new Materials(
                document.id(),
                intake.projectId(),
                intake.uploadedBy(),
                intake.title(),
                document.documentType(),
                intakeFacets.selectByIntakeId(intakeId).stream().map(IntakeFacet::name).toList(),
                document.serverPath() == null ? null : Path.of(document.serverPath()),
                document.documentContent(),
                document.contentState());
    }

    /**
     * 시도 한 줄을 열고 바로 「도는 중」으로 앉힌다. 번호를 돌려준다.
     *
     * <p>⚠ <b>같은 문서·같은 갈래에 살아 있는 시도가 있으면 DB 가 거절한다</b>
     * (부분 유일 인덱스 {@code ..._one_live}). 그 판정을 자바 검사로 옮기지 마라 —
     * 두 탭 경합은 읽고 쓰는 사이가 벌어져 못 막는다.
     */
    @Transactional
    public String openRun(String documentId, DocumentProcessingRun.Kind kind, Instant startedAt) {
        String runId = ids.next(IdSequence.Kind.DOCUMENT_PROCESSING_RUN);
        runs.insert(DocumentProcessingRun.waiting(runId, documentId, kind));
        runs.updateRunning(runId, startedAt);
        return runId;
    }

    /** ⛔ 실패해도 앞의 행을 덮지 않는다 — 이 표는 시도마다 한 줄이다. */
    @Transactional
    public void finishRun(String runId, DocumentProcessingRun.State state,
                          String errorMessage, Instant finishedAt) {
        runs.updateFinished(runId, state, errorMessage, finishedAt);
    }

    @Transactional
    public void markState(String documentId, ContentState state) {
        documents.updateContentState(documentId, state);
    }

    @Transactional
    public void markFailed(String documentId, String reason) {
        documents.updateFailed(documentId, reason);
    }

    /**
     * 멀티모달이 읽어 낸 글을 적는다.
     *
     * 읽어 낸 원문과 요구사항 분석에 쓸 문서 내용을 함께 채우고 등록 완료로 옮긴다.
     */
    @Transactional
    public void saveUnderstood(String documentId, String extracted) {
        documents.updateUnderstood(documentId, extracted);
    }

    /**
     * 서버가 죽었다 살면 <b>안 끝난 시도만</b> 닫는다.
     *
     * <p>⛔ <b>이 청소를 지우지 마라.</b> 2026-08-15 에 「같은 문서·같은 갈래에 살아 있는 시도는
     * 하나」를 <b>부분 유일 인덱스</b>로 옮겼다 — 재기동으로 굳은 {@code RUNNING} 줄이 남으면
     * 그 문서는 <b>영영 다시 시도할 수 없다.</b> 굳은 문서와 접수도 같이 오류로 앉혀
     * 화면에 「다시 시도」가 뜨게 한다.
     *
     * <p>⚠ {@code AiRunService.closeStuckRuns} 와 같은 처방이다 — 새로 정한 것이 아니다.
     */
    /**
     * 아직 줄에 서 있는 문서의 접수 번호.
     * ⚠ {@link DocumentProcessingBootSweep} 이 재기동 때 이것을 다시 데려간다.
     */
    @Transactional(readOnly = true)
    public List<String> queuedIntakeIds() {
        return documents.selectQueuedIntakeIds();
    }

    /**
     * ⚠ <b>{@code @Order} 를 지우지 마라.</b> 굳은 시도를 닫기 <b>전에</b>
     * {@link DocumentProcessingBootSweep} 이 줄 선 문서를 데려가면, 그 문서의 시도가
     * <b>부분 유일 인덱스에 막혀</b> 시작도 못 하고 조용히 돌아간다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    @Transactional
    public void closeStuckRuns() {
        List<DocumentProcessingRun> stuck = runs.selectLive();
        for (DocumentProcessingRun run : stuck) {
            runs.updateFinished(run.id(), DocumentProcessingRun.State.FAILED,
                    "서버가 다시 뜨면서 닫았다 — 이 시도가 실제로 끝났는지는 알 수 없다", Instant.now());
        }
        int documentsFreed = documents.updateStuckProcessingToFailed(
                "서버가 다시 뜨면서 내용 분석이 끊겼습니다 — 다시 시도해 주세요");
        int intakesFreed = intakes.updateStuckRunningToFailed();
        if (!stuck.isEmpty() || documentsFreed > 0 || intakesFreed > 0) {
            log.warn("재기동 청소: 안 끝난 문서 시도 {}건 · 굳은 문서 {}건 · 굳은 요구사항 분석 {}건을 닫았다",
                    stuck.size(), documentsFreed, intakesFreed);
        }
    }
}
