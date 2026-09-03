package com.bizplay.builder.srt;

import com.bizplay.builder.frd.FrdCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SRT의 최소 검증을 마친 뒤 FRD와 같은 개발요청서 준비 흐름으로 넘긴다. */
@Service
public class SrtCompletionService {

    private static final Logger log = LoggerFactory.getLogger(SrtCompletionService.class);
    private final SrtService srts;
    private final FrdCompletionService frdCompletion;
    private final TaskExecutor aiExecutor;
    private final Map<String, Status> progress = new ConcurrentHashMap<>();

    public SrtCompletionService(SrtService srts, FrdCompletionService frdCompletion,
                                @Qualifier("aiExecutor") TaskExecutor aiExecutor) {
        this.srts = srts;
        this.frdCompletion = frdCompletion;
        this.aiExecutor = aiExecutor;
    }

    /** 요청 스레드에서는 상태만 세우고 실제 Claude 실행은 AI 실행기로 넘긴다. */
    public synchronized Status request(String projectId, String srtId) {
        Srt target = srts.analysisTarget(projectId, srtId);
        Status current = progress.get(srtId);
        if (current != null && (current.state() == State.ANALYZING || current.state() == State.COMPLETE)) {
            return current;
        }
        if (current != null && current.state() == State.FAILED
                && target.devRequestId() != null && !target.devRequestId().isBlank()) {
            return current;
        }
        if (target.devRequestId() != null && !target.devRequestId().isBlank()) {
            return Status.complete(target.devRequestId());
        }
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
        if (current != null) {
            if (current.state() == State.COMPLETE) progress.remove(srtId, current);
            return current;
        }
        Srt target = srts.analysisTarget(projectId, srtId);
        if (target.devRequestId() != null && !target.devRequestId().isBlank()) {
            return Status.complete(target.devRequestId());
        }
        return new Status(State.READY, null, null);
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
            progress.put(target.id(), Status.complete(prepared.devRequestId()));
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
