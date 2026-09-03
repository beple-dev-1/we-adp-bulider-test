package com.bizplay.builder.solution;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitException;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클론된 기획 저장소에서 ③ 솔루션 목업 목록을 읽어 온다.
 *
 * <p><b>이 저장소가 기획 레포를 읽는 첫 자리다.</b> 메뉴구조도·BRD 의 대상 화면·작업 목업이
 * 뒤에 같은 길을 쓴다.
 *
 * <p>자료가 셋에서 온다 (2026-08-16 실측 · 클론 {@code 9886e9c} · 화면 274장).
 *
 * <table><caption>어디서 무엇이 나오나</caption>
 * <tr><th>{@code index.json}</th><td>화면ID · 시스템 · 종류 · 화면유형 · 유형근거 · 기관 갈래</td></tr>
 * <tr><th>화면 md</th><td>화면명·목적({@code --- 화면명세 ---})·메뉴 경로·주요 기능·연결 화면</td></tr>
 * <tr><th>{@code git log}</th><td>최초 작성일 · 최종 수정일 · 수정자 · 무엇을 고쳤나</td></tr>
 * </table>
 *
 * <p>⛔ <b>여기서 g2c 를 알지 않는다.</b> 읽는 것은 기획 저장소의 <b>산출물</b>(색인과 md)이지
 * 운영 소스가 아니다 — {@code CLAUDE.md} 의 경계는 {@code dino-*} 를 파싱하는 코드를 말한다.
 *
 * <p>⚠ <b>없으면 빈 목록이다. 던지지 않는다.</b> 클론이 아직 안 앉았거나 색인이 없는 프로젝트가
 * 실제로 있다(테스트의 프로젝트가 그렇다). 그때 500 을 내면 <b>메뉴를 눌렀을 뿐인데 화면이
 * 깨진다</b> — 사람이 할 수 있는 일이 없는 실패다.
 */
@Component
public class SolutionScreenReader {

    private static final Logger log = LoggerFactory.getLogger(SolutionScreenReader.class);

    /** 274장의 이력을 한 번에 훑는 한 판이다. 커밋이 늘어도 이 안에 든다. */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 화면 md 의 원본 글 앞까지만 읽는다. 정의가 긴 화면도 이 크기를 넘으면 요약 재료 수집을 멈춘다.
     */
    private static final int MD_SUMMARY_BYTES = 32768;
    private static final int SUMMARY_FUNCTION_LIMIT = 4;
    private static final int SUMMARY_LINK_LIMIT = 3;

    private final ProjectPaths paths;
    private final GitCommand git;
    private final ObjectMapper json;
    private final ProjectFacetMapper projectFacets;

    /**
     * 프로젝트마다 마지막으로 읽은 것.
     *
     * <p>⚠ <b>도장이 바뀌면 다시 읽는다</b> — 클론을 당기면 HEAD 가 바뀌고, 색인만 손으로
     * 고쳐도 파일 시각이 바뀐다. 둘을 같이 도장에 넣은 까닭이다.
     */
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    public SolutionScreenReader(ProjectPaths paths, GitCommand git, ObjectMapper json,
                                ProjectFacetMapper projectFacets) {
        this.paths = paths;
        this.git = git;
        this.json = json;
        this.projectFacets = projectFacets;
    }

    /** 화면ID 오름차순. 클론이나 색인이 없으면 <b>빈 목록</b>이다. */
    public List<SolutionScreen> read(String projectId) {
        Path clone = paths.cloneDir(projectId);
        Path index = clone.resolve("index.json");
        if (!Files.isReadable(index)) {
            return List.of();
        }

        List<ProjectFacet> facets = projectFacets.selectByProjectId(projectId);
        String facetStamp = facets.stream().map(f -> f.code() + "=" + f.name())
                .collect(java.util.stream.Collectors.joining("|"));
        String stamp = stampOf(clone, index) + ":" + facetStamp;
        Snapshot cached = cache.get(projectId);
        if (cached != null && cached.stamp().equals(stamp)) {
            return cached.screens();
        }

        List<SolutionScreen> screens = load(clone, index, facets);
        cache.put(projectId, new Snapshot(stamp, screens));
        return screens;
    }

    /** 클론 안의 파일 하나를 가리키는 절대 경로. 미리보기가 쓴다. */
    public Path fileInClone(String projectId, String relativeToCore) {
        return paths.cloneDir(projectId).resolve("core").resolve(relativeToCore);
    }

    /** 미리보기가 클론 밖으로 못 나가게 막는 울타리. */
    public Path coreRoot(String projectId) {
        return paths.cloneDir(projectId).resolve("core");
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    private List<SolutionScreen> load(Path clone, Path index, List<ProjectFacet> facets) {
        JsonNode root;
        try {
            root = json.readTree(Files.readString(index));
        } catch (IOException | RuntimeException broken) {
            // ⚠ 색인이 깨졌다고 화면을 깨뜨리지 않는다 — 고칠 사람은 기획팀이고 여기서 할 일이 없다.
            log.warn("기획 저장소 색인을 못 읽었다: {}", index, broken);
            return List.of();
        }

        JsonNode screens = root.path("screens");
        if (!screens.isObject()) {
            return List.of();
        }

        Map<String, List<String>> variantsByScreen = variantsByScreen(root.path("variantIndex"));
        Map<String, String> facetByScreen = facetByScreen(root.path("facetIndex"));
        Map<String, String> facetNames = facets.stream()
                .collect(java.util.stream.Collectors.toMap(ProjectFacet::code, ProjectFacet::name));
        List<String> projectFacetNames = facets.stream().map(ProjectFacet::name).toList();
        Set<String> sharedScreens = sharedScreens(root.path("iaShared"));
        Map<String, ScreenHistory> historyByScreen = historyByScreen(clone);
        Map<String, Md> mdByScreen = new HashMap<>();
        screens.fields().forEachRemaining(entry -> {
            String screenId = entry.getKey();
            String system = entry.getValue().path("system").asText("");
            mdByScreen.put(screenId, readMd(clone, system, screenId));
        });

        List<SolutionScreen> found = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = screens.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String screenId = entry.getKey();
            String system = entry.getValue().path("system").asText("");
            // ⚠ 색인의 ia 칸은 한글 열쇠다 — 남의 규격이라 우리가 이름을 바꾸지 않는다.
            String kind = entry.getValue().path("ia").path("종류").asText("화면");
            String facetCode = facetByScreen.get(screenId);
            String facetName = facetCode == null ? null : labelOf(facetCode, facetNames);
            List<SolutionVariant> variants = variantsByScreen.getOrDefault(screenId, List.of()).stream()
                    .map(code -> new SolutionVariant(code, labelOf(code, facetNames)))
                    .toList();
            JsonNode ia = entry.getValue().path("ia");
            String screenType = ia.path("화면유형").asText("미분류");
            String typeSource = blankToNull(ia.path("유형근거").asText(""));

            Md md = mdByScreen.get(screenId);
            found.add(new SolutionScreen(
                    screenId,
                    md.screenName().isBlank() ? screenId : md.screenName(),
                    summaryOf(md, mdByScreen),
                    system,
                    kind,
                    screenType,
                    typeSource,
                    md.menuPath(),
                    blankToNull(ia.path("경로").asText("")),
                    facetCode,
                    facetName,
                    variants,
                    projectFacetNames,
                    blankToNull(ia.path("상위화면").asText("")),
                    textArray(ia.path("여는화면")),
                    sharedScreens.contains(system + "/" + screenId),
                    historyByScreen.getOrDefault(screenId, ScreenHistory.EMPTY)));
        }

        found.sort(Comparator.comparing(SolutionScreen::screenId));
        return List.copyOf(found);
    }

    private Set<String> sharedScreens(JsonNode iaShared) {
        Set<String> result = new HashSet<>();
        if (!iaShared.isObject()) return result;
        iaShared.fields().forEachRemaining(entry -> {
            for (JsonNode id : entry.getValue()) result.add(entry.getKey() + "/" + id.asText());
        });
        return result;
    }

    private List<String> textArray(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (!value.asText("").isBlank()) result.add(value.asText());
        });
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 색인은 {@code 기관 → 화면들}로 담는다. 화면 쪽에서 물어야 하니 뒤집는다. */
    private Map<String, List<String>> variantsByScreen(JsonNode variantIndex) {
        Map<String, List<String>> byScreen = new HashMap<>();
        if (!variantIndex.isObject()) {
            return byScreen;
        }
        Iterator<Map.Entry<String, JsonNode>> it = variantIndex.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            for (JsonNode screenId : entry.getValue()) {
                byScreen.computeIfAbsent(screenId.asText(), any -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }
        byScreen.values().forEach(java.util.Collections::sort);
        return byScreen;
    }

    /** 색인의 {@code 적용 구분 → 전용 화면들}을 화면 기준으로 뒤집는다. */
    private Map<String, String> facetByScreen(JsonNode facetIndex) {
        Map<String, String> byScreen = new HashMap<>();
        if (!facetIndex.isObject()) {
            return byScreen;
        }
        facetIndex.fields().forEachRemaining(entry -> {
            for (JsonNode screenId : entry.getValue()) {
                byScreen.put(screenId.asText(), entry.getKey());
            }
        });
        return byScreen;
    }

    private String labelOf(String code, Map<String, String> facetNames) {
        return facetNames.getOrDefault(code, code + " (연결 필요)");
    }

    /**
     * 화면 md 머리에서 화면명, 화면 목적, 메뉴 경로를 뗀다.
     *
     * <p>⚠ 없으면 빈 값이다 — 색인에 있는데 md 가 없는 화면이 생길 수 있고, 그때도 목록에는 떠야 한다.
     */
    private Md readMd(Path clone, String system, String screenId) {
        Path md = clone.resolve("core").resolve(system).resolve("pages").resolve(screenId + ".md");
        String head;
        try (var stream = Files.newInputStream(md)) {
            byte[] buffer = stream.readNBytes(MD_SUMMARY_BYTES);
            head = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return new Md("", "", "", List.of(), List.of());
        }

        String screenName = "";
        String purpose = "";
        String menuPath = "";
        List<String> functions = new ArrayList<>();
        List<MdLink> links = new ArrayList<>();
        for (String line : head.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals("--- 원본 글 ---")) {
                break;
            }
            if (screenName.isEmpty() && trimmed.startsWith("화면명:")) {
                screenName = trimmed.substring("화면명:".length()).trim();
            } else if (purpose.isEmpty() && trimmed.startsWith("목적:")) {
                purpose = trimmed.substring("목적:".length()).trim();
            } else if (menuPath.isEmpty() && trimmed.startsWith("id:")) {
                menuPath = fieldOf(trimmed, "기능");
            }

            if (trimmed.startsWith("- ")) {
                String kind = fieldOf(trimmed.substring(2), "구분");
                String explanation = fieldOf(trimmed.substring(2), "해설");
                if ("기능".equals(kind)) {
                    String action = conciseAction(explanation);
                    if (!action.isBlank() && !functions.contains(action) && functions.size() < SUMMARY_FUNCTION_LIMIT) {
                        functions.add(action);
                    }
                } else if ("이동".equals(kind) && links.size() < SUMMARY_LINK_LIMIT) {
                    String targetId = fieldOf(trimmed.substring(2), "이동");
                    if (!targetId.isBlank() && links.stream().noneMatch(link -> link.targetId().equals(targetId))) {
                        links.add(new MdLink(targetId, linkLabel(explanation)));
                    }
                }
            }
        }
        return new Md(screenName, purpose, menuPath, List.copyOf(functions), List.copyOf(links));
    }

    private String summaryOf(Md md, Map<String, Md> mdByScreen) {
        List<String> lines = new ArrayList<>();
        if (!md.purpose().isBlank()) lines.add(md.purpose());
        if (!md.functions().isEmpty()) lines.add("주요 기능: " + String.join(" · ", md.functions()));
        List<String> linkedNames = md.links().stream()
                .map(link -> {
                    Md target = mdByScreen.get(link.targetId());
                    if (target != null && !target.screenName().isBlank()) return target.screenName();
                    return link.label().isBlank() ? null : link.label();
                })
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (!linkedNames.isEmpty()) lines.add("연결 화면: " + String.join(" · ", linkedNames));
        return String.join("\n", lines);
    }

    private String conciseAction(String explanation) {
        if (explanation == null || explanation.isBlank()
                || explanation.contains("닫기") || explanation.contains("취소")) {
            return "";
        }
        String result = before(explanation, " (");
        result = before(result, " = ").trim();
        for (String suffix : List.of(" 팝업 열기", " 확인", " 실행", "하기")) {
            if (result.endsWith(suffix)) result = result.substring(0, result.length() - suffix.length()).trim();
        }
        result = result.replace("**", "").trim();
        return result.length() > 60 || result.contains("`") ? "" : result;
    }

    private String linkLabel(String explanation) {
        if (explanation == null) return "";
        int arrow = explanation.indexOf("→");
        if (arrow < 0) return "";
        String label = before(explanation.substring(arrow + 1).trim(), " (");
        label = label.replaceFirst("(?:로\\s*)?이동.*$", "").trim();
        return label.length() > 60 || label.contains("`") ? "" : label;
    }

    private String before(String value, String delimiter) {
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    /**
     * 꼬리표 한 줄에서 칸 하나를 뗀다.
     *
     * <p>실물: {@code id: wv-card-list / system: webview / 기능: 카드 > 보유 카드 조회 / 과업: []}
     * <p>⚠ 칸 사이는 {@code " / "} 이고 메뉴 경로 안은 {@code ">"} 다 — 그래서 갈라도 안 부딪힌다.
     */
    private String fieldOf(String tagLine, String key) {
        for (String part : tagLine.split(" / ")) {
            String piece = part.trim();
            if (piece.startsWith(key + ":")) {
                return piece.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    // ── 이력 ──────────────────────────────────────────────────────────────

    /**
     * 클론의 {@code git log} 를 <b>한 번</b> 훑어 화면마다 이력을 붙인다.
     *
     * <p>⛔ <b>화면마다 {@code git log} 를 부르지 마라</b> — 274장이면 프로세스를 274번 띄운다.
     * 한 판에 훑고 나눠 담는 것이 같은 결과에 1/274 값이다.
     *
     * <p>⚠ <b>실패하면 빈 지도다.</b> 클론이 얕거나 git 이 없어도 화면은 떠야 한다.
     */
    private Map<String, ScreenHistory> historyByScreen(Path clone) {
        GitResult result;
        try {
            result = git.run(clone, GIT_TIMEOUT,
                    "log", "--date=short", "--format=@@@%ad%x09%an%x09%s", "--name-only", "--", "core");
        } catch (GitException failed) {
            log.debug("클론의 수정 이력을 못 읽었다: {}", clone, failed);
            return Map.of();
        }
        if (result.exitCode() != 0) {
            return Map.of();
        }

        Map<String, List<ScreenHistory.Change>> changes = new HashMap<>();
        ScreenHistory.Change current = null;
        Set<String> seenInCommit = new HashSet<>();

        for (String line : result.stdout().split("\\R")) {
            if (line.startsWith("@@@")) {
                current = parseHeader(line.substring(3));
                seenInCommit = new HashSet<>();
                continue;
            }
            if (current == null || line.isBlank()) {
                continue;
            }
            String screenId = screenIdOf(line.trim());
            // ⚠ 한 커밋이 같은 화면의 md 와 html 을 같이 고친다 — 그러면 이력에 같은 줄이 둘 뜬다.
            if (screenId != null && seenInCommit.add(screenId)) {
                changes.computeIfAbsent(screenId, any -> new ArrayList<>()).add(current);
            }
        }

        Map<String, ScreenHistory> byScreen = new HashMap<>();
        changes.forEach((screenId, list) -> byScreen.put(screenId, new ScreenHistory(List.copyOf(list))));
        return byScreen;
    }

    private ScreenHistory.Change parseHeader(String header) {
        String[] parts = header.split("\t", 3);
        if (parts.length < 3) {
            return null;
        }
        try {
            return new ScreenHistory.Change(LocalDate.parse(parts[0]), parts[1], parts[2]);
        } catch (DateTimeParseException odd) {
            return null;
        }
    }

    /**
     * 바뀐 파일 하나가 어느 화면의 것인가.
     *
     * <p>{@code core/backoffice/pages/bo-bizcard-list.md} → {@code bo-bizcard-list}.
     * 기관 갈래({@code variants-jeju/}) 도 같은 화면으로 센다 — 사람에게는 한 화면이다.
     *
     * <p>⚠ 화면ID 인지는 <b>확인하지 않는다.</b> 색인에 없는 이름은 부르는 쪽에서 안 찾아
     * 저절로 버려진다 — 여기서 색인을 또 들고 다니면 두 자리가 갈린다.
     */
    private String screenIdOf(String path) {
        if (!path.startsWith("core/")) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String extension = name.substring(dot + 1);
        if (!extension.equals("md") && !extension.equals("html")) {
            return null;
        }
        return name.substring(0, dot);
    }

    // ── 도장 ──────────────────────────────────────────────────────────────

    /**
     * 「지금 클론이 어느 상태인가」를 한 글자로 만든다.
     *
     * <p>⚠ HEAD 하나로는 부족하다 — 커밋 안 한 채 색인만 바꿔 넣는 일이 실제로 있다
     * (기획 레포 클론을 손으로 만져 보는 자리). 파일 시각을 같이 넣어 그것도 잡는다.
     */
    private String stampOf(Path clone, Path index) {
        String head = "";
        try {
            GitResult result = git.run(clone, GIT_TIMEOUT, "rev-parse", "HEAD");
            if (result.exitCode() == 0) {
                head = result.stdout().trim();
            }
        } catch (GitException noGit) {
            log.debug("클론의 HEAD 를 못 읽었다: {}", clone);
        }
        try {
            return head + ":" + Files.getLastModifiedTime(index).toMillis();
        } catch (IOException gone) {
            return head;
        }
    }

    private record Snapshot(String stamp, List<SolutionScreen> screens) {
    }

    private record Md(String screenName, String purpose, String menuPath,
                      List<String> functions, List<MdLink> links) {
    }

    private record MdLink(String targetId, String label) {
    }
}
