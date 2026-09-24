package com.bizplay.builder.frd;

import com.bizplay.builder.devrequest.DevRequestPreparation;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * FRD 작업 완료 뒤 개발요청서 준비를 기다리는 자리 (목업 05s · 05t).
 *
 * <p>⭐ <b>완료는 준비까지 기다리고, 기획자는 FRD 화면에서 기다린다</b> (2026-09-24 사용자 확정). 준비가 끝나면
 * 개발요청서로 넘어가고, 못 마치면 요청서를 거두고 FRD 를 완료 전으로 되돌린 까닭을 보인다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/frds/{frdId}")
public class FrdPreparationController {

    private final FrdService frds;
    private final DevRequestPreparation preparation;

    public FrdPreparationController(FrdService frds, DevRequestPreparation preparation) {
        this.frds = frds;
        this.preparation = preparation;
    }

    /** 완료 세 길(작업대 · 빠른 진행 · 화면 없는 시작)이 모두 여기로 온다. */
    static String preparingUrl(String projectId, String frdId) {
        return "redirect:/projects/%s/artifacts/frds/%s/preparing".formatted(projectId, frdId);
    }

    @GetMapping("/preparing")
    public String preparing(@PathVariable String projectId, @PathVariable String frdId, Model model) {
        Frd frd = frds.of(projectId, frdId);
        DevRequestPreparation.Status status = preparation.settleFrd(projectId, frdId);
        switch (status.state()) {
            case READY -> {
                return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, status.requestId());
            }
            case NONE -> {
                return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
            }
            default -> { }
        }
        model.addAttribute("title", frd.title());
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", FrdController.ARTIFACT_KEY);
        model.addAttribute("frd", frd);
        model.addAttribute("failed", status.state() == DevRequestPreparation.State.FAILED);
        model.addAttribute("failure", status.message());
        model.addAttribute("statusUrl",
                "/projects/%s/artifacts/frds/%s/preparation-status".formatted(projectId, frdId));
        return "artifacts/frd-preparing";
    }

    @GetMapping("/preparation-status")
    @ResponseBody
    public PreparationStatus status(@PathVariable String projectId, @PathVariable String frdId) {
        frds.of(projectId, frdId);
        DevRequestPreparation.Status status = preparation.settleFrd(projectId, frdId);
        String requestUrl = status.requestId() == null ? null
                : "/projects/%s/artifacts/dev-requests/%s".formatted(projectId, status.requestId());
        return new PreparationStatus(status.state().name(), status.message(), requestUrl);
    }

    public record PreparationStatus(String state, String message, String requestUrl) { }
}
