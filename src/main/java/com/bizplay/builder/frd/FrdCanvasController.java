package com.bizplay.builder.frd;

import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.ClaudeCredentialService;
import com.bizplay.builder.ai.AiProgress;
import com.bizplay.builder.ai.ClaudeRunner.Progress;
import com.bizplay.builder.solution.PreviewFacets;
import com.bizplay.builder.project.ProjectSystemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 기존 화면별 작업대 위에서 FRD 전체 화면과 이동 관계를 다루는 캔버스. */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/frds/{frdId}/canvas")
public class FrdCanvasController {

    private static final Logger log = LoggerFactory.getLogger(FrdCanvasController.class);
    public static final String COMPARE_URL_PATTERN =
            "/projects/*/artifacts/frds/*/canvas/compare";

    private final FrdService frds;
    private final FrdFacetMapper facets;
    private final AccountMapper accounts;
    private final PreviewFacets previewFacets;
    private final FrdCanvasService canvas;
    private final FrdScreenMapper screens;
    private final FrdScreenFiles screenFiles;
    private final FrdScreenChatService chats;
    private final FrdScreenChatMapper messages;
    private final FrdCanvasChatWorker chatWorker;
    private final FrdScreenChatEvents events;
    private final AiProgress progress;
    private final FrdWorkspace workspaces;
    private final FrdChatCancellation cancellations;
    private final FrdScreenDuplicationService duplications;
    private final FrdCanvasRelationService relations;
    private final ProjectSystemService projectSystems;
    private final FrdCompletionService completion;
    private final ClaudeCredentialService claudeCredentials;

    public FrdCanvasController(FrdService frds, FrdFacetMapper facets, AccountMapper accounts,
                               PreviewFacets previewFacets, FrdCanvasService canvas,
                               FrdScreenMapper screens, FrdScreenFiles screenFiles,
                               FrdScreenChatService chats,
                               FrdScreenChatMapper messages, FrdCanvasChatWorker chatWorker,
                               FrdScreenChatEvents events, AiProgress progress,
                               FrdWorkspace workspaces, FrdChatCancellation cancellations,
                               FrdScreenDuplicationService duplications,
                               FrdCanvasRelationService relations,
                               ProjectSystemService projectSystems,
                               FrdCompletionService completion,
                               ClaudeCredentialService claudeCredentials) {
        this.frds = frds;
        this.facets = facets;
        this.accounts = accounts;
        this.previewFacets = previewFacets;
        this.canvas = canvas;
        this.screens = screens;
        this.screenFiles = screenFiles;
        this.chats = chats;
        this.messages = messages;
        this.chatWorker = chatWorker;
        this.events = events;
        this.progress = progress;
        this.workspaces = workspaces;
        this.cancellations = cancellations;
        this.duplications = duplications;
        this.relations = relations;
        this.projectSystems = projectSystems;
        this.completion = completion;
        this.claudeCredentials = claudeCredentials;
    }

    @GetMapping
    public String canvas(@PathVariable String projectId, @PathVariable String frdId,
                         @RequestParam(defaultValue = "frd") String scope,
                         @RequestParam(name = "query", defaultValue = "") String listQuery,
                         @RequestParam(name = "state", defaultValue = "") String listState,
                         @RequestParam(name = "owner", defaultValue = "") String listOwner,
                         @RequestParam(name = "system", defaultValue = "") String listSystem,
                          @RequestParam(name = "page", defaultValue = "1") int listPage,
                          @RequestParam(name = "pageSize", defaultValue = "10") int listPageSize,
                          @RequestParam(required = false) String facet,
                          @RequestParam(required = false) String comparisonScreenRowId,
                         @RequestParam(required = false) String confirmedCloneHead,
                         @AuthenticationPrincipal BuilderUser me, Model model) {
        Frd frd = frds.of(projectId, frdId);
        if (frd.state() != Frd.State.DRAFTING
                && frd.state() != Frd.State.REVIEW
                && frd.state() != Frd.State.DONE) {
            return "redirect:/projects/%s/artifacts/frds/%s".formatted(projectId, frdId);
        }
        boolean assignedUser = isAssignedUser(frd, me);
        boolean editable = frd.state() == Frd.State.DRAFTING && assignedUser;
        List<FrdFacet> selectedFacets = facets.selectByFrdId(frdId);
        String activeFacet = activeFacet(selectedFacets, facet);
        String previewSkin = previewSkin(projectId, selectedFacets, activeFacet);
        FrdCanvasService.Canvas view = canvas.read(projectId, frdId, "project".equals(scope), previewSkin);
        List<FrdScreen> workScreens = screens.selectByFrdId(frdId);
        boolean workBusy = workScreens.stream().anyMatch(screen -> screen.state() == FrdScreen.State.GENERATING)
                || chats.running(frdId) != null;

        model.addAttribute("frd", frd);
        model.addAttribute("facets", selectedFacets);
        model.addAttribute("activeFacet", activeFacet);
        model.addAttribute("allFacets", frds.usesAllFacets(projectId,
                selectedFacets.stream().map(FrdFacet::name).toList()));
        Map<String, List<String>> splitFacetsByScreen = splitFacetsByScreen(projectId, frd, workScreens);
        model.addAttribute("splitFacetsByScreen", splitFacetsByScreen);
        model.addAttribute("splitFacetOptions", splitFacetsByScreen.values().stream()
                .flatMap(List::stream).distinct().toList());
        model.addAttribute("ownerName", ownerName(frd));
        model.addAttribute("canvas", view);
        model.addAttribute("canvasWorkTargetCount",
                view.nodes().stream().filter(FrdCanvasService.CanvasNode::workTarget).count());
        model.addAttribute("canvasRelatedScreenCount",
                view.nodes().stream().filter(node -> !node.workTarget()).count());
        model.addAttribute("canvasEditable", editable);
        model.addAttribute("assignedUser", assignedUser);
        model.addAttribute("resetDisabled", workBusy || !editable);
        model.addAttribute("draftReadyCount", workScreens.stream().filter(FrdScreen::canGenerateDraft).count());
        model.addAttribute("completionStatus", completionStatus(projectId, frd, workBusy, editable));
        model.addAttribute("previewSkinQuery", previewSkin == null ? "" : "?skin=" + previewSkin);
        model.addAttribute("systemLabels", projectSystems.labels(projectId));
        model.addAttribute("title", "FRD 캔버스");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", FrdController.ARTIFACT_KEY);
        model.addAttribute("claudeIdentity", claudeCredentials.identityOf(me.accountId()).orElse(null));
        model.addAttribute("listQuery", listQuery);
        model.addAttribute("listState", listState);
        model.addAttribute("listOwner", listOwner);
        model.addAttribute("listSystem", listSystem);
        model.addAttribute("listPage", listPage);
        model.addAttribute("listPageSize", listPageSize);
        model.addAttribute("comparisonScreenRowId", comparisonScreenRowId);
        model.addAttribute("confirmedCloneHead", confirmedCloneHead);
        return "artifacts/frd-canvas";
    }

    /** 기획 저장소의 기준 화면과 현재 FRD 수정안을 읽기 전용으로 나란히 보여 준다. */
    @GetMapping("/compare")
    public String compare(@PathVariable String projectId, @PathVariable String frdId,
                          @RequestParam String screenRowId,
                          @RequestParam(required = false) String facet,
                          @RequestParam(defaultValue = "false") boolean embedded,
                          Model model) {
        Frd frd = frds.of(projectId, frdId);
        if (frd.state() != Frd.State.DRAFTING
                && frd.state() != Frd.State.REVIEW
                && frd.state() != Frd.State.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "FRD 작업 화면에서만 변경 내용을 비교할 수 있습니다.");
        }
        List<FrdScreen> workScreens = screens.selectByFrdId(frdId);
        int selectedIndex = -1;
        for (int index = 0; index < workScreens.size(); index++) {
            if (workScreens.get(index).id().equals(screenRowId)) {
                selectedIndex = index;
                break;
            }
        }
        if (selectedIndex < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "비교할 작업 대상 화면이 없습니다.");
        }

        FrdScreen screen = workScreens.get(selectedIndex);
        List<FrdFacet> selectedFacets = facets.selectByFrdId(frdId);
        String activeFacet = activeFacet(selectedFacets, facet);
        String previewSkin = previewSkin(projectId, selectedFacets, activeFacet);
        FrdCanvasService.Canvas view = canvas.read(projectId, frdId, false, previewSkin);
        String baselinePreviewPath = view.nodes().stream()
                .filter(node -> screenRowId.equals(node.frdScreenId()))
                .findFirst().map(FrdCanvasService.CanvasNode::previewPath).orElse(null);
        if (screen.isNewScreen()) baselinePreviewPath = null;

        List<String> comparisonChanges = new ArrayList<>(screen.changeList());
        String changeSummaryLabel = "반영된 변경";
        if (comparisonChanges.isEmpty() && screen.scopeChange() != null && !screen.scopeChange().isBlank()) {
            comparisonChanges.add(screen.scopeChange().strip());
            changeSummaryLabel = "예정된 변경";
        }

        model.addAttribute("frd", frd);
        model.addAttribute("projectId", projectId);
        model.addAttribute("screen", screen);
        model.addAttribute("activeFacet", activeFacet);
        model.addAttribute("baselinePreviewPath", baselinePreviewPath);
        model.addAttribute("draftAvailable", screen.html() != null && !screen.html().isBlank());
        model.addAttribute("previewSkinQuery", previewSkin == null ? "" : "?skin=" + previewSkin);
        model.addAttribute("comparisonChanges", comparisonChanges);
        model.addAttribute("changeSummaryLabel", changeSummaryLabel);
        model.addAttribute("embedded", embedded);
        model.addAttribute("previousScreen", selectedIndex > 0 ? workScreens.get(selectedIndex - 1) : null);
        model.addAttribute("nextScreen",
                selectedIndex + 1 < workScreens.size() ? workScreens.get(selectedIndex + 1) : null);
        return "artifacts/frd-canvas-compare";
    }

    private String previewSkin(String projectId, List<FrdFacet> selectedFacets, String activeFacet) {
        List<String> names = selectedFacets.stream().map(FrdFacet::name).toList();
        String chosen = activeFacet != null ? activeFacet : names.size() == 1 ? names.get(0) : null;
        String previewSkin = chosen == null ? null : previewFacets.codeOfName(projectId, chosen);
        return previewSkin == null ? previewFacets.only(projectId) : previewSkin;
    }

    private static String activeFacet(List<FrdFacet> selectedFacets, String requested) {
        List<String> names = selectedFacets.stream().map(FrdFacet::name).toList();
        if (names.isEmpty()) return null;
        if (requested != null && !requested.isBlank()) {
            return names.stream().filter(requested::equals).findFirst().orElse(names.get(0));
        }
        return names.get(0);
    }

    /** 실제 기관별 HTML이 둘 이상 존재하는 화면만 캔버스 하위 항목으로 내린다. */
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

    private FrdController.CompletionStatus completionStatus(String projectId, Frd frd,
                                                            boolean busy, boolean editable) {
        String frdId = frd.id();
        boolean completingFromReview = completion.canCompleteFromReview(projectId, frd);
        if (!editable && !completingFromReview) {
            return new FrdController.CompletionStatus(false, false, false,
                    "검토 중이거나 완료된 FRD는 읽기 전용입니다.");
        }
        try {
            boolean modified = workspaces.hasChanges(projectId, frdId);
            boolean reopened = !modified && workspaces.hasCompletionToReopen(projectId, frdId,
                    FrdWorkspace.completionMessage(frd.label()));
            if (busy) {
                return new FrdController.CompletionStatus(modified, true, false,
                        "AI가 화면을 수정하고 있어 완료할 수 없습니다.");
            }
            if (!modified && !reopened && !completingFromReview) {
                return new FrdController.CompletionStatus(false, false, false,
                        "수정된 내용이 있어야 FRD 작업을 완료할 수 있습니다.");
            }
            return new FrdController.CompletionStatus(modified || reopened || completingFromReview, false, true,
                    completingFromReview || reopened
                            ? "기존 개발요청서를 최신 FRD 작업 내용으로 다시 만듭니다."
                            : "FRD 작업을 완료합니다.");
        } catch (RuntimeException failure) {
            log.warn("FRD 캔버스에서 작업공간 변경 여부를 확인하지 못했습니다. projectId={} frdId={}",
                    projectId, frdId, failure);
            return new FrdController.CompletionStatus(false, busy, false,
                    "작업공간 변경 여부를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    /** 전체 캔버스 AI 대화를 상세 캔버스와 같은 별도 창으로 넓혀 보는 화면이다. */
    @GetMapping("/chat")
    public String canvasChatWindow(@PathVariable String projectId, @PathVariable String frdId,
                                   @AuthenticationPrincipal BuilderUser me, Model model) {
        Frd frd = frds.of(projectId, frdId);
        if (frd.state() != Frd.State.DRAFTING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "수정 중인 FRD에서만 전체 화면 대화를 사용할 수 있습니다.");
        }
        model.addAttribute("frd", frd);
        model.addAttribute("chatStatusUrl", "/projects/%s/artifacts/frds/%s/canvas/chat/messages"
                .formatted(projectId, frdId));
        model.addAttribute("chatSendUrl", "/projects/%s/artifacts/frds/%s/canvas/chat"
                .formatted(projectId, frdId));
        model.addAttribute("chatEventsUrl", "/projects/%s/artifacts/frds/%s/chat/events"
                .formatted(projectId, frdId));
        model.addAttribute("claudeIdentity", claudeCredentials.identityOf(me.accountId()).orElse(null));
        return "artifacts/frd-canvas-chat";
    }

    @PostMapping("/chat")
    @ResponseBody
    public ResponseEntity<CanvasChatStarted> chat(@PathVariable String projectId,
                                                   @PathVariable String frdId,
                                                   @RequestBody CanvasChatRequest request) {
        Frd frd = requireEditableFrd(projectId, frdId);
        List<FrdScreen> workScreens = screens.selectByFrdId(frdId);
        if (workScreens.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "맵 AI가 기준으로 사용할 화면을 먼저 추가해 주세요.");
        }
        Set<String> selected = request.screenIds() == null ? new java.util.LinkedHashSet<>() : request.screenIds().stream()
                .filter(java.util.Objects::nonNull).map(String::strip).filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> workScreenIds = workScreens.stream().map(FrdScreen::screenId)
                .collect(java.util.stream.Collectors.toSet());
        selected.retainAll(workScreenIds);
        FrdScreen primary = workScreens.stream().filter(screen -> selected.contains(screen.screenId()))
                .findFirst().orElse(workScreens.get(0));
        FrdScreenChatMessage started;
        try {
            started = chats.startCanvas(frdId, primary.id(), request.message());
            events.publish(frdId);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IllegalStateException | DataIntegrityViolationException busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 AI 작업을 처리하고 있습니다. 완료된 뒤 다시 요청해 주세요.");
        }
        try {
            chatWorker.edit(started.id(), List.copyOf(selected));
        } catch (TaskRejectedException busy) {
            chats.fail(started.id(), "AI 작업이 많아 시작하지 못했습니다. 잠시 후 다시 요청해 주세요.");
            events.publish(frdId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 작업이 많아 시작하지 못했습니다. 잠시 후 다시 요청해 주세요.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CanvasChatStarted(started.id(), "AI가 선택한 화면과 연결을 확인하고 있습니다."));
    }

    @GetMapping("/chat/{messageId}")
    @ResponseBody
    public CanvasChatStatus chatStatus(@PathVariable String projectId, @PathVariable String frdId,
                                       @PathVariable String messageId) {
        frds.of(projectId, frdId);
        FrdScreenChatMessage message = messages.selectById(messageId);
        if (message == null || !message.frdId().equals(frdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 맵 AI 요청이 없습니다.");
        }
        List<Progress> steps = new java.util.ArrayList<>(progress.of(FrdCanvasChatWorker.progressKey(messageId)));
        java.util.Collections.reverse(steps);
        var interview = FrdCanvasInterviewContent.decode(message.content());
        return new CanvasChatStatus(message.state().name(),
                interview.map(FrdCanvasInterviewContent.Content::message).orElse(message.content()),
                message.failure(),
                steps.stream().map(Progress::text).toList(), screens.selectByFrdId(frdId).size());
    }

    /** 수정 화면을 작업 대상에서 제외하고 다시 잠근다. */
    @PostMapping("/screens/{screenRowId}/exclude")
    public String excludeWorkTarget(@PathVariable String projectId, @PathVariable String frdId,
                                    @PathVariable String screenRowId, RedirectAttributes flash) {
        return excludeWorkTarget(projectId, frdId, screenRowId, "canvas", flash);
    }

    private String excludeWorkTarget(String projectId, String frdId, String screenRowId,
                                     String returnTo, RedirectAttributes flash) {
        try {
            requireEditableFrd(projectId, frdId);
            if (chats.running(frdId) != null) {
                throw new IllegalStateException("AI가 화면을 작업하는 중에는 작업 대상을 변경할 수 없습니다.");
            }
            frds.excludeScreen(frdId, screenRowId);
            flash.addFlashAttribute("message", "detail".equals(returnTo)
                    ? "작업 화면에서 삭제했습니다."
                    : "수정 화면에서 제외하고 다시 잠갔습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        String suffix = "detail".equals(returnTo) ? "" : "/canvas";
        return "redirect:/projects/%s/artifacts/frds/%s%s".formatted(projectId, frdId, suffix);
    }

    @PostMapping("/screens/exclude")
    public String excludeSelectedWorkTarget(@PathVariable String projectId, @PathVariable String frdId,
                                            @RequestParam String screenRowId,
                                            @RequestParam(defaultValue = "canvas") String returnTo,
                                            RedirectAttributes flash) {
        return excludeWorkTarget(projectId, frdId, screenRowId, returnTo, flash);
    }

    /** 캔버스에서 선택한 신규 화면을 삭제한다. 기존 솔루션 화면은 삭제할 수 없다. */
    @PostMapping("/screens/delete")
    public String deleteNewScreen(@PathVariable String projectId, @PathVariable String frdId,
                                  @RequestParam String screenRowId, RedirectAttributes redirect) {
        try {
            requireEditableFrd(projectId, frdId);
            if (chats.running(frdId) != null) {
                throw new IllegalStateException("AI가 화면을 작업하는 중에는 신규 화면을 삭제할 수 없습니다.");
            }
            FrdScreen screen = screens.selectById(screenRowId);
            if (screen == null || !screen.frdId().equals(frdId) || !screen.isNewScreen()) {
                throw new IllegalArgumentException("삭제할 신규 화면을 찾을 수 없습니다.");
            }
            frds.excludeScreen(frdId, screenRowId);
            redirect.addFlashAttribute("message", "신규 화면을 삭제했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            redirect.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/frds/%s/canvas".formatted(projectId, frdId);
    }

    /** 관련 화면의 잠금을 풀어 FRD 수정 화면으로 승격한다. */
    @PostMapping("/screens/promote")
    public String promoteRelatedScreen(@PathVariable String projectId, @PathVariable String frdId,
                                       @RequestParam String screenId,
                                       @RequestParam String screenName,
                                       @RequestParam(required = false) String screenType,
                                       @RequestParam(required = false) String systemCode,
                                       @RequestParam(defaultValue = "frd") String scope,
                                       @RequestParam(required = false) String facet,
                                       RedirectAttributes redirect) {
        try {
            requireEditableFrd(projectId, frdId);
            if (chats.running(frdId) != null) {
                throw new IllegalStateException("AI가 화면을 작업하는 중에는 관련 화면의 잠금을 해제할 수 없습니다.");
            }
            frds.addScreen(frdId, screenId, screenName, screenId, screenType, systemCode);
            redirect.addFlashAttribute("message", "수정 화면에 추가했습니다: " + screenName);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            redirect.addFlashAttribute("error", rejected.getMessage());
        }
        if ("project".equals(scope)) redirect.addAttribute("scope", "project");
        if (facet != null && !facet.isBlank()) redirect.addAttribute("facet", facet);
        return "redirect:/projects/%s/artifacts/frds/%s/canvas".formatted(projectId, frdId);
    }

    @PostMapping("/screens/duplicate")
    public String duplicateWorkTarget(@PathVariable String projectId, @PathVariable String frdId,
                                      @RequestParam String sourceScreenRowId,
                                      @RequestParam String screenName,
                                      RedirectAttributes flash) {
        try {
            requireEditableFrd(projectId, frdId);
            if (chats.running(frdId) != null) {
                throw new IllegalStateException("AI가 화면을 작업하는 중에는 화면을 복제할 수 없습니다.");
            }
            FrdScreen duplicated = duplications.duplicate(
                    projectId, frdId, sourceScreenRowId, screenName);
            flash.addFlashAttribute("message", "신규 화면을 만들었습니다: " + duplicated.screenName());
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/frds/%s/canvas".formatted(projectId, frdId);
    }

    @PostMapping("/relations/save")
    public String saveRelation(@PathVariable String projectId, @PathVariable String frdId,
                               @RequestParam(defaultValue = "") String originalSourceScreenId,
                               @RequestParam(defaultValue = "") String originalTargetScreenId,
                               @RequestParam(defaultValue = "") String originalAnchor,
                               @RequestParam String sourceScreenId,
                               @RequestParam String targetScreenId,
                               @RequestParam String anchor,
                               RedirectAttributes flash) {
        try {
            requireEditableFrd(projectId, frdId);
            if (chats.running(frdId) != null) {
                throw new IllegalStateException("AI가 화면을 작업하는 중에는 화면 연결을 변경할 수 없습니다.");
            }
            relations.save(projectId, frdId, originalSourceScreenId, originalTargetScreenId, originalAnchor,
                    sourceScreenId, targetScreenId, anchor);
            flash.addFlashAttribute("message", originalSourceScreenId.isBlank()
                    ? "화면 연결을 추가했습니다." : "화면 연결을 변경했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/frds/%s/canvas".formatted(projectId, frdId);
    }

    @PostMapping("/relations/delete")
    public String deleteRelation(@PathVariable String projectId, @PathVariable String frdId,
                                 @RequestParam String originalSourceScreenId,
                                 @RequestParam String originalTargetScreenId,
                                 @RequestParam String originalAnchor,
                                 RedirectAttributes flash) {
        try {
            requireEditableFrd(projectId, frdId);
            if (chats.running(frdId) != null) {
                throw new IllegalStateException("AI가 화면을 작업하는 중에는 화면 연결을 삭제할 수 없습니다.");
            }
            relations.delete(projectId, frdId,
                    originalSourceScreenId, originalTargetScreenId, originalAnchor);
            flash.addFlashAttribute("message", "화면 연결을 삭제했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/frds/%s/canvas".formatted(projectId, frdId);
    }

    /** 완료된 FRD의 캔버스 변경 요청을 화면뿐 아니라 서버에서도 차단한다. */
    private Frd requireEditableFrd(String projectId, String frdId) {
        Frd frd = frds.of(projectId, frdId);
        requireAssignedUser(frd);
        if (frd.state() != Frd.State.DRAFTING) {
            throw new IllegalStateException("완료된 FRD에서는 캔버스를 변경할 수 없습니다.");
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

    @GetMapping("/chat/messages")
    @ResponseBody
    public CanvasChatThread canvasChatMessages(@PathVariable String projectId, @PathVariable String frdId) {
        frds.of(projectId, frdId);
        List<CanvasChatLine> lines = chats.canvasMessages(frdId).stream()
                .map(this::canvasChatLine)
                .toList();
        FrdScreenChatMessage running = chats.running(frdId);
        CanvasActiveChat active = null;
        if (running != null && lines.stream().anyMatch(line -> line.id().equals(running.id()))) {
            List<Progress> kept = new java.util.ArrayList<>(progress.of(FrdCanvasChatWorker.progressKey(running.id())));
            java.util.Collections.reverse(kept);
            active = new CanvasActiveChat(running.id(), kept.stream()
                    .map(step -> new CanvasChatProgress(step.kind().name(), step.text())).toList());
        }
        return new CanvasChatThread(lines, active, running != null, screens.selectByFrdId(frdId).size());
    }

    private CanvasChatLine canvasChatLine(FrdScreenChatMessage message) {
        var interview = FrdCanvasInterviewContent.decode(message.content());
        return new CanvasChatLine(message.id(), message.role().name(), message.state().name(),
                interview.map(FrdCanvasInterviewContent.Content::message).orElse(message.content()),
                message.failure(), message.createdAt(),
                interview.map(FrdCanvasInterviewContent.Content::questions).orElse(List.of()));
    }

    /** Esc로 요청한 전체 캔버스 Claude 작업을 즉시 중단하고 대화 상태를 닫는다. */
    @PostMapping("/chat/{messageId}/cancel")
    @ResponseBody
    public ResponseEntity<Void> cancelCanvasChat(@PathVariable String projectId, @PathVariable String frdId,
                                                  @PathVariable String messageId) {
        requireEditableFrd(projectId, frdId);
        FrdScreenChatMessage message = messages.selectById(messageId);
        boolean canvasMessage = message != null && message.frdId().equals(frdId)
                && chats.canvasMessages(frdId).stream().anyMatch(item -> item.id().equals(messageId));
        if (!canvasMessage) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 전체 화면 작업이 없습니다.");
        if (message.state() == FrdScreenChatMessage.State.RUNNING) {
            cancellations.cancel(messageId);
            chats.fail(messageId, "사용자가 전체 화면 작업을 중단했습니다.");
            events.publish(frdId);
        }
        return ResponseEntity.accepted().build();
    }

    private String ownerName(Frd frd) {
        if (frd.ownerAccountId() == null || frd.ownerAccountId().isBlank()) return "—";
        return accounts.selectById(frd.ownerAccountId()).map(Account::getName).orElse("—");
    }

    public record CanvasChatRequest(String message, List<String> screenIds) { }
    public record CanvasChatStarted(String messageId, String message) { }
    public record CanvasChatStatus(String state, String message, String failure,
                                   List<String> progress, int screenCount) { }
    public record CanvasChatLine(String id, String role, String state, String content,
                                 String failure, java.time.Instant createdAt,
                                 List<FrdCanvasChatReader.InterviewQuestion> questions) { }
    public record CanvasChatProgress(String kind, String text) { }
    public record CanvasActiveChat(String id, List<CanvasChatProgress> progress) { }
    public record CanvasChatThread(List<CanvasChatLine> messages, CanvasActiveChat active,
                                   boolean busy, int screenCount) { }
}
