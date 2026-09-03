package com.bizplay.builder.frd;

import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 완료 직전에 신규 화면의 IA 배치 의도를 화면 정의서 정본으로 만든다. */
@Service
public class FrdScreenIaMaterializer {

    private static final Pattern SECTION = Pattern.compile(
            "(?ms)^---\\s*IA\\s*---\\s*.*?(?=^---\\s*[^\\r\\n]+\\s*---\\s*$|\\z)");
    private static final Pattern DEFINITION_LINE = Pattern.compile("(?m)^- .*?(?:\\R|$)");
    private static final Pattern PLACEMENT_SECTION = Pattern.compile(
            "(?ms)(^---\\s*배치\\s*---\\s*\\R)(.*?)(?=^---\\s*[^\\r\\n]+\\s*---\\s*$|\\z)");
    private static final Pattern IA_PATH = Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*");
    private static final Pattern IA_LABEL = Pattern.compile(
            "(?m)^- ([A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)\\s*:\\s*(.+?)\\s*$");
    private static final Pattern SCREEN_ID_ATTRIBUTE = Pattern.compile(
            "(?i)(data-screen-id\\s*=\\s*)([\"'])([^\"']+)(\\2)");
    private static final Pattern ELEMENT_ID_ATTRIBUTE = Pattern.compile(
            "(?i)(data-element-id\\s*=\\s*)([\"'])([^\"']+)(\\2)");
    private static final Pattern NAV_TARGET_ATTRIBUTE = Pattern.compile(
            "(?i)(data-nav-target\\s*=\\s*)([\"'])([^\"']+)(\\2)");
    private static final Pattern FIRST_INTERACTIVE_TAG = Pattern.compile(
            "(?i)<(button|a|input|select|textarea)\\b");
    private static final Pattern DEFINITION_SECTION = Pattern.compile(
            "(?ms)(^---\\s*정의\\s*---\\s*\\R)(.*?)(?=^---\\s*[^\\r\\n]+\\s*---\\s*$|\\z)");
    private static final List<String> LINK_FIELDS = List.of("이동", "이동modal");

    private final FrdScreenMapper screens;
    private final FrdScreenIaPlacementService placements;
    private final FrdScreenFiles files;
    private final SolutionScreenReader solutions;

    public FrdScreenIaMaterializer(FrdScreenMapper screens,
                                   FrdScreenIaPlacementService placements,
                                   FrdScreenFiles files,
                                   SolutionScreenReader solutions) {
        this.screens = screens;
        this.placements = placements;
        this.files = files;
        this.solutions = solutions;
    }

    /** 개발용 화면 ID를 확보하고 신규 화면의 내부 작업 식별자를 정규화한다. 정식 IA는 변경하지 않는다. */
    @Transactional
    public void materialize(String projectId, String frdId) {
        List<FrdScreen> rows = screens.selectByFrdId(frdId);
        List<SolutionScreen> solutionScreens = solutions.read(projectId);
        List<FrdScreen> newScreens = rows.stream().filter(FrdScreen::isNewScreen).toList();
        Set<String> unavailableNames = new LinkedHashSet<>();
        solutionScreens.forEach(screen -> unavailableNames.add(screen.screenId()));
        Map<String, FrdScreenIaPlacement> placementByScreenId = new LinkedHashMap<>();
        for (FrdScreen screen : newScreens) {
            FrdScreenIaPlacement placement = placementOf(projectId, frdId, screen, rows);
            FrdScreenIaPlacement reserved = placements.reserveDevelopmentFileName(
                    projectId, screen, placement, unavailableNames);
            placementByScreenId.put(screen.screenId(), reserved);
            unavailableNames.add(reserved.developmentFileName());
        }

        Map<Path, String> before = new LinkedHashMap<>();
        try {
            Map<String, String> normalizedHtml = normalizeNewScreenHtml(
                    projectId, frdId, newScreens, placementByScreenId, solutionScreens, before);
            for (FrdScreen screen : newScreens) {
                Path document = files.document(projectId, frdId, screen.systemCode(), screen.screenId());
                String current = Files.isRegularFile(document)
                        ? Files.readString(document, StandardCharsets.UTF_8)
                        : minimalDocument(screen);
                before.putIfAbsent(document, Files.isRegularFile(document) ? current : null);
                Files.createDirectories(document.getParent());
                String merged = current;
                String html = normalizedHtml.get(screen.screenId());
                if (html != null) {
                    merged = mergeDefinitions(merged, definitionsOf(html));
                }
                Files.writeString(document, merged, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException failure) {
            restore(before, failure);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("신규 화면의 개발 식별 정보를 작업공간에 쓰지 못했습니다.", failure);
        }
    }

    /**
     * AI가 신규 화면에 의미형 ID를 지어 넣어도 파일명의 임시 화면ID를 정본으로 되돌린다.
     * 화면 간 이동은 IA에서 확정한 자식이 하나뿐일 때만 보정해 엉뚱한 화면을 연결하지 않는다.
     */
    private Map<String, String> normalizeNewScreenHtml(
            String projectId, String frdId, List<FrdScreen> newScreens,
            Map<String, FrdScreenIaPlacement> placementByScreenId,
            List<SolutionScreen> solutionScreens, Map<Path, String> before) throws IOException {
        Map<String, Path> htmlByScreenId = new LinkedHashMap<>();
        Map<String, String> rawByScreenId = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> knownScreenIds = new HashSet<>();
        solutionScreens.forEach(screen -> knownScreenIds.add(screen.screenId()));
        for (FrdScreen screen : newScreens) {
            String screenId = screen.screenId();
            knownScreenIds.add(screenId);
            Path html = files.targetHtml(projectId, frdId, screen.systemCode(), screenId);
            if (html == null || !Files.isRegularFile(html)) continue;
            String raw = Files.readString(html, StandardCharsets.UTF_8);
            htmlByScreenId.put(screenId, html);
            rawByScreenId.put(screenId, raw);
            String declared = declaredScreenId(raw);
            if (declared != null && !declared.equals(screenId)) aliases.put(declared, screenId);
        }

        Map<String, List<String>> children = new LinkedHashMap<>();
        for (FrdScreen screen : newScreens) {
            FrdScreenIaPlacement placement = placementByScreenId.get(screen.screenId());
            if (placement == null) continue;
            if ((placement.placementMode() == FrdScreenIaPlacement.PlacementMode.CHILD
                    || placement.placementMode() == FrdScreenIaPlacement.PlacementMode.OPENER)
                    && placement.anchorScreenId() != null) {
                children.computeIfAbsent(placement.anchorScreenId(), ignored -> new ArrayList<>())
                        .add(screen.screenId());
            }
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawByScreenId.entrySet()) {
            String screenId = entry.getKey();
            String changed = entry.getValue();
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                changed = changed.replace(alias.getKey(), alias.getValue());
            }
            List<String> childIds = children.getOrDefault(screenId, List.of()).stream().distinct().toList();
            changed = normalizeIdentity(changed, screenId, knownScreenIds, childIds);
            Path path = htmlByScreenId.get(screenId);
            if (!changed.equals(entry.getValue())) {
                before.putIfAbsent(path, entry.getValue());
                Files.writeString(path, changed, StandardCharsets.UTF_8);
            }
            normalized.put(screenId, changed);
        }
        return normalized;
    }

    static String normalizeIdentity(String html, String screenId,
                                    Set<String> knownScreenIds, List<String> childIds) {
        String normalized = replaceAttribute(SCREEN_ID_ATTRIBUTE, html, ignored -> screenId);
        Set<String> usedElementIds = new LinkedHashSet<>();
        int[] next = {1};
        normalized = replaceAttribute(ELEMENT_ID_ATTRIBUTE, normalized, old -> {
            Matcher suffix = Pattern.compile("(?i).*-(e\\d+)$").matcher(old);
            String candidate = suffix.matches() ? screenId + "-" + suffix.group(1).toLowerCase() : null;
            if (candidate == null || !usedElementIds.add(candidate)) {
                do candidate = "%s-e%02d".formatted(screenId, next[0]++);
                while (!usedElementIds.add(candidate));
            }
            return candidate;
        });
        if (usedElementIds.isEmpty()) {
            Matcher interactive = FIRST_INTERACTIVE_TAG.matcher(normalized);
            if (interactive.find()) {
                normalized = interactive.replaceFirst(Matcher.quoteReplacement(
                        "<" + interactive.group(1) + " data-element-id=\"" + screenId + "-e01\""));
            }
        }
        if (childIds.size() == 1) {
            String child = childIds.get(0);
            normalized = replaceAttribute(NAV_TARGET_ATTRIBUTE, normalized,
                    target -> knownScreenIds.contains(target) ? target : child);
        }
        return normalized;
    }

    private static String declaredScreenId(String html) {
        Matcher matcher = SCREEN_ID_ATTRIBUTE.matcher(html);
        return matcher.find() ? matcher.group(3).strip() : null;
    }

    private static String replaceAttribute(Pattern pattern, String html,
                                           java.util.function.Function<String, String> replacement) {
        Matcher matcher = pattern.matcher(html);
        StringBuffer changed = new StringBuffer();
        while (matcher.find()) {
            String value = replacement.apply(matcher.group(3));
            matcher.appendReplacement(changed, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + value + matcher.group(4)));
        }
        matcher.appendTail(changed);
        return changed.toString();
    }

    static List<String> definitionsOf(String html) {
        List<String> definitions = new ArrayList<>();
        for (Element element : Jsoup.parse(html).select("[data-element-id]")) {
            String anchor = element.attr("data-element-id");
            String target = element.attr("data-nav-target");
            String kind = !target.isBlank() ? "이동"
                    : element.is("input,select,textarea") ? "항목" : "기능";
            String coordinate = !element.id().isBlank() ? " / 좌표: id=" + element.id() : "";
            String movement = target.isBlank() ? "" : " / 이동: " + target;
            String description = element.text().replaceAll("\\s+", " ").strip();
            if (description.isBlank()) description = !element.attr("aria-label").isBlank()
                    ? element.attr("aria-label").strip() : element.tagName();
            if (description.length() > 80) description = description.substring(0, 80);
            definitions.add("- 구분: " + kind + coordinate + " / 앵커: " + anchor
                    + movement + " / 해설: " + description);
        }
        return List.copyOf(definitions);
    }

    static String mergeDefinitions(String document, List<String> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return ScreenDefinitionDocument.normalizeStructure(document);
        }
        String body = String.join(System.lineSeparator(), definitions) + System.lineSeparator();
        Matcher matcher = DEFINITION_SECTION.matcher(document);
        if (matcher.find()) {
            return ScreenDefinitionDocument.normalizeStructure(
                    matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + body)));
        }
        return ScreenDefinitionDocument.normalizeStructure(
                document.stripTrailing() + System.lineSeparator() + System.lineSeparator()
                        + "--- 정의 ---" + System.lineSeparator() + body);
    }

    private FrdScreenIaPlacement placementOf(String projectId, String frdId, FrdScreen screen,
                                             List<FrdScreen> rows) {
        FrdScreenIaPlacement placement = placements.of(screen.id());
        if (placement != null && placement.resolved()) return placement;

        FrdScreenIaPlacement.ScreenKind kind = placement == null
                ? FrdScreenIaPlacement.ScreenKind.SCREEN : placement.screenKind();
        FrdScreenIaPlacementService.Request inferred = inferPlacement(
                projectId, frdId, screen, rows, kind);
        if (inferred == null) {
            return placement != null ? placement : placements.save(screen.id(),
                    new FrdScreenIaPlacementService.Request(
                            "UNRESOLVED", null, null, kind.name(), "AI"));
        }
        return placements.save(screen.id(), inferred);
    }

    /** 사람이 IA를 다시 입력하지 않도록 화면 관계·이름·시스템 IA 순서로 배치를 추론한다. */
    private FrdScreenIaPlacementService.Request inferPlacement(
            String projectId, String frdId, FrdScreen screen, List<FrdScreen> rows,
            FrdScreenIaPlacement.ScreenKind kind) {
        if (screen.baseScreenId() != null && !screen.baseScreenId().isBlank()) {
            return relationPlacement(screen.baseScreenId(), kind, "INHERITED");
        }

        String incoming = incomingAnchor(projectId, frdId, screen, rows);
        if (incoming != null) return relationPlacement(incoming, kind, "AI");

        if (kind == FrdScreenIaPlacement.ScreenKind.SCREEN && !isList(screen)) {
            String subject = subjectOf(screen);
            FrdScreen list = rows.stream()
                    .filter(FrdScreen::isNewScreen)
                    .filter(candidate -> candidate != screen)
                    .filter(candidate -> sameSystem(screen, candidate))
                    .filter(FrdScreenIaMaterializer::isList)
                    .filter(candidate -> !subject.isBlank() && subject.equals(subjectOf(candidate)))
                    .findFirst().orElse(null);
            if (list != null) return relationPlacement(list.screenId(), kind, "AI");
        }

        if (kind == FrdScreenIaPlacement.ScreenKind.SCREEN && isList(screen)) {
            String menuPath = inferMenuPath(projectId, frdId, screen);
            if (menuPath != null) {
                return new FrdScreenIaPlacementService.Request(
                        "MENU", null, menuPath, kind.name(), "AI");
            }
        }
        return null;
    }

    private FrdScreenIaPlacementService.Request relationPlacement(
            String anchorScreenId, FrdScreenIaPlacement.ScreenKind kind, String source) {
        return new FrdScreenIaPlacementService.Request(
                kind == FrdScreenIaPlacement.ScreenKind.SCREEN ? "CHILD" : "OPENER",
                anchorScreenId, null, kind.name(), source);
    }

    private String incomingAnchor(String projectId, String frdId, FrdScreen target,
                                  List<FrdScreen> rows) {
        for (FrdScreen source : rows) {
            if (source == target || !sameSystem(source, target)) continue;
            Path document = files.document(projectId, frdId, source.systemCode(), source.screenId());
            if (document == null || !Files.isRegularFile(document)) continue;
            try {
                if (declaresMoveTo(Files.readString(document, StandardCharsets.UTF_8), target.screenId())) {
                    return source.screenId();
                }
            } catch (IOException ignored) {
                // 다른 근거로 계속 추론한다. 실제 확정 단계에서 읽기 실패는 별도로 알린다.
            }
        }
        return null;
    }

    private boolean declaresMoveTo(String document, String targetScreenId) {
        Matcher lines = DEFINITION_LINE.matcher(document);
        while (lines.find()) {
            String line = lines.group();
            for (String field : LINK_FIELDS) {
                if (line.matches("(?s).*\\b" + Pattern.quote(field) + "\\s*:\\s*"
                        + Pattern.quote(targetScreenId)
                        + "(?:\\.[A-Za-z0-9_-]+)?(?:\\s*/|\\s*$).*")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String inferMenuPath(String projectId, String frdId, FrdScreen screen) {
        Path iaDocument = files.iaDocument(projectId, frdId, screen.systemCode());
        if (iaDocument == null || !Files.isRegularFile(iaDocument)) return null;
        String subject = subjectOf(screen);
        if (subject.isBlank()) return null;
        try {
            Matcher labels = IA_LABEL.matcher(Files.readString(iaDocument, StandardCharsets.UTF_8));
            String bestPath = null;
            int bestScore = 0;
            int bestDepth = Integer.MAX_VALUE;
            boolean ambiguous = false;
            while (labels.find()) {
                String label = normalizedSubject(labels.group(2));
                if (label.isBlank()) continue;
                int common = longestCommonSubstring(subject, label);
                if (common < 2) continue;
                int score = (subject.contains(label) || label.contains(subject) ? 1000 : 0)
                        + common * 10;
                String path = labels.group(1);
                int depth = path.split("/").length;
                if (score > bestScore || (score == bestScore && depth < bestDepth)) {
                    bestPath = path;
                    bestScore = score;
                    bestDepth = depth;
                    ambiguous = false;
                } else if (score == bestScore && depth == bestDepth && !path.equals(bestPath)) {
                    ambiguous = true;
                }
            }
            return ambiguous ? null : bestPath;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static boolean isList(FrdScreen screen) {
        return contains(screen.screenType(), "목록") || contains(screen.screenName(), "목록");
    }

    private static String subjectOf(FrdScreen screen) {
        return normalizedSubject(screen.screenName());
    }

    private static String normalizedSubject(String value) {
        if (value == null) return "";
        return value.toLowerCase()
                .replaceAll("목록|상세|조회|등록|수정|관리|화면|팝업|모달|신규|정보|내역", "")
                .replaceAll("[^0-9a-z가-힣]", "");
    }

    private static int longestCommonSubstring(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int best = 0;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            for (int j = 1; j <= right.length(); j++) {
                if (left.charAt(i - 1) == right.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    best = Math.max(best, current[j]);
                }
            }
            previous = current;
        }
        return best;
    }

    private static boolean sameSystem(FrdScreen left, FrdScreen right) {
        return left.systemCode() != null && left.systemCode().equals(right.systemCode());
    }

    private static boolean contains(String value, String token) {
        return value != null && value.contains(token);
    }

    private Resolved resolve(String projectId, String frdId, FrdScreen screen,
                             FrdScreenIaPlacement placement, Map<String, String> systemByScreenId) {
        if (screen.systemCode() == null || screen.systemCode().isBlank()) {
            throw new IllegalStateException(displayName(screen) + " 화면의 시스템을 확인하지 못했습니다.");
        }
        return switch (placement.placementMode()) {
            case MENU -> {
                if (placement.screenKind() != FrdScreenIaPlacement.ScreenKind.SCREEN) {
                    throw new IllegalStateException("팝업·모달은 메뉴에 직접 배치할 수 없습니다: " + displayName(screen));
                }
                if (!IA_PATH.matcher(placement.menuPathKey()).matches()) {
                    throw new IllegalStateException("IA 메뉴 경로의 꼴이 올바르지 않습니다: "
                            + placement.menuPathKey());
                }
                yield new Resolved(screen, placement, "- 종류: 화면", placement.menuPathKey());
            }
            case CHILD -> {
                validateAnchor(screen, placement.anchorScreenId(), systemByScreenId);
                if (placement.screenKind() != FrdScreenIaPlacement.ScreenKind.SCREEN) {
                    throw new IllegalStateException("팝업·모달은 상위화면이 아니라 여는 화면으로 연결해야 합니다: "
                            + displayName(screen));
                }
                yield new Resolved(screen, placement,
                        "- 종류: 화면 / 상위화면: " + placement.anchorScreenId(), null);
            }
            case OPENER -> {
                validateAnchor(screen, placement.anchorScreenId(), systemByScreenId);
                if (placement.screenKind() == FrdScreenIaPlacement.ScreenKind.SCREEN) {
                    throw new IllegalStateException("일반 화면은 여는 화면 방식으로 배치할 수 없습니다: "
                            + displayName(screen));
                }
                if (!hasIncomingRelation(projectId, frdId, screen, placement.anchorScreenId())) {
                    throw new IllegalStateException("여는 화면에서 신규 팝업·모달로 이동하는 연결을 먼저 만들어 주세요: "
                            + displayName(screen));
                }
                yield new Resolved(screen, placement, "- 종류: " + placement.screenKind().label(), null);
            }
            case UNRESOLVED -> throw new IllegalStateException("IA 위치를 확인해 주세요: " + displayName(screen));
        };
    }

    private void validateAnchor(FrdScreen screen, String anchorScreenId,
                                Map<String, String> systemByScreenId) {
        String anchorSystem = systemByScreenId.get(anchorScreenId);
        if (anchorSystem == null) {
            throw new IllegalStateException("IA 기준 화면을 찾지 못했습니다: " + anchorScreenId);
        }
        if (!screen.systemCode().equals(anchorSystem)) {
            throw new IllegalStateException("IA 기준 화면은 신규 화면과 같은 시스템이어야 합니다: "
                    + displayName(screen));
        }
    }

    private boolean hasIncomingRelation(String projectId, String frdId, FrdScreen target,
                                        String openerScreenId) {
        Path opener = files.document(projectId, frdId, target.systemCode(), openerScreenId);
        if (!Files.isRegularFile(opener)) return false;
        try {
            Matcher lines = DEFINITION_LINE.matcher(Files.readString(opener, StandardCharsets.UTF_8));
            while (lines.find()) {
                String line = lines.group();
                for (String field : LINK_FIELDS) {
                    if (line.matches("(?s).*\\b" + Pattern.quote(field) + "\\s*:\\s*"
                            + Pattern.quote(target.screenId()) + "(?:\\.[A-Za-z0-9_-]+)?(?:\\s*/|\\s*$).*")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException unreadable) {
            return false;
        }
    }

    static String mergeIa(String document, String iaLine) {
        String block = "--- IA ---" + System.lineSeparator() + iaLine + System.lineSeparator()
                + System.lineSeparator();
        Matcher matcher = SECTION.matcher(document);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(block));
        }
        int definition = document.indexOf("--- 정의 ---");
        if (definition >= 0) {
            return document.substring(0, definition) + block + document.substring(definition);
        }
        return document.stripTrailing() + System.lineSeparator() + System.lineSeparator() + block;
    }

    static String addMenuPlacement(String document, FrdScreen screen, String parentPath) {
        Matcher placement = PLACEMENT_SECTION.matcher(document);
        if (!placement.find()) {
            throw new IllegalStateException("시스템 IA 문서에서 배치 블록을 찾지 못했습니다.");
        }
        String screenId = screen.screenId();
        if (Pattern.compile("(?m)^- .*?/ 화면:\\s*" + Pattern.quote(screenId) + "\\s*$")
                .matcher(placement.group(2)).find()) {
            return document;
        }
        boolean parentExists = Pattern.compile("(?m)^- " + Pattern.quote(parentPath) + ":\\s*.+$")
                .matcher(document.substring(0, placement.start())).find()
                || Pattern.compile("(?m)^- .*?/ 경로:\\s*" + Pattern.quote(parentPath)
                + "(?:\\s*/|\\s*$)").matcher(placement.group(2)).find();
        if (!parentExists) {
            throw new IllegalStateException("선택한 IA 메뉴 경로를 찾지 못했습니다: " + parentPath);
        }

        String path = parentPath + "/" + screenId;
        String label = screen.screenName() == null || screen.screenName().isBlank()
                ? screenId : screen.screenName().strip();
        String beforePlacement = document.substring(0, placement.start());
        String placementHeader = placement.group(1);
        String rows = placement.group(2).stripTrailing();
        String afterPlacement = document.substring(placement.end());
        String lineBreak = System.lineSeparator();
        String labelLine = "- " + path + ": " + label + lineBreak;
        String rowLine = "- 경로: " + path + " / 화면: " + screenId + lineBreak;
        return beforePlacement.stripTrailing() + lineBreak + labelLine + lineBreak
                + placementHeader + rows + (rows.isBlank() ? "" : lineBreak) + rowLine + afterPlacement;
    }

    private static String minimalDocument(FrdScreen screen) {
        return """
                --- 꼬리표 ---
                id: %s / system: %s / 기능: %s / 과업: []

                --- 화면명세 ---
                화면명: %s
                목적: 신규 화면

                --- 정의 ---

                --- 원본 글 ---
                """.formatted(screen.screenId(), screen.systemCode(), displayName(screen), displayName(screen));
    }

    private void restore(Map<Path, String> before, Exception failure) {
        for (Map.Entry<Path, String> entry : before.entrySet()) {
            try {
                if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
                else Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    private static String displayName(FrdScreen screen) {
        return screen.screenName() == null || screen.screenName().isBlank()
                ? screen.screenId() : screen.screenName();
    }

    private record Resolved(FrdScreen screen, FrdScreenIaPlacement placement, String iaLine,
                            String menuParentPath) { }
}
