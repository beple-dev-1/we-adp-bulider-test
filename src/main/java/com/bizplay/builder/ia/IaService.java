package com.bizplay.builder.ia;

import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** DB를 정본으로 삼는 메뉴구조도 업무 흐름. */
@Service
public class IaService {

    private final IaMapper mapper;
    private final AccountMapper accounts;
    private final IdSequence ids;
    private final ProjectPaths paths;
    private final SolutionMockupService solutions;
    private final ScreenStandardIdMapper standardIds;
    private final IaDocumentCodec codec;
    private final IaPublisher publisher;
    private final TransactionTemplate transactions;
    private final ProjectSystemService projectSystems;
    private final Map<String, CachedWorkbench> workbenchCache = new ConcurrentHashMap<>();

    public IaService(IaMapper mapper, AccountMapper accounts, IdSequence ids, ProjectPaths paths,
                     SolutionMockupService solutions, ScreenStandardIdMapper standardIds,
                     IaDocumentCodec codec, IaPublisher publisher, TransactionTemplate transactions,
                     ProjectSystemService projectSystems) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.ids = ids;
        this.paths = paths;
        this.solutions = solutions;
        this.standardIds = standardIds;
        this.codec = codec;
        this.publisher = publisher;
        this.transactions = transactions;
        this.projectSystems = projectSystems;
    }

    public List<SystemSummary> systems(String projectId) {
        Map<String, List<SolutionScreen>> screensBySystem = new LinkedHashMap<>();
        solutions.screens(projectId).forEach(screen ->
                screensBySystem.computeIfAbsent(screen.system(), ignored -> new ArrayList<>()).add(screen));
        List<IaStructure> storedStructures = mapper.selectStructuresByProject(projectId);
        Map<String, IaStructure> structures = new LinkedHashMap<>();
        storedStructures.forEach(it -> structures.put(it.systemCode(), it));
        List<String> editorIds = storedStructures.stream().map(IaStructure::updatedBy)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, String> editorNames = new LinkedHashMap<>();
        if (!editorIds.isEmpty()) {
            accounts.selectByIdIn(editorIds).forEach(account -> editorNames.put(account.getId(), account.getName()));
        }
        Set<String> codes = new LinkedHashSet<>();
        codes.addAll(screensBySystem.keySet());
        codes.addAll(structures.keySet());
        SystemLabels labels = projectSystems.labels(projectId);
        return codes.stream().sorted().map(code -> {
            IaStructure structure = structures.get(code);
            return new SystemSummary(code, labels.label(code),
                    structure == null ? null : structure.updatedAt(),
                    structure == null || structure.updatedBy() == null ? null
                            : editorNames.getOrDefault(structure.updatedBy(), "알 수 없음"));
        }).toList();
    }

    public Optional<Workbench> find(String projectId, String systemCode) {
        return mapper.selectStructure(projectId, checkedSystem(systemCode))
                .map(structure -> workbench(projectId, structure));
    }

    /** 상세 화면을 처음 열면 ia.md와 index/4 화면 분류를 한 번만 DB에 가져온다. */
    public Workbench findOrImport(String projectId, String systemCode, String accountId) {
        String system = checkedSystem(systemCode);
        Optional<Workbench> stored = find(projectId, system);
        if (stored.isPresent()) {
            RebuildJudgment judgment = judgeRebuild(projectId, system, stored.get());
            return judgment.rebuild()
                    ? upgradeUntouched(projectId, system, accountId, judgment.prepared())
                    : stored.get();
        }
        try {
            importOnce(projectId, system, accountId);
        } catch (IllegalArgumentException raced) {
            Optional<Workbench> concurrent = find(projectId, system);
            if (concurrent.isPresent()) return concurrent.get();
            throw raced;
        }
        return find(projectId, system)
                .orElseThrow(() -> new IllegalStateException("메뉴구조도 최초 가져오기를 완료하지 못했습니다."));
    }

    /**
     * ④ 재작성 판정 (브리프 §3-1 「④의 비교 기준」 원문이 정본이다).
     *
     * <p>⛔ {@code version}·{@code currentRevision} 이 둘 다 0이 아니면 <b>파일을 읽지 않고
     * 바로 거짓이다</b> — 사람이 고친 구조를 지우지 않으려고 순서를 이렇게 둔다. 둘 다 0일
     * 때만 {@link #prepare} 로 지금 색인을 다시 세워, 저장된 행의 (순번·경로키·뎁스·화면ID)
     * 네 값을 {@link IaTreeBuilder.Tree#placements()} 의 같은 네 값과 <b>순서까지</b> 견준다.
     *
     * <p>⚠ {@code prepare()} 가 예외를 내거나(클론 없음·파일 없음) 못 읽으면 <b>거짓으로
     * 본다</b> — 저장된 구조는 클론이 없어도 그대로 열려야 한다.
     *
     * <p>⭐ 판정에 쓴 {@link Prepared} 를 {@link RebuildJudgment} 로 되돌려 {@link #upgradeUntouched}
     * 가 재사용하게 한다. 그러지 않으면 작업대 한 번 여는 데 {@code prepare()}(파일 읽기 +
     * {@link IaTreeBuilder#of} 순수 계산)가 판정·재확인·재작성 세 번 돈다.
     *
     * <p>⛔ <b>{@code importedHash} 로 먼저 걸러 대부분의 열기에서 이 계산을 건너뛰지 않는다</b>
     * (코드리뷰가 제안했지만 반영하지 않았다, 2026-09-04) — 이번에 모양이 바뀐 까닭은
     * {@code ia.md} 내용이 아니라 <b>코드</b>다({@link IaTreeBuilder}). 해시는 그대로인데 뎁스
     * 조립 결과만 달라지므로, 해시로 먼저 걸러 내면 <b>이 과업이 필요로 하는 재작성 자체가
     * 안 돈다</b> — 낡은 행이 영원히 남는다. 다음에 같은 최적화를 또 제안받으면 이 문단을 본다.
     *
     * <p>⛔ <b>{@code catch (RuntimeException)} 은 좁히지 않는다</b> — 브리프가 「클론이 없어도
     * 저장된 구조는 열려야 한다」를 요구했다. 클론이 없거나 {@code ia.md} 가 없으면
     * {@link #prepare} 가 {@link IllegalArgumentException} 을, 색인이 깨졌으면
     * {@link IaTreeBuilder#of} 쪽에서 다른 런타임 예외가 날 수 있는데, 어느 쪽이든 <b>재작성
     * 판정만 포기하고 저장된 구조는 그대로 열려야 한다.</b> 이 패키지에는 로거가 없으므로
     * 새로 들이지 않는다 — 예외를 조용히 삼키는 대신 「거짓으로 본다」는 분기 자체가 그
     * 실패를 드러낸다.
     */
    private RebuildJudgment judgeRebuild(String projectId, String systemCode, Workbench workbench) {
        IaStructure structure = workbench.structure();
        if (structure.version() != 0 || structure.currentRevision() != 0) {
            return new RebuildJudgment(false, null);
        }
        Prepared prepared;
        try {
            prepared = prepare(projectId, systemCode);
        } catch (RuntimeException unreadable) {
            return new RebuildJudgment(false, null);
        }
        return new RebuildJudgment(shapeDiffers(workbench, prepared), prepared);
    }

    /**
     * 이미 세운 {@link Prepared} 를 재사용하는 순수 비교 — 파일을 다시 읽지 않는다.
     *
     * <p>⛔ 이름이 「손 안 댄 옛 구조인가」가 아니다 — 되돌리는 값은 <b>저장된 모양이 지금 다시
     * 세운 모양과 다른가</b>다({@link #sameShape} 의 부정). 이름과 뜻이 갈렸던 자리라
     * {@code shapeDiffers} 로 바꿨다(코드리뷰 반영, 2026-09-04).
     */
    private boolean shapeDiffers(Workbench workbench, Prepared prepared) {
        IaStructure structure = workbench.structure();
        if (structure.version() != 0 || structure.currentRevision() != 0) return false;
        if (prepared == null) return false;
        return !sameShape(workbench.rows(), prepared.tree().placements());
    }

    /** {@code Tree.skipped}·{@code kept} 는 비교 대상이 아니다 — {@code Placement} 네 값만 본다. */
    private static boolean sameShape(List<IaRow> rows, List<IaDocumentCodec.Placement> placements) {
        List<IaRow> screenRows = rows.stream().filter(IaRow::hasScreen).toList();
        if (screenRows.size() != placements.size()) return false;
        for (int index = 0; index < screenRows.size(); index++) {
            IaRow row = screenRows.get(index);
            IaDocumentCodec.Placement placement = placements.get(index);
            if (row.rowOrder() != placement.order()) return false;
            if (!row.pathKey().equals(placement.pathKey())) return false;
            if (!row.screenId().equals(placement.screenId())) return false;
            if (!row.depths().equals(placement.depths())) return false;
        }
        return true;
    }

    private record RebuildJudgment(boolean rebuild, Prepared prepared) {
    }

    private Workbench upgradeUntouched(String projectId, String systemCode, String accountId, Prepared prepared) {
        String system = checkedSystem(systemCode);
        return transactions.execute(status -> {
            IaStructure structure = mapper.selectStructure(projectId, system).orElse(null);
            if (structure == null) return workbench(projectId, insert(projectId, system, accountId, prepared));
            Workbench current = workbench(projectId, structure);
            if (!shapeDiffers(current, prepared)) return current;
            if (mapper.deleteStructure(structure.id(), structure.version()) != 1) {
                return find(projectId, system).orElseThrow();
            }
            return workbench(projectId, insert(projectId, system, accountId, prepared));
        });
    }

    /**
     * 색인으로 트리를 세워 DB 에 한 번 넣는다.
     *
     * <p>⭐ <b>재료가 2026-08-21 에 바뀌었다 (병주 확정)</b> — 뎁스는 색인의 {@code 경로} ·
     * {@code 상위화면} · {@code 여는화면} · 현재 화면에서 오고({@link IaTreeBuilder}), {@code ia.md} 는
     * <b>한글 이름표만</b> 준다. ⛔ 종전처럼 {@code ia.md} 의 {@code --- 배치 ---} 를 쓰면
     * 화면 셋 중 하나만 들어온다(백오피스 실측 82줄 대 240장).
     */
    public IaStructure importOnce(String projectId, String systemCode, String accountId) {
        String system = checkedSystem(systemCode);
        Prepared prepared = prepare(projectId, system);
        return transactions.execute(status -> {
            if (mapper.selectStructure(projectId, system).isPresent()) {
                throw new IllegalArgumentException("이미 최초 가져오기를 마친 시스템입니다. DB 내용을 사용해 주세요.");
            }
            return insert(projectId, system, accountId, prepared);
        });
    }

    /** 읽기만 한다 — 트랜잭션 밖에서 먼저 돌려 놓고, 트랜잭션은 쓰기만 하게 한다. */
    private Prepared prepare(String projectId, String system) {
        Path file = paths.iaFile(projectId, system);
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException missing) {
            throw new IllegalArgumentException("가져올 파일이 없습니다: core/" + system + "/ia.md");
        }
        Map<String, SolutionScreen> screens = screensOf(projectId, system);
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(screens.values(), codec.labels(content));
        if (tree.placements().isEmpty()) {
            throw new IllegalArgumentException("색인에서 세울 메뉴 행이 없습니다: " + system);
        }
        return new Prepared(codec.hash(content), screens, tree);
    }

    private IaStructure insert(String projectId, String system, String accountId, Prepared prepared) {
        String structureId = ids.next(IdSequence.Kind.IA_STRUCTURE);
        mapper.insertStructure(new IaStructure(structureId, projectId, system,
                IaStructure.State.DRAFT, 0, 0, prepared.hash(), null, null, null,
                null, null, null, accountId));
        for (SolutionScreen screen : prepared.screens().values()) {
            mapper.insertScreenProfile(new IaScreenProfile(structureId, screen.screenId(),
                    IaScreenProfile.ScreenKind.fromLabel(screen.kind()),
                    IaScreenProfile.ScreenType.fromLabel(screen.screenType()),
                    IaScreenProfile.TypeSource.fromLabel(screen.typeSource())));
        }
        for (IaDocumentCodec.Placement placement : prepared.tree().placements()) {
            SolutionScreen screen = screenOf(placement.screenId(), prepared.screens());
            List<String> d = placement.depths();
            mapper.insertRow(new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId,
                    placement.order(), placement.pathKey(),
                    at(d, 0), at(d, 1), at(d, 2), at(d, 3), at(d, 4), at(d, 5), at(d, 6),
                    null, null, screen == null ? null : screen.screenType(), placement.screenId(), null, accountId));
        }
        return mapper.selectStructureById(structureId);
    }

    private record Prepared(String hash, Map<String, SolutionScreen> screens, IaTreeBuilder.Tree tree) {
    }

    public void addRow(String projectId, String systemCode, int expectedVersion,
                       RowInput input, String accountId) {
        ensureScreenKnown(projectId, checkedSystem(systemCode), text(input.screenId()));
        transactions.executeWithoutResult(status -> {
            IaStructure structure = required(projectId, systemCode);
            IaRow row = normalized(ids.next(IdSequence.Kind.IA_ROW), structure,
                    mapper.selectNextRowOrder(structure.id()), input, accountId);
            List<IaRow> proposed = new ArrayList<>(mapper.selectRows(structure.id()));
            proposed.add(row);
            codec.validateRows(proposed);
            bump(structure, expectedVersion, accountId);
            mapper.insertRow(row);
        });
    }

    public void updateMenuLocation(String projectId, String systemCode, String rowId, int expectedVersion,
                                   MenuLocationInput input, String accountId) {
        transactions.executeWithoutResult(status -> {
            IaStructure structure = required(projectId, systemCode);
            List<IaRow> rows = mapper.selectRows(structure.id());
            IaRow before = ownedRow(structure, rowId);

            List<TreeNode> roots = treeOf(bareRowViews(rows));
            TreeNode current = roots.stream().map(node -> nodeForRow(node, rowId))
                    .flatMap(Optional::stream).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("수정할 메뉴를 찾지 못했습니다."));
            String parentNodeKey = text(input.parentNodeKey());
            if (parentNodeKey != null && (parentNodeKey.equals(current.nodeKey())
                    || parentNodeKey.startsWith(current.nodeKey() + "/"))) {
                throw new IllegalArgumentException("자기 자신이나 하위 메뉴를 상위 메뉴로 선택할 수 없습니다.");
            }
            List<TreeNode> parentPath = parentNodeKey == null ? List.of()
                    : nodePath(roots, parentNodeKey).orElseThrow(
                            () -> new IllegalArgumentException("선택한 상위 메뉴를 찾지 못했습니다."));
            String menuName = current.label();
            List<String> depths = new ArrayList<>(parentPath.stream().map(TreeNode::label).toList());
            depths.add(menuName);
            if (depths.size() > IaTreeBuilder.MAX_DEPTH) {
                throw new IllegalArgumentException("메뉴는 최대 Depth " + IaTreeBuilder.MAX_DEPTH + "까지 배치할 수 있습니다.");
            }

            List<TreeNode> siblings = parentPath.isEmpty() ? roots : parentPath.get(parentPath.size() - 1).children();
            boolean duplicateName = siblings.stream().anyMatch(node -> !node.nodeKey().equals(current.nodeKey())
                    && node.label().equalsIgnoreCase(menuName));
            if (duplicateName) throw new IllegalArgumentException("같은 상위 메뉴에 동일한 메뉴명이 있습니다.");

            String oldPrefix = current.nodeKey();
            String leafKey = oldPrefix.substring(oldPrefix.lastIndexOf('/') + 1);
            String newPrefix = parentNodeKey == null ? leafKey : parentNodeKey + "/" + leafKey;

            // ⑤ 경로키 중복 거절(브리프 §3-1) — 옮기지 않는 나머지 행(자기 자신·자기 하위 제외)의
            // pathKey 를 미리 모아 둔다. ⚠ codec.validateRows(:320 언저리)도 같은 중복을 잡지만
            // 「어느 행과 부딪혔는지」를 못 담는다 — 그래서 여기서 먼저 걸러 사람이 무엇을 어떻게
            // 고쳐야 하는지 아는 문구를 낸다. 이름 중복 검사(바로 위)와 같은 자리·같은 꼴로 둔다.
            Map<String, IaRow> untouchedByPathKey = rows.stream()
                    .filter(existing -> !existing.id().equals(rowId) && !existing.pathKey().startsWith(oldPrefix + "/"))
                    .collect(java.util.stream.Collectors.toMap(IaRow::pathKey, existing -> existing, (a, b) -> a));
            rejectIfPathCollides(newPrefix, untouchedByPathKey);

            IaRow updated = relocated(before, structure, newPrefix, depths,
                    before.menuType(), before.screenId(), accountId);

            List<IaRow> proposed = new ArrayList<>();
            List<IaRow> changed = new ArrayList<>();
            for (IaRow existing : rows) {
                if (existing.id().equals(rowId)) {
                    proposed.add(updated);
                    changed.add(updated);
                    continue;
                }
                if (!existing.pathKey().startsWith(oldPrefix + "/")) {
                    proposed.add(existing);
                    continue;
                }
                List<String> descendantDepths = new ArrayList<>(depths);
                descendantDepths.addAll(existing.depths().subList(current.depth(), existing.depths().size()));
                if (descendantDepths.size() > IaTreeBuilder.MAX_DEPTH) {
                    throw new IllegalArgumentException("상위 메뉴를 변경하면 하위 메뉴가 최대 뎁스를 초과합니다.");
                }
                // ⚠ 딸려 옮겨지는 하위 행도 부딪힐 수 있다 — untouchedByPathKey 는 자손을 이미
                // 뺐으므로, 옮기지 않는 나머지 행과만 견준다(브리프 §6-3 위험 3).
                String descendantPath = newPrefix + existing.pathKey().substring(oldPrefix.length());
                rejectIfPathCollides(descendantPath, untouchedByPathKey);
                IaRow descendant = relocated(existing, structure, descendantPath, descendantDepths,
                        existing.menuType(), existing.screenId(), accountId);
                proposed.add(descendant);
                changed.add(descendant);
            }
            codec.validateRows(proposed);
            bump(structure, expectedVersion, accountId);
            changed.forEach(mapper::updateRow);
        });
    }

    public void addMenu(String projectId, String systemCode, int expectedVersion,
                        CreateMenuInput input, String accountId) {
        String checkedSystemCode = checkedSystem(systemCode);
        String screenId = text(input.screenId());
        ensureScreenKnown(projectId, checkedSystemCode, screenId);
        transactions.executeWithoutResult(status -> {
            IaStructure structure = required(projectId, checkedSystemCode);
            List<IaRow> rows = mapper.selectRows(structure.id());
            List<TreeNode> roots = treeOf(bareRowViews(rows));
            String parentNodeKey = text(input.parentNodeKey());
            List<TreeNode> parentPath = parentNodeKey == null ? List.of()
                    : nodePath(roots, parentNodeKey).orElseThrow(
                            () -> new IllegalArgumentException("선택한 상위 메뉴를 찾지 못했습니다."));
            String menuName = requiredText(input.menuName(), "메뉴명");
            List<TreeNode> siblings = parentPath.isEmpty() ? roots : parentPath.get(parentPath.size() - 1).children();
            if (siblings.stream().anyMatch(node -> node.label().equalsIgnoreCase(menuName))) {
                throw new IllegalArgumentException("같은 상위 메뉴에 동일한 메뉴명이 있습니다.");
            }

            List<String> depths = new ArrayList<>(parentPath.stream().map(TreeNode::label).toList());
            depths.add(menuName);
            if (depths.size() > IaTreeBuilder.MAX_DEPTH) {
                throw new IllegalArgumentException("메뉴는 최대 Depth " + IaTreeBuilder.MAX_DEPTH + "까지 배치할 수 있습니다.");
            }

            String rowId = ids.next(IdSequence.Kind.IA_ROW);
            String leafKey = screenId == null ? generatedMenuKey(rowId) : screenId;
            String pathKey = parentNodeKey == null ? leafKey : parentNodeKey + "/" + leafKey;
            IaRow row = normalized(rowId, structure, mapper.selectNextRowOrder(structure.id()),
                    new RowInput(null, pathKey,
                            at(depths, 0), at(depths, 1), at(depths, 2), at(depths, 3),
                            at(depths, 4), at(depths, 5), at(depths, 6),
                            null, null, null, screenId), accountId);
            List<IaRow> proposed = new ArrayList<>(rows);
            proposed.add(row);
            codec.validateRows(proposed);
            bump(structure, expectedVersion, accountId);
            mapper.insertRow(row);
        });
    }

    public CreateOptions createOptions(String projectId, String systemCode, String screenId) {
        Workbench workbench = find(projectId, systemCode)
                .orElseThrow(() -> new IllegalStateException("최초 IA를 가져온 뒤 메뉴를 추가할 수 있습니다."));
        SolutionScreen selectedScreen = null;
        String selectedScreenId = text(screenId);
        if (selectedScreenId != null) {
            selectedScreen = workbench.unlinkedScreens().stream()
                    .filter(screen -> screen.screenId().equals(selectedScreenId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("연결할 수 있는 미연결 화면을 찾지 못했습니다."));
        }
        List<ParentOption> parents = new ArrayList<>();
        collectParentOptions(workbench.tree(), "", parents);
        String standardScreenId = selectedScreenId == null ? null : standardIds.selectByProject(projectId).stream()
                .filter(item -> item.screenId().equals(selectedScreenId))
                .findFirst()
                .map(item -> StandardScreenIdFormat.display(item.standardId(), item.origin()))
                .orElse(null);
        return new CreateOptions(selectedScreen == null ? "" : selectedScreen.screenName(),
                selectedScreenId, standardScreenId, List.copyOf(parents));
    }

    public EditOptions editOptions(String projectId, String systemCode, String rowId) {
        Workbench workbench = find(projectId, systemCode)
                .orElseThrow(() -> new IllegalStateException("최초 IA를 가져온 뒤 메뉴를 수정할 수 있습니다."));
        IaRow row = workbench.rows().stream().filter(item -> item.id().equals(rowId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("수정할 메뉴를 찾지 못했습니다."));
        TreeNode current = workbench.tree().stream().map(node -> nodeForRow(node, rowId))
                .flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("수정할 메뉴를 찾지 못했습니다."));
        String parentNodeKey = current.nodeKey().contains("/")
                ? current.nodeKey().substring(0, current.nodeKey().lastIndexOf('/')) : null;
        List<ParentOption> parents = new ArrayList<>();
        collectParentOptions(workbench.tree(), current.nodeKey(), parents);

        String standardScreenId = row.screenId() == null ? null : standardIds.selectByProject(projectId).stream()
                .filter(item -> item.screenId().equals(row.screenId()))
                .findFirst()
                .map(item -> StandardScreenIdFormat.display(item.standardId(), item.origin()))
                .orElse(null);
        return new EditOptions(current.label(), parentNodeKey,
                standardScreenId, row.screenId(), List.copyOf(parents));
    }

    @Transactional
    public void deleteRow(String projectId, String systemCode, String rowId,
                          int expectedVersion, String accountId) {
        IaStructure structure = required(projectId, systemCode);
        ownedRow(structure, rowId);
        bump(structure, expectedVersion, accountId);
        mapper.deleteRow(rowId);
    }

    @Transactional
    public void moveRow(String projectId, String systemCode, String rowId, int expectedVersion,
                        String direction, String accountId) {
        IaStructure structure = required(projectId, systemCode);
        List<IaRow> rows = mapper.selectRows(structure.id());
        String nodeKey = treeOf(bareRowViews(rows)).stream()
                .map(node -> nodeForRow(node, rowId))
                .flatMap(Optional::stream)
                .map(TreeNode::nodeKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이동할 메뉴를 찾지 못했습니다."));
        reorderTree(projectId, structure, rows, nodeKey, expectedVersion, direction, accountId);
    }

    @Transactional
    public MoveResult moveNode(String projectId, String systemCode, String nodeKey, int expectedVersion,
                               String direction, String accountId) {
        IaStructure structure = required(projectId, systemCode);
        List<IaRow> rows = mapper.selectRows(structure.id());
        return reorderTree(projectId, structure, rows, requiredText(nodeKey, "메뉴 노드"),
                expectedVersion, direction, accountId);
    }

    private MoveResult reorderTree(String projectId, IaStructure structure, List<IaRow> rows,
                                   String nodeKey, int expectedVersion,
                                   String direction, String accountId) {
        List<TreeNode> roots = treeOf(bareRowViews(rows));
        MoveContext context = moveContext(roots, nodeKey).orElseThrow(
                () -> new IllegalArgumentException("이동할 메뉴를 찾지 못했습니다."));
        int target = switch (direction) {
            case "up" -> context.index() - 1;
            case "down" -> context.index() + 1;
            default -> throw new IllegalArgumentException("메뉴 이동 방향이 올바르지 않습니다.");
        };
        if (target < 0 || target >= context.siblings().size()) {
            throw new IllegalArgumentException("선택한 메뉴는 같은 단계에서 더 이상 이동할 수 없습니다.");
        }

        List<TreeNode> reorderedSiblings = new ArrayList<>(context.siblings());
        java.util.Collections.swap(reorderedSiblings, context.index(), target);
        List<String> orderedRowIds = new ArrayList<>();
        flattenRows(roots, context.siblings(), reorderedSiblings, orderedRowIds);
        if (orderedRowIds.size() != rows.size()) {
            throw new IllegalStateException("메뉴 순서를 다시 계산하지 못했습니다.");
        }

        bump(structure, expectedVersion, accountId);
        int temporaryBase = rows.stream().mapToInt(IaRow::rowOrder).max().orElse(0) + (rows.size() + 1) * 10;
        mapper.offsetRowOrders(structure.id(), temporaryBase);
        List<IaMapper.RowOrderUpdate> orders = new ArrayList<>(orderedRowIds.size());
        for (int index = 0; index < orderedRowIds.size(); index++) {
            orders.add(new IaMapper.RowOrderUpdate(orderedRowIds.get(index), (index + 1) * 10));
        }
        mapper.updateRowOrders(structure.id(), orders, accountId);
        cacheReorderedWorkbench(projectId, structure, orderedRowIds, accountId);
        return new MoveResult(expectedVersion + 1, nodeKey, direction,
                target > 0, target < context.siblings().size() - 1);
    }

    /** 이동 직후 다음 메뉴 선택이 저장소 전체를 다시 읽지 않도록 기존 작업대 스냅샷의 순서만 바꾼다. */
    private void cacheReorderedWorkbench(String projectId, IaStructure structure,
                                         List<String> orderedRowIds, String accountId) {
        String key = cacheKey(projectId, structure.systemCode());
        CachedWorkbench cached = workbenchCache.get(key);
        if (cached == null || cached.version() != structure.version()) {
            workbenchCache.remove(key);
            return;
        }

        Instant updatedAt = Instant.now();
        Map<String, RowView> viewsById = new LinkedHashMap<>();
        cached.workbench().rowViews().forEach(view -> viewsById.put(view.row().id(), view));
        List<IaRow> reorderedRows = new ArrayList<>(orderedRowIds.size());
        List<RowView> reorderedViews = new ArrayList<>(orderedRowIds.size());
        for (int index = 0; index < orderedRowIds.size(); index++) {
            RowView oldView = viewsById.get(orderedRowIds.get(index));
            if (oldView == null) {
                workbenchCache.remove(key);
                return;
            }
            IaRow old = oldView.row();
            IaRow reordered = new IaRow(old.id(), old.structureId(), (index + 1) * 10, old.pathKey(),
                    old.depth1(), old.depth2(), old.depth3(), old.depth4(), old.depth5(), old.depth6(), old.depth7(),
                    old.userType(), old.menuType(), old.screenType(), old.screenId(), updatedAt, accountId);
            reorderedRows.add(reordered);
            reorderedViews.add(new RowView(reordered, oldView.screen(), oldView.standardScreenId()));
        }
        IaStructure updated = new IaStructure(structure.id(), structure.projectId(), structure.systemCode(),
                IaStructure.State.DRAFT, structure.currentRevision(), structure.version() + 1,
                structure.importedHash(), structure.importedAt(), structure.confirmedAt(), structure.confirmedBy(),
                structure.publishedCommit(), null, updatedAt, accountId);
        Workbench old = cached.workbench();
        Workbench reordered = new Workbench(updated, List.copyOf(reorderedRows), List.copyOf(reorderedViews),
                treeOf(reorderedViews), old.screens(), old.unlinkedScreens(), old.sharedScreens(), old.lastModifiedBy());
        workbenchCache.put(key, new CachedWorkbench(updated.version(), reordered));
    }

    /**
     * DB 스냅샷 저장과 Git 게시는 한 트랜잭션인 척하지 않는다. 먼저 PUBLISHING 리비전을 확정하고,
     * 외부 작업이 끝난 뒤 성공 또는 실패를 별도 트랜잭션으로 기록한다.
     */
    public PublishResult confirm(String projectId, String systemCode, int expectedVersion, String accountId) {
        Set<String> validScreenIds = screensOf(projectId, checkedSystem(systemCode)).keySet();
        ConfirmStart start = transactions.execute(status -> beginConfirmation(
                projectId, systemCode, expectedVersion, accountId, validScreenIds));
        if (start == null) throw new IllegalStateException("IA 확정을 시작하지 못했습니다.");
        try {
            String commit = publisher.publish(projectId, start.systemCode(), start.revision(), start.content());
            transactions.executeWithoutResult(status -> {
                mapper.updateRevisionSuccess(start.revisionId(), commit);
                mapper.updateStructureSuccess(start.structureId(), commit);
            });
            return new PublishResult(start.revision(), commit, null);
        } catch (RuntimeException failed) {
            String reason = concise(failed.getMessage());
            transactions.executeWithoutResult(status -> {
                mapper.updateRevisionFailure(start.revisionId(), reason);
                mapper.updateStructureFailure(start.structureId(), reason);
            });
            return new PublishResult(start.revision(), null, reason);
        }
    }

    public Map<String, IaScreenLink> links(String projectId) {
        Map<String, IaScreenLink> result = new LinkedHashMap<>();
        mapper.selectScreenLinks(projectId).forEach(link -> result.put(link.screenId(), link));
        List<SolutionScreen> screens = solutions.screens(projectId);
        Map<String, SolutionScreen> byId = new LinkedHashMap<>();
        screens.forEach(screen -> byId.put(screen.screenId(), screen));
        Map<String, String> directPaths = new LinkedHashMap<>();
        result.values().forEach(link -> directPaths.put(link.screenId(), link.path()));
        Map<String, IaStructure> structures = new LinkedHashMap<>();
        mapper.selectStructuresByProject(projectId).forEach(it -> structures.put(it.systemCode(), it));
        for (SolutionScreen screen : screens) {
            if (result.containsKey(screen.screenId())) continue;
            IaStructure structure = structures.get(screen.system());
            if (structure == null) continue;
            if (isShared(screen, byId, directPaths)) {
                result.put(screen.screenId(), new IaScreenLink(screen.system(), screen.screenId(), "공용 화면",
                        structure.currentRevision(), structure.state()));
                continue;
            }
            String path = resolvedPath(screen.screenId(), byId, directPaths, new LinkedHashSet<>());
            if (path != null) {
                result.put(screen.screenId(), new IaScreenLink(screen.system(), screen.screenId(), path,
                        structure.currentRevision(), structure.state()));
            }
        }
        return Map.copyOf(result);
    }

    private ConfirmStart beginConfirmation(String projectId, String systemCode,
                                            int expectedVersion, String accountId, Set<String> validScreenIds) {
        IaStructure structure = required(projectId, systemCode);
        List<IaRow> rows = mapper.selectRows(structure.id());
        codec.validateRows(rows);
        for (IaRow row : rows) validateScreenId(row.screenId(), validScreenIds);
        bump(structure, expectedVersion, accountId);
        int revision = structure.currentRevision() + 1;
        String content = codec.serialize(structure.systemCode(), rows);
        String revisionId = ids.next(IdSequence.Kind.IA_REVISION);
        mapper.insertRevision(new IaRevision(revisionId, structure.id(), revision, content,
                codec.hash(content), IaRevision.State.PUBLISHING, null, null, null, accountId, null));
        mapper.updateStructurePublishing(structure.id(), revision, accountId);
        return new ConfirmStart(structure.id(), structure.systemCode(), revisionId, revision, content);
    }

    private Workbench workbench(String projectId, IaStructure structure) {
        List<IaRow> rows = mapper.selectRows(structure.id());
        Map<String, IaScreenProfile> profiles = new LinkedHashMap<>();
        mapper.selectScreenProfiles(structure.id()).forEach(profile -> profiles.put(profile.screenId(), profile));
        List<SolutionScreen> screens = solutions.screens(projectId).stream()
                .filter(screen -> screen.system().equals(structure.systemCode()))
                .map(screen -> classified(screen, profiles.get(screen.screenId())))
                .toList();
        Set<String> linked = rows.stream().map(IaRow::screenId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, SolutionScreen> byId = new LinkedHashMap<>();
        screens.forEach(screen -> byId.put(screen.screenId(), screen));
        Map<String, String> directPaths = new LinkedHashMap<>();
        rows.stream().filter(IaRow::hasScreen).forEach(row -> directPaths.put(row.screenId(), row.path()));
        List<SolutionScreen> shared = screens.stream()
                .filter(screen -> !linked.contains(screen.screenId()))
                .filter(screen -> isShared(screen, byId, directPaths))
                .sorted(Comparator.comparing(SolutionScreen::screenId)).toList();
        Set<String> sharedIds = shared.stream().map(SolutionScreen::screenId).collect(java.util.stream.Collectors.toSet());
        List<SolutionScreen> unlinked = screens.stream()
                .filter(screen -> !linked.contains(screen.screenId()) && !sharedIds.contains(screen.screenId()))
                .filter(screen -> resolvedPath(screen.screenId(), byId, directPaths, new LinkedHashSet<>()) == null)
                .sorted(Comparator.comparing(SolutionScreen::screenId)).toList();
        String lastModifiedBy = structure.updatedBy() == null ? null : accounts.selectById(structure.updatedBy())
                .map(account -> account.getName()).orElse("알 수 없음");
        Map<String, SolutionScreen> classifiedById = new LinkedHashMap<>();
        screens.forEach(screen -> classifiedById.put(screen.screenId(), screen));
        Map<String, String> standardIdByScreen = new LinkedHashMap<>();
        standardIds.selectByProject(projectId).forEach(row -> standardIdByScreen.put(row.screenId(),
                StandardScreenIdFormat.display(row.standardId(), row.origin())));
        List<RowView> rowViews = rows.stream()
                .map(row -> new RowView(row, row.screenId() == null ? null : classifiedById.get(row.screenId()),
                        standardIdByScreen.get(row.screenId())))
                .toList();
        Workbench result = new Workbench(structure, rows, rowViews, treeOf(rowViews), screens, unlinked, shared,
                lastModifiedBy);
        workbenchCache.put(cacheKey(projectId, structure.systemCode()), new CachedWorkbench(structure.version(), result));
        return result;
    }

    private List<TreeNode> treeOf(List<RowView> rows) {
        Map<String, MutableTreeNode> roots = new LinkedHashMap<>();
        for (RowView row : rows) {
            Map<String, MutableTreeNode> level = roots;
            MutableTreeNode node = null;
            List<String> depths = row.row().depths();
            String[] segments = row.row().pathKey().split("/");
            StringBuilder nodeKey = new StringBuilder();
            StringBuilder displayPath = new StringBuilder();
            for (int index = 0; index < depths.size(); index++) {
                if (nodeKey.length() > 0) nodeKey.append('/');
                if (displayPath.length() > 0) displayPath.append(" > ");
                nodeKey.append(segments[index]);
                displayPath.append(depths.get(index));
                String stableKey = nodeKey.toString();
                String stableDisplayPath = displayPath.toString();
                String label = depths.get(index);
                node = level.computeIfAbsent(segments[index],
                        ignored -> new MutableTreeNode(stableKey, label, stableDisplayPath));
                node.rowIds.add(row.row().id());
                if (node.selection == null) node.selection = row;
                level = node.children;
            }
            if (node != null) node.row = row;
        }
        return roots.values().stream().map(MutableTreeNode::freeze).toList();
    }

    public NodeSelection selectNode(Workbench workbench, String nodeKey, String legacyRowId) {
        Optional<NodeSelection> selected = Optional.empty();
        if (nodeKey != null && !nodeKey.isBlank()) {
            selected = selectionOf(workbench.tree(), nodeKey, null, null);
        } else if (legacyRowId != null && !legacyRowId.isBlank()) {
            selected = selectionOf(workbench.tree(), null, legacyRowId, null);
        }
        if (selected.isPresent()) return selected.get();
        if (workbench.tree().isEmpty()) throw new IllegalArgumentException("표시할 메뉴가 없습니다.");
        return new NodeSelection(workbench.tree().get(0), null, 1, workbench.tree().size());
    }

    /** 메뉴 선택은 저장소와 전체 화면 목록을 다시 읽지 않고 직전에 만든 작업대 스냅샷을 사용한다. */
    public SelectionView selection(String projectId, String systemCode, String nodeKey, String legacyRowId) {
        IaStructure structure = required(projectId, systemCode);
        CachedWorkbench cached = workbenchCache.get(cacheKey(projectId, structure.systemCode()));
        Workbench workbench = cached != null && cached.version() == structure.version()
                ? cached.workbench() : workbench(projectId, structure);
        NodeSelection selected = selectNode(workbench, nodeKey, legacyRowId);
        RowView row = selected.node().row();
        SolutionScreen screen = row == null ? null : row.screen();
        return new SelectionView(structure.version(), selected.node().nodeKey(), selected.node().label(),
                selected.node().displayPath(), row == null ? null : row.row().id(), selected.parentLabel(),
                selected.position(), selected.siblingCount(), selected.canMoveUp(), selected.canMoveDown(),
                selected.node().depth(), selected.node().menuType(), selected.node().applicationTarget(),
                screen == null ? null : screen.screenName(), row == null ? null : row.standardScreenId(),
                screen == null ? null : screen.kind(), screen == null ? null : screen.screenType(),
                screen != null && screen.typeNeedsReview(), row == null ? null : row.row().screenId(),
                screen == null ? null : screen.summary());
    }

    private String cacheKey(String projectId, String systemCode) {
        return projectId + ':' + systemCode;
    }

    private Optional<NodeSelection> selectionOf(List<TreeNode> siblings, String nodeKey,
                                                String rowId, String parentLabel) {
        for (int index = 0; index < siblings.size(); index++) {
            TreeNode node = siblings.get(index);
            boolean keyMatches = nodeKey != null && node.nodeKey().equals(nodeKey);
            boolean rowMatches = rowId != null && node.row() != null && node.row().row().id().equals(rowId);
            if (keyMatches || rowMatches) {
                return Optional.of(new NodeSelection(node, parentLabel, index + 1, siblings.size()));
            }
            Optional<NodeSelection> nested = selectionOf(node.children(), nodeKey, rowId, node.label());
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }

    private List<RowView> bareRowViews(List<IaRow> rows) {
        return rows.stream().map(row -> new RowView(row, null, null)).toList();
    }

    private Optional<TreeNode> nodeForRow(TreeNode node, String rowId) {
        if (node.row() != null && node.row().row().id().equals(rowId)) return Optional.of(node);
        return node.children().stream().map(child -> nodeForRow(child, rowId))
                .flatMap(Optional::stream).findFirst();
    }

    private Optional<List<TreeNode>> nodePath(List<TreeNode> nodes, String nodeKey) {
        for (TreeNode node : nodes) {
            if (node.nodeKey().equals(nodeKey)) return Optional.of(List.of(node));
            Optional<List<TreeNode>> nested = nodePath(node.children(), nodeKey);
            if (nested.isPresent()) {
                List<TreeNode> path = new ArrayList<>();
                path.add(node);
                path.addAll(nested.get());
                return Optional.of(List.copyOf(path));
            }
        }
        return Optional.empty();
    }

    private void collectParentOptions(List<TreeNode> nodes, String excludedNodeKey, List<ParentOption> result) {
        for (TreeNode node : nodes) {
            boolean excluded = node.nodeKey().equals(excludedNodeKey)
                    || node.nodeKey().startsWith(excludedNodeKey + "/");
            if (!excluded && node.depth() < IaTreeBuilder.MAX_DEPTH) {
                result.add(new ParentOption(node.nodeKey(), node.displayPath(), node.depth()));
            }
            if (!excluded) collectParentOptions(node.children(), excludedNodeKey, result);
        }
    }

    private Optional<MoveContext> moveContext(List<TreeNode> siblings, String nodeKey) {
        for (int index = 0; index < siblings.size(); index++) {
            TreeNode node = siblings.get(index);
            if (node.nodeKey().equals(nodeKey)) return Optional.of(new MoveContext(siblings, index));
            Optional<MoveContext> nested = moveContext(node.children(), nodeKey);
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }

    private void flattenRows(List<TreeNode> nodes, List<TreeNode> targetSiblings,
                             List<TreeNode> reorderedSiblings, List<String> orderedRowIds) {
        List<TreeNode> current = nodes == targetSiblings ? reorderedSiblings : nodes;
        for (TreeNode node : current) {
            if (node.row() != null) orderedRowIds.add(node.row().row().id());
            flattenRows(node.children(), targetSiblings, reorderedSiblings, orderedRowIds);
        }
    }

    private SolutionScreen classified(SolutionScreen screen, IaScreenProfile profile) {
        if (profile == null) return screen;
        return screen.withClassification(profile.screenKind().label(), profile.screenType().label(),
                profile.typeSource() == null ? null : profile.typeSource().label());
    }

    private boolean isShared(SolutionScreen screen, Map<String, SolutionScreen> byId,
                             Map<String, String> directPaths) {
        if (screen.shared()) return true;
        if (screen.openingScreenIds().isEmpty()) return false;
        return screen.openingScreenIds().stream()
                .map(id -> resolvedPath(id, byId, directPaths, new LinkedHashSet<>()))
                .filter(java.util.Objects::nonNull).distinct().limit(2).count() > 1;
    }

    /** 화면형은 상위화면, 팝업·모달은 여는화면을 따라 DB의 직접 배치 행까지 올라간다. */
    private String resolvedPath(String screenId, Map<String, SolutionScreen> byId,
                                Map<String, String> directPaths, Set<String> visiting) {
        String direct = directPaths.get(screenId);
        if (direct != null) return direct;
        if (!visiting.add(screenId)) return null;
        SolutionScreen screen = byId.get(screenId);
        if (screen == null) return null;
        if (screen.parentScreenId() != null) {
            return resolvedPath(screen.parentScreenId(), byId, directPaths, visiting);
        }
        Set<String> openerPaths = new LinkedHashSet<>();
        for (String opener : screen.openingScreenIds()) {
            String path = resolvedPath(opener, byId, directPaths, new LinkedHashSet<>(visiting));
            if (path != null) openerPaths.add(path);
        }
        return openerPaths.size() == 1 ? openerPaths.iterator().next() : null;
    }

    private IaRow normalized(String id, IaStructure structure, int rowOrder, RowInput input, String accountId) {
        String screenId = text(input.screenId());
        String screenType = screenId == null ? null : mapper.selectScreenProfiles(structure.id()).stream()
                .filter(profile -> profile.screenId().equals(screenId))
                .findFirst()
                .map(profile -> profile.screenType().label())
                .orElseThrow(() -> new IllegalArgumentException("최초 저장된 화면 분류 정보가 없습니다: " + screenId));
        IaRow row = new IaRow(id, structure.id(), rowOrder, text(input.pathKey()), requiredText(input.depth1(), "Depth 1"),
                text(input.depth2()), text(input.depth3()), text(input.depth4()), text(input.depth5()),
                text(input.depth6()), text(input.depth7()), text(input.userType()), text(input.menuType()), screenType, screenId,
                Instant.now(), accountId);
        codec.validateRows(List.of(row));
        return row;
    }

    private IaRow relocated(IaRow row, IaStructure structure, String pathKey, List<String> depths,
                            String menuType, String screenId, String accountId) {
        return normalized(row.id(), structure, row.rowOrder(), new RowInput(row.rowOrder(), pathKey,
                at(depths, 0), at(depths, 1), at(depths, 2), at(depths, 3), at(depths, 4),
                at(depths, 5), at(depths, 6), row.userType(), menuType, null, screenId), accountId);
    }

    private void ensureScreenKnown(String projectId, String systemCode, String screenId) {
        validateScreenId(screenId, screensOf(projectId, systemCode).keySet());
    }

    private void validateScreenId(String screenId, Set<String> validScreenIds) {
        if (screenId == null) return;
        if (!validScreenIds.contains(screenId)) {
            throw new IllegalArgumentException("이 시스템의 기획 저장소에 없는 화면 ID입니다: " + screenId);
        }
    }

    private Map<String, SolutionScreen> screensOf(String projectId, String systemCode) {
        Map<String, SolutionScreen> result = new LinkedHashMap<>();
        solutions.screens(projectId).stream().filter(screen -> screen.system().equals(systemCode))
                .forEach(screen -> result.put(screen.screenId(), screen));
        return result;
    }

    private SolutionScreen screenOf(String screenId, Map<String, SolutionScreen> screens) {
        if (screenId == null) return null;
        SolutionScreen screen = screens.get(screenId);
        if (screen == null) throw new IllegalArgumentException("기획 저장소에 없는 화면 ID입니다: " + screenId);
        return screen;
    }

    private IaStructure required(String projectId, String systemCode) {
        return mapper.selectStructure(projectId, checkedSystem(systemCode))
                .orElseThrow(() -> new IllegalArgumentException("먼저 ia.md를 최초 가져오기 해주세요."));
    }

    /**
     * ⑤ 상위 메뉴 변경이 만든 경로키가 옮기지 않는 다른 행과 같아지면 거절한다.
     *
     * <p>예 — {@code EXW/UWV/20}(휴대폰 본인인증)을 {@code EXW/UWV/50} 밑으로 옮기면
     * {@code newPrefix} 가 {@code EXW/UWV/50/20} 인데 거기 이미 「충전」이 앉아 있다. 라벨이
     * 달라 형제 이름 중복 검사는 통과하지만 두 행의 경로키가 같아진다 — 그것을 여기서 막는다.
     *
     * <p>⭐ <b>「행」만 보고 「마디」를 안 보면 못 잡는 반례가 있다 (코드리뷰 2차 CRITICAL,
     * 2026-09-04)</b> — {@code untouchedByPathKey} 는 {@code pathKey} 정확 일치 맵이다.
     * 옮길 자리에 {@code approval/target/movegroup} 행은 없고 그 자손
     * {@code approval/target/movegroup/other-child} 행만 있으면, 정확 일치로는 못 잡고 남의
     * 자손 밑으로 조용히 접붙는다. 그래서 <b>새 경로키가 다른 행의 경로키의 앞머리</b>이면
     * (그 행이 옮기는 가지 밖이면) 그것도 거절한다 — 자손 경로를 검사하는 호출(:347 언저리)에도
     * 같은 메서드가 그대로 걸린다.
     */
    private void rejectIfPathCollides(String candidatePathKey, Map<String, IaRow> untouchedByPathKey) {
        IaRow exact = untouchedByPathKey.get(candidatePathKey);
        if (exact != null) {
            rejectCollision(candidatePathKey, exact, exact.depths().size() - 1);
            return;
        }
        String descendantPrefix = candidatePathKey + "/";
        int candidateDepth = candidatePathKey.split("/").length;
        untouchedByPathKey.values().stream()
                .filter(row -> row.pathKey().startsWith(descendantPrefix))
                .findFirst()
                .ifPresent(row -> rejectCollision(candidatePathKey, row, candidateDepth - 1));
    }

    private void rejectCollision(String candidatePathKey, IaRow collided, int labelIndex) {
        List<String> collidedDepths = collided.depths();
        // ⚠ depth1 이 빈 값이면 DB 제약(V19:32)이 막아 지금까지 collidedDepths 가 최소 한 칸은
        //    있다고 가정해 왔다 — 그 보증이 마이그레이션 제약 하나에 매달려 있다(코드리뷰 지적,
        //    2026-09-04). 그 제약이 없어지거나 우회되어도 여기서는 안 터지게 candidatePathKey 로
        //    대신한다. 자손으로 잡은 경우도 마찬가지로 인덱스가 범위를 벗어날 수 있어 같은 방어를 쓴다.
        String collidedLabel = labelIndex >= 0 && labelIndex < collidedDepths.size()
                ? collidedDepths.get(labelIndex) : candidatePathKey;
        throw new IllegalArgumentException("이동할 위치에 이미 '" + collidedLabel + "' 메뉴가 있습니다.");
    }

    private IaRow ownedRow(IaStructure structure, String rowId) {
        IaRow row = mapper.selectRow(rowId);
        if (row == null || !row.structureId().equals(structure.id())) {
            throw new IllegalArgumentException("그런 메뉴 행이 없습니다.");
        }
        return row;
    }

    private void bump(IaStructure structure, int expectedVersion, String accountId) {
        if (mapper.bumpVersion(structure.id(), expectedVersion, accountId) == 0) {
            throw new IllegalStateException("다른 사용자가 먼저 메뉴구조도를 변경했습니다. 새로고침한 뒤 다시 저장해 주세요.");
        }
    }

    private String checkedSystem(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("시스템 코드의 꼴이 아닙니다: " + value);
        }
        return value;
    }

    /**
     * 시스템 하나가 화면에 뜨는 말.
     *
     * <p>⚠ 프로젝트 등록 자료에서 온다 — 이름이 아직 없으면 코드 그대로다.
     * ⛔ 코드에 이름표를 다시 박지 마라: 시스템 목록은 사업마다 다르다.
     */
    public String systemLabel(String projectId, String systemCode) {
        return projectSystems.labels(projectId).label(systemCode);
    }

    private String requiredText(String value, String name) {
        String normalized = text(value);
        if (normalized == null) throw new IllegalArgumentException(name + "은 필수입니다.");
        return normalized;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String generatedMenuKey(String rowId) {
        return "menu-" + rowId.replaceAll("[^A-Za-z0-9_-]", "-").toLowerCase(Locale.ROOT);
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static String concise(String message) {
        if (message == null || message.isBlank()) return "게시 중 알 수 없는 오류가 발생했습니다.";
        String oneLine = message.replaceAll("\\s+", " ").strip();
        return oneLine.substring(0, Math.min(1000, oneLine.length()));
    }

    public record RowInput(Integer rowOrder, String pathKey, String depth1, String depth2, String depth3,
                           String depth4, String depth5, String depth6, String depth7, String userType, String menuType,
                           String screenType, String screenId) {}
    public record CreateMenuInput(String menuName, String parentNodeKey, String screenId) {}
    public record CreateOptions(String menuName, String screenId, String standardScreenId,
                                List<ParentOption> parents) {}
    public record MenuLocationInput(String parentNodeKey) {}
    public record ParentOption(String nodeKey, String displayPath, int depth) {}
    public record EditOptions(String menuName, String parentNodeKey,
                              String standardScreenId, String originalScreenId,
                              List<ParentOption> parents) {}
    public record SystemSummary(String systemCode, String systemLabel, Instant lastModifiedAt,
                                String lastModifiedBy) {}
    public record RowView(IaRow row, SolutionScreen screen, String standardScreenId) {}
    public record TreeNode(String nodeKey, String label, String displayPath, RowView row, RowView selection,
                           List<String> rowIds,
                           List<TreeNode> children, int descendantCount) {
        public boolean contains(String rowId) { return rowIds.contains(rowId); }
        public boolean containsNode(String selectedNodeKey) {
            return selectedNodeKey != null && selectedNodeKey.startsWith(nodeKey + "/");
        }
        public int depth() {
            return nodeKey.split("/").length;
        }
        public String menuType() {
            if (row == null) return "그룹";
            if (row.row().menuType() != null && !row.row().menuType().isBlank()) return row.row().menuType();
            return row.screen() == null ? "메뉴" : row.screen().kind();
        }
        public String applicationTarget() {
            if (row == null || row.screen() == null) return "—";
            String summary = row.screen().applicationSummary();
            return summary.isBlank() ? "전체" : summary;
        }
    }
    public record NodeSelection(TreeNode node, String parentLabel, int position, int siblingCount) {
        public boolean canMoveUp() { return position > 1; }
        public boolean canMoveDown() { return position < siblingCount; }
    }
    public record SelectionView(int version, String nodeKey, String label, String displayPath, String rowId,
                                String parentLabel, int position, int siblingCount,
                                boolean canMoveUp, boolean canMoveDown, int depth, String menuType,
                                 String applicationTarget,
                                 String screenName, String standardScreenId, String screenKind, String screenType,
                                 boolean screenTypeNeedsReview, String originalScreenId, String screenSummary) {}
    public record Workbench(IaStructure structure, List<IaRow> rows, List<RowView> rowViews,
                            List<TreeNode> tree,
                            List<SolutionScreen> screens,
                            List<SolutionScreen> unlinkedScreens, List<SolutionScreen> sharedScreens,
                            String lastModifiedBy) {}
    public record PublishResult(int revision, String commit, String failure) {
        public boolean published() { return failure == null; }
    }
    private record ConfirmStart(String structureId, String systemCode, String revisionId,
                                int revision, String content) {}
    private record MoveContext(List<TreeNode> siblings, int index) {}
    private record CachedWorkbench(int version, Workbench workbench) {}
    public record MoveResult(int version, String nodeKey, String direction,
                             boolean canMoveUp, boolean canMoveDown) {}

    private static final class MutableTreeNode {
        private final String nodeKey;
        private final String label;
        private final String displayPath;
        private final Map<String, MutableTreeNode> children = new LinkedHashMap<>();
        private final List<String> rowIds = new ArrayList<>();
        private RowView row;
        private RowView selection;

        private MutableTreeNode(String nodeKey, String label, String displayPath) {
            this.nodeKey = nodeKey;
            this.label = label;
            this.displayPath = displayPath;
        }

        private TreeNode freeze() {
            List<TreeNode> frozen = children.values().stream().map(MutableTreeNode::freeze).toList();
            int descendants = frozen.size() + frozen.stream().mapToInt(TreeNode::descendantCount).sum();
            return new TreeNode(nodeKey, label, displayPath, row, selection, List.copyOf(rowIds), frozen, descendants);
        }
    }
}
