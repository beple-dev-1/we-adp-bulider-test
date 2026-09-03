package com.bizplay.builder.srt;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.intake.FlowPostException;
import com.bizplay.builder.intake.ProjectFacetMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.IntStream;

/** 빠른 개발 요청인 SRT의 목록·등록·상세와 개발요청서 생성을 연다. */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/srts")
public class SrtController {

    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int PAGE_WINDOW = 10;

    private final SrtService srts;
    private final SrtAnalysisService analysis;
    private final SrtCompletionService completion;
    private final ProjectFacetMapper projectFacets;

    @Autowired
    public SrtController(SrtService srts, SrtAnalysisService analysis, SrtCompletionService completion,
                         ProjectFacetMapper projectFacets) {
        this.srts = srts;
        this.analysis = analysis;
        this.completion = completion;
        this.projectFacets = projectFacets;
    }

    /** 기존 단위 테스트용 호환 생성자다. */
    public SrtController(SrtService srts, SrtAnalysisService analysis, SrtCompletionService completion) {
        this(srts, analysis, completion, null);
    }

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(defaultValue = "") String query,
                       @RequestParam(defaultValue = "") String state,
                       @RequestParam(defaultValue = "") String owner,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       @RequestParam(required = false) String register,
                       @RequestParam(required = false) String selected,
                       Model model) {
        List<SrtService.Row> all = srts.list(projectId);
        List<SrtService.Row> matched = all.stream()
                .filter(Objects::nonNull)
                .filter(row -> matchesQuery(row, query))
                .filter(row -> matchesState(row, state))
                .filter(row -> matchesOwner(row, owner))
                .toList();
        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);

        model.addAttribute("title", "SRT");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", "srts");
        model.addAttribute("rows", matched.stream()
                .skip((long) (current - 1) * size)
                .limit(size)
                .toList());
        model.addAttribute("totalCount", all.size());
        model.addAttribute("matchedCount", matched.size());
        model.addAttribute("page", current);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(current, pageCount));
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("query", query);
        model.addAttribute("stateFilter", state);
        model.addAttribute("ownerFilter", owner);
        model.addAttribute("stateOptions", all.stream().filter(Objects::nonNull)
                .map(SrtService.Row::stateLabel).distinct().toList());
        model.addAttribute("ownerOptions", all.stream().filter(Objects::nonNull)
                .map(SrtService.Row::authorName).filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList());
        model.addAttribute("registerOpen", register != null);
        model.addAttribute("registerSource", "flow".equals(register) ? "flow" : "direct");
        var availableFacets = projectFacets == null
                ? List.of() : projectFacets.selectByProjectId(projectId);
        model.addAttribute("availableFacets", availableFacets);
        model.addAttribute("showFacets", !availableFacets.isEmpty());
        if (!model.containsAttribute("typedTitle")) model.addAttribute("typedTitle", "");
        if (!model.containsAttribute("typedContent")) model.addAttribute("typedContent", "");
        if (!model.containsAttribute("typedFlowTaskNumber")) model.addAttribute("typedFlowTaskNumber", "");
        if (!model.containsAttribute("typedFacets")) model.addAttribute("typedFacets", List.of("__ALL__"));
        SrtService.Detail detail = selected == null || selected.isBlank() ? null : srts.read(projectId, selected);
        model.addAttribute("detail", detail);
        model.addAttribute("srtAnalysisStatus", detail == null ? null : analysis.status(projectId, selected));
        model.addAttribute("analysisStatus", detail == null ? null : completion.status(projectId, selected));
        return "artifacts/srts";
    }

    @PostMapping(headers = "Accept!=application/json")
    public String register(@PathVariable String projectId,
                           @RequestParam(defaultValue = "direct") String source,
                           @RequestParam(defaultValue = "") String title,
                           @RequestParam(defaultValue = "") String content,
                           @RequestParam(defaultValue = "") String flowTaskNumber,
                           @RequestParam(required = false) List<String> facet,
                           @AuthenticationPrincipal BuilderUser me,
                           RedirectAttributes flash) {
        try {
            Srt registered = registerSrt(projectId, source, title, content, flowTaskNumber, facet, me);
            analysis.request(projectId, registered.id());
            flash.addFlashAttribute("message", "SRT를 등록했습니다.");
            return "redirect:/projects/%s/artifacts/srts?selected=%s".formatted(projectId, registered.id());
        } catch (IllegalArgumentException | IllegalStateException | FlowPostException rejected) {
            flash.addFlashAttribute("registerError", rejected.getMessage());
            flash.addFlashAttribute("typedTitle", title);
            flash.addFlashAttribute("typedContent", content);
            flash.addFlashAttribute("typedFlowTaskNumber", flowTaskNumber);
            flash.addFlashAttribute("typedFacets", facet == null ? List.of("__ALL__") : facet);
            return "redirect:/projects/%s/artifacts/srts?register=%s".formatted(projectId,
                    "flow".equals(source) ? "flow" : "direct");
        }
    }

    @PostMapping(value = "/{srtId}/dev-request", headers = "Accept!=application/json")
    public String createDevelopmentRequest(@PathVariable String projectId,
                                           @PathVariable String srtId,
                                           RedirectAttributes flash) {
        try {
            SrtCompletionService.Status status = completion.request(projectId, srtId);
            if (status.state() == SrtCompletionService.State.COMPLETE) {
                return "redirect:/projects/%s/artifacts/dev-requests/%s"
                        .formatted(projectId, status.requestId());
            }
            if (status.state() == SrtCompletionService.State.FAILED) {
                flash.addFlashAttribute("error", status.message());
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/srts?selected=%s".formatted(projectId, srtId);
    }

    @PostMapping(value = "/{srtId}/dev-request", headers = "Accept=application/json")
    @ResponseBody
    public ResponseEntity<CompletionStatus> createDevelopmentRequestAsync(@PathVariable String projectId,
                                                                           @PathVariable String srtId) {
        try {
            return ResponseEntity.ok(completionStatus(projectId, completion.request(projectId, srtId)));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return ResponseEntity.badRequest()
                    .body(new CompletionStatus(SrtCompletionService.State.FAILED.name(),
                            rejected.getMessage(), null));
        }
    }

    @PostMapping(headers = "Accept=application/json")
    @ResponseBody
    public ResponseEntity<RegistrationStatus> registerAsync(@PathVariable String projectId,
                                                              @RequestParam(defaultValue = "direct") String source,
                                                              @RequestParam(defaultValue = "") String title,
                                                              @RequestParam(defaultValue = "") String content,
                                                              @RequestParam(defaultValue = "") String flowTaskNumber,
                                                              @RequestParam(required = false) List<String> facet,
                                                              @AuthenticationPrincipal BuilderUser me) {
        try {
            Srt registered = registerSrt(projectId, source, title, content, flowTaskNumber, facet, me);
            SrtAnalysisService.Status status = analysis.request(projectId, registered.id());
            return ResponseEntity.ok(registrationStatus(projectId, registered.id(), status));
        } catch (IllegalArgumentException | IllegalStateException | FlowPostException rejected) {
            return ResponseEntity.badRequest().body(new RegistrationStatus(
                    Srt.AnalysisState.FAILED.name(), rejected.getMessage(), null, null));
        }
    }

    @GetMapping("/{srtId}/analysis-status")
    @ResponseBody
    public RegistrationStatus analysisStatus(@PathVariable String projectId,
                                               @PathVariable String srtId) {
        return registrationStatus(projectId, srtId, analysis.status(projectId, srtId));
    }

    @GetMapping("/{srtId}/dev-request-status")
    @ResponseBody
    public CompletionStatus developmentRequestStatus(@PathVariable String projectId,
                                                       @PathVariable String srtId) {
        return completionStatus(projectId, completion.status(projectId, srtId));
    }

    private static CompletionStatus completionStatus(String projectId, SrtCompletionService.Status status) {
        String requestUrl = status.requestId() == null ? null
                : "/projects/%s/artifacts/dev-requests/%s".formatted(projectId, status.requestId());
        return new CompletionStatus(status.state().name(), status.message(), requestUrl);
    }

    @PostMapping("/{srtId}/update")
    public String update(@PathVariable String projectId, @PathVariable String srtId,
                         @RequestParam(defaultValue = "") String title,
                         @RequestParam(defaultValue = "") String content,
                         RedirectAttributes flash) {
        try {
            srts.update(projectId, srtId, title, content);
            analysis.request(projectId, srtId);
            flash.addFlashAttribute("message", "SRT를 수정했습니다.");
        } catch (IllegalArgumentException | IllegalStateException | FlowPostException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/srts?selected=%s".formatted(projectId, srtId);
    }

    @PostMapping("/{srtId}/delete")
    public String delete(@PathVariable String projectId, @PathVariable String srtId,
                         RedirectAttributes flash) {
        try {
            srts.delete(projectId, srtId);
            flash.addFlashAttribute("message", "SRT를 삭제했습니다.");
            return "redirect:/projects/%s/artifacts/srts".formatted(projectId);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/srts?selected=%s".formatted(projectId, srtId);
        }
    }

    private static boolean matchesQuery(SrtService.Row row, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) return true;
        return contains(row.srt().label(), needle)
                || contains(row.srt().title(), needle)
                || contains(row.srt().flowTaskNumber(), needle)
                || row.request() != null && contains(row.request().label(), needle);
    }

    private static boolean matchesState(SrtService.Row row, String selected) {
        return selected == null || selected.isBlank() || selected.equals(row.stateLabel());
    }

    private static boolean matchesOwner(SrtService.Row row, String selected) {
        return selected == null || selected.isBlank() || selected.equals(row.authorName());
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 현재 페이지를 가운데 두고 페이지 번호를 최대 열 개까지 표시한다. */
    private static List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    private Srt registerSrt(String projectId, String source, String title, String content,
                            String flowTaskNumber, List<String> facet, BuilderUser me) {
        return "flow".equals(source)
                ? srts.registerFlow(projectId, flowTaskNumber, me.accountId(), facet)
                : srts.registerDirect(projectId, title, content, me.accountId(), facet);
    }

    private static RegistrationStatus registrationStatus(String projectId, String srtId,
                                                           SrtAnalysisService.Status status) {
        String detailUrl = "/projects/%s/artifacts/srts?selected=%s".formatted(projectId, srtId);
        String statusUrl = "/projects/%s/artifacts/srts/%s/analysis-status".formatted(projectId, srtId);
        return new RegistrationStatus(status.state().name(), status.message(), detailUrl, statusUrl);
    }

    public record CompletionStatus(String state, String message, String requestUrl) { }
    public record RegistrationStatus(String state, String message, String detailUrl, String statusUrl) { }
}
