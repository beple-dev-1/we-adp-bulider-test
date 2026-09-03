package com.bizplay.builder.srt;

import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.devrequest.DevelopmentRequestMapper;
import com.bizplay.builder.devrequest.DevelopmentRequestService;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdFacet;
import com.bizplay.builder.frd.FrdFacetMapper;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.FlowPostGateway;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SrtServiceTest {

    @Test
    void 화면_상태는_네_가지로_통합한다() {
        assertThat(srt(Srt.AnalysisState.READY, null).stateLabel()).isEqualTo("분석 중");
        assertThat(srt(Srt.AnalysisState.ANALYZING, null).stateLabel()).isEqualTo("분석 중");
        assertThat(srt(Srt.AnalysisState.COMPLETE, null).stateLabel()).isEqualTo("생성 대기");
        assertThat(srt(Srt.AnalysisState.REJECTED, null).stateLabel()).isEqualTo("확인 필요");
        assertThat(srt(Srt.AnalysisState.FAILED, null).stateLabel()).isEqualTo("확인 필요");
        assertThat(srt(Srt.AnalysisState.COMPLETE, "0000106").stateLabel()).isEqualTo("완료");
    }

    @Test
    void 개발요청서_전송_상태는_SRT_상태를_덮어쓰지_않는다() {
        com.bizplay.builder.devrequest.DevelopmentRequest request =
                mock(com.bizplay.builder.devrequest.DevelopmentRequest.class);
        SrtService.Row row = new SrtService.Row(
                srt(Srt.AnalysisState.COMPLETE, "0000106"), "이영희", request);

        assertThat(row.stateLabel()).isEqualTo("완료");
        assertThat(row.stateClass()).isEqualTo("status-badge--complete");
    }

    @Test
    void 플로우로_등록한_SRT는_수정할_수_없다() {
        SrtMapper srts = mock(SrtMapper.class);
        Srt flowSrt = new Srt("0000101", "0000001", 3, Srt.SourceKind.FLOW,
                "757019", "플로우 제목", "플로우 원문", "{}", "0000007", "0000102", null,
                Srt.AnalysisState.COMPLETE, null, null, null);
        when(srts.selectByIdForUpdate("0000101")).thenReturn(flowSrt);
        SrtService service = new SrtService(srts, mock(FrdMapper.class), mock(FrdItemMapper.class),
                mock(FrdAnalysisNoteMapper.class), mock(DevelopmentRequestMapper.class),
                mock(DevelopmentRequestService.class), mock(FlowPostGateway.class),
                mock(AccountMapper.class), mock(IdSequence.class), new ObjectMapper());

        assertThatThrownBy(() -> service.update("0000001", "0000101", "수정 제목", "수정 내용"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("플로우로 등록한 SRT는 수정할 수 없습니다.");
        verify(srts, never()).updateDirect(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 플로우_등록은_첨부파일만_보존하고_댓글은_제외한다() throws Exception {
        SrtMapper srts = mock(SrtMapper.class);
        FrdMapper frds = mock(FrdMapper.class);
        FlowPostGateway flow = mock(FlowPostGateway.class);
        IdSequence ids = mock(IdSequence.class);
        ObjectMapper json = new ObjectMapper();
        when(flow.getByTaskNumber("757019")).thenReturn(new com.bizplay.builder.intake.FlowPost(
                "40001", "플로우 제목", "플로우 원문", "https://flow.example/post/40001", "프로젝트",
                java.util.List.of(new com.bizplay.builder.intake.FlowPost.Attachment(
                        null, 3828925L, "https://files.example/attachment", null)),
                java.util.List.of(new com.bizplay.builder.intake.FlowPost.Remark(
                        "r-1", "작성자", "user-1", "댓글", "20260901120000", null, 0, false))));
        when(ids.next(IdSequence.Kind.SRT)).thenReturn("0000101");
        when(ids.next(IdSequence.Kind.FRD)).thenReturn("0000102");
        when(ids.next(IdSequence.Kind.FRD_ITEM)).thenReturn("0000103");
        when(srts.allocateNumber("0000001")).thenReturn(12);
        when(frds.allocateNumber("0000001")).thenReturn(20);
        when(srts.connectBridge("0000101", "0000102")).thenReturn(1);
        when(srts.selectById("0000101")).thenReturn(new Srt("0000101", "0000001", 12,
                Srt.SourceKind.FLOW, "757019", "플로우 제목", "플로우 원문", null,
                "0000007", "0000102", null, Srt.AnalysisState.READY, null, null, null));
        SrtService service = new SrtService(srts, frds, mock(FrdItemMapper.class),
                mock(FrdAnalysisNoteMapper.class), mock(DevelopmentRequestMapper.class),
                mock(DevelopmentRequestService.class), flow, mock(AccountMapper.class), ids, json);

        service.registerFlow("0000001", "757019", "0000007");

        ArgumentCaptor<Srt> saved = ArgumentCaptor.forClass(Srt.class);
        verify(srts).insert(saved.capture());
        com.bizplay.builder.intake.FlowPost source =
                json.readValue(saved.getValue().sourceJson(), com.bizplay.builder.intake.FlowPost.class);
        assertThat(source.attachments()).hasSize(1);
        assertThat(source.remarks()).isEmpty();
        assertThat(new SourceAttachment(null, "https://files.example/attachment", 3828925L)
                .displayName(1)).isEqualTo("첨부파일 1");
    }

    @Test
    void 직접_입력은_제목과_내용을_저장하지만_개발요청서는_아직_만들지_않는다() {
        SrtMapper srts = mock(SrtMapper.class);
        FrdMapper frds = mock(FrdMapper.class);
        FrdItemMapper items = mock(FrdItemMapper.class);
        FrdAnalysisNoteMapper notes = mock(FrdAnalysisNoteMapper.class);
        DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
        DevelopmentRequestService developmentRequests = mock(DevelopmentRequestService.class);
        ProjectFacetMapper projectFacets = mock(ProjectFacetMapper.class);
        FrdFacetMapper frdFacets = mock(FrdFacetMapper.class);
        IdSequence ids = mock(IdSequence.class);
        when(ids.next(IdSequence.Kind.SRT)).thenReturn("0000101");
        when(ids.next(IdSequence.Kind.FRD)).thenReturn("0000102");
        when(ids.next(IdSequence.Kind.FRD_ITEM)).thenReturn("0000103");
        when(srts.allocateNumber("0000001")).thenReturn(3);
        when(frds.allocateNumber("0000001")).thenReturn(9);
        when(srts.connectBridge("0000101", "0000102")).thenReturn(1);
        when(projectFacets.selectByProjectId("0000001")).thenReturn(java.util.List.of(
                ProjectFacet.create("0000001", "jeju", "제주"),
                ProjectFacet.create("0000001", "iksan", "익산")));
        when(srts.selectById("0000101")).thenReturn(new Srt("0000101", "0000001", 3,
                Srt.SourceKind.DIRECT, null, "버튼명 변경", "확인을 등록으로 바꾼다.", null,
                "0000007", "0000102", null, Srt.AnalysisState.READY, null, null, null));

        SrtService service = new SrtService(srts, frds, items, notes, requests, developmentRequests,
                mock(FlowPostGateway.class), mock(AccountMapper.class), ids, new ObjectMapper(),
                projectFacets, frdFacets);
        Srt result = service.registerDirect("0000001", " 버튼명 변경 ", " 확인을 등록으로 바꾼다. ",
                "0000007", java.util.List.of("제주"));

        assertThat(result.label()).isEqualTo("SRT-003");
        ArgumentCaptor<Frd> bridge = ArgumentCaptor.forClass(Frd.class);
        verify(frds).insert(bridge.capture());
        assertThat(bridge.getValue().sourceKind()).isEqualTo(Frd.SourceKind.SRT);
        assertThat(bridge.getValue().state()).isEqualTo(Frd.State.SCOPE_REVIEW);
        verify(frdFacets).insert(FrdFacet.create("0000102", "0000001", "제주"));
        verify(srts).connectBridge("0000101", "0000102");
        verify(developmentRequests, never()).createFromConfirmedScope("0000001", "0000102");
    }

    @Test
    void 직접_입력은_제목이_필수다() {
        SrtService service = new SrtService(mock(SrtMapper.class), mock(FrdMapper.class),
                mock(FrdItemMapper.class), mock(FrdAnalysisNoteMapper.class),
                mock(DevelopmentRequestMapper.class), mock(DevelopmentRequestService.class),
                mock(FlowPostGateway.class), mock(AccountMapper.class),
                mock(IdSequence.class), new ObjectMapper());

        assertThatThrownBy(() -> service.registerDirect("0000001", " ", "내용", "0000007"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("제목을 입력해 주세요.");
    }

    @Test
    void AI가_정리한_요구사항과_완료_조건을_저장한_뒤_개발요청서를_만든다() {
        SrtMapper srts = mock(SrtMapper.class);
        FrdMapper frds = mock(FrdMapper.class);
        FrdItemMapper items = mock(FrdItemMapper.class);
        FrdAnalysisNoteMapper notes = mock(FrdAnalysisNoteMapper.class);
        DevelopmentRequestService developmentRequests = mock(DevelopmentRequestService.class);
        IdSequence ids = mock(IdSequence.class);
        Srt target = new Srt("0000101", "0000001", 3, Srt.SourceKind.DIRECT,
                null, "버튼명 변경", "원문", null, "0000007", "0000102", null,
                Srt.AnalysisState.COMPLETE, null, null, null);
        Srt prepared = new Srt("0000101", "0000001", 3, Srt.SourceKind.DIRECT,
                null, "버튼명 변경", "원문", null, "0000007", "0000102", "0000106",
                Srt.AnalysisState.COMPLETE, null, null, null);
        when(srts.selectByIdForUpdate("0000101")).thenReturn(target);
        when(frds.selectById("0000102")).thenReturn(new Frd("0000102", "0000001", 9,
                "버튼명 변경", null, Frd.SourceKind.SRT, "SRT-003", "원문", null,
                null, Frd.State.SCOPE_REVIEW, null, "0000007", null, null, null));
        com.bizplay.builder.devrequest.DevelopmentRequest request =
                mock(com.bizplay.builder.devrequest.DevelopmentRequest.class);
        when(request.id()).thenReturn("0000106");
        when(developmentRequests.createFromConfirmedScope(
                "0000001", "0000102", "버튼 명칭 변경 요청입니다.")).thenReturn(request);
        when(srts.connectRequest("0000101", "0000106")).thenReturn(1);
        when(srts.selectById("0000101")).thenReturn(prepared);
        when(ids.next(IdSequence.Kind.FRD_ITEM)).thenReturn("0000103");
        when(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE)).thenReturn("0000104");
        SrtService service = new SrtService(srts, frds, items, notes,
                mock(DevelopmentRequestMapper.class), developmentRequests,
                mock(FlowPostGateway.class), mock(AccountMapper.class), ids, new ObjectMapper());
        SrtAiAnalysis analysis = new SrtAiAnalysis(true, null, "버튼 명칭 변경 요청입니다.",
                java.util.List.of("버튼 명칭을 등록으로 변경한다."),
                java.util.List.of("등록 버튼이 표시된다."));

        Srt result = service.prepareDevelopmentRequest(
                "0000001", "0000101", "버튼명 변경", "원문", analysis);

        assertThat(result.devRequestId()).isEqualTo("0000106");
        verify(items).deleteByFrdId("0000102");
        verify(items).insert(org.mockito.ArgumentMatchers.argThat(item ->
                item.requirement().equals("버튼 명칭을 등록으로 변경한다.")));
        verify(notes).insert(org.mockito.ArgumentMatchers.argThat(note ->
                note.content().equals("등록 버튼이 표시된다.")));
        verify(developmentRequests).createFromConfirmedScope(
                "0000001", "0000102", "버튼 명칭 변경 요청입니다.");
    }

    private static Srt srt(Srt.AnalysisState state, String devRequestId) {
        return new Srt("0000101", "0000001", 3, Srt.SourceKind.DIRECT,
                null, "버튼명 변경", "원문", null, "0000007", "0000102", devRequestId,
                state, null, null, null);
    }

}
