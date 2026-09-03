package com.bizplay.builder.intake;

import com.bizplay.builder.intake.Requirement.ReviewState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 요구사항 — 목록 · 상세 · 확정 · 제외 · 내용 수정.
 *
 * <p>목업 {@code docs/mockups/02-requirements.html}·{@code 02a-requirement-detail.html} 이 정본이다.
 *
 * <p>⚠ <b>{@code requirements} 열쇠는 원래 {@link com.bizplay.builder.artifact.ArtifactListController}
 * 의 빈 화면이 받던 자리다.</b> 여기가 더 좁은 길이라 스프링이 이쪽을 고른다 — 그 컨트롤러의
 * {@code /{key}} 는 나머지 열셋을 계속 받는다.
 *
 * <p>⛔ <b>「정의서 생성 요청」에 뒷단을 붙이지 마라 (2026-08-16 병주 확정).</b> 요구사항정의서는
 * 계획 3 이고 만드는 기계가 없다 — 눌리는 버튼을 달면 화면이 「요청 완료」라고 거짓말한다.
 * 목업대로 자리는 그리되 잠가 둔다.
 *
 * <p>⚠ 프로젝트 이름·번호·알림은 <b>여기서 안 담는다</b> —
 * {@link com.bizplay.builder.web.ProjectContextInterceptor} 가 한 자리에서 얹는다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/requirements")
public class RequirementController {

    private static final String ARTIFACT_KEY = "requirements";

    /** 목록 한 쪽에 몇 줄. 받은 문서 목록과 같은 값이다 — 두 화면이 다른 크기를 고르면 헷갈린다. */
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);

    /** 쪽 번호를 몇 개까지 늘어놓나. 목업이 열 개를 그렸다. */
    private static final int PAGE_WINDOW = 10;

    /** 거르개의 「전체」. ⚠ 빈 문자열과 같은 뜻이다 — 브라우저가 안 고른 칸을 빈 값으로 보낸다. */
    private static final String ANY = "전체";

    private final RequirementMapper requirements;
    private final RequirementReviewService review;
    private final IntakeMapper intakes;
    private final ReceivedDocumentMapper documents;
    private final ProjectFacetMapper projectFacets;
    private final IntakeFacetMapper intakeFacets;

    public RequirementController(RequirementMapper requirements, RequirementReviewService review,
                                 IntakeMapper intakes, ReceivedDocumentMapper documents,
                                 ProjectFacetMapper projectFacets, IntakeFacetMapper intakeFacets) {
        this.requirements = requirements;
        this.review = review;
        this.intakes = intakes;
        this.documents = documents;
        this.projectFacets = projectFacets;
        this.intakeFacets = intakeFacets;
    }

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String sourceIntakeId,
                       @RequestParam(required = false) String reviewState,
                       @RequestParam(required = false) String facet,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       Model model) {
        List<Requirement> found = requirements.selectByProjectId(projectId);

        Map<String, String> intakeTitles = new LinkedHashMap<>();
        intakes.selectByProjectId(projectId)
                .forEach(intake -> intakeTitles.put(intake.id(), intake.title()));

        List<String> intakeIds = List.copyOf(intakeTitles.keySet());
        // ⛔ 빈 목록으로 부르지 마라 — 매퍼의 in 절이 `in ()` 이 되어 SQL 이 깨진다.
        Map<String, List<String>> facetsByIntake = intakeIds.isEmpty() ? Map.of()
                : intakeFacets.selectByIntakeIdIn(intakeIds).stream()
                        .collect(Collectors.groupingBy(IntakeFacet::intakeId,
                                Collectors.mapping(IntakeFacet::name, Collectors.toList())));

        List<Row> all = found.stream()
                .map(requirement -> new Row(requirement,
                        intakeTitles.getOrDefault(requirement.intakeId(), ""),
                        facetsByIntake.getOrDefault(requirement.intakeId(), List.of())))
                .toList();

        /*
         * ⚠ 현황 띠는 거른 결과가 아니라 <b>전체</b>를 센다 — 거르개를 걸었다고 프로젝트의
         *   「몇 건」이 흔들리면 그 숫자를 못 믿는다. 받은 문서 목록과 같은 규칙이다.
         * ⚠ 제외를 띠에 올린 것은 목업에 없던 것이다 — 목업에 제외 개념이 아예 없어서다.
         *   안 올리면 「전체 43 · 생성 9 · 확정 30」에서 남은 넷이 어디 갔나가 화면에 안 적힌다.
         */
        model.addAttribute("totalCount", all.size());
        model.addAttribute("draftedCount", countState(all, ReviewState.DRAFTED));
        model.addAttribute("confirmedCount", countState(all, ReviewState.CONFIRMED));
        model.addAttribute("excludedCount", countState(all, ReviewState.EXCLUDED));

        List<Row> matched = all.stream()
                .filter(row -> matchesQuery(row, query))
                .filter(row -> matchesChoice(row.sourceIntakeId(), sourceIntakeId))
                .filter(row -> matchesChoice(row.reviewStateLabel(), reviewState))
                .filter(row -> matchesFacet(row, facet))
                .toList();

        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);

        model.addAttribute("rows", matched.stream()
                .skip((long) (current - 1) * size)
                .limit(size)
                .toList());
        model.addAttribute("matchedCount", matched.size());
        model.addAttribute("page", current);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(current, pageCount));
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);

        List<String> availableFacets = projectFacets.selectByProjectId(projectId)
                .stream().map(ProjectFacet::name).toList();
        model.addAttribute("hasFacets", !availableFacets.isEmpty());
        model.addAttribute("availableFacets", availableFacets);
        model.addAttribute("sourceDocuments", intakeTitles);
        model.addAttribute("reviewStates", ReviewState.values());
        model.addAttribute("query", query);
        model.addAttribute("sourceFilter", sourceIntakeId);
        model.addAttribute("reviewStateFilter", reviewState);
        model.addAttribute("facetFilter", facet);

        shell(model, "요구사항");
        return "artifacts/requirements";
    }

    /**
     * 상세.
     *
     * <p>⚠ {@code edit} 은 <b>주소로 가른다.</b> 스크립트로 칸을 바꿔치기하면 되돌아갈 자리가 없고,
     * 이 저장소의 화면은 상태를 서버가 가르는 쪽으로 서 있다(받은 문서 상세의 확인 모드와 같다).
     */
    @GetMapping("/{requirementId}")
    public String detail(@PathVariable String projectId, @PathVariable String requirementId,
                         @RequestParam(defaultValue = "false") boolean edit,
                         Model model) {
        Requirement requirement = requirementOf(projectId, requirementId);
        Intake intake = intakes.selectById(requirement.intakeId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 접수가 없다"));

        model.addAttribute("requirement", requirement);
        model.addAttribute("intake", intake);
        /*
         * ⚠ 받은 문서는 없을 수도 있는 것으로 다룬다. 여기의 주인공은 요구사항이라
         *   문서 줄이 없다고 404 를 내면 <b>볼 수 있는 것까지 못 보게</b> 된다.
         */
        model.addAttribute("document", documents.selectByIntakeId(requirement.intakeId())
                .orElse(null));
        model.addAttribute("facets", intakeFacets.selectByIntakeId(requirement.intakeId())
                .stream().map(IntakeFacet::name).toList());
        model.addAttribute("editing", edit);

        shell(model, requirement.title());
        return "artifacts/requirement";
    }

    @PostMapping("/{requirementId}/confirm")
    public String confirm(@PathVariable String projectId, @PathVariable String requirementId) {
        requirementOf(projectId, requirementId);
        review.confirm(requirementId);
        return detailRedirect(projectId, requirementId);
    }

    @PostMapping("/{requirementId}/exclude")
    public String exclude(@PathVariable String projectId, @PathVariable String requirementId,
                          @RequestParam(required = false) String reason,
                          RedirectAttributes flash) {
        requirementOf(projectId, requirementId);
        try {
            review.exclude(requirementId, reason);
        } catch (IllegalArgumentException rejected) {
            // ⛔ 500 을 내지 않는다. 사람이 고칠 수 있는 것이다.
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return detailRedirect(projectId, requirementId);
    }

    @PostMapping("/{requirementId}/content")
    public String editContent(@PathVariable String projectId, @PathVariable String requirementId,
                              @RequestParam(required = false) String title,
                              @RequestParam(required = false) String body,
                              RedirectAttributes flash) {
        requirementOf(projectId, requirementId);
        try {
            review.editContent(requirementId, title, body);
        } catch (IllegalArgumentException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return detailRedirect(projectId, requirementId);
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    /** ⚠ 남의 프로젝트 요구사항은 <b>주소를 알아도</b> 안 열린다. */
    private Requirement requirementOf(String projectId, String requirementId) {
        return requirements.selectById(requirementId)
                .filter(found -> found.projectId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "그런 요구사항이 없다"));
    }

    private String detailRedirect(String projectId, String requirementId) {
        return "redirect:/projects/%s/artifacts/requirements/%s".formatted(projectId, requirementId);
    }

    private long countState(List<Row> rows, ReviewState state) {
        return rows.stream().filter(row -> row.reviewState() == state).count();
    }

    /** ⚠ 번호로도 찾는다 — 사람은 「REQ-042」를 그대로 쳐 넣는다. */
    private boolean matchesQuery(Row row, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase();
        return row.title().toLowerCase().contains(needle)
                || row.body().toLowerCase().contains(needle)
                || row.code().toLowerCase().contains(needle);
    }

    private boolean matchesChoice(String value, String chosen) {
        return chosen == null || chosen.isBlank() || chosen.equals(ANY) || chosen.equals(value);
    }

    private boolean matchesFacet(Row row, String chosen) {
        return chosen == null || chosen.isBlank() || chosen.equals(ANY) || row.facets().contains(chosen);
    }

    /** 지금 쪽을 가운데 두고 열 개를 낸다. 쪽이 열 개 안쪽이면 전부 낸다. */
    private List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return java.util.stream.IntStream.rangeClosed(first, last).boxed().toList();
    }

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }

    /**
     * 화면이 읽는 줄 하나.
     *
     * <p>⚠ 요구사항 자신을 그대로 안고 간다 — 값이 열 개가 넘어 하나씩 펴 담으면
     * 템플릿과 컨트롤러가 <b>같은 값을 다른 이름으로</b> 부르게 된다.
     */
    public record Row(Requirement requirement, String sourceTitle, List<String> facets) {

        public String id() {
            return requirement.id();
        }

        public String code() {
            return requirement.code();
        }

        public String title() {
            return requirement.title();
        }

        public String body() {
            return requirement.body();
        }

        public String sourceIntakeId() {
            return requirement.intakeId();
        }

        public ReviewState reviewState() {
            return requirement.reviewState();
        }

        public String reviewStateLabel() {
            return requirement.reviewState().label();
        }

        public String excludedReason() {
            return requirement.excludedReason();
        }
    }
}
