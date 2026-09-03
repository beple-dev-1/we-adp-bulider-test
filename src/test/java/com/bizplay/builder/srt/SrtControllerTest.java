package com.bizplay.builder.srt;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.intake.FlowPostException;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SrtControllerTest {

    private final SrtService srts = mock(SrtService.class);
    private final SrtAnalysisService analysis = mock(SrtAnalysisService.class);
    private final SrtCompletionService completion = mock(SrtCompletionService.class);
    private final SrtController controller = new SrtController(srts, analysis, completion);

    @Test
    void 빈_목록도_SRT_메뉴와_등록_레이어_모델을_내린다() {
        given(srts.list("project-1")).willReturn(List.of());
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.list("project-1", "", "", "", 1, 10,
                "flow", null, model);

        assertThat(view).isEqualTo("artifacts/srts");
        assertThat(model.getAttribute("current")).isEqualTo("srts");
        assertThat(model.getAttribute("registerOpen")).isEqualTo(true);
        assertThat(model.getAttribute("registerSource")).isEqualTo("flow");
        assertThat(model.getAttribute("matchedCount")).isEqualTo(0);
    }

    @Test
    void 플로우_원문을_가져오지_못하면_업무번호를_보존한_등록_레이어로_돌아간다() {
        given(srts.registerFlow("project-1", "757019", "account-1", List.of("제주")))
                .willThrow(new FlowPostException("플로우 원문을 가져오지 못했습니다."));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String redirect = controller.register("project-1", "flow", "", "", "757019", List.of("제주"),
                user(), flash);

        assertThat(redirect).isEqualTo("redirect:/projects/project-1/artifacts/srts?register=flow");
        assertThat(flash.getFlashAttributes().get("registerError"))
                .isEqualTo("플로우 원문을 가져오지 못했습니다.");
        assertThat(flash.getFlashAttributes().get("typedFlowTaskNumber")).isEqualTo("757019");
        assertThat(flash.getFlashAttributes().get("typedFacets")).isEqualTo(List.of("제주"));
    }

    @Test
    void 등록에_성공하면_AI_분석을_시작하고_SRT_상세로_돌아간다() {
        Srt registered = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "제목", "내용", null, "account-1", "frd-1", null,
                Srt.AnalysisState.READY, null, null, null);
        given(srts.registerDirect("project-1", "제목", "내용", "account-1", List.of("제주")))
                .willReturn(registered);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String redirect = controller.register("project-1", "direct", "제목", "내용", "", List.of("제주"),
                user(), flash);

        assertThat(redirect).isEqualTo("redirect:/projects/project-1/artifacts/srts?selected=srt-1");
        assertThat(flash.getFlashAttributes().get("message")).isEqualTo("SRT를 등록했습니다.");
        verify(analysis).request("project-1", "srt-1");
    }

    @Test
    void 상세에서_생성을_누를_때_개발요청서를_준비한다() {
        given(completion.request("project-1", "srt-1")).willReturn(
                new SrtCompletionService.Status(SrtCompletionService.State.ANALYZING,
                        "AI가 분석하고 있습니다.", null));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String redirect = controller.createDevelopmentRequest("project-1", "srt-1", flash);

        verify(completion).request("project-1", "srt-1");
        assertThat(redirect).isEqualTo("redirect:/projects/project-1/artifacts/srts?selected=srt-1");
    }

    @Test
    void AI가_거절하면_사유를_보존한_SRT_상세로_돌아간다() {
        given(completion.request("project-1", "srt-1"))
                .willReturn(new SrtCompletionService.Status(SrtCompletionService.State.FAILED,
                        "AI 작업을 시작하지 못했습니다.", null));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String redirect = controller.createDevelopmentRequest("project-1", "srt-1", flash);

        assertThat(redirect).isEqualTo("redirect:/projects/project-1/artifacts/srts?selected=srt-1");
        assertThat(flash.getFlashAttributes().get("error"))
                .isEqualTo("AI 작업을 시작하지 못했습니다.");
    }

    private static BuilderUser user() {
        return new BuilderUser("account-1", "planner", "기획자", "planner@example.com",
                "password", false, false, true);
    }
}
