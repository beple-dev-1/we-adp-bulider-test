package com.bizplay.builder.srt;

import com.bizplay.builder.frd.FrdCompletionService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SrtCompletionServiceTest {

    @Test
    void SRT도_개발요청서를_만든_뒤_FRD와_같은_AI_준비를_건다() {
        SrtService srts = mock(SrtService.class);
        FrdCompletionService frdCompletion = mock(FrdCompletionService.class);
        Srt target = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "제목", "원문", null, "account-1", "frd-1", null,
                Srt.AnalysisState.COMPLETE, null, null, null);
        Srt prepared = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "제목", "내용", null, "account-1", "frd-1", "request-1",
                Srt.AnalysisState.COMPLETE, null, null, null);
        SrtAiAnalysis analysis = new SrtAiAnalysis(true, null, "정리된 변경 요청입니다.",
                java.util.List.of("정리된 요구사항"), java.util.List.of("완료 조건"));
        when(srts.analysisTarget("project-1", "srt-1")).thenReturn(target);
        when(srts.storedAnalysis("project-1", "srt-1")).thenReturn(analysis);
        when(srts.prepareDevelopmentRequest("project-1", "srt-1", "제목", "원문", analysis))
                .thenReturn(prepared);
        SrtCompletionService completion = new SrtCompletionService(
                srts, frdCompletion, Runnable::run);

        SrtCompletionService.Status status = completion.request("project-1", "srt-1");

        assertThat(status.state()).isEqualTo(SrtCompletionService.State.ANALYZING);
        assertThat(completion.status("project-1", "srt-1").requestId()).isEqualTo("request-1");
        InOrder order = inOrder(srts, frdCompletion);
        order.verify(srts).analysisTarget("project-1", "srt-1");
        order.verify(srts).storedAnalysis("project-1", "srt-1");
        order.verify(srts).prepareDevelopmentRequest("project-1", "srt-1", "제목", "원문", analysis);
        order.verify(frdCompletion).prepareDevelopmentRequest("project-1", "request-1");
    }

    @Test
    void 저장된_AI_분석을_확인하지_못하면_개발요청서를_만들지_않는다() {
        SrtService srts = mock(SrtService.class);
        FrdCompletionService frdCompletion = mock(FrdCompletionService.class);
        Srt target = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "안부", "오늘 점심 뭐 먹지", null, "account-1", "frd-1", null,
                Srt.AnalysisState.FAILED, "분석 실패", null, null);
        when(srts.analysisTarget("project-1", "srt-1")).thenReturn(target);
        when(srts.storedAnalysis("project-1", "srt-1"))
                .thenThrow(new IllegalStateException("SRT 분석이 끝나지 않았습니다."));
        SrtCompletionService completion = new SrtCompletionService(
                srts, frdCompletion, Runnable::run);

        completion.request("project-1", "srt-1");
        SrtCompletionService.Status status = completion.status("project-1", "srt-1");

        assertThat(status.state()).isEqualTo(SrtCompletionService.State.FAILED);
        assertThat(status.message()).contains("개발요청서를 준비하지 못했습니다").contains("다시 시도")
                .doesNotContain("SRT 분석이 끝나지 않았습니다");

        org.mockito.Mockito.verify(srts, org.mockito.Mockito.never())
                .prepareDevelopmentRequest(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(frdCompletion);
    }

    @Test
    void 개발요청서_번호가_생겨도_FRD_준비가_끝나기_전에는_분석_중이다() {
        SrtService srts = mock(SrtService.class);
        FrdCompletionService frdCompletion = mock(FrdCompletionService.class);
        Srt target = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "제목", "원문", null, "account-1", "frd-1", null,
                Srt.AnalysisState.COMPLETE, null, null, null);
        Srt requestCreated = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "제목", "원문", null, "account-1", "frd-1", "request-1",
                Srt.AnalysisState.COMPLETE, null, null, null);
        when(srts.analysisTarget("project-1", "srt-1")).thenReturn(target, requestCreated, requestCreated);
        SrtCompletionService completion = new SrtCompletionService(
                srts, frdCompletion, command -> { });

        completion.request("project-1", "srt-1");
        SrtCompletionService.Status status = completion.status("project-1", "srt-1");

        assertThat(status.state()).isEqualTo(SrtCompletionService.State.ANALYZING);
        assertThat(status.requestId()).isNull();
        assertThat(completion.request("project-1", "srt-1").state())
                .isEqualTo(SrtCompletionService.State.ANALYZING);
    }
}
