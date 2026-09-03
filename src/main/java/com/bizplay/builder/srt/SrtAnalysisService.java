package com.bizplay.builder.srt;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** SRT 등록 원문을 비동기로 분석하고 결과를 개발요청서 생성 전까지 보존한다. */
@Service
public class SrtAnalysisService {
    private final SrtService srts;
    private final SrtAiAnalyzer analyzer;
    private final TaskExecutor aiExecutor;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public SrtAnalysisService(SrtService srts, SrtAiAnalyzer analyzer,
                              @Qualifier("aiExecutor") TaskExecutor aiExecutor) {
        this.srts = srts;
        this.analyzer = analyzer;
        this.aiExecutor = aiExecutor;
    }

    public Status request(String projectId, String srtId) {
        Srt target = srts.analysisTarget(projectId, srtId);
        if (target.analysisState() == Srt.AnalysisState.COMPLETE) return statusOf(target);
        if (!running.add(srtId)) return statusOf(target);
        target = srts.beginAnalysis(projectId, srtId);
        Srt submitted = target;
        try {
            aiExecutor.execute(() -> analyzeNow(projectId, submitted));
        } catch (TaskRejectedException full) {
            running.remove(srtId);
            srts.finishAnalysis(projectId, srtId, Srt.AnalysisState.FAILED,
                    "AI 작업이 많아 분석을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            return status(projectId, srtId);
        }
        return statusOf(target);
    }

    public Status status(String projectId, String srtId) {
        Srt target = srts.analysisTarget(projectId, srtId);
        if ((target.analysisState() == Srt.AnalysisState.READY
                || target.analysisState() == Srt.AnalysisState.ANALYZING) && !running.contains(srtId)) {
            return request(projectId, srtId);
        }
        return statusOf(target);
    }

    private void analyzeNow(String projectId, Srt target) {
        try {
            SrtAiAnalysis analysis = analyzer.analyze(target);
            if (!analysis.eligible()) {
                srts.finishAnalysis(projectId, target.id(), Srt.AnalysisState.REJECTED,
                        "개발요청서를 생성할 수 없습니다. " + analysis.rejectionReason()
                                + " 내용을 수정한 뒤 다시 등록해 주세요.");
                return;
            }
            srts.completeAnalysis(projectId, target.id(), analysis);
        } catch (SrtAiAnalyzer.AnalysisException failure) {
            srts.finishAnalysis(projectId, target.id(), Srt.AnalysisState.FAILED,
                    failure.getMessage());
        } catch (RuntimeException failure) {
            srts.finishAnalysis(projectId, target.id(), Srt.AnalysisState.FAILED,
                    "AI가 SRT를 분석하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } finally {
            running.remove(target.id());
        }
    }

    private static Status statusOf(Srt srt) {
        return new Status(srt.analysisState(), srt.analysisMessage());
    }

    public record Status(Srt.AnalysisState state, String message) { }
}
