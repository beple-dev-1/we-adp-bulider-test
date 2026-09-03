package com.bizplay.builder.frd;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FRD 마법사 — 두 걸음. 요구사항을 붙여넣으면(걸음 1) AI 가 화면을 짚고, 사람이 그것을 확인·확정한다(걸음 2).
 *
 * <p>목업 {@code docs/mockups/05b-frd-wizard.html} 이 정본이다.
 * 설계는 {@code docs/superpowers/specs/2026-08-18-frd-fast-track-design.md}.
 *
 * <p>⚠ 프로젝트 이름·번호·알림은 <b>여기서 안 담는다</b> —
 * {@link com.bizplay.builder.web.ProjectContextInterceptor} 가 한 자리에서 얹는다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/frds")
public class FrdWizardController {

    private static final Logger log = LoggerFactory.getLogger(FrdWizardController.class);

    private final FrdService frds;
    private final FrdScreenMapper screens;
    private final FrdItemMapper items;
    private final ScreenPickWorker picker;
    private final SolutionScreenReader solutions;
    private final FrdInterviewService interviews;
    private final ProjectFacetMapper projectFacets;
    /** ⚠ 시험 전용 생성자에서는 {@code null} 이다 — 부르기 전에 반드시 확인한다. */
    private final com.bizplay.builder.solution.PreviewFacets previewFacets;
    private final FrdFacetMapper frdFacets;
    private final FrdScreenChatEvents liveEvents;
    private final ProjectSystemService projectSystems;
    private final FrdScreenIaPlacementService iaPlacements;

    private final com.bizplay.builder.ai.AiProgress progress;

    @Autowired
    public FrdWizardController(FrdService frds, FrdScreenMapper screens, FrdItemMapper items,
                               ScreenPickWorker picker, SolutionScreenReader solutions,
                               com.bizplay.builder.ai.AiProgress progress,
                               FrdInterviewService interviews, ProjectFacetMapper projectFacets,
                               com.bizplay.builder.solution.PreviewFacets previewFacets,
                               FrdFacetMapper frdFacets, FrdScreenChatEvents liveEvents,
                               ProjectSystemService projectSystems,
                               FrdScreenIaPlacementService iaPlacements) {
        this.frds = frds;
        this.screens = screens;
        this.items = items;
        this.picker = picker;
        this.solutions = solutions;
        this.progress = progress;
        this.interviews = interviews;
        this.projectFacets = projectFacets;
        this.previewFacets = previewFacets;
        this.frdFacets = frdFacets;
        this.liveEvents = liveEvents;
        this.projectSystems = projectSystems;
        this.iaPlacements = iaPlacements;
    }

    /** 대기줄 거절만 직접 검사하는 기존 단위 테스트용 생성자. */
    public FrdWizardController(FrdService frds, FrdScreenMapper screens, FrdItemMapper items,
                               ScreenPickWorker picker, SolutionScreenReader solutions,
                               com.bizplay.builder.ai.AiProgress progress) {
        this(frds, screens, items, picker, solutions, progress, null, null, null, null, null, null, null);
    }

    /**
     * 이 자리를 어느 기관으로 그리나 — {@code FrdController.previewSkin} 과 같은 차례다.
     *
     * <p>⛔ 여럿이면 {@code null} 이다. 못 정할 때 아무 기관이나 고르면 방향이 반대인 시스템이
     * 통째로 틀린다(설계 {@code 2026-08-22-preview-skin-design} §3).
     */
    private String previewSkin(String projectId, List<String> facetNames) {
        if (previewFacets == null) {
            return null;
        }
        if (facetNames.size() == 1) {
            String code = previewFacets.codeOfName(projectId, facetNames.get(0));
            if (code != null) {
                return code;
            }
        }
        return previewFacets.only(projectId);
    }

    private List<String> frdFacetNames(String frdId) {
        return frdFacets == null ? List.of()
                : frdFacets.selectByFrdId(frdId).stream().map(FrdFacet::name).toList();
    }

    /** 걸음 1 — 요구사항을 붙여넣는 자리. */
    @GetMapping("/new")
    public String newFrd(@PathVariable String projectId, Model model) {
        List<SolutionScreen> candidates = solutions.read(projectId);
        Map<String, String> previewPaths = new LinkedHashMap<>();
        Set<String> systems = candidateSystemCodes(projectId, candidates);
        String previewSkin = previewSkin(projectId, List.of());
        candidates.forEach(screen -> {
            previewPaths.put(screen.screenId(), screen.previewPath(previewSkin));
        });
        model.addAttribute("previewSkin", previewSkin);
        model.addAttribute("previewSkinQuery", previewSkin == null ? "" : "?skin=" + previewSkin);
        model.addAttribute("availableFacets", projectFacets == null
                ? List.of() : projectFacets.selectByProjectId(projectId));
        model.addAttribute("candidates", candidates);
        model.addAttribute("candidatePreviewPaths", previewPaths);
        model.addAttribute("newScreenTypes", FrdService.NEW_SCREEN_TYPES);
        model.addAttribute("candidateSystems", systems);
        model.addAttribute("systemLabels", systemLabels(projectId));
        shell(model, "FRD 작업 만들기");
        return "artifacts/frd-wizard";
    }

    /**
     * 걸음 1 을 제출한다 — FRD 를 앉히고 화면 짚기를 깨운 뒤 걸음 2 로 보낸다.
     *
     * <p>⛔ <b>일꾼은 트랜잭션 밖에서 깨운다</b>({@code IntakeController.analyzeRequirements} 와 같은
     * 규칙) — 안에서 부르면 일꾼이 아직 커밋 전인 줄을 볼 수 있다.
     */
    @PostMapping
    public String open(@PathVariable String projectId, @RequestParam String sourceText,
                       @RequestParam(required = false) List<String> facet,
                       @RequestParam(required = false) List<String> screenId,
                       @RequestParam(required = false) List<String> screenType,
                       @RequestParam(required = false) List<String> screenName,
                       @RequestParam(required = false) List<String> baseScreenId,
                       @RequestParam(name = "newScreenName", required = false) List<String> newScreenNames,
                       @RequestParam(name = "newScreenType", required = false) List<String> newScreenTypes,
                       @RequestParam(name = "newScreenSystem", required = false) List<String> newScreenSystems,
                       @RequestParam(name = "newScreenKind", required = false) List<String> newScreenKinds,
                       @RequestParam(name = "newScreenPlacementMode", required = false) List<String> newScreenPlacementModes,
                       @RequestParam(name = "newScreenAnchor", required = false) List<String> newScreenAnchors,
                       @RequestParam(name = "newScreenMenuPath", required = false) List<String> newScreenMenuPaths,
                       @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        String frdId;
        try {
            List<FrdService.ScreenSelection> selectedScreens = new ArrayList<>(
                    selections(screenId, screenName, baseScreenId, screenType));
            selectedScreens.addAll(newScreenSelections(newScreenNames, newScreenTypes, newScreenSystems,
                    newScreenKinds, newScreenPlacementModes, newScreenAnchors, newScreenMenuPaths));
            frdId = frds.open(projectId, sourceText, me.accountId(), facet,
                    selectedScreens);
        } catch (IllegalArgumentException rejected) {
            // ⛔ 500 을 내지 않는다. 사람이 고칠 수 있는 것이다.
            flash.addFlashAttribute("error", rejected.getMessage());
            return "redirect:/projects/%s/artifacts/frds/new".formatted(projectId);
        }
        dispatchPick(frdId);
        return pickRedirect(projectId, frdId);
    }

    /**
     * 걸음 2 — 상태를 보고 갈래를 낸다: 아직 짚는 중(대기) · 분석 오류(재시도) · 짚은 화면 확인.
     *
     * <p>⛔ <b>작업대(FrdController 의 {@code GET /frds/{frdId}})는 여기가 아니다</b> —
     * {@code ANALYZING}·{@code ANALYSIS_FAILED}·{@code PICKED} 인 FRD 는 그쪽에서 이 걸음으로 되돌린다.
     */
    @GetMapping({"/{frdId}/pick", "/{frdId}/interview"})
    public String pick(@PathVariable String projectId, @PathVariable String frdId,
                       @RequestParam(name = "query", defaultValue = "") String listQuery,
                       @RequestParam(name = "state", defaultValue = "") String listState,
                       @RequestParam(name = "owner", defaultValue = "") String listOwner,
                       @RequestParam(name = "system", defaultValue = "") String listSystem,
                       @RequestParam(name = "page", defaultValue = "1") int listPage,
                       @RequestParam(name = "pageSize", defaultValue = "10") int listPageSize,
                       Model model) {
        Frd frd = frds.of(projectId, frdId);
        List<FrdScreen> selectedScreens = screens.selectByFrdId(frdId);
        List<SolutionScreen> candidateScreens = solutions.read(projectId);
        Map<String, SolutionScreen> coreScreensById = new LinkedHashMap<>();
        candidateScreens.forEach(screen -> coreScreensById.put(screen.screenId(), screen));
        String previewSkin = previewSkin(projectId, frdFacetNames(frdId));
        Map<String, String> screenPreviewPaths = new LinkedHashMap<>();
        for (FrdScreen screen : selectedScreens) {
            String previewScreenId = screen.isNewScreen() && screen.baseScreenId() != null
                ? screen.baseScreenId() : screen.screenId();
            SolutionScreen coreScreen = coreScreensById.get(previewScreenId);
            if (coreScreen != null) {
                screenPreviewPaths.put(screen.id(), coreScreen.previewPath(previewSkin));
            }
        }
        Map<String, String> candidatePreviewPaths = new LinkedHashMap<>();
        Set<String> candidateSystems = candidateSystemCodes(projectId, candidateScreens);
        for (SolutionScreen candidate : candidateScreens) {
            candidatePreviewPaths.put(candidate.screenId(), candidate.previewPath(previewSkin));
        }
        model.addAttribute("previewSkin", previewSkin);
        model.addAttribute("previewSkinQuery", previewSkin == null ? "" : "?skin=" + previewSkin);
        Set<String> selectedScreenIds = new LinkedHashSet<>();
        selectedScreens.forEach(screen -> selectedScreenIds.add(screen.screenId()));
        List<FrdBackendChange> backendChangeList = interviews == null
                ? List.of() : interviews.backendChanges(frdId);
        List<FrdBackendChange> requiredBackendChanges = backendChangeList.stream()
                .filter(FrdBackendChange::required).toList();
        model.addAttribute("frd", frd);
        model.addAttribute("screens", selectedScreens);
        Map<String, FrdScreenIaPlacement> iaPlacementByScreenRowId = new LinkedHashMap<>();
        if (iaPlacements != null) {
            iaPlacements.all(frdId).forEach(placement ->
                    iaPlacementByScreenRowId.put(placement.frdScreenId(), placement));
        }
        model.addAttribute("iaPlacementByScreenRowId", iaPlacementByScreenRowId);
        model.addAttribute("screenPreviewPaths", screenPreviewPaths);
        model.addAttribute("candidatePreviewPaths", candidatePreviewPaths);
        model.addAttribute("newScreenTypes", FrdService.NEW_SCREEN_TYPES);
        model.addAttribute("candidateSystems", candidateSystems);
        model.addAttribute("systemLabels", systemLabels(projectId));
        model.addAttribute("selectedScreenIds", selectedScreenIds);
        // ⭐ 요구사항 항목마다의 판정. 이것이 조용한 누락을 사람 눈에 드러내는 유일한 자리다
        //   (2026-08-18 실측 — 6건짜리가 화면 1장으로 끝나고 다섯이 말없이 사라졌다).
        List<FrdItem> requirementItems = items.selectByFrdId(frdId);
        model.addAttribute("items", requirementItems);
        List<FrdInterviewMessage> interviewMessages = interviews == null
                ? List.of() : interviews.messages(frdId);
        model.addAttribute("interviewMessages", interviewMessages);
        model.addAttribute("questionRound", interviews == null ? 0 : interviews.currentQuestionRound(frdId));
        model.addAttribute("latestAnalysisSummary", interviewMessages.stream()
                .filter(message -> message.role() == FrdInterviewMessage.Role.AI
                        && message.kind() == FrdInterviewMessage.Kind.SUMMARY)
                .reduce((before, latest) -> latest).map(FrdInterviewMessage::content).orElse(null));
        model.addAttribute("latestAssistantMessage", interviewMessages.stream()
                .filter(message -> message.role() == FrdInterviewMessage.Role.AI
                        && message.kind() == FrdInterviewMessage.Kind.MESSAGE)
                .reduce((before, latest) -> latest).map(FrdInterviewMessage::content).orElse(null));
        model.addAttribute("hasResultSummary", !interviewMessages.isEmpty()
                && interviewMessages.get(interviewMessages.size() - 1).role() == FrdInterviewMessage.Role.AI
                && interviewMessages.get(interviewMessages.size() - 1).kind() == FrdInterviewMessage.Kind.SUMMARY);
        model.addAttribute("currentQuestion", interviewMessages.stream()
                .filter(message -> message.kind() == FrdInterviewMessage.Kind.QUESTION)
                .reduce((before, latest) -> latest).orElse(null));
        model.addAttribute("backendChanges", backendChangeList);
        model.addAttribute("requiredBackendChanges", requiredBackendChanges);
        model.addAttribute("hasDevelopmentChanges",
                !selectedScreens.isEmpty() || !requiredBackendChanges.isEmpty());
        List<FrdAnalysisNote> analysisNotes = interviews == null ? List.of() : interviews.notes(frdId);
        model.addAttribute("acceptanceCriteria", analysisNotes.stream()
                .filter(note -> note.kind() == FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION).toList());
        model.addAttribute("openIssues", analysisNotes.stream()
                .filter(note -> note.kind() == FrdAnalysisNote.Kind.OPEN_ISSUE).toList());
        FrdAnalysisNote workMode = analysisNotes.stream()
                .filter(note -> note.kind() == FrdAnalysisNote.Kind.WORK_MODE_FAST_TRACK
                        || note.kind() == FrdAnalysisNote.Kind.WORK_MODE_FRD)
                .findFirst().orElse(null);
        boolean hasOpenIssues = analysisNotes.stream()
                .anyMatch(note -> note.kind() == FrdAnalysisNote.Kind.OPEN_ISSUE);
        /*
         * ⭐ 빠른 진행은 화면 작업이 없을 때 하나다 (2026-09-02 병주 확정).
         *   종전에는 AI 권장(FAST_TRACK)이 있으면 화면 한 장까지 열었다 — 그 몫은 SRT 가 받는다.
         *   ⛔ 권장 여부로 화면 있는 작업을 다시 열지 마라. 문은 FrdCompletionService 가 같은 기준으로 지킨다.
         */
        model.addAttribute("fastTrackAvailable", !hasOpenIssues && selectedScreens.isEmpty()
                && frd.state() == Frd.State.SCOPE_REVIEW);
        model.addAttribute("workModeReason", workMode == null
                ? (selectedScreens.isEmpty() ? "프론트 화면 작업이 없어 개발요청서로 바로 진행할 수 있습니다." : null)
                : workMode.content());
        // ⚠ ANALYZING 이면 화면이 스스로 다시 읽는다 — 몇 분짜리 일이라 아무 말도 안 하면
        //   사람이 「눌리긴 한 건가」를 알 수 없다(2026-08-16 병주 실측).
        model.addAttribute("waiting", frd.state() == Frd.State.ANALYZING);
        // ⭐ 도는 중이면 AI 가 지금 무엇을 하고 무엇을 알아냈는지 그대로 보여 준다
        //   (2026-08-18 병주 요청). ⚠ 열쇠는 일꾼과 같은 자리에서 만든다 — 갈리면 늘 빈 목록이다.
        // AiProgress 공통 저장소는 최신 항목부터 돌려주지만, 대화창은 시간순으로 아래에 붙인다.
        List<com.bizplay.builder.ai.ClaudeRunner.Progress> progressSteps =
                new ArrayList<>(progress.of(ScreenPickWorker.progressKey(frdId)));
        Collections.reverse(progressSteps);
        model.addAttribute("progress", progressSteps);
        model.addAttribute("failed", frd.state() == Frd.State.ANALYSIS_FAILED);
        model.addAttribute("candidates", candidateScreens);   // 「화면 직접 고르기」의 재료
        model.addAttribute("listQuery", listQuery);
        model.addAttribute("listState", listState);
        model.addAttribute("listOwner", listOwner);
        model.addAttribute("listSystem", listSystem);
        model.addAttribute("listPage", listPage);
        model.addAttribute("listPageSize", listPageSize);
        shell(model, "FRD 작업 만들기");
        return "artifacts/frd-wizard";
    }

    /** 인터뷰 상태가 바뀐 뒤 페이지 이동 없이 작업 영역만 다시 그린다. */
    @GetMapping(value = "/{frdId}/interview/fragment", produces = MediaType.TEXT_HTML_VALUE)
    public String interviewFragment(@PathVariable String projectId, @PathVariable String frdId, Model model) {
        pick(projectId, frdId, "", "", "", "", 1, 10, model);
        return "artifacts/frd-wizard :: wizard-content";
    }

    /** 분석 화면이 전체 페이지를 다시 읽지 않고 새 진행 내용과 상태만 확인한다. */
    @GetMapping("/{frdId}/interview/progress")
    @ResponseBody
    public InterviewProgress interviewProgress(@PathVariable String projectId,
                                                @PathVariable String frdId) {
        Frd frd = frds.of(projectId, frdId);
        List<com.bizplay.builder.ai.ClaudeRunner.Progress> progressSteps =
                new ArrayList<>(progress.of(ScreenPickWorker.progressKey(frdId)));
        Collections.reverse(progressSteps);
        return new InterviewProgress(frd.state().name(), progressSteps.stream()
                .map(step -> new InterviewProgressLine(step.kind().name(), step.text()))
                .toList());
    }

    /** 작업공간 채팅과 같은 SSE 연결로 인터뷰 분석 상태 변경을 즉시 알린다. */
    @GetMapping(value = "/{frdId}/interview/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter interviewEvents(@PathVariable String projectId,
                                      @PathVariable String frdId) {
        frds.of(projectId, frdId);
        return liveEvents.subscribe(frdId);
    }

    /** 새로고침·탭 닫기로 끝난 SSE 연결은 사용자에게 보여 줄 서버 오류가 아니다. */
    @ExceptionHandler(IOException.class)
    public ModelAndView disconnectedInterview(IOException failure) throws IOException {
        if (!FrdScreenChatEvents.isClientDisconnect(failure)) throw failure;
        log.debug("FRD 인터뷰 SSE 연결이 브라우저에서 종료되었습니다: {}", failure.getMessage());
        return new ModelAndView();
    }

    public record InterviewProgress(String state, List<InterviewProgressLine> progress) { }

    public record InterviewProgressLine(String kind, String text) { }

    /** 분석 오류에서 「다시 분석하기」 — 상태를 되돌리고 일꾼을 다시 깨운다. */
    @PostMapping({"/{frdId}/pick/retry", "/{frdId}/interview/retry"})
    public String retry(@PathVariable String projectId, @PathVariable String frdId, RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            frds.retryPick(frdId);
        } catch (IllegalStateException rejected) {
            // ⛔ 500 을 내지 않는다. ANALYSIS_FAILED 가 아닌데 눌러도 사람이 고칠 수 있다.
            flash.addFlashAttribute("error", rejected.getMessage());
            return pickRedirect(projectId, frdId);
        }
        dispatchPick(frdId);
        return pickRedirect(projectId, frdId);
    }

    /** 현재 질문에 답하고 다음 단위 분석을 시작한다. */
    @PostMapping("/{frdId}/interview/answers")
    public String answer(@PathVariable String projectId, @PathVariable String frdId,
                         @RequestParam String questionId,
                         @RequestParam String answerType,
                         @RequestParam(required = false) String answer,
                         @RequestParam(required = false) String directAnswer,
                         RedirectAttributes flash) {
        frds.of(projectId, frdId);
        String chosen = "DIRECT".equals(answerType) ? directAnswer : answer;
        try {
            interviews.answer(frdId, questionId, chosen);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return pickRedirect(projectId, frdId);
        }
        dispatchPick(frdId);
        return pickRedirect(projectId, frdId);
    }

    /** 답변을 저장하고 AI 분석을 시작한다. 브라우저는 인터뷰 영역을 그대로 유지한다. */
    @PostMapping(value = "/{frdId}/interview/answers/async", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> answerAsync(@PathVariable String projectId,
                                                            @PathVariable String frdId,
                                                            @RequestParam String questionId,
                                                            @RequestParam String answerType,
                                                            @RequestParam(required = false) String answer,
                                                            @RequestParam(required = false) String directAnswer) {
        frds.of(projectId, frdId);
        String chosen = "DIRECT".equals(answerType) ? directAnswer : answer;
        try {
            interviews.answer(frdId, questionId, chosen);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "message", rejected.getMessage()));
        }
        dispatchPick(frdId);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    /** 사용자가 더 묻지 않고 지금까지 확인한 내용으로 범위 정리를 요청한다. */
    @PostMapping("/{frdId}/interview/finish")
    public String finishInterview(@PathVariable String projectId, @PathVariable String frdId,
                                  @RequestParam String questionId, RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            interviews.answer(frdId, questionId, "현재 내용으로 범위 정리해 주세요.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return pickRedirect(projectId, frdId);
        }
        dispatchPick(frdId);
        return pickRedirect(projectId, frdId);
    }

    /** 결과가 충분하지 않을 때 인터뷰를 한 차례 더 요청한다. */
    @PostMapping("/{frdId}/interview/continue")
    public String continueInterview(@PathVariable String projectId, @PathVariable String frdId,
                                    RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            interviews.requestMoreQuestions(frdId);
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return pickRedirect(projectId, frdId);
        }
        dispatchPick(frdId);
        return pickRedirect(projectId, frdId);
    }

    /** 개발 범위 확인에서 기존 결과를 유지하고 인터뷰 결과 화면으로 돌아간다. */
    @PostMapping("/{frdId}/interview/reopen")
    public String reopenInterview(@PathVariable String projectId, @PathVariable String frdId,
                                  RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            interviews.reopen(frdId);
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return pickRedirect(projectId, frdId);
    }

    /** 분석 결과를 보며 입력한 질문이나 추가 조건을 대화에 남기고 다시 분석한다. */
    @PostMapping("/{frdId}/interview/messages")
    public String continueWithMessage(@PathVariable String projectId, @PathVariable String frdId,
                                      @RequestParam String message, RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            interviews.continueWithMessage(frdId, message);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return pickRedirect(projectId, frdId);
        }
        dispatchPick(frdId);
        return pickRedirect(projectId, frdId);
    }

    /** 사람이 승인한 분석 결과를 확정하고 결과 확인 단계로 이동한다. */
    @PostMapping({"/{frdId}/pick", "/{frdId}/interview/confirm"})
    public String confirmPick(@PathVariable String projectId, @PathVariable String frdId,
                              @RequestParam(required = false) List<String> keep,
                              RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            frds.confirmPick(frdId, keep);
        } catch (IllegalStateException rejected) {
            // ⛔ 500 을 내지 않는다. 아직 짚는 중이거나 오류인 FRD 에 확정을 눌러도 사람이 고칠 수 있다.
            flash.addFlashAttribute("error", rejected.getMessage());
            return pickRedirect(projectId, frdId);
        }
        /*
         * ⭐ 인터뷰가 끝났으니 실행 자리를 놓아준다 (2026-08-19). 그 안에는 요구사항 사본과
         *   이어붙이던 대화가 있다 — 정본은 DB 이고, 확정한 뒤로는 남길 까닭이 없다.
         *   ⚠ 확정이 성공한 뒤에만 부른다: 실패해서 되돌아가는 길에 지우면 다음 판이 처음부터 돈다.
         */
        picker.release(frdId);
        return pickRedirect(projectId, frdId);
    }

    /**
     * 「화면 직접 고르기」· 「신규 화면 개발」 — 사람이 화면을 더한다.
     *
     * <p>⚠ <b>{@code screenId} 는 선택이다 (2026-08-22).</b> 「신규 화면 개발」 폼은 그 칸을 아예
     * 안 보낸다 — 이름은 {@link TemporaryScreenId} 가 행의 기본키로 짓는다.
     */
    @PostMapping("/{frdId}/screens")
    public String addScreen(@PathVariable String projectId, @PathVariable String frdId,
                            @RequestParam(required = false) String screenId,
                            @RequestParam(required = false) String screenName,
                            @RequestParam(required = false) String baseScreenId,
                            @RequestParam(required = false) String screenType,
                            @RequestParam(required = false) String systemCode,
                            @RequestParam(required = false) String screenKind,
                            @RequestParam(required = false) String iaPlacementMode,
                            @RequestParam(required = false) String iaAnchorScreenId,
                            @RequestParam(required = false) String iaMenuPathKey,
                            @AuthenticationPrincipal BuilderUser me,
                            RedirectAttributes flash) {
        Frd frd = frds.of(projectId, frdId);
        if (frd.state() == Frd.State.DRAFTING
                && frd.ownerAccountId() != null && !frd.ownerAccountId().isBlank()
                && (me == null || !frd.ownerAccountId().equals(me.accountId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "담당자만 FRD 작업을 수정할 수 있습니다.");
        }
        try {
            if (frd.state() == Frd.State.REVIEW || frd.state() == Frd.State.DONE) {
                throw new IllegalStateException("완료된 FRD에서는 화면을 추가할 수 없습니다.");
            }
            FrdScreenIaPlacementService.Request placement = screenId == null || screenId.isBlank()
                    ? new FrdScreenIaPlacementService.Request(iaPlacementMode, iaAnchorScreenId,
                    iaMenuPathKey, screenKind,
                    iaPlacementMode == null && iaAnchorScreenId == null && iaMenuPathKey == null ? "AI" : "USER")
                    : null;
            frds.addScreen(frdId, screenId, screenName, baseScreenId, screenType, systemCode, placement);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            // ⛔ 500 을 내지 않는다. 사람이 고칠 수 있는 것이다.
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        if (frd.state() == Frd.State.DRAFTING || frd.state() == Frd.State.REVIEW
                || frd.state() == Frd.State.DONE) {
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        return pickRedirect(projectId, frdId);
    }

    /** 대기줄 거절을 직접 검사하는 기존 단위 테스트용 진입점. */
    String open(String projectId, String sourceText, BuilderUser me, RedirectAttributes flash) {
        return open(projectId, sourceText, null, null, null, null, null, null, null,
                null, null, null, null, null, me, flash);
    }

    /**
     * 기존 화면 목록 네 칸을 <b>자리로</b> 맞춰 묶는다.
     *
     * <p>신규 화면은 화면 ID가 없으므로 {@link #newScreenSelections(List, List, List)}에서 별도로 받는다.
     */
    private List<FrdService.ScreenSelection> selections(List<String> screenIds,
                                                        List<String> screenNames,
                                                        List<String> baseScreenIds,
                                                        List<String> screenTypes) {
        if (screenIds == null || screenIds.isEmpty()) {
            return List.of();
        }
        /*
         * ⚠ 화면이 모두 「이미 있는 화면」이면 브라우저가 screenType 을 아예 안 보낼 수 있다 —
         *   그때 던지면 FRD 만들기가 통째로 막힌다. 없는 칸은 빈 값으로 채워 자리를 맞춘다.
         */
        List<String> types = screenTypes == null
                ? Collections.nCopies(screenIds.size(), null) : screenTypes;
        if (screenNames == null || baseScreenIds == null
                || screenIds.size() != screenNames.size() || screenIds.size() != baseScreenIds.size()
                || screenIds.size() != types.size()) {
            throw new IllegalArgumentException("선택한 화면 정보를 다시 확인해 주세요.");
        }
        List<FrdService.ScreenSelection> selections = new ArrayList<>();
        for (int index = 0; index < screenIds.size(); index++) {
            selections.add(new FrdService.ScreenSelection(screenIds.get(index),
                    screenNames.get(index), baseScreenIds.get(index), types.get(index), null));
        }
        return selections;
    }

    /**
     * 신규 화면은 화면 ID가 아직 없으므로 기존 화면의 ID 목록과 분리해 받는다. 빈 화면 ID는
     * Spring이 요청 목록에서 제외할 수 있어, 같은 위치의 이름·유형과 맞추는 방식으로는 보존할 수 없다.
     */
    private List<FrdService.ScreenSelection> newScreenSelections(List<String> screenNames,
                                                                   List<String> screenTypes,
                                                                   List<String> systemCodes,
                                                                   List<String> screenKinds,
                                                                   List<String> placementModes,
                                                                   List<String> anchors,
                                                                   List<String> menuPaths) {
        if (screenNames == null || screenNames.isEmpty()) {
            return List.of();
        }
        if (screenTypes == null || screenNames.size() != screenTypes.size()
                || (systemCodes != null && screenNames.size() != systemCodes.size())
                || (screenKinds != null && screenNames.size() != screenKinds.size())
                || (placementModes != null && screenNames.size() != placementModes.size())
                || (anchors != null && screenNames.size() != anchors.size())
                || (menuPaths != null && screenNames.size() != menuPaths.size())) {
            throw new IllegalArgumentException("신규 화면 정보를 다시 확인해 주세요.");
        }
        List<FrdService.ScreenSelection> selections = new ArrayList<>();
        for (int index = 0; index < screenNames.size(); index++) {
            String screenName = screenNames.get(index);
            String screenType = screenTypes.get(index);
            String systemCode = systemCodes == null ? null : systemCodes.get(index);
            String screenKind = screenKinds == null ? null : screenKinds.get(index);
            String placementMode = placementModes == null ? null : placementModes.get(index);
            String anchor = anchors == null ? null : anchors.get(index);
            String menuPath = menuPaths == null ? null : menuPaths.get(index);
            if (screenName == null || screenName.isBlank() || screenType == null || screenType.isBlank()) {
                throw new IllegalArgumentException("신규 화면의 이름과 유형을 입력해 주세요.");
            }
            selections.add(new FrdService.ScreenSelection(null, screenName, null, screenType, systemCode,
                    screenKind, placementMode, anchor, menuPath));
        }
        return selections;
    }

    private SystemLabels systemLabels(String projectId) {
        return projectSystems == null ? SystemLabels.of(List.of()) : projectSystems.labels(projectId);
    }

    private Set<String> candidateSystemCodes(String projectId, List<SolutionScreen> candidates) {
        Set<String> codes = new LinkedHashSet<>();
        if (projectSystems != null) {
            projectSystems.all(projectId).forEach(system -> codes.add(system.systemCode()));
        }
        candidates.forEach(screen -> codes.add(screen.system()));
        return codes;
    }

    /** 개발 범위 확인에서 선택된 프론트 화면 하나를 제외한다. */
    @PostMapping("/{frdId}/screens/{screenRowId}/delete")
    public String removeScreen(@PathVariable String projectId, @PathVariable String frdId,
                               @PathVariable String screenRowId, RedirectAttributes flash) {
        frds.of(projectId, frdId);
        try {
            frds.excludeScreen(frdId, screenRowId);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return pickRedirect(projectId, frdId);
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    /**
     * 일꾼을 깨운다.
     *
     * <p>⛔ <b>대기줄이 차서 거절돼도 사람에게 500 을 내지 마라.</b>
     *
     * <p>⭐ [2026-08-18 최종 리뷰 I1] 예전 문서는 「FRD 는 이미 앉았고 화면 찾는 중 그대로다 —
     * 사람이 걸음 2 에서 「다시 보기」를 누르면 그때 또 시도할 수 있다」고 적었는데 <b>사실이 아니었다</b> —
     * 마법사의 「다시 보기」는 같은 페이지로 가는 GET 링크일 뿐이고, 「다시 분석하기」 문은
     * {@code ANALYSIS_FAILED} 에서만 열린다. 그래서 로그만 남기면 {@code ANALYZING} + 일꾼 없음으로
     * 영원히 굳는다. 이제 거절되면 {@link FrdService#rejectDispatch} 로 상태를 분석 오류로 닫아
     * 기존 「다시 분석하기」 문이 그대로 열리게 한다.
     */
    private void dispatchPick(String frdId) {
        try {
            picker.pick(frdId);
        } catch (TaskRejectedException full) {
            log.warn("화면 짚기를 제출하지 못했다 — 대기줄이 찼다 frdId={}", frdId);
            frds.rejectDispatch(frdId, "지금은 AI 실행 자리가 다 찼습니다. 잠시 뒤 다시 분석해 주세요.");
        }
    }

    private String pickRedirect(String projectId, String frdId) {
        return "redirect:/projects/%s/artifacts/frds/%s/pick".formatted(projectId, frdId);
    }

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", FrdController.ARTIFACT_KEY);
    }
}
