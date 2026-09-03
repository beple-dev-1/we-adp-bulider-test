package com.bizplay.builder.srt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SrtAnalysisServiceTest {

    @Test
    void 등록된_SRT를_분석하고_결과를_저장한다() {
        SrtService srts = mock(SrtService.class);
        SrtAiAnalyzer analyzer = mock(SrtAiAnalyzer.class);
        Srt ready = srt(Srt.AnalysisState.READY);
        Srt analyzing = srt(Srt.AnalysisState.ANALYZING);
        SrtAiAnalysis result = new SrtAiAnalysis(true, null, "버튼 명칭을 바꾸는 요청입니다.",
                List.of("버튼 명칭을 변경한다."), List.of("변경된 버튼이 표시된다."));
        when(srts.analysisTarget("project-1", "srt-1")).thenReturn(ready);
        when(srts.beginAnalysis("project-1", "srt-1")).thenReturn(analyzing);
        when(analyzer.analyze(analyzing)).thenReturn(result);
        SrtAnalysisService service = new SrtAnalysisService(srts, analyzer, Runnable::run);

        service.request("project-1", "srt-1");

        verify(srts).completeAnalysis("project-1", "srt-1", result);
    }

    @Test
    void 개발_요청으로_성립하지_않으면_검토_필요_사유를_저장한다() {
        SrtService srts = mock(SrtService.class);
        SrtAiAnalyzer analyzer = mock(SrtAiAnalyzer.class);
        Srt ready = srt(Srt.AnalysisState.READY);
        Srt analyzing = srt(Srt.AnalysisState.ANALYZING);
        when(srts.analysisTarget("project-1", "srt-1")).thenReturn(ready);
        when(srts.beginAnalysis("project-1", "srt-1")).thenReturn(analyzing);
        when(analyzer.analyze(analyzing)).thenReturn(new SrtAiAnalysis(
                false, "개발 변경 내용을 확인할 수 없습니다.", null, List.of(), List.of()));
        SrtAnalysisService service = new SrtAnalysisService(srts, analyzer, Runnable::run);

        service.request("project-1", "srt-1");

        verify(srts).finishAnalysis("project-1", "srt-1", Srt.AnalysisState.REJECTED,
                "개발요청서를 생성할 수 없습니다. 개발 변경 내용을 확인할 수 없습니다. 내용을 수정한 뒤 다시 등록해 주세요.");
    }

    private static Srt srt(Srt.AnalysisState state) {
        return new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "버튼명 변경", "확인을 등록으로 바꾼다.", null,
                "account-1", "frd-1", null, state, null, null, null);
    }
}
