package com.bizplay.builder.devrequest;

import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdBackendChangeMapper;
import com.bizplay.builder.frd.FrdFacetMapper;
import com.bizplay.builder.frd.FrdItem;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdInterviewMessage;
import com.bizplay.builder.frd.FrdInterviewMessageMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenHistory;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdScreenIaPlacement;
import com.bizplay.builder.frd.FrdScreenIaPlacementService;
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import com.bizplay.builder.frd.ScreenDefinitionDocument;
import com.bizplay.builder.frd.FrdScreenMarkerMapper;
import com.bizplay.builder.frd.FrdScreenMemoCommentMapper;
import com.bizplay.builder.frd.FrdWorkspace;
import com.bizplay.builder.ia.IaMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectRepositoryLocks;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.ScreenStandardIdService;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FRD 완료와 개발요청서 생성을 하나의 DB 트랜잭션으로 처리한다. */
@Service
public class DevelopmentRequestService {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentRequestService.class);
    private static final Pattern SCREEN_DEFINITION = Pattern.compile("(?m)^- .*?(?:\\R|$)");
    private static final Set<String> LINK_FIELDS = Set.of("이동", "이동modal", "이동native", "이동cross");

    private final DevelopmentRequestMapper requests;
    private final FrdMapper frds;
    private final FrdItemMapper items;
    private final FrdInterviewMessageMapper interviewMessages;
    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final FrdBackendChangeMapper backendChanges;
    private final FrdAnalysisNoteMapper notes;
    private final FrdFacetMapper facets;
    private final FrdScreenMarkerMapper screenMarkers;
    private final FrdScreenMemoCommentMapper screenMemos;
    private final IaMapper ia;
    private final AccountMapper accounts;
    private final ScreenStandardIdMapper standardIds;
    private final ScreenStandardIdService standardIdAllocator;
    private final FrdScreenIaPlacementService iaPlacements;
    private final ProjectPaths paths;
    private final ProjectRepositoryLocks repositoryLocks;
    private final DevRequestPackageBuilder packages;
    private final DevRequestPackageZipper zipper;
    private final DevRequestPrecheck prechecks;
    private final ScreenTobeDocumentWorker tobeDocuments;
    private final DevRequestTestScenarioWorker testScenarios;
    private final FrdWorkspace workspaces;
    private final DevRequestDeliveryMapper attempts;
    private final DevHandoffGateway gateway;
    private final IdSequence ids;
    private final ObjectMapper json;

    public DevelopmentRequestService(DevelopmentRequestMapper requests, FrdMapper frds,
                                     FrdItemMapper items, FrdInterviewMessageMapper interviewMessages,
                                     FrdScreenMapper screens,
                                     FrdScreenHistoryMapper histories,
                                     FrdBackendChangeMapper backendChanges,
                                     FrdAnalysisNoteMapper notes, FrdFacetMapper facets,
                                     FrdScreenMarkerMapper screenMarkers,
                                     FrdScreenMemoCommentMapper screenMemos, IaMapper ia,
                                     AccountMapper accounts, ScreenStandardIdMapper standardIds,
                                     ScreenStandardIdService standardIdAllocator,
                                     FrdScreenIaPlacementService iaPlacements,
                                     ProjectPaths paths, ProjectRepositoryLocks repositoryLocks,
                                     DevRequestPackageBuilder packages, DevRequestPackageZipper zipper,
                                     DevRequestPrecheck prechecks,
                                     ScreenTobeDocumentWorker tobeDocuments,
                                     DevRequestTestScenarioWorker testScenarios,
                                     FrdWorkspace workspaces,
                                     DevRequestDeliveryMapper attempts, DevHandoffGateway gateway,
                                     IdSequence ids, ObjectMapper json) {
        this.requests = requests;
        this.frds = frds;
        this.items = items;
        this.interviewMessages = interviewMessages;
        this.screens = screens;
        this.histories = histories;
        this.backendChanges = backendChanges;
        this.notes = notes;
        this.facets = facets;
        this.screenMarkers = screenMarkers;
        this.screenMemos = screenMemos;
        this.ia = ia;
        this.accounts = accounts;
        this.standardIds = standardIds;
        this.standardIdAllocator = standardIdAllocator;
        this.iaPlacements = iaPlacements;
        this.paths = paths;
        this.repositoryLocks = repositoryLocks;
        this.packages = packages;
        this.zipper = zipper;
        this.prechecks = prechecks;
        this.tobeDocuments = tobeDocuments;
        this.testScenarios = testScenarios;
        this.workspaces = workspaces;
        this.attempts = attempts;
        this.gateway = gateway;
        this.ids = ids;
        this.json = json;
    }

    /**
     * 목록 한 줄. 담당 이름을 같이 안고 간다.
     *
     * <p>⭐ <b>담당은 기준 FRD 에서 온다</b> — {@code adk_builder_dev_request} 에 담당 열이 없다.
     * 개발요청서는 FRD 하나당 한 장이고({@code frd_id} 가 unique) 넘길 책임도 그 FRD 를 맡은 사람에게
     * 있어서, 열을 새로 두는 대신 이미 있는 관계를 읽는다.
     *
     * <p>⚠ {@code ownerName} 은 계정ID 를 이름으로 바꾼 값이다 — {@link #list} 가 <b>한 번에</b>
     * 읽어 채운다. 여기서 계정을 다시 읽지 마라(FRD 목록의 {@code FrdService.Row} 와 같은 규칙이다).
     */
    public record Row(DevelopmentRequest request, String ownerName, boolean generating,
                      int screenCount, int newScreenCount, int backendChangeCount,
                      int blockingCount, boolean precheckChecking,
                      DevRequestDeliveryAttempt latestHandoff, Frd source) {

        public Row(DevelopmentRequest request, String ownerName, boolean generating,
                   int screenCount, int newScreenCount, int backendChangeCount,
                   int blockingCount, boolean precheckChecking,
                   DevRequestDeliveryAttempt latestHandoff) {
            this(request, ownerName, generating, screenCount, newScreenCount, backendChangeCount,
                    blockingCount, precheckChecking, latestHandoff, null);
        }

        /** 담당 칸에 뜨는 말. 담당이 없거나 이름을 못 찾으면 대시다. */
        public String ownerLabel() {
            return ownerName == null || ownerName.isBlank() ? "—" : ownerName;
        }

        /** 목록 상태 배지 — 「생성중」이 {@link DevelopmentRequest#deliveryStateLabel()} 위에 얹힌다. */
        public String stateLabel() {
            if (generating) {
                return GENERATING_LABEL;
            }
            if (request.deliveryState() == DevelopmentRequest.DeliveryState.NOT_SENT) {
                if (precheckChecking) {
                    return "전송 전 확인 중";
                }
                if (blockingCount > 0) {
                    return "전송 전 확인 %d건".formatted(blockingCount);
                }
            }
            return request.deliveryStateLabel();
        }

        public String stateClass() {
            if (generating || precheckChecking) {
                return GENERATING_CLASS;
            }
            if (request.deliveryState() == DevelopmentRequest.DeliveryState.NOT_SENT && blockingCount > 0) {
                return "status-badge--review";
            }
            return request.deliveryStateClass();
        }

        public String rangeLabel() {
            List<String> parts = new ArrayList<>();
            if (screenCount == 0) {
                parts.add("프론트 없음");
            } else {
                parts.add("화면 %d개".formatted(screenCount));
                if (newScreenCount > 0) {
                    parts.add("신규 %d개".formatted(newScreenCount));
                }
                int changedScreenCount = screenCount - newScreenCount;
                if (changedScreenCount > 0) {
                    parts.add("수정 %d개".formatted(changedScreenCount));
                }
            }
            parts.add(backendChangeCount == 0
                    ? "백엔드 없음"
                    : "백엔드 %d건".formatted(backendChangeCount));
            return String.join(" · ", parts);
        }

        public Instant requestedAt() {
            return latestHandoff == null ? null : latestHandoff.startedAt();
        }

        public boolean srtSource() {
            return source != null && source.sourceKind() == Frd.SourceKind.SRT;
        }

        public String sourceLabel() {
            return srtSource() ? source.sourceRef() : request.frdLabel();
        }
    }

    /**
     * 「생성중」 — 변경 예정 기능정의서를 AI 가 만드는 동안 보이는 상태 (병주 지시 2026-08-25).
     *
     * <p>⛔ <b>{@link DevelopmentRequest.DeliveryState} 에 넣지 않는다.</b> 그 축은 「창구가 뭐라 답했나」이고
     * 넷째 값 금지가 걸려 있다. 이것은 <b>{@code NOT_SENT} 를 화면이 어떻게 읽나</b>의 문제다.
     * 생성 상태는 화면 이력에 시각과 함께 두고, 제한 시간을 넘긴 진행 상태는 더 이상 생성중으로 읽지 않는다.
     */
    static final String GENERATING_LABEL = "생성중";
    static final String GENERATING_CLASS = "status-badge--progress";

    /** 대기 중인 개발요청서의 화면 가운데 하나라도 기능정의서를 만드는 중인가. */
    private boolean generating(DevelopmentRequest request, DevelopmentRequestContent content) {
        if (request.deliveryState() != DevelopmentRequest.DeliveryState.NOT_SENT) {
            return false;
        }
        for (var screen : content.screens()) {
            if (!tobeDocuments.isGenerating(screen.frdScreenId())) {
                continue;
            }
            FrdScreenHistory latest = histories.selectLatestByScreenId(screen.frdScreenId());
            if (latest == null || latest.md() == null || latest.md().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** 커밋이 끝난 FRD를 검토 단계로 옮기면서 당시 내용을 개발요청서로 고정한다. */
    @Transactional
    public DevelopmentRequest createFromCompletedFrd(String projectId, String frdId) {
        return createFromCompletedFrd(projectId, frdId, null, null);
    }

    /** 완료 커밋의 앞판과 뒷판을 개발요청서에 고정한다. */
    @Transactional
    public DevelopmentRequest createFromCompletedFrd(String projectId, String frdId,
                                                      String workspaceBaseSha, String workspaceHeadSha) {
        return createFromFrd(projectId, frdId, Frd.State.DRAFTING,
                workspaceBaseSha, workspaceHeadSha,
                "수정 중인 FRD만 작업을 완료할 수 있습니다.");
    }

    /** 검토 중인 FRD에 남아 있는 미전송 개발요청서를 찾는다. */
    @Transactional(readOnly = true)
    public DevelopmentRequest findNotSentByFrd(String projectId, String frdId) {
        DevelopmentRequest request = requests.selectByFrdId(frdId);
        if (request == null || !request.projectId().equals(projectId)
                || request.deliveryState() != DevelopmentRequest.DeliveryState.NOT_SENT) {
            return null;
        }
        return request;
    }

    /** 간단 변경의 분석 결과를 FRD 작업대·워크트리 없이 개발요청서로 고정한다. */
    @Transactional
    public DevelopmentRequest createFromConfirmedScope(String projectId, String frdId) {
        return createFromFrd(projectId, frdId, Frd.State.SCOPE_REVIEW,
                null, null,
                "개발 범위를 확인한 간단 변경만 바로 개발요청서로 만들 수 있습니다.");
    }

    /** SRT의 AI 분석 요약을 인터뷰 요약 대신 개발요청서에 고정한다. */
    @Transactional
    public DevelopmentRequest createFromConfirmedScope(String projectId, String frdId,
                                                         String requirementSummary) {
        return createFromFrd(projectId, frdId, Frd.State.SCOPE_REVIEW,
                null, null,
                "개발 범위를 확인한 간단 변경만 바로 개발요청서로 만들 수 있습니다.",
                requirementSummary);
    }

    private DevelopmentRequest createFromFrd(String projectId, String frdId,
                                              Frd.State expectedState,
                                              String workspaceBaseSha, String workspaceHeadSha,
                                              String rejectedMessage) {
        return createFromFrd(projectId, frdId, expectedState, workspaceBaseSha, workspaceHeadSha,
                rejectedMessage, null);
    }

    private DevelopmentRequest createFromFrd(String projectId, String frdId,
                                              Frd.State expectedState,
                                              String workspaceBaseSha, String workspaceHeadSha,
                                              String rejectedMessage, String requirementSummary) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || !frd.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 FRD가 없습니다: " + frdId);
        }
        if (frd.state() != expectedState) {
            throw new IllegalStateException(rejectedMessage);
        }

        List<FrdScreen> screenRows = screens.selectByFrdId(frdId);
        reserveDevelopmentScreenIds(projectId, screenRows);
        // 화면 관리번호는 재료가 있을 때만 채번한다. 없어도 개발용 화면 ID가 있으므로 요청을 막지 않는다.
        allocateStandardIdsForNewScreens(projectId, screenRows);
        DevelopmentRequestContent content = withRequirementSummary(
                snapshot(frd, screenRows), requirementSummary);
        String facetNames = String.join(", ", facets.selectByFrdId(frdId).stream().map(facet -> facet.name()).toList());
        DevelopmentRequest request = new DevelopmentRequest(
                ids.next(IdSequence.Kind.DEV_REQUEST), projectId, requests.allocateNumber(projectId),
                frdId, frd.number(), frd.title(), frd.systemCode(), facetNames.isBlank() ? null : facetNames,
                write(content), DevelopmentRequest.DeliveryState.NOT_SENT, null,
                null, null, null, null, null, null, null,
                workspaceBaseSha, workspaceHeadSha, null, null);
        requests.insert(request);

        if (frds.transitionState(frdId, expectedState, Frd.State.REVIEW) != 1) {
            throw new IllegalStateException("다른 요청이 먼저 FRD 상태를 변경했습니다. 목록에서 다시 확인해 주세요.");
        }
        return requests.selectById(request.id());
    }

    private DevelopmentRequestContent withRequirementSummary(
            DevelopmentRequestContent content, String requirementSummary) {
        if (requirementSummary == null || requirementSummary.isBlank()) {
            return content;
        }
        return new DevelopmentRequestContent(content.summary(), requirementSummary.strip(),
                content.requirements(), content.screens(), content.backendChanges(), content.notes(),
                content.testScenarios());
    }

    @Transactional(readOnly = true)
    public List<Row> list(String projectId) {
        List<DevelopmentRequest> all = requests.selectByProjectId(projectId);
        Map<String, String> ownerNames = ownerNames(all);
        Map<String, Frd> sources = sources(all);
        Map<String, DevRequestDeliveryAttempt> latestHandoffs = latestHandoffs(all);
        return all.stream()
                .map(request -> {
                    DevelopmentRequestContent content = readContent(request.contentJson());
                    DevRequestPrecheck.Result gate = savedGate(request);
                    int newScreenCount = (int) content.screens().stream()
                            .filter(DevelopmentRequestContent.Screen::isNewScreen)
                            .count();
                    return new Row(request, ownerNames.get(request.frdId()), generating(request, content),
                            content.screens().size(), newScreenCount, content.requiredChanges().size(),
                            gate == null ? 0 : gate.blocking().size(), gate != null && gate.checking(),
                            latestHandoffs.get(request.id()), sources.get(request.frdId()));
                })
                .toList();
    }

    private Map<String, DevRequestDeliveryAttempt> latestHandoffs(List<DevelopmentRequest> all) {
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<String, DevRequestDeliveryAttempt> found = new HashMap<>();
        attempts.selectLatestHandoffByRequestIds(all.stream().map(DevelopmentRequest::id).toList())
                .forEach(attempt -> found.put(attempt.devRequestId(), attempt));
        return found;
    }

    private Map<String, Frd> sources(List<DevelopmentRequest> all) {
        if (all.isEmpty()) return Map.of();
        Map<String, Frd> found = new HashMap<>();
        frds.selectByIdIn(all.stream().map(DevelopmentRequest::frdId).distinct().toList())
                .forEach(frd -> found.put(frd.id(), frd));
        return found;
    }

    /** 목록에서는 저장된 전송 전 확인만 읽는다. 검사기를 새로 실행하지 않는다. */
    private DevRequestPrecheck.Result savedGate(DevelopmentRequest request) {
        if (request.precheckJson() == null || request.precheckJson().isBlank()) {
            return null;
        }
        try {
            return json.readValue(request.precheckJson(), DevRequestPrecheck.Result.class);
        } catch (JsonProcessingException unreadable) {
            log.warn("개발요청서 목록에서 전송 전 확인 기록을 읽지 못했다. requestId={}",
                    request.id(), unreadable);
            return null;
        }
    }

    /** 개발요청서ID 가 아니라 <b>FRD ID</b> 로 담당 이름을 찾아 주는 표다. 질의 둘로 끝낸다. */
    private Map<String, String> ownerNames(List<DevelopmentRequest> all) {
        if (all.isEmpty()) {
            return Map.of();
        }
        List<Frd> sources = frds.selectByIdIn(all.stream().map(DevelopmentRequest::frdId).distinct().toList());
        List<String> ownerAccountIds = sources.stream()
                .map(Frd::ownerAccountId).filter(Objects::nonNull).distinct().toList();
        if (ownerAccountIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> namesByAccountId = new HashMap<>();
        accounts.selectByIdIn(ownerAccountIds)
                .forEach(account -> namesByAccountId.put(account.getId(), account.getName()));
        Map<String, String> namesByFrdId = new HashMap<>();
        sources.stream().filter(frd -> frd.ownerAccountId() != null)
                .forEach(frd -> namesByFrdId.put(frd.id(), namesByAccountId.get(frd.ownerAccountId())));
        return namesByFrdId;
    }

    @Transactional(readOnly = true)
    public View read(String projectId, String requestId) {
        DevelopmentRequest request = requests.selectById(requestId);
        if (request == null || !request.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 개발요청서가 없습니다: " + requestId);
        }
        String ownerName = ownerNames(List.of(request)).get(request.frdId());
        DevelopmentRequestContent content = withLegacyInterviewSummary(
                readContent(request.contentJson()), request.frdId());
        Map<String, String> standardIdsByScreenId = new HashMap<>();
        standardIds.selectByProject(projectId)
                .forEach(row -> standardIdsByScreenId.put(row.screenId(),
                        StandardScreenIdFormat.display(row.standardId(), row.origin())));
        Map<String, FrdScreen> screenByRowId = new HashMap<>();
        screens.selectByFrdId(request.frdId()).forEach(screen -> screenByRowId.put(screen.id(), screen));
        Map<String, String> developmentFileNames = new HashMap<>();
        Map<String, String> iaPlacementLabels = new HashMap<>();
        Map<String, FrdScreenIaPlacement> placementByRowId = new HashMap<>();
        iaPlacements.all(request.frdId()).forEach(placement -> {
            placementByRowId.put(placement.frdScreenId(), placement);
            FrdScreen screen = screenByRowId.get(placement.frdScreenId());
            if (screen == null) return;
            if (placement.developmentFileName() != null) {
                developmentFileNames.put(screen.screenId(), placement.developmentFileName());
            }
            iaPlacementLabels.put(screen.screenId(), iaPlacementLabel(placement));
        });
        content.screens().forEach(screen -> {
            if (screen.managementNumber() != null && !screen.managementNumber().isBlank()) {
                standardIdsByScreenId.put(screen.screenId(), screen.managementNumber());
            }
            if (screen.fileName() != null && !screen.fileName().isBlank()) {
                developmentFileNames.put(screen.screenId(), screen.fileName());
            }
        });
        Frd source = frds.selectById(request.frdId());
        content = withDeliveryScreenIds(content, developmentFileNames,
                source, screenByRowId, placementByRowId);
        return new View(request, content, ownerName, Map.copyOf(standardIdsByScreenId),
                Map.copyOf(developmentFileNames), Map.copyOf(iaPlacementLabels),
                generating(request, content), source);
    }

    /** 옛 개발요청 스냅샷의 신규 화면에도 현재 예약된 개발 파일명을 전달 화면 ID로 채운다. */
    private DevelopmentRequestContent withDeliveryScreenIds(
            DevelopmentRequestContent content, Map<String, String> developmentFileNames,
            Frd frd, Map<String, FrdScreen> screenByRowId,
            Map<String, FrdScreenIaPlacement> placementByRowId) {
        Map<String, String> deliveryIds = new LinkedHashMap<>();
        for (var screen : content.screens()) {
            String deliveryId = screen.developmentScreenId();
            if ((deliveryId == null || deliveryId.isBlank()) && screen.isNewScreen()) {
                deliveryId = screenIdOf(developmentFileNames.get(screen.screenId()));
            }
            if (deliveryId == null || deliveryId.isBlank()) deliveryId = screen.screenId();
            deliveryIds.put(screen.screenId(), deliveryId);
        }

        List<DevelopmentRequestContent.Screen> screens = content.screens().stream().map(screen -> {
            String deliveryId = deliveryIds.getOrDefault(screen.screenId(), screen.deliveryScreenId());
            String fileName = screen.fileName();
            if (fileName == null || fileName.isBlank()) fileName = deliveryId + ".html";
            FrdScreen current = screenByRowId.get(screen.frdScreenId());
            List<DevelopmentRequestContent.Connection> sourceConnections = screen.connections();
            if (sourceConnections.isEmpty() && frd != null && current != null) {
                sourceConnections = connections(frd, current, deliveryIds);
            }
            List<DevelopmentRequestContent.Connection> connections = sourceConnections.stream()
                    .map(connection -> new DevelopmentRequestContent.Connection(
                            connection.anchor(),
                            deliveryIds.getOrDefault(connection.targetScreenId(), connection.targetScreenId()),
                            connection.kind(), connection.label(), connection.condition()))
                    .toList();
            return new DevelopmentRequestContent.Screen(
                    screen.frdScreenId(), screen.screenId(), screen.screenName(), screen.systemCode(),
                    screen.menuPath(), screen.changes(), screen.markers(), screen.memos(), deliveryId,
                    fileName, screen.managementNumber(), screen.screenType(), screen.newScreen(),
                    restoredEntryPoint(screen, current, placementByRowId, deliveryIds), connections);
        }).toList();
        return new DevelopmentRequestContent(content.summary(), content.interviewSummary(),
                content.requirements(), screens,
                content.backendChanges(), content.notes(), content.testScenarios());
    }

    /** 이 필드가 생기기 전에 만든 개발요청서는 FRD 인터뷰 기록에서 요약만 보완해 표시한다. */
    private DevelopmentRequestContent withLegacyInterviewSummary(
            DevelopmentRequestContent content, String frdId) {
        if (content.interviewSummary() != null && !content.interviewSummary().isBlank()) {
            return content;
        }
        String interviewSummary = latestInterviewSummary(frdId);
        if (interviewSummary == null || interviewSummary.isBlank()) {
            return content;
        }
        return new DevelopmentRequestContent(content.summary(), interviewSummary,
                content.requirements(), content.screens(), content.backendChanges(), content.notes(),
                content.testScenarios());
    }

    private String latestInterviewSummary(String frdId) {
        return interviewMessages.selectByFrdId(frdId).stream()
                .filter(message -> message.role() == FrdInterviewMessage.Role.AI
                        && message.kind() == FrdInterviewMessage.Kind.SUMMARY)
                .reduce((before, latest) -> latest)
                .map(FrdInterviewMessage::content)
                .orElse(null);
    }

    private static String restoredEntryPoint(
            DevelopmentRequestContent.Screen screen, FrdScreen current,
            Map<String, FrdScreenIaPlacement> placementByRowId, Map<String, String> deliveryIds) {
        String entryPoint = screen.entryPoint();
        if ((entryPoint == null || entryPoint.isBlank()) && current != null) {
            entryPoint = entryPoint(current, placementByRowId.get(current.id()), deliveryIds);
        }
        return ScreenDefinitionDocument.replaceScreenIds(entryPoint, deliveryIds);
    }

    private static String screenIdOf(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String value = fileName.strip();
        return value.endsWith(".html") ? value.substring(0, value.length() - 5) : value;
    }

    private static String iaPlacementLabel(FrdScreenIaPlacement placement) {
        return switch (placement.placementMode()) {
            case MENU -> "메뉴 · " + placement.menuPathKey();
            case CHILD -> placement.anchorScreenId() + " 하위";
            case OPENER -> placement.anchorScreenId() + "에서 여는 " + placement.screenKind().label();
            case UNRESOLVED -> "IA 위치 미정";
        };
    }

    @Transactional
    public void savePlannerComment(String projectId, String requestId, String comment) {
        read(projectId, requestId);
        String normalized = comment == null ? null : comment.strip();
        if (normalized != null && normalized.length() > 4000) {
            throw new IllegalArgumentException("전달사항은 4,000자 이내로 입력해 주세요.");
        }
        if (requests.updatePlannerComment(requestId,
                normalized == null || normalized.isBlank() ? null : normalized) != 1) {
            throw new IllegalStateException("개발요청서 전달사항을 저장하지 못했습니다.");
        }
    }

    /**
     * 같은 화면을 건드린 앞선 개발요청서 후보.
     *
     * <p>⛔ <b>이 목록에서 하나를 골라 자동으로 채우지 마라.</b> 화면이 겹치는 것과
     * <b>같은 업무인 것은 다르다</b> — 겹치는 것이 여럿일 수 있고, 틀린 것이 실리면
     * 개발이 엉뚱한 앞 문서와 이어 읽는다. 사람이 고른다.
     *
     * <p>⚠ 화면 0장 개발요청서는 <b>빈 목록</b>이 정상이다.
     */
    /**
     * 「변경 예정 기능정의서」가 없는 화면들에 만들기를 건다.
     *
     * <p>⛔ <b>전송을 여기에 매달지 마라.</b> AI 실행은 분 단위라 전송 클릭이 그만큼 멈춘다 —
     * 사람이 따로 눌러 걸고, 다 될 때까지 검증이 <b>경고</b>로 남겨 준다.
     *
     * <p>⚠ 이미 있는 것은 건드리지 않는다 — 세는 것은 <b>건 것</b>이지 만들어진 것이 아니다.
     *
     * @return 만들기를 건 화면 수
     *
     * <p>⛔ <b>트랜잭션을 걸지 마라 (2026-08-26).</b> 종전에는 {@code @Transactional} 이었는데, 그 안에서
     * {@code generate()} 를 비동기로 던지면 <b>REQUESTED 갱신이 커밋되기 전에 일꾼이 깨어</b>
     * RUNNING 을 먼저 쓰고, 뒤늦게 커밋된 REQUESTED 가 그것을 덮을 수 있었다. 갱신은 한 줄씩 곧바로 커밋한다.
     */
    public int requestTobeDocuments(String projectId, String requestId) {
        View view = read(projectId, requestId);
        int asked = 0;
        for (var screen : view.content().screens()) {
            FrdScreenHistory latest = histories.selectLatestByScreenId(screen.frdScreenId());
            if (latest == null || (latest.md() != null && !latest.md().isBlank())) {
                continue;
            }
            tobeDocuments.markRequested(screen.frdScreenId());
            tobeDocuments.generate(screen.frdScreenId());
            asked++;
        }
        return asked;
    }

    /**
     * 개발이 받을 <b>테스트 시나리오</b> 만들기를 건다 — {@link #requestTobeDocuments} 와 같은 자리, 같은 규칙이다.
     *
     * <p>⭐ <b>왜 여기로 왔나 (병주 지시 2026-08-27).</b> 종전에는 상세 화면을 열 때
     * {@code DevRequestPrecheck} 가 청했다. 그 자리는 {@link #precheck} 의 <b>{@code readOnly = true}
     * 트랜잭션 안</b>이라, 만들기가 스냅샷을 저장하는 순간 PostgreSQL 이 그 트랜잭션을 통째로 중단시켰고
     * <b>FRD 완료의 도착 화면이 500 으로 죽어</b> 「완료는 됐는데 개발요청서가 안 만들어졌다」로 보였다.
     * 읽기라고 선언한 자리에 부작용을 두지 않는다.
     *
     * <p>⛔ <b>트랜잭션을 걸지 마라</b> — {@link #requestTobeDocuments} 에 적은 사유가 그대로다.
     *
     * @return 청했으면 참. 이미 있거나·도는 중이거나·앞서 실패했으면 거짓이다
     */
    public boolean requestTestScenarios(String projectId, String requestId) {
        View view = read(projectId, requestId);
        return testScenarios.requestIfMissing(view.request(), view.content());
    }

    /**
     * FRD 로 되돌리기 — <b>전송 전</b> 개발요청서를 지우고 FRD 작업을 다시 연다 (병주 지시 2026-08-25).
     *
     * <p>⭐ <b>왜 있나.</b> 개발요청서가 생기면 FRD 는 {@code REVIEW} 로 가고 거기서 나가는 길이
     * {@code DONE} 만이었다 — 인터뷰가 남긴 「확인 필요」를 정리하거나 화면을 더 고칠 길이 없었다.
     * 반대쪽 「전송 철회」({@link #withdraw})는 있는데 전송 전 폐기가 없는 것도 비대칭이다.
     *
     * <p>⛔ <b>{@code NOT_SENT} 만이다.</b> 보낸 것(SENT)은 철회의 자리고, 무른 것(WITHDRAWN)은 개발이
     * 한 번 받은 이력이라 지우면 안 된다 — 그건 「고쳐서 다시 보내기」로 간다.
     *
     * <p>⚠ <b>FRD 가 어디로 돌아가나는 워크트리 존재로 가른다.</b> {@code DRAFTING} 은 워크트리를 만든 뒤에만
     * 찍히고(생애 설계), 워크트리는 {@code reset}·{@code rollback} 안에서만 지워진다. 그래서
     * 워크트리가 있으면 작업대에서 완료한 것 → {@code DRAFTING}, 없으면 간단 변경 → {@code SCOPE_REVIEW}.
     * 개발요청서에 출처 칸을 더 두지 않은 까닭이다 — 같은 사실을 두 곳에 두면 갈린다.
     *
     * <p>⛔ <b>{@code frd_screen_history.md}(변경 예정 기능정의서)는 지우지 않는다.</b> FRD 것이라
     * 다시 완료할 때 그대로 실린다.
     *
     * <p>⭐ <b>{@code DRAFTING} 으로 돌아갈 때는 완료 커밋을 푼다</b> (2026-08-25 병주 실측). 「작업 완료」
     * 버튼은 커밋 안 된 변경이 있을 때만 켜지는데, 완료가 변경을 커밋해 버려 되돌아온 화면의 버튼이
     * 꺼져 있었다. 파일은 그대로 두고 커밋만 푼다 — 그래야 이어 고친 뒤 다시 완료할 수 있다.
     * ⚠ git 이 실패하면 던진다 — 트랜잭션이 DB 를 되돌리고, 워크트리는 아직 안 바뀐 상태다.
     */
    @Transactional
    public void returnToFrd(String projectId, String requestId) {
        DevelopmentRequest request = read(projectId, requestId).request();
        if (request.deliveryState() != DevelopmentRequest.DeliveryState.NOT_SENT) {
            throw new IllegalStateException("전송 전인 개발요청서만 FRD 로 되돌릴 수 있습니다.");
        }
        if (requests.countReferencing(requestId) > 0) {
            throw new IllegalStateException(
                    "다른 개발요청서가 이 문서를 앞 개발요청서로 가리키고 있어 되돌릴 수 없습니다.");
        }
        Frd frd = frds.selectById(request.frdId());
        if (frd == null || frd.state() != Frd.State.REVIEW) {
            throw new IllegalStateException("FRD 가 검토 단계가 아니어서 되돌릴 수 없습니다.");
        }
        Frd.State back = Files.isDirectory(paths.frdWorktree(projectId, frd.id()))
                ? Frd.State.DRAFTING : Frd.State.SCOPE_REVIEW;

        attempts.deleteByRequestId(requestId);
        if (requests.deleteNotSent(requestId) != 1) {
            throw new IllegalStateException("그 사이 전송이 시작됐습니다. 다시 확인해 주세요.");
        }
        if (frds.transitionState(frd.id(), Frd.State.REVIEW, back) != 1) {
            throw new IllegalStateException("다른 요청이 먼저 FRD 상태를 변경했습니다. 목록에서 다시 확인해 주세요.");
        }
        if (back == Frd.State.DRAFTING
                && !workspaces.uncommitCompletion(projectId, frd.id(), FrdWorkspace.completionMessage(frd.label()))) {
            log.warn("작업 완료 커밋을 풀지 못했다 — HEAD 가 완료 커밋이 아니다. 작업 완료 버튼이 꺼져 있을 수 있다 frdId={}",
                    frd.id());
        }
        deleteAttachmentQuietly(request);
    }

    /** ⚠ 전송이 실패로 끝난 개발요청서는 첨부 파일만 남아 있을 수 있다. 파일은 DB 커밋 뒤 사라져도 해가 없다. */
    private void deleteAttachmentQuietly(DevelopmentRequest request) {
        if (request.attachmentPath() == null || request.attachmentPath().isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(request.attachmentPath()));
        } catch (IOException | RuntimeException ignored) {
            // 고아 파일 하나가 되돌리기를 막을 이유는 아니다.
        }
    }

    /** 상세 화면이 가볍게 묻는 진행 상태 — 둘 다 거짓이 되는 순간 화면이 스스로 다시 읽는다 (2026-08-25). */
    public record Progress(boolean generating, boolean checking) {
        public boolean pending() {
            return generating || checking;
        }
    }

    @Transactional(readOnly = true)
    public Progress progress(String projectId, String requestId) {
        View view = read(projectId, requestId);
        // ⛔ 여기서 게이트를 다시 재지 않는다 — 검사기는 전송을 누를 때만 돈다(2026-08-25 병주 지시).
        return new Progress(view.generating(), false);
    }

    /** 전송 게이트를 미리 보여 준다. ⚠ 읽기만 한다 — 여기서 상태를 바꾸지 않는다. */
    @Transactional(readOnly = true)
    public DevRequestPrecheck.Result precheck(String projectId, String requestId) {
        return gateOf(read(projectId, requestId));
    }

    /**
     * 「개발요청 전송」을 누른 순간에 <b>검사기까지 돌려</b> 재고, 그 결과를 굳힌다.
     *
     * <p>⭐ <b>부르는 자리는 전송 버튼 하나다</b> (2026-08-25 병주 지시 — 「화면 들어갈 때마다 검증하지 마라」).
     * 상세는 이 기록을 <b>읽기만</b> 한다. 막혀서 못 나가도 기록은 남아, 무엇 때문에 막혔는지가 화면에 그대로 뜬다.
     *
     * <p>⚠ <b>{@link #requestDelivery} 보다 먼저, 별도 트랜잭션으로 부른다.</b> 전송이 막히면 그 트랜잭션은
     * 되돌려지는데 <b>이 기록은 되돌려지면 안 된다</b> — 막힌 까닭을 보여 주려고 적는 것이다.
     */
    @Transactional
    public DevRequestPrecheck.Result measureDeliveryGate(String projectId, String requestId) {
        DevRequestPrecheck.Result gate = prechecks.checkForDelivery(read(projectId, requestId));
        requests.updatePrecheck(requestId, write(gate));
        return gate;
    }

    /**
     * 「전송 전 확인」 — <b>마지막으로 잰 결과를 읽는다. 여기서 새로 재지 않는다</b> (2026-08-25 병주 지시).
     *
     * <p>⭐ <b>실물에서 발견.</b> 전송완료된 {@code DR-011} 의 「전송 전 확인」이 상세를 열 때마다 달라졌다 —
     * 매번 지금 클론·워크트리를 다시 재고 있었고, 검사기 UNKNOWN 이 10분마다 만료돼
     * 「점검 중 ↔ 돌리지 못했다」를 왕복했다. 「전송 <b>전</b> 확인」은 계약 시점의 기록이어야 한다.
     *
     * <p>⭐ <b>기록이 있으면 상태를 안 가리고 그것을 보여 준다.</b> 그 기록은 「개발요청 전송」을 누른 순간에
     * 잰 것이다 — 막혀서 못 나갔어도 <b>왜 막혔는지</b>가 거기 있다(검사기 결과까지). 고쳐서 다시 누르면 새로 잰다.
     *
     * <p>⚠ 기록이 없으면(아직 한 번도 안 눌렀거나 옛 판) DB 로 아는 것만 잰다 — <b>검사기는 안 부른다.</b>
     */
    private DevRequestPrecheck.Result gateOf(View view) {
        DevelopmentRequest request = view.request();
        if (request.precheckJson() != null && !request.precheckJson().isBlank()) {
            try {
                return json.readValue(request.precheckJson(), DevRequestPrecheck.Result.class);
            } catch (JsonProcessingException unreadable) {
                log.warn("얼린 전송 전 확인을 읽지 못해 지금 상태를 다시 잰다. requestId={}", request.id(), unreadable);
            }
        }
        return prechecks.check(view);
    }

    private String write(DevRequestPrecheck.Result gate) {
        try {
            return json.writeValueAsString(gate);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("전송 전 확인 결과를 저장할 수 없습니다.", failure);
        }
    }

    @Transactional(readOnly = true)
    public List<DevelopmentRequest> previousCandidates(String projectId, String requestId) {
        read(projectId, requestId);
        return requests.selectPreviousCandidates(projectId, requestId);
    }

    /**
     * 화면 관리번호를 만들 수 있는 신규 화면에는 선제적으로 채번한다. 채번 실패는 개발요청을 막지 않는다.
     * 개발에서 사용하는 화면 ID와 파일명은 {@link #reserveDevelopmentScreenIds}가 별도로 확보한다.
     *
     * <p>⭐ <b>실물에서 두 번 걸렸다 (2026-08-25 FRD-035).</b>
     * <ul>
     *   <li>기준이 <b>다른 신규 화면</b>인 사슬(복사본의 복사본) — 한 번 훑으면 순서에 따라 기준이 아직 안 박혀
     *       있을 수 있다. <b>진전이 없을 때까지 다시 훑는다.</b></li>
     *   <li>채번 기준은 신규 화면의 IA 기준 화면이다. 같은 시스템이라는 이유만으로 임의의 형제를 빌리지 않는다.</li>
     * </ul>
     */
    private void reserveDevelopmentScreenIds(String projectId, List<FrdScreen> rows) {
        rows.stream().filter(FrdScreen::isNewScreen)
                .forEach(screen -> iaPlacements.reserveDevelopmentFileName(projectId, screen));
    }

    private void allocateStandardIdsForNewScreens(String projectId, List<FrdScreen> rows) {
        List<FrdScreen> pending = new java.util.ArrayList<>(rows.stream().filter(FrdScreen::isNewScreen).toList());
        Map<String, FrdScreenIaPlacement> placementByRowId = new HashMap<>();
        iaPlacements.all(rows.isEmpty() ? "" : rows.get(0).frdId())
                .forEach(placement -> placementByRowId.put(placement.frdScreenId(), placement));
        boolean progressed = true;
        while (!pending.isEmpty() && progressed) {
            progressed = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                FrdScreen row = it.next();
                FrdScreenIaPlacement placement = placementByRowId.get(row.id());
                String anchor = placement != null && placement.resolved()
                        && placement.placementMode() != FrdScreenIaPlacement.PlacementMode.MENU
                        ? placement.anchorScreenId() : row.baseScreenId();
                var allocated = placement != null && placement.resolved()
                        && placement.placementMode() == FrdScreenIaPlacement.PlacementMode.MENU
                        ? standardIdAllocator.allocateForNewScreenAtMenu(projectId, row.screenId(),
                        row.systemCode(), placement.menuPathKey(), row.screenName(), row.screenType())
                        : standardIdAllocator.allocateForNewScreen(projectId, row.screenId(), anchor,
                        row.screenType());
                if (allocated.isPresent()) {
                    it.remove();
                    progressed = true;
                }
            }
        }
        for (FrdScreen row : pending) {
            log.warn("IA 기준 화면의 표준ID가 없어 신규 화면을 채번하지 못했다 projectId={} screenId={}",
                    projectId, row.screenId());
        }
    }

    private DevelopmentRequestContent snapshot(Frd frd, List<FrdScreen> screenRows) {
        List<DevelopmentRequestContent.Requirement> requirementSnapshot = items.selectByFrdId(frd.id()).stream()
                .map(item -> new DevelopmentRequestContent.Requirement(item.seq(), item.requirement(),
                        item.nature().name(), natureLabel(item.nature()), item.note()))
                .toList();
        Map<String, String> menuPaths = menuPaths(frd.projectId());
        Map<String, FrdScreenIaPlacement> placementsByRowId = new HashMap<>();
        iaPlacements.all(frd.id()).forEach(placement -> placementsByRowId.put(placement.frdScreenId(), placement));
        Map<String, String> managementNumbers = new HashMap<>();
        standardIds.selectByProject(frd.projectId()).forEach(row -> managementNumbers.put(
                row.screenId(), StandardScreenIdFormat.display(row.standardId(), row.origin())));
        Map<String, String> deliveryIds = new LinkedHashMap<>();
        for (FrdScreen screen : screenRows) {
            FrdScreenIaPlacement placement = placementsByRowId.get(screen.id());
            String deliveryId = screen.isNewScreen() && placement != null
                    && placement.developmentFileName() != null && !placement.developmentFileName().isBlank()
                    ? placement.developmentFileName() : screen.screenId();
            deliveryIds.put(screen.screenId(), deliveryId);
        }
        List<DevelopmentRequestContent.Screen> screenSnapshot = screenRows.stream()
                .map(screen -> new DevelopmentRequestContent.Screen(screen.id(), screen.screenId(),
                        screen.screenName(), screen.systemCode(), menuPaths.get(screen.screenId()),
                        latestChanges(screen), markerSnapshot(screen), memoSnapshot(screen),
                        deliveryIds.get(screen.screenId()), deliveryIds.get(screen.screenId()) + ".html",
                        managementNumbers.get(screen.screenId()), screen.screenType(), screen.isNewScreen(),
                        entryPoint(screen, placementsByRowId.get(screen.id()), deliveryIds),
                        connections(frd, screen, deliveryIds)))
                .toList();
        // ⛔ 「변경 없음」을 여기서 걸러 내지 마라 — 확인 기록이 계약에 실려야 한다.
        //    거르는 자리는 DevelopmentRequestContent.requiredChanges() 다.
        List<DevelopmentRequestContent.BackendChange> backendSnapshot = backendChanges.selectByFrdId(frd.id()).stream()
                .map(change -> new DevelopmentRequestContent.BackendChange(change.category().name(),
                        change.categoryLabel(), change.target(), change.changeDetail(),
                        change.requirementSeq(), change.evidence(), change.verification(),
                        change.required()))
                .toList();
        List<DevelopmentRequestContent.Note> noteSnapshot = notes.selectByFrdId(frd.id()).stream()
                .map(note -> new DevelopmentRequestContent.Note(note.kind().name(), note.content()))
                .toList();
        String interviewSummary = latestInterviewSummary(frd.id());
        return new DevelopmentRequestContent(frd.sourceText(), interviewSummary, requirementSnapshot,
                screenSnapshot, backendSnapshot, noteSnapshot);
    }

    private List<DevelopmentRequestContent.Connection> connections(
            Frd frd, FrdScreen screen, Map<String, String> deliveryIds) {
        String system = screen.systemCode() == null || screen.systemCode().isBlank()
                ? frd.systemCode() : screen.systemCode();
        if (system == null || !system.matches("^[A-Za-z0-9_-]+$")
                || screen.screenId() == null || !screen.screenId().matches("^[A-Za-z0-9_-]+$")) {
            return List.of();
        }
        Path worktree = paths.frdWorktree(frd.projectId(), frd.id()).toAbsolutePath().normalize();
        Path md = worktree.resolve("core").resolve(system).resolve("pages")
                .resolve(screen.screenId() + ".md").normalize();
        if (!md.startsWith(worktree) || !Files.isRegularFile(md)) return List.of();
        try {
            List<DevelopmentRequestContent.Connection> result = new ArrayList<>();
            Matcher matcher = SCREEN_DEFINITION.matcher(Files.readString(md, StandardCharsets.UTF_8));
            while (matcher.find()) {
                Map<String, String> fields = definitionFields(matcher.group().strip());
                if (!"이동".equals(fields.get("구분"))) continue;
                String kind = LINK_FIELDS.stream().filter(fields::containsKey).findFirst().orElse(null);
                if (kind == null) continue;
                String target = fields.get(kind);
                if (target == null || target.isBlank()) continue;
                result.add(new DevelopmentRequestContent.Connection(
                        fields.get("앵커"), deliveryIds.getOrDefault(target, target), kind,
                        fields.get("라벨"), fields.get("조건")));
            }
            return List.copyOf(result);
        } catch (IOException unreadable) {
            log.warn("개발요청서에 화면 연결 안내를 고정하지 못했습니다. frdId={} screenId={}",
                    frd.id(), screen.screenId(), unreadable);
            return List.of();
        }
    }

    private static String entryPoint(FrdScreen screen, FrdScreenIaPlacement placement,
                                     Map<String, String> deliveryIds) {
        if (placement == null || placement.placementMode() == FrdScreenIaPlacement.PlacementMode.UNRESOLVED
                || placement.status() == FrdScreenIaPlacement.Status.INVALID) {
            return screen.baseScreenId() == null || screen.baseScreenId().isBlank() ? null
                    : deliveryIds.getOrDefault(screen.baseScreenId(), screen.baseScreenId())
                    + "를 기준으로 진입 위치 확인";
        }
        return switch (placement.placementMode()) {
            case MENU -> placement.menuPathKey() == null ? null : "메뉴 " + placement.menuPathKey() + "에서 진입";
            case CHILD -> placement.anchorScreenId() == null ? null
                    : deliveryIds.getOrDefault(placement.anchorScreenId(), placement.anchorScreenId()) + "에서 진입";
            case OPENER -> placement.anchorScreenId() == null ? null
                    : deliveryIds.getOrDefault(placement.anchorScreenId(), placement.anchorScreenId())
                    + "에서 " + placement.screenKind().label() + " 열기";
            case UNRESOLVED -> null;
        };
    }

    private static Map<String, String> definitionFields(String line) {
        String content = line.startsWith("- ") ? line.substring(2) : line;
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : content.split(" / ")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            result.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
        }
        return result;
    }

    /** 전달사항과 첨부파일을 고정하고 외부 전송 일꾼이 가져갈 수 있도록 전송중으로 옮긴다. */
    @Transactional
    public void requestDelivery(String projectId, String requestId, String comment,
                                LocalDate developmentCompletedOn, LocalDate deploymentOn,
                                String previousRequestId, MultipartFile attachment) {
        repositoryLocks.run(projectId, () -> requestDeliveryLocked(projectId, requestId, comment,
                developmentCompletedOn, deploymentOn, previousRequestId, attachment));
    }

    private void requestDeliveryLocked(String projectId, String requestId, String comment,
                                       LocalDate developmentCompletedOn, LocalDate deploymentOn,
                                       String previousRequestId, MultipartFile attachment) {
        View before = read(projectId, requestId);
        DevelopmentRequest request = before.request();
        String previous = normalizePrevious(projectId, requestId, previousRequestId);
        String normalized = comment == null ? null : comment.strip();
        if (normalized != null && normalized.length() > 4000) {
            throw new IllegalArgumentException("개발팀 전달사항은 4,000자 이내로 입력해 주세요.");
        }
        if (developmentCompletedOn != null && deploymentOn != null
                && deploymentOn.isBefore(developmentCompletedOn)) {
            throw new IllegalArgumentException("배포일은 개발 완료일과 같거나 이후여야 합니다.");
        }
        boolean hasAttachment = attachment != null && !attachment.isEmpty();
        if (hasAttachment && attachment.getSize() > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("첨부파일은 20MB 이하로 추가해 주세요.");
        }
        // ⭐ 방금 입력한 것부터 잰다 — 게이트를 먼저 올리면 날짜를 잘못 넣은 사람이
        //    엉뚱한 말을 듣는다. ⛔ 다만 게이트는 「전송중」으로 옮기기 전이어야 한다 —
        //    옮긴 뒤에 막으면 그 개발요청서가 다시 못 눌린다.
        Path stored = null;
        String originalName = null;
        FrdWorkspace.Commit materialization = null;
        try {
            // AI가 만든 최종 기능정의서와 전달 화면 파일을 먼저 확정한 뒤 실제로 보낼 판을 검사한다.
            // 검사나 전송 준비가 실패하면 아래 보상 처리로 이 커밋과 파일을 함께 되돌린다.
            materialization = prepareDeliveryWorkspace(before);
            View prepared = read(projectId, requestId);
            DevRequestPrecheck.Result gate = prechecks.checkForDelivery(prepared);
            // ⭐ 잰 것을 굳힌다 — 상세는 이제 검사기를 안 돌리므로 이 기록이 「무엇 때문에 막혔나」를 보여 줄 유일한 자리다.
            requests.updatePrecheck(requestId, write(gate));
            if (!gate.sendable()) {
                throw new IllegalStateException("전송 전에 확인할 것이 %d건 있습니다: %s"
                        .formatted(gate.blocking().size(), gate.blocking().get(0).message()));
            }
            if (materialization != null
                    && requests.updateWorkspaceHeadSha(request.id(), materialization.after()) != 1) {
                throw new IllegalStateException("개발요청서의 작업트리 기준판을 저장하지 못했습니다.");
            }

            if (hasAttachment) {
                originalName = safeFileName(attachment.getOriginalFilename());
                Path room = paths.devRequestAttachmentDir(projectId);
                Files.createDirectories(room);
                stored = room.resolve(requestId + "-" + originalName);
                try (var input = attachment.getInputStream()) {
                    Files.copy(input, stored, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (requests.requestDelivery(requestId,
                    normalized == null || normalized.isBlank() ? null : normalized,
                    originalName, stored == null ? null : stored.toString(),
                    hasAttachment ? attachment.getSize() : null,
                    developmentCompletedOn, deploymentOn, previous, write(gate)) != 1) {
                throw new IllegalStateException(
                        "이미 전송되었거나 앞선 전송이 아직 끝나지 않았습니다. 잠시 후 다시 확인해 주세요.");
            }
            // ⚠ 「전송중」을 확정한 뒤에 굽고 보낸다 — 순서가 계약이다(전송 설계).
            //    묶으면 상대는 받았는데 우리 쪽이 되돌아가 다시 「대기」가 되고 다음 클릭이 또 보낸다.
            View fixed = read(projectId, requestId);
            DevelopmentRequest previousRow = previous == null ? null : requests.selectById(previous);
            String key = deliveryKey(requestId);
            DevRequestPackage built = packages.build(fixed, key, Instant.now().toString(),
                    previousRow == null ? null : previousRow.label());
            built = zipper.store(built, paths.devRequestPackageArchive(
                    projectId, requestId, fixed.request().number()));
            handOff(requestId, key, built);
        } catch (IOException failed) {
            deleteQuietly(stored);
            workspaces.rollbackMaterialization(materialization);
            throw new IllegalStateException("첨부파일을 보관하지 못했습니다. 파일을 다시 선택해 주세요.", failed);
        } catch (RuntimeException failed) {
            deleteQuietly(stored);
            workspaces.rollbackMaterialization(materialization);
            throw failed;
        }
    }

    /** 화면 산출물을 전부 FRD 작업트리에 앉힌 뒤 그 커밋을 개발요청서의 전달 기준판으로 고정한다. */
    private FrdWorkspace.Commit prepareDeliveryWorkspace(View view) {
        DevelopmentRequest request = view.request();
        if (request.workspaceBaseSha() == null || request.workspaceBaseSha().isBlank()) {
            // 화면 없는 간단 변경과 이 기능 도입 전 개발요청서는 고정된 작업트리 판이 없다.
            return null;
        }
        List<FrdWorkspace.TobeDocument> documents = new ArrayList<>();
        Map<String, String> deliveryScreenIds = new LinkedHashMap<>();
        for (var screen : view.content().screens()) {
            deliveryScreenIds.put(screen.screenId(), screen.deliveryScreenId());
        }
        for (var screen : view.content().screens()) {
            FrdScreenHistory latest = histories.selectLatestByScreenId(screen.frdScreenId());
            if (latest == null || latest.md() == null || latest.md().isBlank()) {
                throw new IllegalStateException("변경 예정 기능정의서가 없어 개발요청서를 작업트리에서 확정할 수 없습니다: "
                        + screen.displayName());
            }
            String systemCode = screen.systemCode();
            if (systemCode == null || systemCode.isBlank()) {
                systemCode = request.systemCode();
            }
            if (systemCode == null || systemCode.isBlank()) {
                throw new IllegalStateException("화면의 시스템이 없어 기능정의서를 작업트리에 저장할 수 없습니다: "
                        + screen.displayName());
            }
            String document = ScreenDefinitionDocument.forDelivery(latest.md(), deliveryScreenIds);
            documents.add(new FrdWorkspace.TobeDocument(
                    systemCode, screen.screenId(), screen.deliveryScreenId(), document));
        }
        return workspaces.materializeTobeDocuments(
                request.projectId(), request.frdId(), request.label(), documents);
    }

    /**
     * 고른 앞 개발요청서를 검사한다.
     *
     * <p>⛔ <b>같은 프로젝트인지 반드시 잰다.</b> 번호는 프로젝트마다 1번부터라 남의 사업 것을
     * 가리켜도 글자만으로는 그럴싸하다 — 그것이 계약서에 실리면 개발이 없는 문서를 찾는다.
     */
    private String normalizePrevious(String projectId, String requestId, String previousRequestId) {
        if (previousRequestId == null || previousRequestId.isBlank()) {
            return null;
        }
        String candidate = previousRequestId.strip();
        if (candidate.equals(requestId)) {
            throw new IllegalArgumentException("앞 개발요청서로 자기 자신을 고를 수 없습니다.");
        }
        DevelopmentRequest previous = requests.selectById(candidate);
        if (previous == null || !previous.projectId().equals(projectId)) {
            throw new IllegalArgumentException("앞 개발요청서를 찾을 수 없습니다. 목록에서 다시 골라 주세요.");
        }
        return candidate;
    }

    /**
     * 창구를 한 번 부르고 <b>시도 한 줄</b>을 남긴다.
     *
     * <p>⛔ <b>여는 것과 닫는 것을 갈라 둔다.</b> 부르기 전에 줄을 열어 두지 않으면, 부르는 중에
     * 서버가 죽었을 때 <b>보냈는지 안 보냈는지 아무 기록이 없다.</b>
     *
     * <p>⛔ <b>몰래 재시도하지 않는다.</b> 한 번 부르고 답에 따라 상태를 옮기는 것이 전부다 —
     * 다시 보내는 것은 사람이 위험을 떠안고 고른다.
     */
    private void handOff(String requestId, String deliveryKey, DevRequestPackage built) {
        String attemptId = ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY);
        attempts.insert(new DevRequestDeliveryAttempt(attemptId, requestId, deliveryKey,
                built.fingerprint(), DeliveryOutcome.SENDING, null, null, null, null, null, null));
        DevHandoffGateway.Receipt receipt;
        try {
            receipt = gateway.send(built, deliveryKey);
        } catch (RuntimeException failed) {
            // ⚠ 던졌다는 것은 답을 못 받았다는 뜻이다 — 상대가 이미 받았을 수 있다.
            //    ⛔ 「대기」로 뭉치지 마라. 그러면 다시 누를 때 두 번 간다.
            receipt = DevHandoffGateway.Receipt.sending(String.valueOf(failed.getMessage()));
        }
        try {
            attempts.finish(attemptId, receipt.outcome(), receipt.httpStatus(),
                    receipt.responseId(), receipt.failure());
            if (receipt.outcome() != DeliveryOutcome.SENDING) {
                // ⛔ 「전송중」일 때만 옮긴다 — 그 사이 사람이 손으로 갈라 준 것을 되돌리지 않는다.
                attempts.moveFromSending(requestId, receipt.outcome());
            }
        } catch (RuntimeException recordFailure) {
            // 외부 창구를 이미 불렀다. 여기서 예외를 되던지면 DB와 Git 기준판이 롤백되어
            // 사용자가 다시 눌렀을 때 같은 개발요청이 중복 전송될 수 있다. 전송중으로 남겨 수동 확인한다.
            log.error("개발요청 외부 전송 뒤 결과를 기록하지 못했다 requestId={} deliveryKey={}",
                    requestId, deliveryKey, recordFailure);
        }
    }

    /**
     * 보낸 것을 <b>철회</b>한다 — 병주 지시 2026-08-25.
     *
     * <p>⭐ <b>「취소」가 아니다.</b> 이미 나간 것은 없던 일로 못 만든다 — 개발에게 알림이 갔고
     * 읽음이 남는다. 하는 것은 <b>그쪽 언어로 「무릅니다」를 알리는 것</b>이다:
     * 라벨을 {@code intake} 에서 빼고 {@code withdrawn} 을 붙이고 이슈를 닫는다.
     *
     * <p>⭐ <b>라벨이 {@code intake} 일 때만 된다.</b> 개발이 집어가면 그쪽 워크플로가 라벨을
     * 바꾸므로, <b>라벨이 그대로인지 보는 것만으로 「아직 아무도 손 안 댔다」가 판정된다</b> —
     * 개발과 따로 합의할 것이 없다. 이 판정은 <b>창구가</b> 한다.
     *
     * <p>⚠ <b>기획자가 누른다.</b> 자기가 보낸 것을 무르는 것이고, 라벨 검사가 안전장치다.
     *
     * <p>⛔ <b>창구가 철회를 못 했으면 DB 를 안 옮긴다.</b> 이슈는 열려 있는데 우리만 철회로 알면
     * 개발이 그것을 집어간다 — 「없으니 무른 셈 치자」가 가장 나쁜 결말이다.
     */
    @Transactional
    public void withdraw(String projectId, String requestId, String reason, String accountId) {
        repositoryLocks.run(projectId, () -> withdrawLocked(projectId, requestId, reason, accountId));
    }

    private void withdrawLocked(String projectId, String requestId, String reason, String accountId) {
        View view = read(projectId, requestId);
        if (view.request().deliveryState() != DevelopmentRequest.DeliveryState.SENT) {
            throw new IllegalStateException("전송완료인 개발요청서만 취소할 수 있습니다.");
        }
        String key = deliveryKey(requestId);
        String attemptId = ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY);
        attempts.insert(new DevRequestDeliveryAttempt(attemptId, requestId, key, null,
                DeliveryOutcome.SENDING, null, null, null, accountId, null, null));

        DevHandoffGateway.Receipt receipt;
        try {
            receipt = gateway.withdraw(projectId, key, reason);
        } catch (RuntimeException failed) {
            receipt = new DevHandoffGateway.Receipt(DeliveryOutcome.SENT, null, null,
                    "개발요청을 취소하지 못했습니다: " + failed.getMessage());
        }
        attempts.finish(attemptId, receipt.outcome(), receipt.httpStatus(),
                receipt.responseId(), receipt.failure());

        if (receipt.outcome() != DeliveryOutcome.WITHDRAWN) {
            throw new IllegalStateException(receipt.failure() == null
                    ? "개발요청을 취소하지 못했습니다." : receipt.failure());
        }
        if (requests.withdrawDelivery(requestId) != 1) {
            throw new IllegalStateException("그 사이 전송 상태가 바뀌었습니다. 다시 확인해 주세요.");
        }
    }

    /** 시도 이력. 최신이 맨 위다. */
    @Transactional(readOnly = true)
    public List<DevRequestDeliveryAttempt> deliveryAttempts(String projectId, String requestId) {
        read(projectId, requestId);
        return attempts.selectByRequestId(requestId);
    }

    /** 전송 완료 상태이며 당시 저장한 ZIP 원본이 실제로 남아 있을 때만 다운로드를 연다. */
    @Transactional(readOnly = true)
    public boolean hasStoredPackage(DevelopmentRequest request) {
        return request != null
                && request.deliveryState() == DevelopmentRequest.DeliveryState.SENT
                && Files.isRegularFile(packageArchive(request));
    }

    /** 주소의 프로젝트와 개발요청서 소유 관계를 확인한 뒤 저장 ZIP을 돌려준다. */
    @Transactional(readOnly = true)
    public StoredPackage storedPackage(String projectId, String requestId) {
        DevelopmentRequest request = read(projectId, requestId).request();
        if (request.deliveryState() != DevelopmentRequest.DeliveryState.SENT) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "전송 완료된 개발요청서가 아닙니다.");
        }
        Path archive = packageArchive(request);
        if (!Files.isRegularFile(archive) || !Files.isReadable(archive)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 개발요청서 ZIP이 없습니다.");
        }
        try {
            return new StoredPackage(archive, request.label() + ".zip", Files.size(archive));
        } catch (IOException failed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "저장된 개발요청서 ZIP을 읽을 수 없습니다.", failed);
        }
    }

    private Path packageArchive(DevelopmentRequest request) {
        return paths.devRequestPackageArchive(
                request.projectId(), request.id(), request.number()).toAbsolutePath().normalize();
    }

    public record StoredPackage(Path path, String fileName, long size) {}

    /**
     * 이 시도를 가리키는 세상에 하나뿐인 값.
     *
     * <p>⛔ <b>사람이 보는 번호({@code DR-003})로 거르게 하지 마라</b> — 번호는 프로젝트마다
     * 1번부터라 서로 다른 사업의 {@code DR-001} 이 여럿이다. 개발이 번호로 거르면 남의 것을
     * 중복으로 집는다.
     *
     * <p>⚠ <b>다시 보내면 같은 키여야 한다.</b> 지금은 개발요청서 하나에 꾸러미 하나라
     * 개발요청서 ID 에서 결정적으로 낸다 — 시도마다 달라지는 값을 쓰면 재시도가 새 요청이 된다.
     */
    static String deliveryKey(String requestId) {
        return "DRK-" + requestId;
    }

    static String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "첨부파일";
        }
        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[^\\p{L}\\p{N}._-]", "_").replaceAll("^[._]+", "");
        return name.isBlank() ? "첨부파일" : name;
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // DB 상태는 전송 전으로 남는다. 고아 파일은 같은 요청의 다음 첨부가 덮어쓴다.
        }
    }

    /**
     * 화면ID → 메뉴 경로. ⭐ 정본은 <b>빌더 DB</b> 다.
     *
     * <p>⛔ 클론의 {@code ia.md} 를 읽지 마라 — {@code IaPublisher} 가 <b>확정할 때만</b> 다시 써서
     * 「마지막 확정 시점」에 굳어 있다. 낡은 파일은 확신에 찬 답을 낸다.
     * <p>⛔ 화면마다 한 번씩 읽지 마라 — 프로젝트 단위로 <b>한 번</b> 읽어 표로 만든다.
     */
    private Map<String, String> menuPaths(String projectId) {
        Map<String, String> paths = new HashMap<>();
        ia.selectScreenLinks(projectId).forEach(link -> paths.put(link.screenId(), link.path()));
        return paths;
    }

    /** 목업 위에 찍어 둔 지시. 변경 목록이 못 하는 말을 한다 — 어느 요소인가까지 적힌다. */
    private List<DevelopmentRequestContent.Marker> markerSnapshot(FrdScreen screen) {
        return screenMarkers.selectByScreenId(screen.id()).stream()
                .map(marker -> new DevelopmentRequestContent.Marker(marker.markerNo(),
                        marker.elementLabel(), marker.selector(), marker.description()))
                .toList();
    }

    private List<DevelopmentRequestContent.Memo> memoSnapshot(FrdScreen screen) {
        return screenMemos.selectByScreenId(screen.id()).stream()
                .map(memo -> new DevelopmentRequestContent.Memo(memo.authorName(), memo.content()))
                .toList();
    }

    private List<String> latestChanges(FrdScreen screen) {
        FrdScreenHistory latest = histories.selectLatestByScreenId(screen.id());
        List<String> changes = latest == null ? List.of() : latest.changeList();
        if (changes.isEmpty()) {
            changes = screen.changeList();
        }
        if (changes.isEmpty() && screen.pickReason() != null && !screen.pickReason().isBlank()) {
            changes = List.of(screen.pickReason().strip());
        }
        return changes;
    }

    private String natureLabel(FrdItem.Nature nature) {
        return switch (nature) {
            case DEVELOP -> "개발";
            case OPERATE -> "운영";
            case OUTSIDE -> "범위 밖";
        };
    }

    private String write(DevelopmentRequestContent content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("개발요청서 내용을 저장할 수 없습니다.", failure);
        }
    }

    private DevelopmentRequestContent readContent(String contentJson) {
        try {
            return json.readValue(contentJson, DevelopmentRequestContent.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("저장된 개발요청서 내용을 읽을 수 없습니다.", failure);
        }
    }

    public record View(DevelopmentRequest request, DevelopmentRequestContent content, String ownerName,
                       Map<String, String> standardIdsByScreenId,
                       Map<String, String> developmentFileNamesByScreenId,
                       Map<String, String> iaPlacementLabelsByScreenId,
                       boolean generating, Frd source) {

        public View(DevelopmentRequest request, DevelopmentRequestContent content, String ownerName,
                    Map<String, String> standardIdsByScreenId,
                    Map<String, String> developmentFileNamesByScreenId,
                    Map<String, String> iaPlacementLabelsByScreenId,
                    boolean generating) {
            this(request, content, ownerName, standardIdsByScreenId, developmentFileNamesByScreenId,
                    iaPlacementLabelsByScreenId, generating, null);
        }

        /** 상세 기본 정보에 표시할 기준 FRD 담당자 이름. */
        public String ownerLabel() {
            return ownerName == null || ownerName.isBlank() ? "—" : ownerName;
        }

        /** 상세 상단은 전송 완료 전에는 전달 상태를, 완료 후에는 개발 상태를 표시한다. */
        public String stateLabel() {
            return request.deliveryState() == DevelopmentRequest.DeliveryState.SENT
                    ? request.developmentStateLabel()
                    : request.deliveryStateLabel();
        }

        public String stateClass() {
            return request.deliveryState() == DevelopmentRequest.DeliveryState.SENT
                    ? request.developmentStateClass()
                    : request.deliveryStateClass();
        }

        public boolean srtSource() {
            return source != null && source.sourceKind() == Frd.SourceKind.SRT;
        }

        public String sourceLabel() {
            return srtSource() ? source.sourceRef() : request.frdLabel();
        }

        /** 화면 소스 식별자에 대응하는 표준 화면 ID. 아직 채번되지 않았다면 대시다. */
        public String standardScreenId(String screenId) {
            return standardIdsByScreenId.getOrDefault(screenId, "—");
        }

        public String developmentFileName(String screenId) {
            return developmentFileNamesByScreenId.getOrDefault(screenId, "—");
        }

        public String deliveryScreenId(DevelopmentRequestContent.Screen screen) {
            return screen.deliveryScreenId();
        }

        public String iaPlacementLabel(String screenId) {
            return iaPlacementLabelsByScreenId.getOrDefault(screenId, "—");
        }
    }
}
