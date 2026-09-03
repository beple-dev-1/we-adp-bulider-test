package com.bizplay.builder.devrequest;

import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.project.ProjectSystemService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
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
    private final DevelopmentRequestMergeService merges;
    private final ProjectFacetMapper projectFacets;
    private final ProjectSystemService projectSystems;

    public DevelopmentRequestController(DevelopmentRequestService requests,
                                        DevelopmentRequestMergeService merges,
                                        ProjectFacetMapper projectFacets,
                                        ProjectSystemService projectSystems) {
        this.requests = requests;
        this.merges = merges;
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
        model.addAttribute("stateOptions", List.of(DevelopmentRequest.DeliveryState.values()).stream()
                .filter(value -> all.stream().filter(Objects::nonNull)
                        .anyMatch(row -> row.request().deliveryState() == value))
                .map(value -> new FilterOption(value.name(), all.stream()
                        .filter(Objects::nonNull)
                        .filter(row -> row.request().deliveryState() == value)
                        .findFirst().orElseThrow().request().deliveryStateLabel()))
                .toList());
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

    /** 개발 완료 확인과 별개로, 사용자가 명시적으로 선택했을 때만 기본 브랜치에 병합한다. */
    @PostMapping("/{requestId}/merge")
    public String merge(@PathVariable String projectId, @PathVariable String requestId,
                        RedirectAttributes flash) {
        try {
            merges.merge(projectId, requestId);
            flash.addFlashAttribute("message", "개발 결과를 기준본에 반영했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/dev-requests/%s"
                .formatted(projectId, requestId);
    }

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
        model.addAttribute("packageDownloadable", requests.hasStoredPackage(view.request()));
        // ⛔ 후보를 골라 채워 주지 않는다 — 화면이 겹치는 것과 같은 업무인 것은 다르다.
        model.addAttribute("previousCandidates", requests.previousCandidates(projectId, requestId));
        model.addAttribute("precheck", requests.precheck(projectId, requestId));
        model.addAttribute("listQuery", listQuery);
        model.addAttribute("listState", listState);
        model.addAttribute("listOwner", listOwner);
        model.addAttribute("listSystem", listSystem);
        model.addAttribute("listPage", listPage);
        model.addAttribute("listPageSize", listPageSize);
        return "artifacts/dev-request";
    }

    /** 개발팀에 전송한 것과 같은 ZIP 원본을 내려준다. */
    @GetMapping("/{requestId}/download")
    public ResponseEntity<Resource> download(@PathVariable String projectId,
                                             @PathVariable String requestId) {
        DevelopmentRequestService.StoredPackage stored = requests.storedPackage(projectId, requestId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + stored.fileName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(stored.size())
                .body(new FileSystemResource(stored.path()));
    }

    /** 상세 화면의 진행 조회 — 검사기를 돌리지 않는다(캐시 조회 + git rev-parse). 끝났을 때만 화면이 다시 읽는다. */
    @GetMapping("/{requestId}/progress")
    @ResponseBody
    public DevelopmentRequestService.Progress progress(@PathVariable String projectId,
                                                       @PathVariable String requestId) {
        return requests.progress(projectId, requestId);
    }

    @PostMapping("/{requestId}/comment")
    public String saveComment(@PathVariable String projectId, @PathVariable String requestId,
                              @RequestParam(required = false) String plannerComment,
                              RedirectAttributes flash) {
        try {
            requests.savePlannerComment(projectId, requestId, plannerComment);
            flash.addFlashAttribute("message", "개발팀 전달사항을 저장했습니다.");
        } catch (IllegalArgumentException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
    }

    /**
     * FRD 로 되돌리기 — 병주 지시 2026-08-25. 전송 전 개발요청서를 지우고 FRD 작업으로 돌아간다.
     * 성공하면 개발요청서가 없으니 FRD 화면으로 보낸다.
     */
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

    @PostMapping("/{requestId}/send")
    public String send(@PathVariable String projectId, @PathVariable String requestId,
                       @RequestParam(required = false) String plannerComment,
                       @RequestParam(required = false) LocalDate developmentCompletedOn,
                       @RequestParam(required = false) LocalDate deploymentOn,
                       @RequestParam(required = false) String previousRequestId,
                       @RequestParam(required = false) MultipartFile attachment,
                       RedirectAttributes flash) {
        // ⭐ 검사기는 여기서만 돈다 (2026-08-25 병주 지시 — 상세를 열 때마다 검증하지 않는다).
        //    ⚠ 전송보다 먼저·별도 트랜잭션이다: 막혀서 되돌려져도 「무엇 때문에 막혔나」는 남아 화면에 뜬다.
        DevRequestPrecheck.Result gate = requests.measureDeliveryGate(projectId, requestId);
        if (!gate.sendable()) {
            flash.addFlashAttribute("error",
                    "전송 전에 확인할 것이 %d건 있습니다. 아래 「전송 전 확인」을 봐 주세요."
                            .formatted(gate.blocking().size()));
            return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
        }
        try {
            requests.requestDelivery(projectId, requestId, plannerComment,
                    developmentCompletedOn, deploymentOn, previousRequestId, attachment);
            flash.addFlashAttribute("message", "개발요청을 접수했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
    }

    /**
     * 전송 철회 — 병주 지시 2026-08-25.
     *
     * <p>⚠ <b>기획자가 누른다.</b> 자기가 보낸 것을 무르는 것이고, 「라벨이 {@code intake} 일 때만」이
     * 안전장치다 — 개발이 집어갔으면 창구가 거절한다.
     */
    @PostMapping("/{requestId}/withdraw")
    public String withdraw(@PathVariable String projectId, @PathVariable String requestId,
                           @RequestParam(required = false) String reason,
                           @AuthenticationPrincipal BuilderUser me,
                           RedirectAttributes flash) {
        try {
            requests.withdraw(projectId, requestId, reason, me == null ? null : me.accountId());
            flash.addFlashAttribute("message", "개발요청을 취소했습니다. 내용을 고쳐 다시 보낼 수 있습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
    }
}
