package com.bizplay.builder.devrequest;

import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.frd.FrdMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DevRequestTestScenarioWorkerAsyncTest {

    @Test
    void 상세_조회에서_요청해도_AI와_DB_작업은_별도_실행기로_넘긴다() {
        DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
        ClaudeCredentialRunner credentialRunner = mock(ClaudeCredentialRunner.class);
        TaskExecutor aiExecutor = mock(TaskExecutor.class);
        BuilderProperties properties = mock(BuilderProperties.class);
        given(properties.aiRunTimeout()).willReturn(Duration.ofMinutes(10));
        DevRequestTestScenarioWorker worker = new DevRequestTestScenarioWorker(
                requests,
                mock(FrdMapper.class),
                mock(DevRequestTestScenarioReader.class),
                credentialRunner,
                properties,
                mock(ObjectMapper.class),
                aiExecutor);
        DevelopmentRequest request = mock(DevelopmentRequest.class);
        DevelopmentRequestContent content = mock(DevelopmentRequestContent.class);
        given(request.id()).willReturn("0000032");
        given(content.hasTestScenarios()).willReturn(false);
        given(content.acceptanceCriteria()).willReturn(List.of(
                new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION", "완료 조건")));
        given(content.requiredChanges()).willReturn(List.of());

        assertThat(worker.requestIfMissing(request, content)).isTrue();

        verify(aiExecutor).execute(any(Runnable.class));
        verifyNoInteractions(requests, credentialRunner);
        assertThat(worker.isGenerating(request.id())).isTrue();
    }

    /**
     * ⛔ <b>대기줄이 차면 「만드는 중」으로 남겨 두지 마라.</b> {@code aiExecutor} 는 자리가 없으면
     * 제출을 거절하고 던진다({@code AbortPolicy}). 그 예외가 그대로 올라가면 <b>부른 화면이 죽고</b>,
     * 진행 상태는 {@code REQUESTED} 로 남아 실행 제한시간의 두 배 동안 <b>돌지도 않는 일을
     * 「만들고 있습니다」로 보여 준다.</b> {@code AiRunService#submitWorker} 와 같은 처리다.
     */
    @Test
    void 대기줄이_차서_거절되면_실패로_닫고_던지지_않는다() {
        TaskExecutor aiExecutor = mock(TaskExecutor.class);
        BuilderProperties properties = mock(BuilderProperties.class);
        given(properties.aiRunTimeout()).willReturn(Duration.ofMinutes(10));
        willThrow(new TaskRejectedException("대기줄이 찼다"))
                .given(aiExecutor).execute(any(Runnable.class));
        DevRequestTestScenarioWorker worker = new DevRequestTestScenarioWorker(
                mock(DevelopmentRequestMapper.class),
                mock(FrdMapper.class),
                mock(DevRequestTestScenarioReader.class),
                mock(ClaudeCredentialRunner.class),
                properties,
                mock(ObjectMapper.class),
                aiExecutor);
        worker.markRequested("0000032");

        worker.generate("0000032");

        assertThat(worker.isGenerating("0000032")).as("돌지도 않는 일을 만드는 중으로 보이면 안 된다").isFalse();
        assertThat(worker.hasFailed("0000032")).isTrue();
    }
}
