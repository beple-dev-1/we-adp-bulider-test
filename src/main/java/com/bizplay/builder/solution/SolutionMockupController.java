package com.bizplay.builder.solution;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.featurespec.FeatureSpecService;
import com.bizplay.builder.ia.IaService;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 솔루션 목업 — 목록 · 상세 · 「실물과 다름」 표시.
 *
 * <p>목업 {@code docs/mockups/08-solution-mockups.html}·{@code 08a-solution-mockup-detail.html} 이 정본이다.
 *
 * <p>⚠ <b>{@code solution-mockups} 열쇠는 원래 {@link com.bizplay.builder.artifact.ArtifactListController}
 * 의 빈 화면이 받던 자리다.</b> 여기가 더 좁은 길이라 스프링이 이쪽을 고른다.
 *
 * <p>⛔ <b>「보정하기」에 뒷단을 붙이지 마라</b> (2026-08-16 병주 확정).
 * 워크트리 → 커밋 → 푸시를 타는데 그 기계(계획 2 Task 6·7)가 2026-08-14 에 얼려 있다.
 * 눌리는 버튼을 달면 화면이 「보정했다」고 거짓말한다. 목업대로 자리는 그리되 잠가 둔다.
 *
 * <p>⛔ <b>여기서 화면을 고치는 길을 만들지 마라.</b> ③ 은 기획 저장소가 정본이다 —
 * 빌더가 쓰는 것은 「실물과 다름」 표시 하나뿐이고 그것도 레포가 아니라 빌더 DB 로 간다.
 *
 * <p>⚠ 프로젝트 이름·번호·알림은 <b>여기서 안 담는다</b> —
 * {@link com.bizplay.builder.web.ProjectContextInterceptor} 가 한 자리에서 얹는다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/solution-mockups")
public class SolutionMockupController {

    private static final String ARTIFACT_KEY = "solution-mockups";

    /** 목록 한 쪽에 몇 줄. 받은 문서·요구사항 목록과 같은 값이다. */
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);

    /** 쪽 번호를 몇 개까지 늘어놓나. 목업이 열 개를 그렸다. */
    private static final int PAGE_WINDOW = 10;

    /** 거르개의 「전체」. ⚠ 빈 문자열과 같은 뜻이다 — 브라우저가 안 고른 칸을 빈 값으로 보낸다. */
    private static final String ANY = "전체";

    private final SolutionMockupService solutions;
    private final IaService ia;
    private final ScreenStandardIdMapper standardIds;
    private final ProjectSystemService projectSystems;
    private final PreviewFacets previewFacets;
    private final FeatureSpecService featureSpecs;

    public SolutionMockupController(SolutionMockupService solutions, IaService ia,
                                    ScreenStandardIdMapper standardIds,
                                    ProjectSystemService projectSystems,
                                    PreviewFacets previewFacets,
                                    FeatureSpecService featureSpecs) {
        this.solutions = solutions;
        this.ia = ia;
        this.standardIds = standardIds;
        this.projectSystems = projectSystems;
        this.previewFacets = previewFacets;
        this.featureSpecs = featureSpecs;
    }

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String system,
                       @RequestParam(required = false) String facet,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       Model model) {
        List<SolutionScreen> all = solutions.screens(projectId);
        // ⚠ 시스템 이름은 프로젝트 등록 자료에서 온다 — 코드에 박힌 표가 아니다.
        //   이름이 아직 없는 시스템은 코드가 그대로 뜬다(빈칸을 내지 않는다).
        SystemLabels systemLabels = projectSystems.labels(projectId);
        Map<String, Long> mismatchCounts = solutions.mismatchCounts(projectId);
        Map<String, ScreenStandardId> standardIdByScreen = standardIds.selectByProject(projectId).stream()
                .collect(Collectors.toMap(ScreenStandardId::screenId, Function.identity()));

        /*
         * ⚠ 현황 띠는 거른 결과가 아니라 전체를 센다 — 거르개를 걸었다고 「몇 장인가」가
         *   흔들리면 그 숫자를 못 믿는다. 받은 문서·요구사항 목록과 같은 규칙이다.
         */
        model.addAttribute("totalCount", all.size());
        model.addAttribute("systemCounts", countsBySystem(all, systemLabels));
        model.addAttribute("mismatchTotal", mismatchCounts.values().stream().mapToLong(Long::longValue).sum());

        List<SolutionScreen> matched = all.stream()
                .filter(screen -> matchesQuery(screen,
                        displayStandardId(standardIdByScreen.get(screen.screenId()), screen.screenId()), query))
                .filter(screen -> matchesChoice(systemLabels.label(screen.system()), system))
                .filter(screen -> screen.appliesTo(facet))
                .toList();

        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);

        model.addAttribute("rows", matched.stream()
                .skip((long) (current - 1) * size)
                .limit(size)
                .map(screen -> new Row(screen,
                        displayStandardId(standardIdByScreen.get(screen.screenId()), screen.screenId()),
                        mismatchCounts.getOrDefault(screen.screenId(), 0L),
                        systemLabels.label(screen.system())))
                .toList());
        model.addAttribute("matchedCount", matched.size());
        model.addAttribute("page", current);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(current, pageCount));
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);

        // ⚠ 거르개의 보기는 실제 자료에서 만든다 — 목업에 박힌 「전자결재·근태 관리」를 옮기면
        //   그 사업에 없는 메뉴가 뜨고, 진짜 메뉴는 못 고른다.
        model.addAttribute("systems", all.stream().map(screen -> systemLabels.label(screen.system()))
                .distinct().sorted().toList());
        model.addAttribute("hasFacets", all.stream().anyMatch(SolutionScreen::hasFacetAxis));
        model.addAttribute("facets", all.stream().flatMap(screen -> screen.applicationNames().stream())
                .distinct().sorted().toList());
        model.addAttribute("query", query);
        model.addAttribute("systemFilter", system);
        model.addAttribute("facetFilter", facet);

        shell(model, "솔루션 템플릿");
        return "artifacts/solution-mockups";
    }

    @GetMapping("/{screenId}")
    public String detail(@PathVariable String projectId, @PathVariable String screenId,
                         @RequestParam(required = false) String variant,
                         @RequestParam(required = false) String query,
                         @RequestParam(required = false) String system,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "10") int pageSize,
                         Model model) {
        SolutionScreen screen = screenOf(projectId, screenId);

        model.addAttribute("screen", screen);
        ScreenStandardId standardId = standardIds.selectByProject(projectId).stream()
                .filter(row -> row.screenId().equals(screenId))
                .findFirst()
                .orElse(null);
        model.addAttribute("standardId", displayStandardId(standardId, screenId));

        /*
         * ⭐ 기관은 한 값이고 지렛대는 둘이다 (설계 2026-08-22-preview-skin-design).
         *    갈래 화면이면 그 기관의 **파일**을 열고, 스킨 화면이면 같은 파일을 열되
         *    **css 폴더**를 갈아끼운다. 탭은 둘 중 있는 축의 것을 그린다.
         * ⚠ 갈래는 기저 html 이 아예 없어 **반드시 하나를 골라야** 열린다 — 못 정하면 첫 기관이다.
         *    스킨은 그 반대로, 못 정하면 **안 갈아끼우는 것**이 맞다(그린 그대로 둔다).
         */
        List<SolutionVariant> facetTabs = screen.hasVariants()
                ? screen.variants()
                : previewFacets.of(projectId, screen.system());
        String chosen = facetTabs.stream().map(SolutionVariant::code)
                .filter(code -> code.equals(variant))
                .findFirst()
                .orElseGet(() -> screen.hasVariants()
                        ? screen.variants().get(0).code()
                        : previewFacets.only(projectId));
        model.addAttribute("facetTabs", facetTabs);
        model.addAttribute("variant", chosen);
        model.addAttribute("systemLabel", projectSystems.labels(projectId).label(screen.system()));
        model.addAttribute("previewPath", screen.previewPath(chosen));
        model.addAttribute("screenDocument", featureSpecs.document(projectId, screen));
        model.addAttribute("mismatches", solutions.mismatchesOf(projectId, screenId));
        model.addAttribute("iaLink", ia.links(projectId).get(screenId));
        model.addAttribute("listQuery", query);
        model.addAttribute("listSystem", system);
        model.addAttribute("listPage", page);
        model.addAttribute("listPageSize", pageSize);

        shell(model, screen.screenName());
        return "artifacts/solution-mockup";
    }

    /**
     * 「실물과 다름」을 짚는다.
     *
     * <p>⛔ 여기에 보정 권한 검사를 붙이지 마라 — 설계가 이 문만 <b>권한도 자격도 없이</b>
     * 열어 뒀다(2026-08-14). 슈퍼계정도 이 문은 쓸 수 있다.
     */
    @PostMapping("/{screenId}/mismatch")
    public String reportMismatch(@PathVariable String projectId, @PathVariable String screenId,
                                 @RequestParam(required = false) String reason,
                                 @AuthenticationPrincipal BuilderUser me,
                                 RedirectAttributes flash) {
        screenOf(projectId, screenId);
        try {
            solutions.report(projectId, screenId, reason, me.accountId());
        } catch (IllegalArgumentException rejected) {
            // ⛔ 500 을 내지 않는다. 사람이 고칠 수 있는 것이다.
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/solution-mockups/%s".formatted(projectId, screenId);
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    /**
     * ⚠ 남의 프로젝트 화면은 <b>주소를 알아도</b> 안 열린다 — 클론이 프로젝트마다 따로라
     * 그 프로젝트의 색인에 없으면 없는 것이다.
     */
    private SolutionScreen screenOf(String projectId, String screenId) {
        return solutions.screen(projectId, screenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "그런 화면이 없다: " + screenId));
    }

    /** 시스템마다 몇 장인가. 순서를 지키려고 {@code LinkedHashMap} 이다. */
    private Map<String, Long> countsBySystem(List<SolutionScreen> screens, SystemLabels labels) {
        Map<String, Long> counts = new LinkedHashMap<>();
        screens.stream()
                .map(screen -> labels.label(screen.system()))
                .sorted(Comparator.naturalOrder())
                .forEach(label -> counts.merge(label, 1L, Long::sum));
        return counts;
    }

    /** ⚠ 화면ID·표준 ID 로도 찾는다 — 사람은 알고 있는 ID 를 그대로 쳐 넣는다. */
    private boolean matchesQuery(SolutionScreen screen, String standardId, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase();
        return screen.screenId().toLowerCase().contains(needle)
                || standardId.toLowerCase().contains(needle)
                || screen.screenName().toLowerCase().contains(needle)
                || screen.menuPath().toLowerCase().contains(needle)
                || screen.applicationNames().stream().anyMatch(name -> name.toLowerCase().contains(needle));
    }

    /** DB 에 나뉘어 저장된 태생 마디까지 붙여 사람이 쓰는 여섯 마디 표준 ID 로 만든다. */
    private String displayStandardId(ScreenStandardId row, String fallback) {
        return row == null ? fallback : StandardScreenIdFormat.display(row.standardId(), row.origin());
    }

    private boolean matchesChoice(String value, String chosen) {
        return chosen == null || chosen.isBlank() || chosen.equals(ANY) || chosen.equals(value);
    }

    /** 지금 쪽을 가운데 두고 열 개를 낸다. 쪽이 열 개 안쪽이면 전부 낸다. */
    private List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }

    /**
     * 목록 한 줄. 화면 자신과 「몇 건 짚혔나」·시스템 이름을 같이 안고 간다.
     *
     * <p>⚠ 시스템 이름이 여기 실리는 까닭 — {@link SolutionScreen} 은 클론의 파일에서 읽은 값
     * 묶음이라 자기가 어느 프로젝트인지 모른다. 이름은 프로젝트 등록 자료에 있다.
     */
    public record Row(SolutionScreen screen, String standardId, long mismatchCount, String systemLabel) {

        public boolean hasMismatch() {
            return mismatchCount > 0;
        }
    }
}
