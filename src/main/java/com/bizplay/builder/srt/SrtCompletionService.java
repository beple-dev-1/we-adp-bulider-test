package com.bizplay.builder.srt;

import com.bizplay.builder.devrequest.DevRequestPreparation;
import com.bizplay.builder.frd.FrdCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SRT의 최소 검증을 마친 뒤 FRD와 같은 개발요청서 준비 흐름으로 넘긴다.
 *
 * <p>⭐ <b>생성은 준비(TC)까지 기다린다</b> (2026-09-24 사용자 확정). 기획자는 SRT 레이어의 「개발요청서 준비 중」에서
 * 기다리고, 준비를 못 마치면 요청서를 거두고 SRT 를 생성 전으로 되돌려 까닭을 보인다.
 */
@Service
public class SrtCompletionService {

    private static final Logger log = LoggerFactory.getLogger(SrtCompletionService.class);
    static final String PREPARING = "개발요청서를 준비하고 있습니다. 테스트 시나리오를 만드는 데 몇 분 걸립니다.";

    private final SrtService srts;
    private final FrdCompletionService frdCompletion;
    private final TaskExecutor aiExecutor;
    /** ⚠ 없으면(단위 시험) 준비를 기다리지 않는다 — 종전 동작. */
    private final DevRequestPreparation preparation;
    private final Map<String, Status> progress = new ConcurrentHashMap<>();

    @Autowired
    public SrtCompletionService(SrtService srts, FrdCompletionService frdCompletion,
                                @Qualifier("aiExecutor") TaskExecutor aiExecutor,
                                DevRequestPreparation preparation) {
        this.srts = srts;
        this.frdCompletion = frdCompletion;
        this.aiExecutor = aiExecutor;
        this.preparation = preparation;
    }

    public SrtCompletionService(SrtService srts, FrdCompletionService frdCompletion, TaskExecutor aiExecutor) {
        this(srts, frdCompletion, aiExecutor, null);
    }

    /** 요청 스레드에서는 상태만 세우고 실제 Claude 실행은 AI 실행기로 넘긴다. */
    public synchronized Status request(String projectId, String srtId) {
        Srt target = srts.analysisTarget(projectId, srtId);
        Status current = progress.get(srtId);
        if (current != null && (current.state() == State.ANALYZING || current.state() == State.COMPLETE)) {
            return status(projectId, srtId);
        }
        if (current != null && current.state() == State.FAILED
                && target.devRequestId() != null && !target.devRequestId().isBlank()) {
            return current;
        }
        if (target.devRequestId() != null && !target.devRequestId().isBlank()) {
            return status(projectId, srtId);
        }
        // ⭐ 확인은 생성 전에 끝난다 — 사람이 풀 막음은 요청 자리에서 까닭과 함께 돌려준다.
        srts.requireConfirmed(projectId, srtId);
        Status running = new Status(State.ANALYZING, "AI가 SRT 원문을 분석하고 있습니다.", null);
        progress.put(srtId, running);
        try {
            aiExecutor.execute(() -> completeNow(projectId, target));
        } catch (TaskRejectedException full) {
            Status failed = new Status(State.FAILED,
                    "AI 작업이 많아 분석을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
            progress.put(srtId, failed);
            return failed;
        }
        return running;
    }

    public Status status(String projectId, String srtId) {
        Status current = progress.get(srtId);
        if (current != null && current.state() == State.ANALYZING && current.requestId() == null) {
            return current;   // AI 가 아직 원문을 정리하는 중 — 요청서가 서기 전
        }
        if (current != null && current.state() != State.ANALYZING) {
            if (current.state() == State.COMPLETE) progress.remove(srtId, current);
            return current;
        }
        String requestId = current != null ? current.requestId() : null;
        if (requestId == null) {
            Srt target = srts.analysisTarget(projectId, srtId);
            if (target.devRequestId() == null || target.devRequestId().isBlank()) {
                return new Status(State.READY, null, null);
            }
            requestId = target.devRequestId();
        }
        return prepared(srtId, projectId, requestId);
    }

    /** 요청서가 섰으면 준비를 잰다 — 끝났으면 COMPLETE, 못 마쳐 거뒀으면 FAILED. */
    private Status prepared(String srtId, String projectId, String requestId) {
        if (preparation == null) return Status.complete(requestId);
        DevRequestPreparation.Status settled = preparation.settle(projectId, requestId);
        Status next = switch (settled.state()) {
            case READY, NONE -> Status.complete(requestId);
            case PREPARING -> new Status(State.ANALYZING, PREPARING, requestId);
            case FAILED -> new Status(State.FAILED, settled.message(), null);
        };
        if (next.state() == State.COMPLETE) {
            progress.remove(srtId);
        } else {
            progress.put(srtId, next);
        }
        return next;
    }

    private void completeNow(String projectId, Srt target) {
        try {
            SrtAiAnalysis analysis = srts.storedAnalysis(projectId, target.id());
            Srt prepared = srts.prepareDevelopmentRequest(
                    projectId, target.id(), target.title(), target.content(), analysis);
            if (prepared.devRequestId() == null || prepared.devRequestId().isBlank()) {
                throw new IllegalStateException("SRT의 개발요청서를 확인하지 못했습니다.");
            }
            frdCompletion.prepareDevelopmentRequest(projectId, prepared.devRequestId());
            progress.put(target.id(), preparation == null
                    ? Status.complete(prepared.devRequestId())
                    : new Status(State.ANALYZING, PREPARING, prepared.devRequestId()));
        } catch (SrtService.ConfirmationRequired rejected) {
            // ⭐ 사람이 풀 수 있는 까닭(고칠 화면을 안 골랐다)만 그대로 보여 준다. 내부 까닭은 아래에서 감춘다.
            log.info("SRT 개발요청서를 만들지 않았다 projectId={} srtId={} {}", projectId, target.id(),
                    rejected.getMessage());
            progress.put(target.id(), new Status(State.FAILED, rejected.getMessage(), null));
        } catch (RuntimeException failure) {
            log.warn("SRT 개발요청서 준비가 실패했다 projectId={} srtId={}", projectId, target.id(), failure);
            progress.put(target.id(), new Status(State.FAILED,
                    "개발요청서를 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.", null));
        }
    }

    public enum State { READY, ANALYZING, REJECTED, FAILED, COMPLETE }

    public record Status(State state, String message, String requestId) {
        static Status complete(String requestId) {
            return new Status(State.COMPLETE, null, requestId);
        }
    }
}
