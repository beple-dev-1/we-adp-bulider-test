package com.bizplay.builder.devrequest;

import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 개발요청서 준비 — 완료는 기능정의서와 TC 까지 기다린다 (2026-09-24 사용자 확정).
 * 끝나면 목록에 올리고, 못 마치면 가리키는 연결부터 풀고 FRD 로 되돌린다.
 */
class DevRequestPreparationTest {

    private static final String PROJECT = "0000001";
    private static final String REQUEST = "0000009";

    private DevelopmentRequestMapper requests;
    private DevelopmentRequestService service;
    private DevRequestTestScenarioWorker testScenarios;
    private DevRequestLink link;
    private DevRequestPreparation preparation;

    @BeforeEach
    void setUp() {
        requests = mock(DevelopmentRequestMapper.class);
        service = mock(DevelopmentRequestService.class);
        testScenarios = mock(DevRequestTestScenarioWorker.class);
        link = mock(DevRequestLink.class);
        preparation = new DevRequestPreparation(requests, service, mock(ScreenTobeDocumentWorker.class),
                mock(FrdScreenHistoryMapper.class), testScenarios, List.of(link));
        when(requests.isPreparing(REQUEST)).thenReturn(true);
    }

    @Test
    void 만드는_중이면_기다린다() {
        DevelopmentRequestService.View view = view(withCriterion(false));
        when(service.read(PROJECT, REQUEST)).thenReturn(view);
        when(testScenarios.isGenerating(REQUEST)).thenReturn(true);

        assertThat(preparation.settle(PROJECT, REQUEST).state()).isEqualTo(DevRequestPreparation.State.PREPARING);
        verify(requests, never()).markPrepared(REQUEST);
    }

    @Test
    void TC가_붙으면_목록에_올린다() {
        DevelopmentRequestService.View view = view(withCriterion(true));
        when(service.read(PROJECT, REQUEST)).thenReturn(view);

        DevRequestPreparation.Status status = preparation.settle(PROJECT, REQUEST);

        assertThat(status.state()).isEqualTo(DevRequestPreparation.State.READY);
        assertThat(status.requestId()).isEqualTo(REQUEST);
        verify(requests).markPrepared(REQUEST);
        verify(service, never()).returnToFrd(PROJECT, REQUEST);
    }

    /** ⛔ 연결(SRT)을 먼저 풀어야 요청서를 지울 수 있다 — FK 가 걸려 있다. */
    @Test
    void 만들기가_멈췄는데_TC가_없으면_연결을_풀고_FRD로_되돌린다() {
        DevelopmentRequestService.View view = view(withCriterion(false));
        when(service.read(PROJECT, REQUEST)).thenReturn(view);

        DevRequestPreparation.Status status = preparation.settle(PROJECT, REQUEST);

        assertThat(status.state()).isEqualTo(DevRequestPreparation.State.FAILED);
        assertThat(status.message()).isEqualTo(DevRequestPreparation.TEST_SCENARIO_FAILED);
        var order = inOrder(link, service);
        order.verify(link).release(REQUEST);
        order.verify(service).returnToFrd(PROJECT, REQUEST);
        assertThat(preparation.settleFrd(PROJECT, "0000002").message())
                .isEqualTo(DevRequestPreparation.TEST_SCENARIO_FAILED);
    }

    @Test
    void 완료_조건도_화면_외_구현도_없으면_TC_없이_올린다() {
        DevelopmentRequestService.View view = view(new DevelopmentRequestContent(
                "요약", null, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(service.read(PROJECT, REQUEST)).thenReturn(view);

        assertThat(preparation.settle(PROJECT, REQUEST).state()).isEqualTo(DevRequestPreparation.State.READY);
    }

    private static DevelopmentRequestContent withCriterion(boolean scenarios) {
        return new DevelopmentRequestContent("요약", null, List.of(), List.of(), List.of(),
                List.of(new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION", "저장된다")),
                scenarios ? List.of(new DevelopmentRequestContent.TestScenario("INTEGRATION", 1, "IT-01",
                        "저장된다", null, "작성 중이다", "저장을 누른다", "저장된다")) : List.of());
    }

    private static DevelopmentRequestService.View view(DevelopmentRequestContent content) {
        DevelopmentRequest request = mock(DevelopmentRequest.class);
        when(request.id()).thenReturn(REQUEST);
        when(request.frdId()).thenReturn("0000002");
        return new DevelopmentRequestService.View(request, content, null, java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), false, null);
    }
}
