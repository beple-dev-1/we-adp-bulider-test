package com.bizplay.builder.devrequest;

import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.ProjectSystemService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.IntStream;

@Controller
@RequestMapping("/projects/{projectId}/artifacts/dev-requests")
public class DevelopmentRequestController {

    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int PAGE_WINDOW = 10;

    private final DevelopmentRequestService requests;
    private final ProjectFacetMapper projectFacets;
    private final ProjectSystemService projectSystems;

    public DevelopmentRequestController(DevelopmentRequestService requests,
                                        ProjectFacetMapper projectFacets,
                                        ProjectSystemService projectSystems) {
        this.requests = requests;
        this.projectFacets = projectFacets;
        this.projectSystems = projectSystems;
    }

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(defaultValue = "") String query,
                       @RequestParam(defaultValue = "") String state,
                       @RequestParam(defaultValue = "") String owner,
                       @RequestParam(defaultValue = "") String system,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       Model model) {
        List<DevelopmentRequestService.Row> all = requests.list(projectId);
        var systemLabels = projectSystems.labels(projectId);
        List<DevelopmentRequestService.Row> matched = all.stream()
                .filter(row -> matchesQuery(row, query))
                .filter(row -> matchesState(row, state))
                .filter(row -> matchesOwner(row, owner))
                .filter(row -> matchesSystem(row, system))
                .toList();
        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);

        model.addAttribute("title", "개발요청서");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", "dev-requests");
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
        model.addAttribute("systemLabels", systemLabels);
        model.addAttribute("query", query);
        model.addAttribute("stateFilter", state);
        model.addAttribute("ownerFilter", owner);
        model.addAttribute("systemFilter", system);
        model.addAttribute("ownerOptions", all.stream().filter(Objects::nonNull)
                .map(DevelopmentRequestService.Row::ownerName)
                .filter(value -> value != null && !value.isBlank())
                .distinct().sorted()
                .map(value -> new FilterOption(value, value)).toList());
        model.addAttribute("systemOptions", all.stream().filter(Objects::nonNull)
                .map(row -> row.request().systemCode())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .map(value -> new FilterOption(value, systemLabels.label(value)))
                .sorted(Comparator.comparing(FilterOption::label)).toList());
        // ⛔ 접수처를 안 쓰는 프로젝트에 「적용 구분」 열을 그리지 않는다 — 언제나 비는 열은 거짓말이다.
        //    FRD 목록이 2026-08-18 리뷰에서 같은 이유로 그 열을 뺐다.
        model.addAttribute("showFacets", !projectFacets.selectByProjectId(projectId).isEmpty());
        return "artifacts/dev-requests";
    }

    private static boolean matchesQuery(DevelopmentRequestService.Row row, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) return true;
        return row != null && (contains(row.request().label(), needle)
                || contains(row.request().title(), needle)
                || contains(row.sourceLabel(), needle));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean matchesState(DevelopmentRequestService.Row row, String selected) {
        return selected == null || selected.isBlank()
                || row != null && selected.equals(row.request().deliveryState().name());
    }

    private static boolean matchesOwner(DevelopmentRequestService.Row row, String selected) {
        return selected == null || selected.isBlank()
                || row != null && selected.equals(row.ownerName());
    }

    private static boolean matchesSystem(DevelopmentRequestService.Row row, String selected) {
        return selected == null || selected.isBlank()
                || row != null && selected.equals(row.request().systemCode());
    }

    record FilterOption(String value, String label) { }

    /** 현재 페이지를 가운데 두고 페이지 번호를 최대 열 개까지 표시한다. */
    private List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    @GetMapping("/{requestId}")
    public String detail(@PathVariable String projectId, @PathVariable String requestId,
                         @RequestParam(name = "query", defaultValue = "") String listQuery,
                         @RequestParam(name = "state", defaultValue = "") String listState,
                         @RequestParam(name = "owner", defaultValue = "") String listOwner,
                         @RequestParam(name = "system", defaultValue = "") String listSystem,
                         @RequestParam(name = "page", defaultValue = "1") int listPage,
                         @RequestParam(name = "pageSize", defaultValue = "10") int listPageSize,
                         Model model) {
        DevelopmentRequestService.View view = requests.read(projectId, requestId);
        model.addAttribute("title", "개발요청서 상세");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", "dev-requests");
        model.addAttribute("view", view);
        model.addAttribute("precheck", requests.precheck(projectId, requestId));
        model.addAttribute("listQuery", listQuery);
        model.addAttribute("listState", listState);
        model.addAttribute("listOwner", listOwner);
        model.addAttribute("listSystem", listSystem);
        model.addAttribute("listPage", listPage);
        model.addAttribute("listPageSize", listPageSize);
        return "artifacts/dev-request";
    }



    /** 상세 화면의 진행 조회 — 검사기를 돌리지 않는다(캐시 조회 + git rev-parse). 끝났을 때만 화면이 다시 읽는다. */
    @GetMapping("/{requestId}/progress")
    @ResponseBody
    public DevelopmentRequestService.Progress progress(@PathVariable String projectId,
                                                       @PathVariable String requestId) {
        return requests.progress(projectId, requestId);
    }

    @PostMapping("/{requestId}/return-to-frd")
    public String returnToFrd(@PathVariable String projectId, @PathVariable String requestId,
                              RedirectAttributes flash) {
        String frdId = requests.read(projectId, requestId).request().frdId();
        try {
            requests.returnToFrd(projectId, requestId);
            flash.addFlashAttribute("message", "개발요청서를 지우고 FRD 작업으로 돌아왔습니다.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
        }
    }
}
