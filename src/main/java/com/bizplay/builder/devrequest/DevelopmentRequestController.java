package com.bizplay.builder.devrequest;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.ProjectSystemService;
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
    private final DevRequestDeliveryService deliveries;
    private final DevRequestReceiveService receives;
    private final ProjectFacetMapper projectFacets;
    private final ProjectSystemService projectSystems;
    /** ⚠ 없으면(단위 시험) 준비 중인 요청서를 재지 않는다. */
    private final DevRequestPreparation preparation;

    @org.springframework.beans.factory.annotation.Autowired
    public DevelopmentRequestController(DevelopmentRequestService requests,
                                        DevRequestDeliveryService deliveries,
                                        DevRequestReceiveService receives,
                                        ProjectFacetMapper projectFacets,
                                        ProjectSystemService projectSystems,
                                        DevRequestPreparation preparation) {
        this.deliveries = deliveries;
        this.receives = receives;
        this.requests = requests;
        this.projectFacets = projectFacets;
        this.projectSystems = projectSystems;
        this.preparation = preparation;
    }

    public DevelopmentRequestController(DevelopmentRequestService requests,
                                        DevRequestDeliveryService deliveries,
                                        DevRequestReceiveService receives,
                                        ProjectFacetMapper projectFacets,
                                        ProjectSystemService projectSystems) {
        this(requests, deliveries, receives, projectFacets, projectSystems, null);
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
        // ⭐ 준비 중인 요청서는 목록에 안 보인다 — 끝난 것은 올리고 못 마친 것은 거둔 뒤에 그린다.
        if (preparation != null) preparation.settleProject(projectId);
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
        // ⛔ 화면에서 브랜치 이름을 다시 짓지 않는다 — 이름 규칙은 DeliveryIndex 한 자리다.
        model.addAttribute("deliveryBranch",
                DeliveryIndex.deliveryBranch(view.request().systemCode(), view.request().label()));
        model.addAttribute("returnBranch",
                DeliveryIndex.returnBranch(view.request().systemCode(), view.request().label()));
        model.addAttribute("listQuery", listQuery);
        model.addAttribute("listState", listState);
        model.addAttribute("listOwner", listOwner);
        model.addAttribute("listSystem", listSystem);
        model.addAttribute("listPage", listPage);
        model.addAttribute("listPageSize", listPageSize);
        model.addAttribute("sourceFiles", deliveries == null ? List.of()
                : deliveries.sourceFilesOf(view.request().frdId()));
        return "artifacts/dev-request";
    }



    /** 상세 화면의 진행 조회 — 검사기를 돌리지 않는다(캐시 조회 + git rev-parse). 끝났을 때만 화면이 다시 읽는다. */
    @GetMapping("/{requestId}/progress")
    @ResponseBody
    public DevelopmentRequestService.Progress progress(@PathVariable String projectId,
                                                       @PathVariable String requestId) {
        return requests.progress(projectId, requestId);
    }

    /**
     * 「개발에 넘기기」 — 설계의 칸 3.
     *
     * <p>⛔ <b>사람이 누른다.</b> 바깥으로 나가는 일이라 되돌리기 어렵고, 「이걸로 넘겨도 된다」는
     * 기계가 아니라 사람만 아는 판단이다.
     *
     * <p>⭐ 실패해도 <b>개발요청서는 그대로 남는다</b> — 상태만 「대기」로 돌아온다. 사유를 보고
     * 고친 뒤 다시 누르면 <b>같은 전송 키</b>로 다시 간다.
     */
    @PostMapping("/{requestId}/deliver")
    public String deliver(@PathVariable String projectId, @PathVariable String requestId,
                          @RequestParam(required = false)
                          @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                          java.time.LocalDate developmentCompletedOn,
                          @RequestParam(required = false)
                          @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                          java.time.LocalDate deploymentOn,
                          @RequestParam(required = false) String plannerComment,
                          @RequestParam(required = false) org.springframework.web.multipart.MultipartFile attachment,
                          @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            // ⭐ 「개발에 넘기기」 레이어에서 고른 것을 먼저 적는다 (목업 06b) — 모두 선택이다.
            requests.saveSendDetails(projectId, requestId, developmentCompletedOn, deploymentOn,
                    plannerComment, attachment);
            var published = deliveries.deliver(projectId, requestId, me == null ? null : me.accountId());
            flash.addFlashAttribute("message",
                    "개발에 넘겼습니다. 기획 저장소의 %s 브랜치에 꾸러미를 올렸습니다.".formatted(published.branch()));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
    }

    /**
     * 「개발 결과 받기」 — 역류를 사람이 부르는 자리.
     *
     * <p>⭐ <b>거절이면 아무것도 안 놓인다</b> — 기획 저장소도 DB 도. 사유를 그대로 보여 주고,
     * 개발이 고쳐 다시 밀면 다시 누르면 된다.
     *
     * <p>⚠ 설계는 「받으면 바로 넣는다 — 사람이 끼어들지 않는다」이다. 이 버튼은 <b>언제 받나</b>를
     * 사람이 고르는 것이지 <b>무엇을 받나</b>를 고르는 것이 아니다 — 그 판정은 코드가 한다.
     */
    @PostMapping("/{requestId}/receive")
    public String receive(@PathVariable String projectId, @PathVariable String requestId,
                          @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            DevRequestReceiveService.Result result = receives.receive(projectId, requestId,
                    me == null ? null : me.accountId());
            if (result.accepted() && result.alreadyReceived()) {
                // ⭐ 「거절」로 보이면 사람이 무엇이 틀렸나 찾는다 — 이미 받은 것이라고 말한다.
                flash.addFlashAttribute("message",
                        "이미 받은 판입니다. 테스트 결과 %d줄을 다시 담았습니다.".formatted(result.testRows()));
            } else if (result.accepted()) {
                flash.addFlashAttribute("message", result.commit() == null
                        ? "받을 변경이 없었습니다. 테스트 결과 %d줄을 담았습니다.".formatted(result.testRows())
                        : "개발 결과를 반영했습니다. 테스트 결과 %d줄을 담았습니다.".formatted(result.testRows()));
            } else {
                flash.addFlashAttribute("error",
                        "개발 결과를 받지 못했습니다 — " + String.join(" · ", result.rejections()));
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
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
