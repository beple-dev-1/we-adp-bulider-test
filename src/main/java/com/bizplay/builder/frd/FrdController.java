package com.bizplay.builder.frd;

import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.ai.AiProgress;
import com.bizplay.builder.ai.ClaudeRunner.Progress;
import com.bizplay.builder.claude.ClaudeCredentialService;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.bizplay.builder.solution.SolutionPreviewController;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * FRD 작업 — 목록과 작업대.
 *
 * <p>목업 {@code docs/mockups/05-frds.html}·{@code 05a-frd-workbench.html} 이 정본이다.
 * 설계는 {@code docs/superpowers/specs/2026-08-18-frd-fast-track-design.md}.
 *
 * <p>⚠ 프로젝트 이름·번호·알림은 여기서 안 담는다 — {@code ProjectContextInterceptor} 가 얹는다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/frds")
public class FrdController {

    private static final Logger log = LoggerFactory.getLogger(FrdController.class);
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int PAGE_WINDOW = 10;

    /** ⚠ {@code FrdWizardController}(Task 5) 도 같은 열쇠를 쓴다 — 글자를 두 군데 박지 말고 이 상수를 쓴다. */
    static final String ARTIFACT_KEY = "frds";

    /**
     * 미리보기 문의 주소 모양 — {@code SecurityConfig} 가 {@code X-Frame-Options} 를 여기서만
     * 느슨하게 하려고 쓴다({@code SolutionPreviewController.URL_PATTERN} 과 같은 자리다).
     *
     * <p>⛔ <b>{@code @GetMapping} 의 실제 경로와 따로 놀게 두지 마라.</b> 둘이 갈리면
     * 미리보기 칸이 <b>말없이</b> 다시 빈다 — 서버는 200 을 내고 브라우저만 안 그린다.
     */
    public static final String PREVIEW_URL_PATTERN =
            "/projects/*/artifacts/frds/*/screens/*/preview";

    /** 변경 이력의 읽기 전용 HTML도 작업대 iframe 안에서만 미리본다. */
    public static final String HISTORY_PREVIEW_URL_PATTERN =
            "/projects/*/artifacts/frds/*/history/*/preview";

    private final FrdService frds;
    /** ⭐ Task 6 의 작업대·미리보기가 화면 목록·html 을 읽는 데 쓴다. */
    private final FrdScreenMapper screens;
    private final ScreenMockupWorker mockupWorker;
    private final ScreenMockupBatchWorker mockupBatchWorker;
    private final FrdScreenHistoryMapper screenHistories;
    private final FrdScreenHistoryService screenHistoryService;
    private final FrdScreenChatService screenChats;
    private final FrdScreenChatWorker screenChatWorker;
    private final FrdScreenChatEvents screenChatEvents;
    private final FrdScreenDocumentService screenDocuments;
    private final FrdScreenMemoService screenMemos;
    private final FrdScreenMarkerService screenMarkers;
    private final FrdScreenDirectEditService directEdits;
    private final AiProgress aiProgress;
    private final FrdDraftingService drafting;
    private final SolutionScreenReader solutions;
    private final com.bizplay.builder.solution.PreviewFacets previewFacets;
    private final com.bizplay.builder.solution.SkinRewriter skins;
    private final FrdFacetMapper frdFacets;
    private final FrdItemMapper frdItems;
    private final AccountMapper accounts;
    private final ProjectPaths paths;
    private final FrdScreenFiles screenFiles;
    private final FrdWorkspace workspaces;
    private final FrdCompletionService completion;
    private final FrdChatCancellation chatCancellations;
    private final ProjectSystemService projectSystems;
    private final ScreenStandardIdMapper standardIds;
    private final FrdChatReferenceImageService referenceImages;
    private final ClaudeCredentialService claudeCredentials;

    public FrdController(FrdService frds, FrdScreenMapper screens, ScreenMockupWorker mockupWorker,
                         ScreenMockupBatchWorker mockupBatchWorker,
                         FrdScreenHistoryMapper screenHistories,
                         FrdScreenHistoryService screenHistoryService,
                         FrdScreenChatService screenChats, FrdScreenChatWorker screenChatWorker,
                         FrdScreenChatEvents screenChatEvents, FrdScreenDocumentService screenDocuments,
                         FrdScreenMemoService screenMemos, FrdScreenMarkerService screenMarkers,
                         FrdScreenDirectEditService directEdits,
                         AiProgress aiProgress,
                         FrdDraftingService drafting, SolutionScreenReader solutions,
                         com.bizplay.builder.solution.PreviewFacets previewFacets,
                         com.bizplay.builder.solution.SkinRewriter skins,
                         FrdFacetMapper frdFacets, FrdItemMapper frdItems,
                         AccountMapper accounts, ProjectPaths paths, FrdScreenFiles screenFiles,
                         FrdWorkspace workspaces,
                         FrdCompletionService completion, FrdChatCancellation chatCancellations,
                         ProjectSystemService projectSystems,
                         ScreenStandardIdMapper standardIds,
                         FrdChatReferenceImageService referenceImages,
                         ClaudeCredentialService claudeCredentials) {
        this.frds = frds;
        this.screens = screens;
        this.mockupWorker = mockupWorker;
        this.mockupBatchWorker = mockupBatchWorker;
        this.screenHistories = screenHistories;
        this.screenHistoryService = screenHistoryService;
        this.screenChats = screenChats;
        this.screenChatWorker = screenChatWorker;
        this.screenChatEvents = screenChatEvents;
        this.screenDocuments = screenDocuments;
        this.screenMemos = screenMemos;
        this.screenMarkers = screenMarkers;
        this.directEdits = directEdits;
        this.aiProgress = aiProgress;
        this.drafting = drafting;
        this.solutions = solutions;
        this.previewFacets = previewFacets;
        this.skins = skins;
        this.frdFacets = frdFacets;
        this.frdItems = frdItems;
        this.accounts = accounts;
        this.paths = paths;
        this.screenFiles = screenFiles;
        this.workspaces = workspaces;
        this.completion = completion;
        this.chatCancellations = chatCancellations;
        this.projectSystems = projectSystems;
        this.standardIds = standardIds;
        this.referenceImages = referenceImages;
        this.claudeCredentials = claudeCredentials;
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
        List<FrdService.Row> all = frds.list(projectId);
        var systemLabels = projectSystems.labels(projectId);
        List<FrdService.Row> matched = all.stream()
                .filter(row -> matchesQuery(row, query))
                .filter(row -> matchesChoice(row.frd().state().name(), state))
                .filter(row -> matchesChoice(row.frd().ownerAccountId(), owner))
                .filter(row -> system == null || system.isBlank() || row.systems().contains(system))
                .toList();
        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);

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
        Set<Frd.State> statesInUse = all.stream().map(row -> row.frd().state())
                .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("stateOptions", List.of(Frd.State.values()).stream()
                .filter(statesInUse::contains)
                .map(value -> new FilterOption(value.name(), all.stream()
                        .filter(row -> row.frd().state() == value)
                        .findFirst().orElseThrow().frd().stateLabel()))
                .toList());
        model.addAttribute("ownerOptions", all.stream()
                .filter(row -> row.frd().ownerAccountId() != null && !row.frd().ownerAccountId().isBlank())
                .map(row -> new FilterOption(row.frd().ownerAccountId(), row.ownerLabel()))
                .distinct().sorted(java.util.Comparator.comparing(FilterOption::label)).toList());
        model.addAttribute("systemOptions", all.stream().flatMap(row -> row.systems().stream())
                .distinct().map(code -> new FilterOption(code, systemLabels.label(code)))
                .sorted(java.util.Comparator.comparing(FilterOption::label)).toList());
        shell(model, "FRD 작업");
        return "artifacts/frds";
    }

    private static boolean matchesQuery(FrdService.Row row, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) return true;
        return contains(row.frd().label(), needle)
                || contains(row.frd().title(), needle)
                || contains(row.frd().sourceText(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean matchesChoice(String actual, String selected) {
        return selected == null || selected.isBlank() || selected.equals(actual);
    }

    private static boolean hasListContext(String query, String state, String owner, String system,
                                          int page, int pageSize) {
        return !query.isBlank() || !state.isBlank() || !owner.isBlank() || !system.isBlank()
                || page != 1 || pageSize != 10;
    }

    record FilterOption(String value, String label) { }

    /** 현재 페이지를 가운데 두고 페이지 번호를 최대 열 개까지 표시한다. */
    private List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    /** 완료 전 FRD 작업을 상세 화면에서 삭제한다. */
    @PostMapping("/{frdId}/delete")
    public String delete(@PathVariable String projectId, @PathVariable String frdId,
                         @RequestParam(name = "query", defaultValue = "") String listQuery,
                         @RequestParam(name = "state", defaultValue = "") String listState,
                         @RequestParam(name = "owner", defaultValue = "") String listOwner,
                         @RequestParam(name = "system", defaultValue = "") String listSystem,
                         @RequestParam(name = "page", defaultValue = "1") int listPage,
                         @RequestParam(name = "pageSize", defaultValue = "10") int listPageSize,
                         RedirectAttributes redirect) {
        Frd frd = frds.of(projectId, frdId);
        requireAssignedUser(frd);
        try {
            FrdScreenChatMessage runningChat = frd.canDelete() ? screenChats.running(frdId) : null;
            if (runningChat != null) {
                chatCancellations.cancel(runningChat.id());
            }
            frds.delete(projectId, frdId);
            redirect.addFlashAttribute("message", frd.label() + " 작업을 삭제했습니다.");
            preserveListContext(redirect, listQuery, listState, listOwner, listSystem,
                    listPage, listPageSize);
            return "redirect:/projects/%s/artifacts/frds".formatted(projectId);
        } catch (IllegalStateException rejected) {
            redirect.addFlashAttribute("error", rejected.getMessage());
            preserveListContext(redirect, listQuery, listState, listOwner, listSystem,
                    listPage, listPageSize);
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
    }

    private static void preserveListContext(RedirectAttributes redirect, String query, String state,
                                            String owner, String system, int page, int pageSize) {
        if (!hasListContext(query, state, owner, system, page, pageSize)) return;
        redirect.addAttribute("query", query);
        redirect.addAttribute("state", state);
        redirect.addAttribute("owner", owner);
        redirect.addAttribute("system", system);
        redirect.addAttribute("page", page);
        redirect.addAttribute("pageSize", pageSize);
    }

    /** 작업대 — 화면마다 to-be 목업을 만들고 미리본다. */
    @GetMapping("/{frdId}")
    public String workbench(@PathVariable String projectId, @PathVariable String frdId,
                            @RequestParam(name = "query", defaultValue = "") String listQuery,
                            @RequestParam(name = "state", defaultValue = "") String listState,
                            @RequestParam(name = "owner", defaultValue = "") String listOwner,
                             @RequestParam(name = "system", defaultValue = "") String listSystem,
                             @RequestParam(name = "page", defaultValue = "1") int listPage,
                             @RequestParam(name = "pageSize", defaultValue = "10") int listPageSize,
                             @RequestParam(required = false) String facet,
                             @AuthenticationPrincipal BuilderUser me, Model model,
                            RedirectAttributes redirect) {
        Frd frd = frds.of(projectId, frdId);
        // ⛔ 아직 확정 전이면 작업대가 아니라 마법사다 — 빈 작업대를 보여주지 않는다.
        if (frd.state() == Frd.State.ANALYZING || frd.state() == Frd.State.WAITING_ANSWER
                || frd.state() == Frd.State.ANALYSIS_FAILED
                || frd.state() == Frd.State.PICKED
                || frd.state() == Frd.State.SCOPE_REVIEW) {
            if (hasListContext(listQuery, listState, listOwner, listSystem, listPage, listPageSize)) {
                redirect.addAttribute("query", listQuery);
                redirect.addAttribute("state", listState);
                redirect.addAttribute("owner", listOwner);
                redirect.addAttribute("system", listSystem);
                redirect.addAttribute("page", listPage);
                redirect.addAttribute("pageSize", listPageSize);
            }
            return "redirect:/projects/%s/artifacts/frds/%s/pick".formatted(projectId, frdId);
        }
        List<FrdScreen> mine = screens.selectByFrdId(frdId);
        List<FrdFacet> selectedFacets = frdFacets.selectByFrdId(frdId);
        List<String> selectedFacetNames = selectedFacets.stream().map(FrdFacet::name).toList();
        List<SolutionScreen> allCandidates = solutions.read(projectId);
        List<SolutionScreen> candidates = selectedFacetNames.isEmpty() ? allCandidates
                : allCandidates.stream().filter(candidate -> selectedFacetNames.stream()
                        .anyMatch(candidate::appliesTo)).toList();
        Map<String, SolutionScreen> solutionScreensById = new LinkedHashMap<>();
        allCandidates.forEach(screen -> solutionScreensById.put(screen.screenId(), screen));
        /*
         * ⭐ 이 FRD 를 어느 기관으로 그리나 (설계 2026-08-22-preview-skin-design).
         *    갈래 화면이면 그 기관의 **파일**을 열고, 스킨 화면이면 css 폴더를 갈아끼운다 —
         *    부르는 쪽은 그 둘을 구분하지 않는다.
         */
        String activeFacet = activeFacet(selectedFacetNames, facet);
        String previewSkin = previewSkin(projectId,
                activeFacet == null ? selectedFacetNames : List.of(activeFacet));
        String previewSkinQuery = previewSkin == null ? "" : "?skin=" + previewSkin;
        model.addAttribute("activeFacet", activeFacet);
        model.addAttribute("allFacets", frds.usesAllFacets(projectId, selectedFacetNames));
        model.addAttribute("splitFacetsByScreen", splitFacetsByScreen(projectId, frd, mine));
        model.addAttribute("previewSkin", previewSkin);
        model.addAttribute("previewSkinQuery", previewSkinQuery);
        Map<String, String> sourcePreviewPaths = new LinkedHashMap<>();
        for (FrdScreen screen : mine) {
            String sourceScreenId = screen.isNewScreen() && screen.baseScreenId() != null
                ? screen.baseScreenId() : screen.screenId();
            SolutionScreen sourceScreen = solutionScreensById.get(sourceScreenId);
            if (sourceScreen != null) {
                sourcePreviewPaths.put(screen.id(), sourceScreen.previewPath(previewSkin));
            }
        }
        model.addAttribute("frd", frd);
        model.addAttribute("screens", mine);
        model.addAttribute("frdItems", frdItems.selectByFrdId(frdId));
        model.addAttribute("sourcePreviewPaths", sourcePreviewPaths);
        model.addAttribute("facets", selectedFacets);
        model.addAttribute("ownerName", ownerName(frd));
        model.addAttribute("candidates", candidates);
        Map<String, String> candidatePreviewPaths = new LinkedHashMap<>();
        Set<String> candidateSystems = new LinkedHashSet<>();
        candidates.forEach(candidate -> {
            candidatePreviewPaths.put(candidate.screenId(), candidate.previewPath(previewSkin));
            candidateSystems.add(candidate.system());
        });
        projectSystems.all(projectId).forEach(projectSystem ->
                candidateSystems.add(projectSystem.systemCode()));
        model.addAttribute("candidatePreviewPaths", candidatePreviewPaths);
        model.addAttribute("newScreenTypes", FrdService.NEW_SCREEN_TYPES);
        model.addAttribute("candidateSystems", candidateSystems);
        model.addAttribute("systemLabels", projectSystems.labels(projectId));
        Map<String, String> managementNumbers = new LinkedHashMap<>();
        standardIds.selectByProject(projectId).forEach(row -> managementNumbers.put(row.screenId(),
                StandardScreenIdFormat.display(row.standardId(), row.origin())));
        model.addAttribute("managementNumbers", managementNumbers);
        model.addAttribute("selectedScreenIds", mine.stream().map(FrdScreen::screenId).collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        List<FrdScreenHistory> histories = screenHistories.selectByFrdId(frdId);
        Map<String, Long> latestHistoryIds = new LinkedHashMap<>();
        Map<String, List<FrdScreenHistory>> screenChangeHistories = new LinkedHashMap<>();
        histories.forEach(history -> latestHistoryIds.putIfAbsent(history.frdScreenId(), history.id()));
        histories.stream().filter(history -> !history.changeList().isEmpty()).forEach(history ->
                screenChangeHistories.computeIfAbsent(history.frdScreenId(), ignored -> new ArrayList<>())
                        .add(history));
        model.addAttribute("screenHistories", histories);
        model.addAttribute("latestHistoryIds", latestHistoryIds);
        model.addAttribute("screenChangeHistories", screenChangeHistories);
        // ⚠ 완료 수는 컨트롤러에서 센다 — 템플릿의 SpEL 선택식으로 거르는 것보다 안전하다.
        model.addAttribute("generatedCount",
                mine.stream().filter(screen -> screen.state() == FrdScreen.State.GENERATED).count());
        model.addAttribute("draftReadyCount", mine.stream().filter(FrdScreen::canGenerateDraft).count());
        model.addAttribute("draftRequired",
                mine.stream().anyMatch(screen -> screen.isNewScreen() && screen.canGenerateDraft()));
        boolean workBusy = workBusy(frdId, mine);
        boolean assignedUser = isAssignedUser(frd, me);
        boolean workbenchEditable = frd.state() == Frd.State.DRAFTING && assignedUser;
        model.addAttribute("assignedUser", assignedUser);
        model.addAttribute("workbenchEditable", workbenchEditable);
        model.addAttribute("resetDisabled", workBusy || !workbenchEditable);
        model.addAttribute("completionStatus", assignedUser
                ? completionStatus(projectId, frd, workBusy)
                : new CompletionStatus(false, workBusy, false,
                        "담당자만 FRD 작업을 완료할 수 있습니다."));
        model.addAttribute("claudeIdentity", claudeCredentials.identityOf(me.accountId()).orElse(null));
        model.addAttribute("listQuery", listQuery);
        model.addAttribute("listState", listState);
        model.addAttribute("listOwner", listOwner);
        model.addAttribute("listSystem", listSystem);
        model.addAttribute("listPage", listPage);
        model.addAttribute("listPageSize", listPageSize);
        shell(model, frd.title());
        return "artifacts/frd";
    }

    /** 실제 기관별 HTML이 둘 이상 존재하는 화면만 상세 캔버스 하위 항목으로 내린다. */
    private Map<String, List<String>> splitFacetsByScreen(String projectId, Frd frd,
                                                           List<FrdScreen> workScreens) {
        Map<String, List<String>> split = new LinkedHashMap<>();
        for (FrdScreen screen : workScreens) {
            String sourceScreenId = screen.baseScreenId() == null || screen.baseScreenId().isBlank()
                    ? screen.screenId() : screen.baseScreenId();
            String systemCode = screen.systemCode() == null || screen.systemCode().isBlank()
                    ? frd.systemCode() : screen.systemCode();
            if (systemCode == null || systemCode.isBlank()) continue;
            List<String> variants = screenFiles.variantFacets(
                    projectId, frd.id(), systemCode, sourceScreenId);
            if (variants.size() > 1) split.put(screen.id(), variants);
        }
        return split;
    }

    private String ownerName(Frd frd) {
        if (frd.ownerAccountId() == null || frd.ownerAccountId().isBlank()) {
            return "—";
        }
        return accounts.selectById(frd.ownerAccountId()).map(Account::getName).orElse("—");
    }

    /** 개발 범위 확인을 마치고 실제 FRD 작업을 시작한다. */
    @PostMapping("/{frdId}/start")
    public String startDrafting(@PathVariable String projectId, @PathVariable String frdId,
                                RedirectAttributes flash) {
        frds.of(projectId, frdId);
        boolean hasScreens = !screens.selectByFrdId(frdId).isEmpty();
        try {
            drafting.start(projectId, frdId);
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/frds/%s/pick".formatted(projectId, frdId);
        }
        if (!hasScreens) {
            try {
                String requestId = completion.completeWithoutScreenWork(projectId, frdId);
                flash.addFlashAttribute("message",
                    "FRD 작업을 완료하고 개발요청서를 만들었습니다. 변경 예정 기능정의서는 AI 가 만들고 있어 몇 분 걸립니다.");
                return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
            } catch (IllegalStateException rejected) {
                flash.addFlashAttribute("error", rejected.getMessage());
                return "redirect:/projects/%s/artifacts/dev-requests".formatted(projectId);
            }
        }
        return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
    }

    /** 간단 변경은 FRD 작업대와 워크트리를 건너뛰고 개발요청서로 바로 보낸다. */
    @PostMapping("/{frdId}/fast-track")
    public String fastTrack(@PathVariable String projectId, @PathVariable String frdId,
                            RedirectAttributes flash) {
        try {
            String requestId = completion.completeFastTrack(projectId, frdId);
            flash.addFlashAttribute("message", "분석 결과로 개발요청서를 만들었습니다.");
            return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/frds/%s/pick".formatted(projectId, frdId);
        }
    }

    /** 현재 변경을 모두 버리고 원본 저장소 기준으로 FRD 작업 공간을 다시 만든다. */
    @PostMapping("/{frdId}/reset")
    public String resetDrafting(@PathVariable String projectId, @PathVariable String frdId,
                                RedirectAttributes flash) {
        requireAssignedUser(frds.of(projectId, frdId));
        if (screenChats.running(frdId) != null) {
            flash.addFlashAttribute("error", "AI가 화면 요청을 처리하고 있어 작업을 초기화할 수 없습니다.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        try {
            drafting.reset(projectId, frdId);
            flash.addFlashAttribute("message", "작업을 초기화했습니다.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
    }

    /** 작업공간 변경을 커밋하고 FRD를 검토 단계로 넘긴다. */
    @PostMapping("/{frdId}/complete")
    public String completeDrafting(@PathVariable String projectId, @PathVariable String frdId,
                                   @RequestParam(required = false) String confirmedCloneHead,
                                   RedirectAttributes flash) {
        requireAssignedUser(frds.of(projectId, frdId));
        try {
            String requestId = completion.complete(projectId, frdId, confirmedCloneHead);
            flash.addFlashAttribute("message", "FRD 작업을 완료하고 개발요청서를 만들었습니다.");
            return "redirect:/projects/%s/artifacts/dev-requests/%s".formatted(projectId, requestId);
        } catch (FrdCompletionService.LatestReviewRequired review) {
            flash.addFlashAttribute("message", review.getMessage());
            return "redirect:/projects/%s/artifacts/frds/%s/canvas?comparisonScreenRowId=%s&confirmedCloneHead=%s"
                    .formatted(projectId, frdId, review.screenRowId(), review.cloneHead());
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
    }

    /** 선택한 수정 이력의 HTML을 현재 워크트리와 미리보기에 복원한다. */
    @PostMapping("/{frdId}/history/{historyId}/restore")
    public String restoreHistory(@PathVariable String projectId, @PathVariable String frdId,
                                 @PathVariable long historyId, RedirectAttributes flash) {
        try {
            requireEditableFrd(projectId, frdId);
            screenHistoryService.restore(projectId, frdId, historyId);
            flash.addFlashAttribute("message", "선택한 변경 이력으로 되돌렸습니다.");
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage());
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
    }

    /**
     * 만들어진 목업을 그대로 내준다. 작업대의 {@code <iframe>} 이 이 주소를 연다.
     *
     * <p>⛔ <b>클론 파일을 여기서 내주지 마라</b> — 그것은 솔루션 목업의 미리보기가 하는 일이고
     * 울타리 검사가 거기 있다. 여기서 내주는 것은 <b>DB 에 있는 우리 글자</b>뿐이다.
     *
     * <p>⭐ <b>여기 내용물은 사람이 붙여넣은 요구사항을 재료로 Claude 가 새로 지어낸 html 이다</b>
     * (2026-08-18 최종 리뷰 C2) — 기획 저장소의 정적 파일보다 위험하다. {@code SolutionPreviewController}
     * 와 <b>같은 두 헤더</b>를 붙인다. 주소를 직접 열어도(iframe 밖) {@code JSESSIONID} 를 쥔 채
     * 우리 출처에서 스크립트가 돌면 안 된다.
     */
    @GetMapping(value = "/{frdId}/screens/{screenRowId}/preview", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> preview(@PathVariable String projectId, @PathVariable String frdId,
                          @PathVariable String screenRowId,
                          @RequestParam(required = false) String facet) {
        frds.of(projectId, frdId);
        FrdScreen screen = screens.selectById(screenRowId);
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 FRD 화면이 없다");
        }
        String activeFacet = activeFacet(frdFacets.selectByFrdId(frdId).stream()
                .map(FrdFacet::name).toList(), facet);
        String html = worktreeHtml(projectId, frdId, screen, activeFacet);
        // 전환 전에 생성된 기존 데이터는 워크트리 파일이 없을 수 있어 DB 스냅샷으로만 호환한다.
        if (html == null) html = screen.html();
        if (html == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 FRD 화면이 없다");
        }
        return ResponseEntity.ok()
                // ⛔ 확장자로 정한 종류를 브라우저가 다시 짐작하지 못하게 막는다.
                .header("X-Content-Type-Options", "nosniff")
                // ⛔ 새 창으로 열어도 스크립트가 안 돌게 한다 — iframe sandbox 는 그때 없다.
                .header("Content-Security-Policy", "sandbox " + SolutionPreviewController.SANDBOX)
                .body(withSourceAssetBase(projectId, screen, html, activeFacet));
    }

    /** 선택한 변경 이력을 현재 파일로 복원하지 않고 캔버스에서만 미리 본다. */
    @GetMapping(value = "/{frdId}/history/{historyId}/preview", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> historyPreview(@PathVariable String projectId,
                                                 @PathVariable String frdId,
                                                 @PathVariable long historyId) {
        frds.of(projectId, frdId);
        FrdScreenHistory history = screenHistories.selectById(historyId);
        FrdScreen screen = history == null ? null : screens.selectById(history.frdScreenId());
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 변경 이력이 없다");
        }
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox " + SolutionPreviewController.SANDBOX)
                .body(withSourceAssetBase(projectId, screen, history.html(), null));
    }

    /** 선택한 화면 변경 이력에 함께 저장된 실행 마커를 읽기 전용으로 전달한다. */
    @GetMapping("/{frdId}/history/{historyId}/markers")
    @ResponseBody
    public List<FrdScreenMarkerService.MarkerView> historyMarkers(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable long historyId) {
        frds.of(projectId, frdId);
        FrdScreenHistory history = screenHistories.selectById(historyId);
        FrdScreen screen = history == null ? null : screens.selectById(history.frdScreenId());
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 변경 이력이 없다");
        }
        return screenMarkers.readHistory(history);
    }

    /** AI가 직접 수정한 FRD 워크트리 파일을 우선 읽는다. */
    private String worktreeHtml(String projectId, String frdId, FrdScreen screen, String requestedFacet) {
        String systemCode = screen.systemCode();
        if (systemCode == null || systemCode.isBlank()) {
            Frd frd = frds.of(projectId, frdId);
            systemCode = frd.systemCode();
        }
        if (systemCode == null || systemCode.isBlank()) return null;
        String facet = requestedFacet == null || requestedFacet.isBlank() ? screen.facet() : requestedFacet;
        Path candidate = screenFiles.existingHtml(projectId, frdId, systemCode, screen.screenId(), facet);
        if (candidate == null || !Files.isRegularFile(candidate)) return null;
        try {
            return Files.readString(candidate, StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return null;
        }
    }

    /**
     * 생성 화면은 FRD 전용 주소에서 열리므로 원본의 {@code ../assets/...} 가 다른 곳을 가리킨다.
     * 원본 화면 파일이 있던 디렉터리를 {@code base} 로 명시해 기존 CSS·이미지 상대 경로를 살린다.
     * DB의 생성 HTML은 바꾸지 않아 이미 만들어진 화면에도 바로 적용된다.
     */
    private String withSourceAssetBase(String projectId, FrdScreen screen, String html,
                                       String requestedFacet) {
        String sourceScreenId = screen.isNewScreen() && screen.baseScreenId() != null
                ? screen.baseScreenId() : screen.screenId();
        SolutionScreen source = solutions.read(projectId).stream()
                .filter(candidate -> candidate.screenId().equals(sourceScreenId))
                .findFirst().orElse(null);
        String selectedFacet = requestedFacet == null || requestedFacet.isBlank()
                ? screen.facet() : requestedFacet;
        List<String> facetNames = frdFacets.selectByFrdId(screen.frdId()).stream()
                .map(FrdFacet::name).toList();
        String skin = previewSkin(projectId,
                selectedFacet == null ? facetNames : List.of(selectedFacet));
        String sourcePath;
        if (source != null) {
            sourcePath = source.previewPath(skin);
        } else if (screen.systemCode() != null && !screen.systemCode().isBlank()) {
            // 색인이 잠시 갱신 중이어도 화면을 고를 때 함께 저장한 시스템으로 기본 배치를 복원한다.
            sourcePath = "%s/pages/%s.html".formatted(screen.systemCode(), sourceScreenId);
        } else {
            return html;
        }
        int fileNameAt = sourcePath.lastIndexOf('/');
        if (fileNameAt < 0) {
            return html;
        }
        String sourceDir = sourcePath.substring(0, fileNameAt);
        String baseHref = "/projects/%s/artifacts/solution-mockups/files/%s/"
                .formatted(projectId, sourceDir);
        /*
         * ⚠ 순서가 있다. **치환을 먼저** 하고 base 를 끼운다 — base 는 절대주소라 치환이 안 건드리지만,
         *    끼운 뒤에 돌리면 그 한 줄을 매번 헛되이 훑는다.
         * ⛔ 저장본은 안 고친다. 여기서 만든 글자는 이 응답 하나에만 산다.
         */
        return injectBaseHref(skins.draw(projectId, "core/" + sourceDir, skin, html), baseHref);
    }

    /**
     * 이 FRD 를 어느 기관으로 그리나.
     *
     * <p>차례가 있다 — ① <b>적용 대상이 하나면 그것</b> ② 프로젝트의 적용 구분이 하나뿐이면 그것
     * ③ <b>못 정하면 {@code null}</b>.
     *
     * <p>⛔ <b>여럿일 때 아무거나 고르지 마라.</b> 그것이 「제주 사업인데 익산으로 보이는」 고장의 씨다.
     * 못 정하면 색인이 그린 그대로 두는 것이 맞다 — 방향은 시스템마다 반대다(g2c {@code online-pg}).
     */
    private String previewSkin(String projectId, List<String> facetNames) {
        if (facetNames.size() == 1) {
            String code = previewFacets.codeOfName(projectId, facetNames.get(0));
            if (code != null) {
                return code;
            }
        }
        return previewFacets.only(projectId);
    }

    /** 요청값이 없거나 잘못됐으면 FRD에 저장된 첫 적용 대상을 기본 화면으로 사용한다. */
    private static String activeFacet(List<String> facetNames, String requested) {
        if (facetNames.isEmpty()) return null;
        if (requested != null && !requested.isBlank()) {
            return facetNames.stream().filter(requested::equals).findFirst().orElse(facetNames.get(0));
        }
        return facetNames.get(0);
    }

    static String injectBaseHref(String html, String baseHref) {
        int headAt = html.toLowerCase(java.util.Locale.ROOT).indexOf("<head");
        if (headAt < 0) {
            return html;
        }
        int headCloseAt = html.indexOf('>', headAt);
        if (headCloseAt < 0) {
            return html;
        }
        String base = "<base href=\"%s\">".formatted(HtmlUtils.htmlEscape(baseHref));
        return html.substring(0, headCloseAt + 1) + base + html.substring(headCloseAt + 1);
    }

    public record ScreenStatus(String state, String stateLabel, List<String> changes,
                               String failure, java.time.Instant generatedAt,
                               String progress) { }

    public record CompletionStatus(boolean modified, boolean busy, boolean canComplete, String message) { }

    public record LatestScreenHistory(Long historyId) { }

    /** 현재 선택한 화면과 짝지어진 Markdown을 기능정의서 확인 창에 전달한다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/document")
    @ResponseBody
    public FrdScreenDocumentService.ScreenDocument screenDocument(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId) {
        Frd frd = frds.of(projectId, frdId);
        return screenDocuments.read(projectId, frdId, ownedScreen(frdId, screenRowId), frd.systemCode());
    }

    /** 현재 선택한 화면의 댓글형 메모를 작성 순서대로 전달한다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/memo")
    @ResponseBody
    public FrdScreenMemoService.MemoThread screenMemo(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId) {
        frds.of(projectId, frdId);
        return screenMemos.read(ownedScreen(frdId, screenRowId));
    }

    /** 현재 선택한 화면에 작성자와 작성 시각을 보존하는 메모 한 건을 추가한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/memo")
    @ResponseBody
    public FrdScreenMemoService.MemoComment addScreenMemo(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId, @AuthenticationPrincipal BuilderUser me,
            @RequestParam(required = false) String content) {
        requireEditableFrd(projectId, frdId);
        try {
            return screenMemos.add(ownedScreen(frdId, screenRowId), me, content);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    /** 현재 선택한 화면의 실행 마커를 번호순으로 전달한다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/markers")
    @ResponseBody
    public List<FrdScreenMarkerService.MarkerView> screenMarkers(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId) {
        frds.of(projectId, frdId);
        return screenMarkers.read(ownedScreen(frdId, screenRowId));
    }

    /** 미리보기에서 선택한 단일 요소의 문구만 직접 바꾸고 새 화면 버전으로 저장한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/direct-text")
    @ResponseBody
    public FrdScreenDirectEditService.Result editScreenText(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId,
            @RequestParam String selector, @RequestParam String expectedText,
            @RequestParam String newText) {
        requireEditableFrd(projectId, frdId);
        try {
            return directEdits.edit(projectId, frdId, ownedScreen(frdId, screenRowId),
                    selector, expectedText, newText);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IllegalStateException rejected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, rejected.getMessage());
        }
    }

    /** 미리보기에서 선택한 DOM 요소와 위치에 실행 마커를 추가한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/markers")
    @ResponseBody
    public FrdScreenMarkerService.MarkerView addScreenMarker(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId, @AuthenticationPrincipal BuilderUser me,
            @RequestParam String selector, @RequestParam String elementLabel,
            @RequestParam Double relativeX, @RequestParam Double relativeY,
            @RequestParam Double documentX, @RequestParam Double documentY,
            @RequestParam String description) {
        requireEditableFrd(projectId, frdId);
        try {
            return screenMarkers.add(ownedScreen(frdId, screenRowId), me,
                    new FrdScreenMarkerService.MarkerPosition(selector, elementLabel,
                            relativeX, relativeY, documentX, documentY), description);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    /** 실행 마커의 설명을 수정한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/markers/{markerId}")
    @ResponseBody
    public FrdScreenMarkerService.MarkerView updateScreenMarker(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId, @PathVariable String markerId,
            @RequestParam String description) {
        requireEditableFrd(projectId, frdId);
        try {
            return screenMarkers.update(ownedScreen(frdId, screenRowId), markerId, description);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    /** 실행 마커 한 건을 삭제한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/markers/{markerId}/delete")
    @ResponseBody
    public ResponseEntity<Void> deleteScreenMarker(
            @PathVariable String projectId, @PathVariable String frdId,
            @PathVariable String screenRowId, @PathVariable String markerId) {
        requireEditableFrd(projectId, frdId);
        try {
            screenMarkers.delete(ownedScreen(frdId, screenRowId), markerId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, invalid.getMessage());
        }
    }

    /** 작업대가 새로고침 없이 AI 진행·완료·실패를 확인하는 가벼운 상태 문의다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/status")
    @ResponseBody
    public ScreenStatus screenStatus(@PathVariable String projectId, @PathVariable String frdId,
                                     @PathVariable String screenRowId) {
        frds.of(projectId, frdId);
        FrdScreen screen = screens.selectById(screenRowId);
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 화면이 없다");
        }
        List<String> changes = screen.changes() == null ? List.of()
                : screen.changes().lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        String currentProgress = aiProgress.of(ScreenMockupWorker.progressKey(screen.id())).stream()
                .findFirst().map(Progress::text).orElse(null);
        return new ScreenStatus(screen.state().name(), screen.stateLabel(), changes,
                screen.failure(), screen.generatedAt(), currentProgress);
    }

    /** 파일 변경과 실행 중인 AI 작업을 함께 확인해 FRD 작업 완료 버튼 상태를 결정한다. */
    @GetMapping("/{frdId}/completion-status")
    @ResponseBody
    public CompletionStatus completionStatus(@PathVariable String projectId, @PathVariable String frdId) {
        Frd frd = frds.of(projectId, frdId);
        List<FrdScreen> mine = screens.selectByFrdId(frdId);
        return completionStatus(projectId, frd, workBusy(frdId, mine));
    }

    private CompletionStatus completionStatus(String projectId, Frd frd, boolean busy) {
        String frdId = frd.id();
        try {
            boolean modified = workspaces.hasChanges(projectId, frdId);
            boolean reopened = !modified && workspaces.hasCompletionToReopen(projectId, frdId,
                    FrdWorkspace.completionMessage(frd.label()));
            boolean completingFromReview = completion.canCompleteFromReview(projectId, frd);
            if (frd.state() != Frd.State.DRAFTING && !completingFromReview) {
                return new CompletionStatus(modified, busy, false,
                        "수정 중인 FRD만 작업을 완료할 수 있습니다.");
            }
            if (busy) {
                return new CompletionStatus(modified, true, false,
                        "AI가 화면을 수정하고 있어 완료할 수 없습니다.");
            }
            if (!modified && !reopened && !completingFromReview) {
                return new CompletionStatus(false, false, false,
                        "수정된 내용이 있어야 FRD 작업을 완료할 수 있습니다.");
            }
            return new CompletionStatus(modified || reopened || completingFromReview, false, true,
                    completingFromReview || reopened
                            ? "기존 개발요청서를 최신 FRD 작업 내용으로 다시 만듭니다."
                            : "FRD 작업을 완료합니다.");
        } catch (RuntimeException failure) {
            log.warn("FRD 작업공간 변경 여부를 확인하지 못했습니다. projectId={} frdId={}", projectId, frdId,
                    failure);
            return new CompletionStatus(false, busy, false,
                    "작업공간 변경 여부를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    private boolean workBusy(String frdId, List<FrdScreen> mine) {
        return mine.stream().anyMatch(screen -> screen.state() == FrdScreen.State.GENERATING)
                || screenChats.running(frdId) != null;
    }

    /** AI 완료를 감지한 순간에만 팝업에 더할 최신 변경 이력을 읽는다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/history/latest")
    @ResponseBody
    public LatestScreenHistory latestScreenHistory(@PathVariable String projectId,
                                                    @PathVariable String frdId,
                                                    @PathVariable String screenRowId) {
        frds.of(projectId, frdId);
        FrdScreen screen = screens.selectById(screenRowId);
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 화면이 없다");
        }
        FrdScreenHistory latest = screenHistories.selectLatestByScreenId(screenRowId);
        if (latest == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 변경 이력이 없다");
        }
        return new LatestScreenHistory(latest.id());
    }

    /** 작업대의 AI 대화를 별도 창으로 넓혀 보는 화면이다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/chat")
    public String screenChatWindow(@PathVariable String projectId, @PathVariable String frdId,
                                   @PathVariable String screenRowId,
                                   @AuthenticationPrincipal BuilderUser me, Model model) {
        frds.of(projectId, frdId);
        FrdScreen screen = screens.selectById(screenRowId);
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 화면이 없다");
        }
        model.addAttribute("screen", screen);
        model.addAttribute("chatStatusUrl", "/projects/%s/artifacts/frds/%s/screens/%s/chat/messages"
                .formatted(projectId, frdId, screenRowId));
        model.addAttribute("chatSendUrl", "/projects/%s/artifacts/frds/%s/screens/%s/chat/messages"
                .formatted(projectId, frdId, screenRowId));
        model.addAttribute("chatEventsUrl", "/projects/%s/artifacts/frds/%s/chat/events"
                .formatted(projectId, frdId));
        model.addAttribute("claudeIdentity", claudeCredentials.identityOf(me.accountId()).orElse(null));
        return "artifacts/frd-chat";
    }

    public record ScreenChatLine(String id, String role, String state, String content,
                                 String failure, java.time.Instant createdAt) { }

    public record ScreenChatProgress(String kind, String text) { }

    public record ActiveScreenChat(String id, String screenRowId, String screenId,
                                   String screenName, List<ScreenChatProgress> progress) { }

    public record ScreenChatStatus(List<ScreenChatLine> messages, ActiveScreenChat active, int screenCount) { }

    public record ScreenChatStarted(String id, String screenRowId) { }

    /** 화면 대화 상태가 바뀔 때 브라우저가 즉시 알림을 받는 SSE 연결이다. */
    @GetMapping(value = "/{frdId}/chat/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter screenChatEvents(@PathVariable String projectId, @PathVariable String frdId) {
        frds.of(projectId, frdId);
        return screenChatEvents.subscribe(frdId);
    }

    /** 팝업·탭 종료로 끊어진 SSE 응답은 서버 장애가 아니므로 스택 트레이스를 남기지 않는다. */
    @ExceptionHandler(IOException.class)
    public ModelAndView disconnectedScreenChat(IOException failure) throws IOException {
        if (!FrdScreenChatEvents.isClientDisconnect(failure)) throw failure;
        log.debug("FRD 화면 대화 SSE 연결이 브라우저에서 종료됐다: {}", failure.getMessage());
        return new ModelAndView();
    }

    /** 화면별 대화 이력과 FRD에서 현재 실행 중인 수정 작업을 함께 돌려준다. */
    @GetMapping("/{frdId}/screens/{screenRowId}/chat/messages")
    @ResponseBody
    public ScreenChatStatus screenChatMessages(@PathVariable String projectId, @PathVariable String frdId,
                                               @PathVariable String screenRowId) {
        frds.of(projectId, frdId);
        FrdScreen requested = ownedScreen(frdId, screenRowId);
        List<ScreenChatLine> lines = screenChats.messages(requested.id()).stream()
                .map(message -> new ScreenChatLine(message.id(), message.role().name(), message.state().name(),
                        message.content(), message.failure(), message.createdAt()))
                .toList();
        FrdScreenChatMessage running = screenChats.running(frdId);
        ActiveScreenChat active = null;
        if (running != null) {
            FrdScreen activeScreen = screens.selectById(running.frdScreenId());
            List<Progress> kept = new ArrayList<>(aiProgress.of(FrdScreenChatWorker.progressKey(running.id())));
            Collections.reverse(kept);
            active = new ActiveScreenChat(running.id(), running.frdScreenId(), activeScreen.screenId(),
                    activeScreen.screenName() == null ? activeScreen.screenId() : activeScreen.screenName(),
                    kept.stream().map(step -> new ScreenChatProgress(step.kind().name(), step.text())).toList());
        }
        return new ScreenChatStatus(lines, active, screens.selectByFrdId(frdId).size());
    }

    /** 선택한 화면에 사용자 요청을 저장하고 Sonnet 화면 대화를 시작한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/chat/messages")
    @ResponseBody
    public ResponseEntity<ScreenChatStarted> startScreenChat(@PathVariable String projectId,
                                                              @PathVariable String frdId,
                                                              @PathVariable String screenRowId,
                                                              @RequestParam String message,
                                                              @RequestParam(required = false) String selectedRegion,
                                                              @RequestParam(required = false) MultipartFile referenceImage) {
        requireEditableFrd(projectId, frdId);
        FrdScreen screen = ownedScreen(frdId, screenRowId);
        String region = selectedRegion == null ? null : selectedRegion.strip();
        if (region != null && region.length() > 12_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "선택한 수정 영역 정보가 너무 큽니다. 더 작은 영역을 선택해 주세요.");
        }
        if (region != null && region.isBlank()) region = null;
        if (screens.selectByFrdId(frdId).stream()
                .anyMatch(candidate -> candidate.state() == FrdScreen.State.GENERATING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "AI 초안을 만들고 있습니다. 완료된 뒤 화면 질문이나 작업을 요청해 주세요.");
        }
        Path storedImage;
        try {
            storedImage = referenceImages.store(referenceImage);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IllegalStateException failed) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failed.getMessage());
        }
        String request = message;
        if ((request == null || request.isBlank()) && storedImage != null) {
            request = "첨부한 참고 이미지의 구조를 반영해 현재 화면을 수정해 주세요.";
        }
        FrdScreenChatMessage started;
        try {
            started = screenChats.start(frdId, screen.id(), request);
            screenChatEvents.publish(frdId);
        } catch (IllegalArgumentException invalid) {
            referenceImages.delete(storedImage);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IllegalStateException busy) {
            referenceImages.delete(storedImage);
            throw new ResponseStatusException(HttpStatus.CONFLICT, busy.getMessage());
        } catch (DataIntegrityViolationException raced) {
            referenceImages.delete(storedImage);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "다른 화면 요청을 AI가 처리하고 있습니다. 완료된 뒤 다시 요청해 주세요.");
        }
        try {
            screenChatWorker.edit(started.id(), region, storedImage);
        } catch (TaskRejectedException busy) {
            referenceImages.delete(storedImage);
            screenChats.fail(started.id(), "AI 작업이 많아 시작하지 못했습니다. 잠시 후 다시 요청해 주세요.");
            screenChatEvents.publish(frdId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 작업이 많아 시작하지 못했습니다. 잠시 후 다시 요청해 주세요.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ScreenChatStarted(started.id(), started.frdScreenId()));
    }

    /** Esc로 요청한 화면 Claude 작업을 즉시 중단하고 수정 전 파일로 돌아가게 한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/chat/messages/{messageId}/cancel")
    @ResponseBody
    public ResponseEntity<Void> cancelScreenChat(@PathVariable String projectId,
                                                  @PathVariable String frdId,
                                                  @PathVariable String screenRowId,
                                                  @PathVariable String messageId) {
        requireEditableFrd(projectId, frdId);
        FrdScreen screen = ownedScreen(frdId, screenRowId);
        FrdScreenChatMessage message = screenChats.messages(screen.id()).stream()
                .filter(item -> item.id().equals(messageId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 화면 작업이 없습니다."));
        if (message.state() == FrdScreenChatMessage.State.RUNNING) {
            chatCancellations.cancel(messageId);
            screenChats.fail(messageId, "사용자가 화면 작업을 중단했습니다.");
            screenChatEvents.publish(frdId);
        }
        return ResponseEntity.accepted().build();
    }

    private FrdScreen ownedScreen(String frdId, String screenRowId) {
        FrdScreen screen = screens.selectById(screenRowId);
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 화면이 없다");
        }
        return screen;
    }

    /** 완료된 FRD의 상세 캔버스 변경 요청을 서버에서도 차단한다. */
    private Frd requireEditableFrd(String projectId, String frdId) {
        Frd frd = frds.of(projectId, frdId);
        requireAssignedUser(frd);
        if (frd.state() != Frd.State.DRAFTING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "완료된 FRD에서는 상세 캔버스를 변경할 수 없습니다.");
        }
        return frd;
    }

    private static boolean isAssignedUser(Frd frd, BuilderUser user) {
        return frd.ownerAccountId() == null || frd.ownerAccountId().isBlank()
                || user != null && frd.ownerAccountId().equals(user.accountId());
    }

    private static void requireAssignedUser(Frd frd) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof BuilderUser user) || !isAssignedUser(frd, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "담당자만 FRD 작업을 수정할 수 있습니다.");
        }
    }

    /** 「AI 초안 만들기」·「AI 초안 다시 만들기」 — 일꾼을 깨우고 작업대로 돌아간다. */
    @PostMapping("/{frdId}/generate")
    public String generateAll(@PathVariable String projectId, @PathVariable String frdId,
                              RedirectAttributes flash) {
        requireEditableFrd(projectId, frdId);
        if (screenChats.running(frdId) != null) {
            flash.addFlashAttribute("error", "AI가 화면 요청을 처리하고 있습니다. 완료된 뒤 초안을 만들어 주세요.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        List<FrdScreen> mine = screens.selectByFrdId(frdId);
        if (mine.stream().filter(FrdScreen::isAiDraftEligible)
                .anyMatch(screen -> screen.state() == FrdScreen.State.GENERATING)) {
            flash.addFlashAttribute("error", "AI 초안을 이미 만들고 있습니다.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        if (mine.stream().noneMatch(FrdScreen::isAiDraftEligible)) {
            flash.addFlashAttribute("message", "AI 초안을 만들 수 있는 화면이 없습니다. 사용자 선택 화면은 AI와 화면 대화에서 작업해 주세요.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        if (mine.stream().noneMatch(FrdScreen::canGenerateDraft)) {
            flash.addFlashAttribute("message", "AI 초안이 모두 준비되어 있습니다.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        try {
            log.info("FRD 일괄 AI 초안 만들기 요청 projectId={} frdId={} 대상={}개", projectId, frdId,
                    mine.stream().filter(FrdScreen::canGenerateDraft).count());
            mockupBatchWorker.generate(frdId);
        } catch (TaskRejectedException full) {
            log.warn("FRD 일괄 목업 만들기를 제출하지 못했다 — 대기줄이 찼다 frdId={}", frdId);
            flash.addFlashAttribute("error", "지금은 자리가 다 찼습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
    }

    /** 「AI 초안 만들기」·「AI 초안 다시 만들기」 — 일꾼을 깨우고 작업대로 돌아간다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/generate")
    public String generate(@PathVariable String projectId, @PathVariable String frdId,
                           @PathVariable String screenRowId, RedirectAttributes flash) {
        requireEditableFrd(projectId, frdId);
        FrdScreen screen = screens.selectById(screenRowId);
        if (screen == null || !screen.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 화면이 없다");
        }
        if (!screen.isAiDraftEligible()) {
            flash.addFlashAttribute("error", "사용자가 선택한 화면은 AI와 화면 대화에서 작업해 주세요.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        // ⛔ [2026-08-18 최종 리뷰 I2] WAITING·FAILED 일 때만 통과시킨다 — retryPick·confirmPick 과
        //    같은 관용구다. 문지기가 없으면 GENERATING 인 화면에 또 눌러 이중 제출이 된다.
        if (screen.state() != FrdScreen.State.WAITING && screen.state() != FrdScreen.State.FAILED) {
            flash.addFlashAttribute("error", "AI 초안을 이미 만들고 있습니다.");
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        try {
            mockupWorker.generate(screenRowId);
        } catch (TaskRejectedException full) {
            // ⛔ 대기줄이 차서 거절돼도 500 을 내지 않는다 — 화면은 그대로 초안 생성 가능 상태다.
            log.warn("목업 만들기를 제출하지 못했다 — 대기줄이 찼다 frdScreenId={}", screenRowId);
            flash.addFlashAttribute("error", "지금은 자리가 다 찼습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }
}
