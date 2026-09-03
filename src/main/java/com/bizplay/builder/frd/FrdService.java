package com.bizplay.builder.frd;

import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.ProjectSystem;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FRD 의 <b>DB 토막</b>. ⛔ 여기에 프로세스를 띄우는 코드를 넣지 마라 —
 * 트랜잭션을 연 채 몇 분짜리 일을 하면 커넥션을 그동안 물고 있는다.
 */
@Service
public class FrdService {

    private static final Logger log = LoggerFactory.getLogger(FrdService.class);

    private static final String ALL_FACETS = "__ALL__";

    private final FrdMapper frds;
    private final FrdScreenMapper screens;
    private final IdSequence ids;
    private final AccountMapper accounts;
    private final SolutionScreenReader solutions;
    private final ProjectFacetMapper projectFacets;
    private final FrdFacetMapper frdFacets;
    private final FrdBackendChangeMapper backendChanges;
    private final ProjectSystemService projectSystems;
    private final FrdWorkspace workspaces;
    private final FrdScreenIaPlacementService iaPlacements;
    private final BuilderProperties properties;

    public FrdService(FrdMapper frds, FrdScreenMapper screens, IdSequence ids, AccountMapper accounts,
                      SolutionScreenReader solutions, ProjectFacetMapper projectFacets,
                      FrdFacetMapper frdFacets, FrdBackendChangeMapper backendChanges,
                      ProjectSystemService projectSystems,
                      FrdWorkspace workspaces, FrdScreenIaPlacementService iaPlacements,
                      BuilderProperties properties) {
        this.frds = frds;
        this.screens = screens;
        this.ids = ids;
        this.accounts = accounts;
        this.solutions = solutions;
        this.projectFacets = projectFacets;
        this.frdFacets = frdFacets;
        this.backendChanges = backendChanges;
        this.projectSystems = projectSystems;
        this.workspaces = workspaces;
        this.iaPlacements = iaPlacements;
        this.properties = properties;
    }

    /** 완료 전 FRD와 그 연결 데이터를 지우고, 커밋 뒤 남은 실행·Git 작업공간을 정리한다. */
    @Transactional
    public void delete(String projectId, String frdId) {
        Frd frd = of(projectId, frdId);
        if (!frd.canDelete()) {
            throw new IllegalStateException("완료된 FRD 작업은 삭제할 수 없습니다.");
        }
        if (frds.deleteIncomplete(projectId, frdId) != 1) {
            throw new IllegalStateException("FRD 상태가 변경되어 삭제하지 못했습니다. 목록에서 다시 확인해 주세요.");
        }
        Runnable cleanup = () -> cleanupDeletedWork(projectId, frdId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    private void cleanupDeletedWork(String projectId, String frdId) {
        try {
            workspaces.discard(projectId, frdId);
        } catch (RuntimeException failure) {
            log.warn("삭제한 FRD의 Git 작업공간을 정리하지 못했다 projectId={} frdId={}",
                    projectId, frdId, failure);
        }
        try {
            new FrdRunSpace(properties.dataRoot().resolve("frd-runs"), frdId).wipe();
        } catch (RuntimeException failure) {
            log.warn("삭제한 FRD의 AI 실행공간을 정리하지 못했다 projectId={} frdId={}",
                    projectId, frdId, failure);
        }
    }

    /** FRD 작업 목록 한 줄. 목록에서 적용 대상과 작업 범위를 바로 판단할 수 있는 정보만 담는다. */
    public record Row(Frd frd, List<String> facets, List<String> systems,
                      boolean allFacets, int screenCount, int newScreenCount,
                      int backendCount, String ownerName) {

        /** 담당 칸에 뜨는 말. 담당이 없거나 이름을 못 찾으면 대시다. */
        public String ownerLabel() {
            return ownerName == null || ownerName.isBlank() ? "—" : ownerName;
        }

        /** 프론트 화면의 신규·수정 구분과 백엔드 범위를 한 칸에 요약한다. */
        public String workScopeLabel() {
            if ((frd.state() == Frd.State.ANALYZING
                    || frd.state() == Frd.State.WAITING_ANSWER
                    || frd.state() == Frd.State.ANALYSIS_FAILED)
                    && screenCount == 0 && backendCount == 0) {
                return "확인 중";
            }
            List<String> parts = new java.util.ArrayList<>();
            if (screenCount == 0) {
                parts.add("프론트 없음");
            } else {
                parts.add("화면 %d개".formatted(screenCount));
                if (newScreenCount > 0) parts.add("신규 %d개".formatted(newScreenCount));
                int changed = screenCount - newScreenCount;
                if (changed > 0) parts.add("수정 %d개".formatted(changed));
            }
            parts.add(backendCount == 0 ? "백엔드 없음" : "백엔드 %d건".formatted(backendCount));
            return String.join(" · ", parts);
        }

        public String stateClass() {
            return switch (frd.state()) {
                case ANALYSIS_FAILED -> "status-badge--error";
                case ANALYZING, DRAFTING -> "status-badge--progress";
                case WAITING_ANSWER, PICKED, SCOPE_REVIEW -> "status-badge--review";
                case REVIEW, DONE -> "status-badge--complete";
            };
        }

    }

    /**
     * 그 프로젝트의 FRD 하나. <b>없거나 남의 것이면 404 다.</b>
     *
     * <p>⚠ 남의 프로젝트 FRD 는 <b>주소를 알아도</b> 안 열린다.
     * ⛔ 이 검사를 컨트롤러마다 복사하지 마라 — 목록·마법사·작업대 셋이 이것을 쓴다.
     */
    @Transactional(readOnly = true)
    public Frd of(String projectId, String frdId) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || !frd.projectId().equals(projectId) || frd.sourceKind() == Frd.SourceKind.SRT) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 FRD 가 없다: " + frdId);
        }
        return frd;
    }

    /**
     * ⚠ N+1 이다. FRD 가 수백 줄이 되면 화면 수를 한 번에 세는 질의로 바꾼다 —
     * 지금은 프로젝트당 몇십 줄이라 두지만, 줄이 늘면 여기가 첫 번째로 느려진다.
     */
    @Transactional(readOnly = true)
    public List<Row> list(String projectId) {
        List<Frd> all = frds.selectVisibleByProjectId(projectId);
        Map<String, String> ownerNames = ownerNames(all);
        Set<String> projectFacetNames = projectFacets.selectByProjectId(projectId).stream()
                .map(ProjectFacet::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return all.stream().map(frd -> {
            List<FrdScreen> mine = screens.selectByFrdId(frd.id());
            int newScreens = (int) mine.stream().filter(FrdScreen::isNewScreen).count();
            List<String> systemCodes = mine.stream().map(FrdScreen::systemCode)
                    .filter(Objects::nonNull).map(String::strip).filter(code -> !code.isBlank())
                    .distinct().toList();
            if (systemCodes.isEmpty() && frd.systemCode() != null && !frd.systemCode().isBlank()) {
                systemCodes = List.of(frd.systemCode().strip());
            }
            int backendCount = (int) backendChanges.selectByFrdId(frd.id()).stream()
                    .filter(FrdBackendChange::required).count();
            List<String> facets = frdFacets.selectByFrdId(frd.id()).stream()
                    .map(FrdFacet::name).toList();
            boolean allFacets = !projectFacetNames.isEmpty()
                    && new LinkedHashSet<>(facets).equals(projectFacetNames);
            // ⚠ ownerNames 는 Map.of() 로도 돌아온다 — 그 구현은 get(null) 에서 던진다.
            //   담당이 없는 FRD 가 흔해서 널 키로 묻는 일이 실제로 있다.
            String ownerName = frd.ownerAccountId() == null ? null : ownerNames.get(frd.ownerAccountId());
            return new Row(frd, facets, systemCodes, allFacets,
                    mine.size(), newScreens, backendCount, ownerName);
        }).toList();
    }

    /** FRD가 프로젝트에 등록된 적용 대상을 빠짐없이 선택했는지 목록과 같은 기준으로 판단한다. */
    @Transactional(readOnly = true)
    public boolean usesAllFacets(String projectId, List<String> selectedFacetNames) {
        Set<String> projectFacetNames = projectFacets.selectByProjectId(projectId).stream()
                .map(ProjectFacet::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return !projectFacetNames.isEmpty()
                && new LinkedHashSet<>(selectedFacetNames).equals(projectFacetNames);
    }

    /**
     * 붙여넣기로 FRD 를 연다. <b>번호를 집는 것과 줄을 앉히는 것이 한 트랜잭션이다.</b>
     *
     * @throws IllegalArgumentException 요구사항이 비었다 — 사람이 고칠 수 있는 것이라 500 을 내지 않는다
     */
    @Transactional
    public String open(String projectId, String sourceText, String accountId) {
        return open(projectId, sourceText, accountId, List.of(), List.of());
    }

    /** 분석 조건과 사용자가 먼저 고른 솔루션 화면을 함께 보존하며 FRD를 연다. */
    @Transactional
    public String open(String projectId, String sourceText, String accountId,
                       List<String> facetNames, List<ScreenSelection> selectedScreens) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("요구사항 내용을 넣어 주세요.");
        }
        List<ProjectFacet> availableFacets = projectFacets.selectByProjectId(projectId);
        Set<String> allowedFacetNames = availableFacets.stream()
                .map(ProjectFacet::name).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> requestedFacets = facetNames == null ? List.of() : facetNames.stream()
                .filter(Objects::nonNull).map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
        List<String> chosenFacets = !availableFacets.isEmpty()
                && (requestedFacets.isEmpty() || requestedFacets.contains(ALL_FACETS))
                ? List.copyOf(allowedFacetNames) : requestedFacets;
        if (!allowedFacetNames.containsAll(chosenFacets)) {
            throw new IllegalArgumentException("프로젝트에 등록되지 않은 적용 대상이 포함되어 있습니다.");
        }

        List<ScreenSelection> chosenScreens = selectedScreens == null ? List.of() : selectedScreens;
        Map<String, SolutionScreen> knownScreens = new LinkedHashMap<>();
        solutions.read(projectId).forEach(screen -> knownScreens.put(screen.screenId(), screen));
        Set<String> chosenScreenIds = new LinkedHashSet<>();
        for (ScreenSelection selection : chosenScreens) {
            validateSelection(selection, knownScreens, chosenFacets, chosenScreenIds);
        }

        String id = ids.next(IdSequence.Kind.FRD);
        int number = frds.allocateNumber(projectId);
        // ⚠ 제목은 AI 가 짚으면서 고쳐 준다. 그때까지는 첫 줄을 임시로 쓴다.
        frds.insert(Frd.pasted(id, projectId, number, firstLine(sourceText), sourceText.strip(), accountId));
        chosenFacets.forEach(name -> frdFacets.insert(FrdFacet.create(id, projectId, name)));
        for (ScreenSelection selection : chosenScreens) {
            String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
            String baseId = selection.baseScreenId() == null || selection.baseScreenId().isBlank()
                    ? null : selection.baseScreenId().strip();
            SolutionScreen base = baseId == null ? null : knownScreens.get(baseId);
            String screenId = resolveScreenId(rowId, selection.screenId(), baseId);
            String screenName = selection.screenName() == null || selection.screenName().isBlank()
                    ? screenId : selection.screenName().strip();
            // ⚠ 신규 화면은 기준 화면이 없다 — 목업을 만들 때 AI 가 같은 유형에서 고른다(정본 §기준 화면).
            if (base == null) {
                screens.insert(FrdScreen.drafted(rowId, id, screenId, screenName,
                        selection.screenType(), chosenFacets.size() == 1 ? chosenFacets.get(0) : null,
                        resolveNewScreenSystem(projectId, selection.systemCode())));
                String placementSource = selection.iaPlacementMode() == null
                        && selection.iaAnchorScreenId() == null && selection.iaMenuPathKey() == null
                        ? "AI" : "USER";
                iaPlacements.save(rowId, new FrdScreenIaPlacementService.Request(
                        selection.iaPlacementMode(), selection.iaAnchorScreenId(),
                        selection.iaMenuPathKey(), selection.screenKind(), placementSource));
                continue;
            }
            screens.insert(new FrdScreen(rowId, id, screenId, screenName, base.screenId(),
                    selectedFacetOf(base, chosenFacets), null, FrdScreen.State.WAITING,
                    null, null, null, null, null, base.system(), null, null));
        }
        return id;
    }

    /**
     * 최초 분석 전에 사용자가 고른 기존 화면 또는 신규 화면.
     *
     * <p>⚠ <b>화면 목록이 네 칸을 자리로 맞춰 보낸다</b>({@code FrdWizardController.selections}) —
     * 신규 화면은 {@code screenId}·{@code baseScreenId} 가 비고 {@code screenType} 이 차고,
     * 이미 있는 화면은 그 반대다. <b>빈 값이라도 자리는 온다.</b>
     *
     * @param screenId   이미 있는 화면이면 그 화면ID(= {@code baseScreenId}), 신규 화면이면 빈다
     * @param screenType 신규 화면의 유형. 이미 있는 화면이면 빈다
     */
    public record ScreenSelection(String screenId, String screenName, String baseScreenId,
                                  String screenType, String systemCode, String screenKind,
                                  String iaPlacementMode, String iaAnchorScreenId,
                                  String iaMenuPathKey) {

        public ScreenSelection(String screenId, String screenName, String baseScreenId,
                               String screenType, String systemCode) {
            this(screenId, screenName, baseScreenId, screenType, systemCode,
                    null, null, null, null);
        }
    }

    private void validateSelection(ScreenSelection selection, Map<String, SolutionScreen> knownScreens,
                                   List<String> chosenFacets, Set<String> chosenScreenIds) {
        if (selection == null) {
            throw new IllegalArgumentException("고른 화면이 비어 있습니다.");
        }
        boolean hasBase = selection.baseScreenId() != null && !selection.baseScreenId().isBlank();
        boolean hasType = selection.screenType() != null && !selection.screenType().isBlank();
        if (!hasBase && !hasType) {
            throw new IllegalArgumentException("화면 유형을 선택해 주세요.");
        }
        if (hasType && !NEW_SCREEN_TYPES.contains(selection.screenType().strip())) {
            throw new IllegalArgumentException("그런 화면 유형이 없습니다: " + selection.screenType());
        }
        /*
         * ⚠ 신규 화면은 아직 화면ID 가 없다 — 이름은 행을 앉힐 때 기본키로 짓는다. 그래서 중복
         *   검사는 「이미 있는 화면을 두 번 골랐나」에만 건다. 신규 화면 둘을 같은 기준 화면으로
         *   만드는 것은 막지 않는다(사람이 일부러 그럴 수 있다).
         */
        String screenId = selection.screenId() == null || selection.screenId().isBlank()
                ? null : selection.screenId().strip();
        if (screenId != null && !chosenScreenIds.add(screenId)) {
            throw new IllegalArgumentException("같은 화면을 두 번 선택할 수 없습니다.");
        }
        if (!hasBase) {
            return;   // 신규 화면 — 기준 화면이 없으니 대조할 것도 적용 대상을 잴 것도 없다
        }
        SolutionScreen base = knownScreens.get(selection.baseScreenId().strip());
        if (base == null) {
            throw new IllegalArgumentException("선택한 기준 화면을 솔루션 목업에서 찾을 수 없습니다.");
        }
        if (!chosenFacets.isEmpty() && !base.applicationNames().isEmpty()
                && base.applicationNames().stream().noneMatch(chosenFacets::contains)) {
            throw new IllegalArgumentException("선택한 적용 대상에 포함되지 않는 화면이 있습니다.");
        }
    }

    private String selectedFacetOf(SolutionScreen screen, List<String> chosenFacets) {
        List<String> matched = screen.applicationNames().stream().filter(chosenFacets::contains).toList();
        return matched.size() == 1 ? matched.get(0) : null;
    }

    /**
     * 사람이 체크한 화면만 남기고 작업대를 연다.
     *
     * <p>⚠ <b>하나도 안 남기는 것이 정상이다</b> — 화면 일이 아닌 요건이 그렇다.
     *
     * @throws IllegalStateException 아직 짚은 화면을 확인할 준비가 안 됐다({@code PICKED} 가 아니다) —
     *                                예를 들어 {@code ANALYZING} 인데 확정을 누르면 이 자리로 온다.
     *                                사람이 고칠 수 있는 것이라 500 을 내지 않는다
     */
    @Transactional
    public void confirmPick(String frdId, List<String> keepScreenIds) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.PICKED) {
            throw new IllegalStateException("아직 화면을 확인할 준비가 안 됐습니다.");
        }
        List<String> keep = keepScreenIds == null ? List.of() : keepScreenIds;
        screens.selectByFrdId(frdId).stream()
                .filter(screen -> !keep.contains(screen.id()))
                .forEach(screen -> screens.excludeById(screen.id()));
        frds.updateState(frdId, Frd.State.SCOPE_REVIEW);
    }

    /** 개발 범위를 확인한 FRD를 실제 작업 상태로 전환한다. */
    @Transactional
    public void startDrafting(String frdId) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.SCOPE_REVIEW) {
            throw new IllegalStateException("개발 범위를 확인하는 중에만 FRD 작업을 시작할 수 있습니다.");
        }
        if (frds.transitionState(frdId, Frd.State.SCOPE_REVIEW, Frd.State.DRAFTING) != 1) {
            throw new IllegalStateException("다른 요청이 먼저 FRD 작업을 시작했습니다. 목록에서 다시 열어 주세요.");
        }
    }

    /**
     * 사람이 화면을 더한다 — 후보에서 그대로 고르거나(「화면 직접 고르기」), 새 화면을 만든다(「신규 화면 개발」).
     *
     * <p>⭐ <b>화면ID 를 사람에게 묻지 않는다 (2026-08-22 병주 확정).</b> 종전에는 자유 입력이었는데
     * 형식도, 기존 화면과의 중복도 안 봤고, 그 값이 그대로 클론 안의 파일 이름이 됐다
     * ({@link ScreenMockupWorker} 가 {@code core/<시스템>/pages/<화면ID>.html} 로 앉힌다).
     * 이제 {@code screenId} 가 비면 <b>신규 화면</b>이고 {@link TemporaryScreenId} 가 이 행의
     * 기본키로 이름을 짓는다. 정본: {@code docs/superpowers/specs/2026-08-22-new-screen-id-design.md}.
     *
     * <p>⭐ <b>기준 화면을 사람에게 묻지 않는다 (2026-08-22 병주 확정).</b> 기준 화면이 실제로 주는
     * 값어치는 「이 화면과 비슷하게」가 아니라 <b>그 시스템의 셸·공통 요소 관례</b>인데, 그것은 한 장의
     * 성질이 아니다. 그래서 사람은 <b>시스템과 유형</b>을 고르고({@code systemCode}, {@code screenType}), 기준 화면은 목업을
     * 만들 때 {@link ScreenMockupWorker} 안의 AI 가 같은 유형 화면 중에서 고른다.
     * ⚠ <b>캔버스는 예외다</b> — 거기 AI 는 이미 기준 화면을 스스로 골라 보내므로 그대로 받는다.
     *
     * @param screenId   이미 있는 화면이면 그 화면ID(= {@code baseScreenId}), <b>신규 화면이면 비운다</b>
     * @param baseScreenId 이미 있는 화면이면 자기 자신. <b>사람이 만든 신규 화면이면 비운다</b> —
     *                     AI 가 나중에 채운다. 캔버스가 만든 신규 화면은 AI 가 고른 값이 온다
     * @param screenType 신규 화면의 유형(목록·상세·등록·수정·안내). 기준 화면이 없을 때 필수다
     * @throws IllegalArgumentException 기준도 유형도 없거나, 기준이 이 프로젝트의 실제 화면이 아니거나,
     *                                   신규 화면인데 사람이 화면ID 를 적어 보냈다 —
     *                                   사람이 고칠 수 있는 것이라 500 을 내지 않는다
     */
    @Transactional
    public void addScreen(String frdId, String screenId, String screenName,
                          String baseScreenId, String screenType, String systemCode) {
        addScreen(frdId, screenId, screenName, baseScreenId, screenType, systemCode, null);
    }

    /** 신규 화면의 IA 배치 의도까지 한 트랜잭션으로 앉힌다. */
    @Transactional
    public void addScreen(String frdId, String screenId, String screenName,
                          String baseScreenId, String screenType, String systemCode,
                          FrdScreenIaPlacementService.Request iaPlacement) {
        Frd frd = frds.selectById(frdId);
        if (frd == null) {
            throw new IllegalArgumentException("그런 FRD 가 없습니다.");
        }
        String base = baseScreenId == null || baseScreenId.isBlank() ? null : baseScreenId.strip();
        String type = screenType == null || screenType.isBlank() ? null : screenType.strip();
        if (base == null && type == null) {
            throw new IllegalArgumentException("화면 유형을 선택해 주세요.");
        }
        if (type != null && !NEW_SCREEN_TYPES.contains(type)) {
            throw new IllegalArgumentException("그런 화면 유형이 없습니다: " + type);
        }

        /*
         * ⭐ [2026-08-18 최종 리뷰 C3] baseScreenId 는 밖에서 오는 값이다 — 색인에 없는 값을 그대로
         *   앉히면 ScreenMockupWorker 가 그 값으로 클론 경로를 지어 읽는다. 「../../..」 꼴을 넣으면
         *   클론 밖(남의 프로젝트 포함)에 닿을 수 있다. 여기서 실제 화면ID 인지 먼저 확인하면
         *   그 문이 닫히고, 덤으로 오타 베이스가 몇 분 뒤 「as-is 를 못 읽었다」가 아니라
         *   즉시 거절로 바뀐다.
         */
        SolutionScreen baseScreen = base == null ? null : solutions.read(frd.projectId()).stream()
                .filter(screen -> screen.screenId().equals(base))
                .findFirst().orElse(null);
        FrdScreen baseWorkScreen = base == null ? null : screens.selectByFrdId(frdId).stream()
                .filter(screen -> screen.screenId().equals(base)).findFirst().orElse(null);
        if (base != null && baseScreen == null && baseWorkScreen == null) {
            throw new IllegalArgumentException(
                    "기준 화면 \"%s\" 를 찾을 수 없습니다 — 기획 저장소에 없는 화면입니다.".formatted(base));
        }

        if (baseScreen != null && screens.restoreExcluded(frdId, base) == 1) {
            return;
        }

        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String cleanScreenId = resolveScreenId(rowId, screenId, base);
        String name = screenName == null || screenName.isBlank() ? cleanScreenId : screenName.strip();
        List<String> selectedFacets = frdFacets.selectByFrdId(frdId).stream().map(FrdFacet::name).toList();
        if (baseScreen == null && baseWorkScreen == null) {
            screens.insert(FrdScreen.drafted(rowId, frdId, cleanScreenId, name, type,
                    selectedFacets.size() == 1 ? selectedFacets.get(0) : null,
                    resolveNewScreenSystem(frd.projectId(), systemCode)));
            iaPlacements.save(rowId, placementOf(iaPlacement, base));
            return;
        }
        String resolvedSystem = baseScreen != null ? baseScreen.system() : baseWorkScreen.systemCode();
        String resolvedFacet = baseScreen != null
                ? selectedFacetOf(baseScreen, selectedFacets) : baseWorkScreen.facet();
        screens.insert(new FrdScreen(rowId, frdId, cleanScreenId, name, base,
                resolvedFacet, null, FrdScreen.State.WAITING,
                null, null, null, null, null, resolvedSystem, type, null));
        if (TemporaryScreenId.isTemporary(cleanScreenId)) {
            iaPlacements.save(rowId, placementOf(iaPlacement, base));
        }
    }

    private static FrdScreenIaPlacementService.Request placementOf(
            FrdScreenIaPlacementService.Request requested, String baseScreenId) {
        if (requested != null) return requested;
        return new FrdScreenIaPlacementService.Request(
                baseScreenId == null ? null : "CHILD", baseScreenId, null, "화면", "USER");
    }

    /** 신규 화면의 시스템은 프로젝트 등록 목록에서만 고른다. 하나뿐이면 입력 없이 자동 확정한다. */
    private String resolveNewScreenSystem(String projectId, String requestedSystemCode) {
        Set<String> available = projectSystems.all(projectId).stream()
                .map(ProjectSystem::systemCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        solutions.read(projectId).stream().map(SolutionScreen::system).forEach(available::add);
        String requested = requestedSystemCode == null || requestedSystemCode.isBlank()
                ? null : requestedSystemCode.strip();
        if (requested != null && !available.contains(requested)) {
            throw new IllegalArgumentException("프로젝트에 등록되지 않은 시스템입니다: " + requested);
        }
        if (requested != null) {
            return requested;
        }
        if (available.size() == 1) {
            return available.iterator().next();
        }
        if (available.isEmpty()) {
            throw new IllegalArgumentException("프로젝트 시스템을 먼저 등록해 주세요.");
        }
        throw new IllegalArgumentException("신규 화면의 시스템을 선택해 주세요.");
    }

    /** ⚠ 옛 네 인자 꼴 — 캔버스와 화면 대화가 기준 화면을 이미 손에 쥐고 부른다. */
    @Transactional
    public void addScreen(String frdId, String screenId, String screenName, String baseScreenId) {
        addScreen(frdId, screenId, screenName, baseScreenId, null, null);
    }

    /** 기존 호출 호환용. 신규 화면은 프로젝트에 시스템이 하나일 때만 자동 확정된다. */
    @Transactional
    public void addScreen(String frdId, String screenId, String screenName,
                          String baseScreenId, String screenType) {
        addScreen(frdId, screenId, screenName, baseScreenId, screenType, null);
    }

    /**
     * 이미 있는 화면이면 그 화면ID 를 그대로, <b>신규 화면이면 이 행의 기본키로 임시 이름을 짓는다.</b>
     *
     * <p>⛔ <b>사람이 적은 화면ID 를 받지 않는다 (2026-08-22 병주 확정).</b> 「신규 화면 개발」은
     * 기준 화면과 화면명만 묻고 화면ID 는 안 묻는다. 그런데도 값이 오면 화면을 앉히기 전에 거절한다 —
     * 폼이 바뀌어 옛 칸이 되살아나거나 밖에서 직접 POST 하는 길을 여기서 막는다.
     */
    private String resolveScreenId(String rowId, String screenId, String base) {
        if (screenId == null || screenId.isBlank()) {
            return TemporaryScreenId.of(rowId);
        }
        String given = screenId.strip();
        if (!given.equals(base)) {
            throw new IllegalArgumentException("신규 화면의 화면ID 는 빌더가 짓습니다 — 직접 적을 수 없습니다.");
        }
        return given;
    }

    /** 사람이 「화면 추가」에서 고를 수 있는 화면 유형. ⚠ 라벨은 {@code IaScreenProfile} 이 정본이다. */
    public static final List<String> NEW_SCREEN_TYPES = List.of("목록", "상세", "등록", "수정", "안내");

    /** 개발 범위 확인에서 프론트 화면 하나를 제외한다. */
    @Transactional
    public void excludeScreen(String frdId, String screenRowId) {
        Frd frd = frds.selectById(frdId);
        FrdScreen screen = screens.selectById(screenRowId);
        if (frd == null || screen == null || !screen.frdId().equals(frdId)) {
            throw new IllegalArgumentException("작업 대상에서 제외할 화면을 찾을 수 없습니다.");
        }
        if (screen.state() == FrdScreen.State.GENERATING) {
            throw new IllegalStateException("AI 초안을 만드는 중인 화면은 작업 대상에서 제외할 수 없습니다.");
        }
        String systemCode = screen.systemCode() == null || screen.systemCode().isBlank()
                ? frd.systemCode() : screen.systemCode();
        workspaces.discardScreenFiles(frd.projectId(), frdId, systemCode, screen.screenId());
        if (screen.isNewScreen()) iaPlacements.release(screenRowId);
        screens.excludeById(screenRowId);
    }

    /**
     * 화면 짚기 일꾼을 못 깨웠을 때(대기줄이 참) 상태를 분석 오류로 닫는다.
     *
     * <p>⭐ [2026-08-18 최종 리뷰 I1] 로그만 남기고 {@code ANALYZING} 에 그대로 두면 안 된다 —
     * 「다시 분석하기」 문은 {@link #retryPick} 이 {@code ANALYSIS_FAILED} 에서만 열어서, 그대로
     * 두면 사람이 다시 시도할 길이 없어진다(번호도 이미 태워졌다). 여기서 닫으면 그 문이 산다.
     */
    @Transactional
    public void rejectDispatch(String frdId, String reason) {
        frds.updateAfterPick(frdId, null, null, null, Frd.State.ANALYSIS_FAILED, reason);
    }

    /**
     * 분석 오류를 다시 돌린다. ⛔ <b>이 문은 상태만 되돌린다</b> — 실제로 다시 돌리는 것은
     * {@link ScreenPickWorker#pick(String)} 이고, 그것을 깨우는 것은 부르는 쪽(컨트롤러) 몫이다
     * (트랜잭션 밖에서 깨워야 한다 — {@code IntakeController} 와 같은 규칙).
     *
     * <p>⭐ <b>{@code ANALYSIS_FAILED} 에서만 연다(2026-08-18 리뷰 확정).</b> 문지기가 없으면
     * 「다시 분석」을 두 번 누르거나 이미 {@code ANALYZING}·{@code PICKED}·{@code DRAFTING} 인데
     * 눌러도 일꾼이 또 돌아 {@code unique(frd_id, screen_id)} 에 부딪힌다 — 그리고 이 Task 의
     * 전역 제약 「{@code ANALYZING} 으로 되돌리지 마라」를 어기게 된다.
     *
     * @throws IllegalStateException 지금 상태에서는 다시 분석할 수 없다 — 사람이 고칠 수 있는 것이라
     *                                500 을 내지 않는다
     */
    @Transactional
    public void retryPick(String frdId) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.ANALYSIS_FAILED) {
            throw new IllegalStateException("지금은 다시 분석할 수 없습니다.");
        }
        frds.updateState(frdId, Frd.State.ANALYZING);
    }

    /** FRD 목록에 담긴 담당 계정들의 이름을 한 번에 읽는다. ⛔ FRD 마다 따로 읽지 마라 — N+1 이다. */
    private Map<String, String> ownerNames(List<Frd> all) {
        List<String> ownerAccountIds = all.stream()
                .map(Frd::ownerAccountId).filter(Objects::nonNull).distinct().toList();
        if (ownerAccountIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        accounts.selectByIdIn(ownerAccountIds).forEach(account -> names.put(account.getId(), account.getName()));
        return names;
    }

    /** ⚠ 제목 열이 {@code varchar(255)} 다. 첫 줄이 길면 자른다. */
    private String firstLine(String sourceText) {
        String line = sourceText.strip().lines().findFirst().orElse("요구사항").strip();
        return line.length() <= 255 ? line : line.substring(0, 255);
    }
}
