package com.bizplay.builder.frd;

import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 화면 MD의 이동 정의를 읽어 FRD 캔버스용 화면·관계 지도를 만든다. */
@Service
public class FrdCanvasService {

    private static final Logger log = LoggerFactory.getLogger(FrdCanvasService.class);
    private static final Set<String> LINK_FIELDS = Set.of("이동", "이동modal", "이동native", "이동cross");

    private final ProjectPaths paths;
    private final FrdScreenMapper frdScreens;
    private final SolutionScreenReader solutions;
    private final FrdScreenIaPlacementService iaPlacements;
    private final ScreenStandardIdMapper standardIds;

    @Autowired
    public FrdCanvasService(ProjectPaths paths, FrdScreenMapper frdScreens,
                            SolutionScreenReader solutions,
                            FrdScreenIaPlacementService iaPlacements,
                            ScreenStandardIdMapper standardIds) {
        this.paths = paths;
        this.frdScreens = frdScreens;
        this.solutions = solutions;
        this.iaPlacements = iaPlacements;
        this.standardIds = standardIds;
    }

    FrdCanvasService(ProjectPaths paths, FrdScreenMapper frdScreens,
                     SolutionScreenReader solutions) {
        this(paths, frdScreens, solutions, null, null);
    }

    public Canvas read(String projectId, String frdId, boolean projectScope, String previewSkin) {
        List<FrdScreen> workScreens = frdScreens.selectByFrdId(frdId);
        Map<String, FrdScreenIaPlacement> placementByRowId = new LinkedHashMap<>();
        if (iaPlacements != null) {
            iaPlacements.all(frdId).forEach(item -> placementByRowId.put(item.frdScreenId(), item));
        }
        Map<String, FrdScreen> workByScreenId = new LinkedHashMap<>();
        workScreens.forEach(screen -> workByScreenId.put(screen.screenId(), screen));

        Map<String, SolutionScreen> solutionById = new LinkedHashMap<>();
        solutions.read(projectId).forEach(screen -> solutionById.put(screen.screenId(), screen));
        Map<String, String> managementNumberByScreenId = new LinkedHashMap<>();
        if (standardIds != null) {
            standardIds.selectByProject(projectId).forEach(row -> managementNumberByScreenId.put(
                    row.screenId(), StandardScreenIdFormat.display(row.standardId(), row.origin())));
        }

        Map<LinkKey, Relation> baseline = relations(paths.cloneDir(projectId));
        Path workspace = paths.frdWorktree(projectId, frdId);
        Path activeRepository = Files.isDirectory(workspace) ? workspace : paths.cloneDir(projectId);
        Map<LinkKey, Relation> working = Files.isDirectory(workspace) ? relations(workspace) : baseline;

        List<CanvasRelation> relations = compare(baseline, working);
        Set<String> visibleIds = visibleIds(projectScope, workByScreenId.keySet(),
                solutionById.keySet(), relations);
        List<CanvasNode> nodes = nodes(visibleIds, workByScreenId, solutionById, placementByRowId,
                managementNumberByScreenId,
                previewSkin, activeRepository);
        Set<String> actualNodeIds = nodes.stream().map(CanvasNode::screenId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<CanvasRelation> visibleRelations = relations.stream()
                .filter(link -> actualNodeIds.contains(link.sourceScreenId())
                        && actualNodeIds.contains(link.targetScreenId()))
                .filter(link -> projectScope || workByScreenId.containsKey(link.sourceScreenId())
                        || workByScreenId.containsKey(link.targetScreenId()))
                .toList();
        return new Canvas(nodes, visibleRelations, projectScope);
    }

    private Set<String> visibleIds(boolean projectScope, Set<String> workIds,
                                   Set<String> solutionIds,
                                   List<CanvasRelation> relations) {
        if (projectScope) {
            Set<String> all = new LinkedHashSet<>(solutionIds);
            relations.forEach(link -> {
                all.add(link.sourceScreenId());
                all.add(link.targetScreenId());
            });
            all.addAll(workIds);
            return all;
        }
        Set<String> visible = new LinkedHashSet<>(workIds);
        relations.forEach(link -> {
            if (workIds.contains(link.sourceScreenId()) || workIds.contains(link.targetScreenId())) {
                visible.add(link.sourceScreenId());
                visible.add(link.targetScreenId());
            }
        });
        return visible;
    }

    private List<CanvasNode> nodes(Set<String> visibleIds, Map<String, FrdScreen> workByScreenId,
                                   Map<String, SolutionScreen> solutionById,
                                   Map<String, FrdScreenIaPlacement> placementByRowId,
                                   Map<String, String> managementNumberByScreenId,
                                   String previewSkin,
                                   Path activeRepository) {
        List<CanvasNode> result = new ArrayList<>();
        for (String screenId : visibleIds) {
            FrdScreen work = workByScreenId.get(screenId);
            SolutionScreen solution = solutionById.get(screenId);
            if (work == null && solution == null) continue;
            String name = work != null && work.screenName() != null && !work.screenName().isBlank()
                    ? work.screenName() : solution.screenName();
            String system = work != null && work.systemCode() != null && !work.systemCode().isBlank()
                    ? work.systemCode() : solution.system();
            String previewPath = solution == null ? null : solution.previewPath(previewSkin);
            FrdScreenIaPlacement placement = work == null ? null : placementByRowId.get(work.id());
            result.add(new CanvasNode(screenId, name, system,
                    managementNumberByScreenId.get(screenId), work != null && work.isNewScreen(),
                    work == null ? null : work.id(), work != null,
                    work != null && work.isAiDraftEligible(),
                    work == null ? null : work.state().name(),
                    work == null ? null : work.stateLabel(),
                    work == null ? null : work.screenType(),
                    placement == null ? null : placement.statusLabel(),
                    placement == null ? null : placementLabel(placement),
                    placement == null ? null : placement.screenKind().label(),
                    previewPath, work == null ? List.of() : work.changeList(),
                    work == null ? List.of() : clickableElements(activeRepository, system, screenId)));
        }
        Map<String, Integer> workOrder = new LinkedHashMap<>();
        int workIndex = 0;
        for (String screenId : workByScreenId.keySet()) {
            workOrder.put(screenId, workIndex++);
        }
        result.sort(Comparator.comparing(CanvasNode::workTarget).reversed()
                .thenComparingInt(node -> node.workTarget()
                        ? workOrder.getOrDefault(node.screenId(), Integer.MAX_VALUE)
                        : Integer.MAX_VALUE)
                .thenComparing(CanvasNode::systemCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(CanvasNode::screenId));
        return List.copyOf(result);
    }

    private String placementLabel(FrdScreenIaPlacement placement) {
        return switch (placement.placementMode()) {
            case MENU -> "메뉴 · " + placement.menuPathKey();
            case CHILD -> placement.anchorScreenId() + " 하위";
            case OPENER -> placement.anchorScreenId() + "에서 열기";
            case UNRESOLVED -> "위치를 정해 주세요";
        };
    }

    private List<CanvasElement> clickableElements(Path repository, String systemCode, String screenId) {
        if (systemCode == null || systemCode.isBlank()) return List.of();
        Path core = repository.resolve("core").toAbsolutePath().normalize();
        Path md = core.resolve(systemCode).resolve("pages").resolve(screenId + ".md").normalize();
        Path html = core.resolve(systemCode).resolve("pages").resolve(screenId + ".html").normalize();
        if (!md.startsWith(core) || !html.startsWith(core)) return List.of();
        Map<String, CanvasElement> result = new LinkedHashMap<>();
        if (Files.isRegularFile(md)) {
            try {
                for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (!trimmed.startsWith("- ")) continue;
                    Map<String, String> fields = fields(trimmed.substring(2));
                    String anchor = fields.get("앵커");
                    if (anchor == null || anchor.isBlank()) continue;
                    String kind = fields.getOrDefault("구분", "요소");
                    String label = first(fields, "라벨", "내용", "해설", "좌표");
                    if (label.isBlank()) label = anchor;
                    if (label.length() > 90) label = label.substring(0, 89).strip() + "…";
                    result.putIfAbsent(anchor, new CanvasElement(anchor, label, kind));
                }
            } catch (IOException failure) {
                log.debug("FRD 캔버스가 클릭 요소 MD를 건너뛴다 file={}", md, failure);
            }
        }
        readHtmlElements(html, result);
        return List.copyOf(result.values());
    }

    /** 신규 화면은 기능정의서 MD보다 HTML 초안이 먼저 생기므로 실제 요소를 즉시 연결 후보로 쓴다. */
    private void readHtmlElements(Path html, Map<String, CanvasElement> result) {
        if (!Files.isRegularFile(html)) return;
        try {
            String selector = "[data-element-id], a[id], button[id], [role=button][id], "
                    + "[onclick][id], [data-nav-target][id], input[type=button][id], input[type=submit][id]";
            for (Element element : Jsoup.parse(Files.readString(html, StandardCharsets.UTF_8)).select(selector)) {
                String anchor = firstAttribute(element, "data-element-id", "id");
                if (anchor.isBlank()) continue;
                String label = elementLabel(element, anchor);
                result.putIfAbsent(anchor, new CanvasElement(anchor, label, elementKind(element)));
            }
        } catch (IOException failure) {
            log.debug("FRD 캔버스가 클릭 요소 HTML을 건너뛴다 file={}", html, failure);
        }
    }

    private String elementLabel(Element element, String fallback) {
        String label = firstAttribute(element, "aria-label", "title", "value", "placeholder");
        if (label.isBlank()) label = element.text().strip();
        if (label.isBlank()) label = fallback;
        return label.length() > 90 ? label.substring(0, 89).strip() + "…" : label;
    }

    private String elementKind(Element element) {
        return switch (element.tagName()) {
            case "a" -> "링크";
            case "button" -> "버튼";
            case "input", "textarea" -> "입력";
            case "select" -> "선택";
            case "tr" -> "행";
            default -> "요소";
        };
    }

    private List<CanvasRelation> compare(Map<LinkKey, Relation> baseline,
                                         Map<LinkKey, Relation> working) {
        List<CanvasRelation> result = new ArrayList<>();
        for (Map.Entry<LinkKey, Relation> entry : working.entrySet()) {
            Relation relation = entry.getValue();
            result.add(relation.canvas(baseline.containsKey(entry.getKey()) ? State.CURRENT : State.ADDED));
        }
        for (Map.Entry<LinkKey, Relation> entry : baseline.entrySet()) {
            if (!working.containsKey(entry.getKey())) result.add(entry.getValue().canvas(State.REMOVED));
        }
        result.sort(Comparator.comparing(CanvasRelation::sourceScreenId)
                .thenComparing(CanvasRelation::targetScreenId)
                .thenComparing(CanvasRelation::anchor));
        return List.copyOf(result);
    }

    private Map<LinkKey, Relation> relations(Path repository) {
        Map<LinkKey, Relation> result = new LinkedHashMap<>();
        Path core = repository.resolve("core").normalize();
        if (!Files.isDirectory(core)) return result;
        try (Stream<Path> files = Files.walk(core, 4)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getParent() != null && "pages".equals(path.getParent().getFileName().toString()))
                    .filter(path -> path.getFileName().toString().endsWith(".md")
                            || path.getFileName().toString().endsWith(".html"))
                    .sorted()
                    .forEach(path -> {
                        if (path.getFileName().toString().endsWith(".md")) readRelations(path, result);
                        else if (!hasMdContract(path)) readHtmlRelations(path, result);
                    });
        } catch (IOException failure) {
            log.warn("FRD 캔버스 화면 관계를 읽지 못했다 repository={}", repository, failure);
        }
        return result;
    }

    private boolean hasMdContract(Path html) {
        String fileName = html.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - 5);
        return Files.isRegularFile(html.resolveSibling(stem + ".md"));
    }

    private void readRelations(Path md, Map<LinkKey, Relation> result) {
        String fileName = md.getFileName().toString();
        String source = fileName.substring(0, fileName.length() - 3);
        try {
            for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("- ")) continue;
                Map<String, String> fields = fields(trimmed.substring(2));
                if (!"이동".equals(fields.get("구분"))) continue;
                for (String field : LINK_FIELDS) {
                    String target = fields.get(field);
                    if (target == null || target.isBlank()) continue;
                    Relation relation = new Relation(source, target.strip(), field,
                            first(fields, "앵커", "anchor"), relationLabel(fields));
                    result.putIfAbsent(relation.key(), relation);
                }
            }
        } catch (IOException failure) {
            log.debug("FRD 캔버스 화면 MD를 건너뛴다 file={}", md, failure);
        }
    }

    /** 신규 화면은 아직 MD가 없을 수 있어 HTML에 기록된 화면 이동 계약도 함께 읽는다. */
    private void readHtmlRelations(Path html, Map<LinkKey, Relation> result) {
        String fileName = html.getFileName().toString();
        String source = fileName.substring(0, fileName.length() - 5);
        try {
            String content = Files.readString(html, StandardCharsets.UTF_8);
            if (!content.contains("data-nav-target")) return;
            Set<String> targets = new LinkedHashSet<>();
            for (Element element : Jsoup.parse(content).select("[data-nav-target]")) {
                String anchor = firstAttribute(element, "data-element-id", "id");
                String label = navigationLabel(element);
                for (String rawTarget : element.attr("data-nav-target").split(",")) {
                    String target = rawTarget.strip();
                    if (target.isBlank() || source.equals(target) || !targets.add(target)) continue;
                    Relation relation = new Relation(source, target, "이동", anchor, label);
                    result.putIfAbsent(relation.key(), relation);
                }
            }
        } catch (IOException failure) {
            log.debug("FRD 캔버스가 화면 HTML을 건너뛴다 file={}", html, failure);
        }
    }

    private String navigationLabel(Element element) {
        String label = firstAttribute(element, "aria-label", "title");
        if (label.isBlank() && ("a".equals(element.tagName()) || "button".equals(element.tagName()))) {
            label = element.text().strip();
        }
        if (label.isBlank()) return "화면 이동";
        return label.length() > 60 ? label.substring(0, 60).strip() : label;
    }

    private String firstAttribute(Element element, String... names) {
        for (String name : names) {
            String value = element.attr(name);
            if (!value.isBlank()) return value.strip();
        }
        return "";
    }

    private Map<String, String> fields(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : line.split(" / ")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            result.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
        }
        return result;
    }

    private String first(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private String relationLabel(Map<String, String> fields) {
        String label = first(fields, "라벨", "내용");
        if (!label.isBlank()) return label;
        String explanation = first(fields, "해설");
        int detail = explanation.indexOf('(');
        return (detail > 0 ? explanation.substring(0, detail) : explanation).strip();
    }

    public enum State { CURRENT, ADDED, REMOVED }

    public record Canvas(List<CanvasNode> nodes, List<CanvasRelation> relations, boolean projectScope) { }

    public record CanvasNode(String screenId, String screenName, String systemCode,
                             String managementNumber, boolean newScreen,
                             String frdScreenId, boolean workTarget, boolean aiDraftEligible,
                             String stateName, String stateLabel, String screenType,
                             String iaStatusLabel, String iaPlacementLabel, String screenKindLabel,
                             String previewPath, List<String> changes,
                             List<CanvasElement> clickableElements) {
        public boolean modified() {
            return !changes.isEmpty();
        }

        public String managementNumberLabel() {
            if (managementNumber != null && !managementNumber.isBlank()) return managementNumber;
            return newScreen ? "미채번" : "관리번호 없음";
        }
    }

    public record CanvasElement(String anchor, String label, String kind) { }

    public record CanvasRelation(String sourceScreenId, String targetScreenId, String kind,
                                 String anchor, String label, State state) { }

    private record LinkKey(String source, String target, String kind, String anchor) { }

    private record Relation(String source, String target, String kind, String anchor, String label) {
        LinkKey key() { return new LinkKey(source, target, kind, anchor); }
        CanvasRelation canvas(State state) {
            return new CanvasRelation(source, target, kind, anchor, label, state);
        }
    }
}
