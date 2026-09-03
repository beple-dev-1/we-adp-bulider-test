package com.bizplay.builder.devrequest;

import com.bizplay.builder.checker.PlanningRepoCheckCache;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdWorkspace;
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import com.bizplay.builder.project.ProjectPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 전송 전 확인은 테스트 시나리오를 <b>읽기만 한다 — 청하지 않는다</b> (병주 지시 2026-08-27).
 *
 * <p>⭐ <b>실물이 낳은 시험이다.</b> 청하는 자리가 상세 화면 렌더 경로
 * ({@code DevelopmentRequestService#precheck}, {@code readOnly = true})에 있었다. 거기서 만들기가
 * 돌면 읽기 전용 트랜잭션 안에서 스냅샷 UPDATE 가 나가 PostgreSQL 이 그 트랜잭션을 통째로 중단시켰고,
 * <b>FRD 작업 완료의 도착 화면이 500 으로 죽어</b> 「완료는 됐는데 개발요청서가 안 만들어졌다」로 보였다.
 *
 * <p>⛔ <b>읽기라고 선언한 자리에서 부작용을 걸지 마라.</b> 청하는 자리는 FRD 완료 하나다
 * ({@code FrdCompletionService}) — 「변경 예정 기능정의서」와 같은 자리이고 같은 이유다.
 */
class DevRequestPrecheckTestScenarioTest {

    private static final String PROJECT = "0000001";
    private static final String REQUEST = "0000003";
    private static final String FRD = "0000036";

    private DevRequestTestScenarioWorker testScenarios;
    private DevRequestPrecheck precheck;

    @BeforeEach
    void setUp() {
        testScenarios = mock(DevRequestTestScenarioWorker.class);
        FrdWorkspace workspaces = mock(FrdWorkspace.class);
        given(workspaces.hasChanges(anyString(), anyString())).willReturn(false);
        precheck = new DevRequestPrecheck(mock(FrdScreenMapper.class),
                mock(FrdScreenHistoryMapper.class), workspaces,
                mock(PlanningRepoCheckCache.class), mock(ProjectPaths.class),
                mock(ScreenTobeDocumentWorker.class), testScenarios);
    }

    @Test
    void 상세를_열어도_테스트_시나리오_만들기를_청하지_않는다() {
        precheck.check(view());

        verify(testScenarios, never()).requestIfMissing(any(), any());
        verify(testScenarios, never()).generate(anyString());
        verify(testScenarios, never()).markRequested(anyString());
    }

    @Test
    void 만드는_중이면_경고로_알려_준다() {
        given(testScenarios.isGenerating(REQUEST)).willReturn(true);

        DevRequestPrecheck.Result result = precheck.check(view());

        assertThat(result.warnings()).extracting(DevRequestPrecheck.Item::message)
                .contains("테스트 시나리오를 만들고 있습니다.");
        assertThat(result.blocking()).extracting(DevRequestPrecheck.Item::message)
                .as("차단이 아니다 — 빈 양식으로도 계약은 성립한다")
                .doesNotContain("테스트 시나리오를 만들고 있습니다.");
    }

    @Test
    void 만들지_못했으면_경고로_알려_준다() {
        given(testScenarios.hasFailed(REQUEST)).willReturn(true);

        DevRequestPrecheck.Result result = precheck.check(view());

        assertThat(result.warnings()).extracting(DevRequestPrecheck.Item::message)
                .contains("테스트 시나리오를 만들지 못했습니다.");
    }

    private static DevelopmentRequestService.View view() {
        return new DevelopmentRequestService.View(request(), content(), "담당",
                Map.of(), Map.of(), Map.of(), false);
    }

    private static DevelopmentRequestContent content() {
        return new DevelopmentRequestContent("요약", null,
                List.of(new DevelopmentRequestContent.Requirement(1, "문구를 고친다", null, null, null)),
                List.of(), List.of(),
                List.of(new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION", "문구가 바뀐다")),
                List.of());
    }

    private static DevelopmentRequest request() {
        return new DevelopmentRequest(REQUEST, PROJECT, 3, FRD, 36, "제목", "webview", null,
                "{}", DevelopmentRequest.DeliveryState.NOT_SENT, null, null, null, null, null, null,
                null, null, null, null, Instant.now(), Instant.now());
    }
}
