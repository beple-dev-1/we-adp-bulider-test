package com.bizplay.builder.frd;

import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 전체 캔버스에서 사람이 화면 이동 관계를 추가·수정·삭제한다. */
@Service
public class FrdCanvasRelationService {

    private static final Set<String> LINK_FIELDS = Set.of("이동", "이동modal", "이동native", "이동cross");
    private static final Pattern DEFINITION_LINE = Pattern.compile("(?m)^- .*?(?:\\R|$)");
    private static final Pattern SAFE_ANCHOR = Pattern.compile(
            "^(?:[A-Za-z0-9._:#-]+|\\{\\{draftKey\\}\\}[A-Za-z0-9._:#-]*)$");

    private final FrdMapper frds;
    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final ProjectPaths paths;
    private final SolutionScreenReader solutions;
    private final ScreenMockupService mockups;
    private final FrdScreenIaPlacementService iaPlacements;

    public FrdCanvasRelationService(FrdMapper frds, FrdScreenMapper screens,
                                    FrdScreenHistoryMapper histories, ProjectPaths paths,
                                    SolutionScreenReader solutions, ScreenMockupService mockups,
                                    FrdScreenIaPlacementService iaPlacements) {
        this.frds = frds;
        this.screens = screens;
        this.histories = histories;
        this.paths = paths;
        this.solutions = solutions;
        this.mockups = mockups;
        this.iaPlacements = iaPlacements;
    }

    @Transactional
    public void save(String projectId, String frdId,
                     String originalSource, String originalTarget, String originalAnchor,
                     String sourceId, String targetId, String anchorValue) {
        Frd frd = draftingFrd(projectId, frdId);
        String source = requiredScreenId(sourceId);
        String target = requiredScreenId(targetId);
        String anchor = requiredAnchor(anchorValue);
        if (source.equals(target)) throw new IllegalArgumentException("서로 다른 화면을 선택해 주세요.");

        Map<String, FrdScreen> work = workScreens(frdId);
        FrdScreen sourceScreen = editableSource(work, source);
        String label = automaticLabel(targetName(projectId, work, target));

        boolean editing = hasText(originalSource) || hasText(originalTarget) || hasText(originalAnchor);
        Map<FrdScreen, String> before = new LinkedHashMap<>();
        Map<FrdScreen, String> after = new LinkedHashMap<>();
        if (editing) {
            String oldSource = requiredScreenId(originalSource);
            String oldTarget = requiredScreenId(originalTarget);
            String oldAnchor = requiredAnchor(originalAnchor);
            FrdScreen oldSourceScreen = editableSource(work, oldSource);
            String oldMd = readMd(projectId, frdId, frd, oldSourceScreen);
            before.put(oldSourceScreen, oldMd);
            after.put(oldSourceScreen, removeRelation(oldMd, oldTarget, oldAnchor));
        }

        String sourceMd = after.containsKey(sourceScreen)
                ? after.get(sourceScreen) : readMd(projectId, frdId, frd, sourceScreen);
        before.putIfAbsent(sourceScreen, sourceMd);
        if (!declaresAnchor(sourceMd, anchor)) {
            throw new IllegalArgumentException("선택한 클릭 요소를 원본 화면 정의에서 찾지 못했습니다.");
        }
        if (findRelation(sourceMd, target, anchor) != null) {
            throw new IllegalArgumentException("같은 클릭 요소에 동일한 화면 연결이 이미 있습니다.");
        }
        after.put(sourceScreen, appendRelation(sourceMd, target, anchor, label));

        if (before.equals(after)) throw new IllegalArgumentException("변경할 화면 연결 내용이 없습니다.");
        persist(projectId, frdId, frd, before, after,
                editing ? "화면 연결을 변경했습니다." : "화면 연결을 추가했습니다.");
    }

    @Transactional
    public void delete(String projectId, String frdId,
                       String sourceId, String targetId, String anchorValue) {
        Frd frd = draftingFrd(projectId, frdId);
        String source = requiredScreenId(sourceId);
        String target = requiredScreenId(targetId);
        String anchor = requiredAnchor(anchorValue);
        FrdScreen sourceScreen = editableSource(workScreens(frdId), source);
        String md = readMd(projectId, frdId, frd, sourceScreen);
        Map<FrdScreen, String> before = Map.of(sourceScreen, md);
        Map<FrdScreen, String> after = Map.of(sourceScreen, removeRelation(md, target, anchor));
        persist(projectId, frdId, frd, before, after, "화면 연결을 삭제했습니다.");
    }

    private Frd draftingFrd(String projectId, String frdId) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || !frd.projectId().equals(projectId)) {
            throw new IllegalArgumentException("FRD 작업을 찾을 수 없습니다.");
        }
        if (frd.state() != Frd.State.DRAFTING) {
            throw new IllegalStateException("FRD를 수정하는 중에만 화면 연결을 변경할 수 있습니다.");
        }
        return frd;
    }

    private Map<String, FrdScreen> workScreens(String frdId) {
        Map<String, FrdScreen> result = new LinkedHashMap<>();
        screens.selectByFrdId(frdId).forEach(screen -> result.put(screen.screenId(), screen));
        return result;
    }

    private FrdScreen editableSource(Map<String, FrdScreen> work, String screenId) {
        FrdScreen screen = work.get(screenId);
        if (screen == null) throw new IllegalArgumentException("연결이 시작되는 화면은 작업 대상이어야 합니다.");
        if (screen.state() == FrdScreen.State.GENERATING) {
            throw new IllegalStateException("AI 초안을 만드는 중인 화면의 연결은 변경할 수 없습니다.");
        }
        return screen;
    }

    private String targetName(String projectId, Map<String, FrdScreen> work, String target) {
        FrdScreen workTarget = work.get(target);
        if (workTarget != null && hasText(workTarget.screenName())) return workTarget.screenName().strip();
        return solutions.read(projectId).stream()
                .filter(screen -> screen.screenId().equals(target))
                .map(screen -> hasText(screen.screenName()) ? screen.screenName().strip() : target)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이동할 화면을 찾을 수 없습니다."));
    }

    private String automaticLabel(String targetName) {
        String label = (targetName + " 열기").replaceAll("\\s+", " ").replace(" / ", " · ").strip();
        return label.length() <= 120 ? label : label.substring(0, 119).strip() + "…";
    }

    private String readMd(String projectId, String frdId, Frd frd, FrdScreen screen) {
        Path md = file(projectId, frdId, frd, screen, ".md");
        try {
            return Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("화면 연결 정보를 읽지 못했습니다.", failure);
        }
    }

    private Path file(String projectId, String frdId, Frd frd, FrdScreen screen, String extension) {
        String system = hasText(screen.systemCode()) ? screen.systemCode() : frd.systemCode();
        if (!hasText(system) || !system.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalStateException("화면 파일의 시스템을 확인하지 못했습니다.");
        }
        Path core = paths.frdWorktree(projectId, frdId).resolve("core").toAbsolutePath().normalize();
        Path target = core.resolve(system).resolve("pages").resolve(screen.screenId() + extension).normalize();
        if (!target.startsWith(core) || !Files.isRegularFile(target)) {
            throw new IllegalStateException("화면 연결 파일을 작업공간에서 찾지 못했습니다.");
        }
        try {
            if (!target.toRealPath().startsWith(core.toRealPath())) {
                throw new IllegalStateException("화면 연결 파일 경로가 작업공간을 벗어났습니다.");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("화면 연결 파일 경로를 확인하지 못했습니다.", failure);
        }
        return target;
    }

    private void persist(String projectId, String frdId, Frd frd,
                         Map<FrdScreen, String> before, Map<FrdScreen, String> after,
                         String change) {
        try {
            for (Map.Entry<FrdScreen, String> entry : after.entrySet()) {
                Path md = file(projectId, frdId, frd, entry.getKey(), ".md");
                Files.writeString(md, entry.getValue(), StandardCharsets.UTF_8);
            }
            for (Map.Entry<FrdScreen, String> entry : after.entrySet()) {
                FrdScreen screen = entry.getKey();
                Path htmlFile = file(projectId, frdId, frd, screen, ".html");
                String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
                long historyId = mockups.markGenerated(screen.id(),
                        new ScreenMockupReader.Mockup(html, List.of(change)));
                histories.fillMd(historyId, entry.getValue());
            }
        } catch (IOException failure) {
            IllegalStateException rejected = new IllegalStateException("화면 연결을 작업공간에 저장하지 못했습니다.", failure);
            restore(projectId, frdId, frd, before, rejected);
            throw rejected;
        } catch (RuntimeException failure) {
            restore(projectId, frdId, frd, before, failure);
            throw failure;
        }
    }

    private void restore(String projectId, String frdId, Frd frd,
                         Map<FrdScreen, String> before, RuntimeException failure) {
        for (Map.Entry<FrdScreen, String> entry : before.entrySet()) {
            try {
                Files.writeString(file(projectId, frdId, frd, entry.getKey(), ".md"),
                        entry.getValue(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    private String appendRelation(String md, String target, String anchor, String label) {
        if (!md.contains("--- 정의 ---")) {
            throw new IllegalStateException("원본 화면의 정의 영역을 찾지 못했습니다.");
        }
        String line = "- 구분: 이동 / 앵커: %s / 이동: %s / 라벨: %s".formatted(anchor, target, label);
        int boundary = md.indexOf("--- 원본 글 ---");
        if (boundary < 0) return md.stripTrailing() + System.lineSeparator() + line + System.lineSeparator();
        String head = md.substring(0, boundary).stripTrailing();
        String tail = md.substring(boundary).stripLeading();
        return head + System.lineSeparator() + line + System.lineSeparator() + System.lineSeparator() + tail;
    }

    private String removeRelation(String md, String target, String anchor) {
        Match found = findRelation(md, target, anchor);
        if (found == null) throw new IllegalArgumentException("변경할 화면 연결을 찾지 못했습니다.");
        return md.substring(0, found.start()) + md.substring(found.end());
    }

    private Match findRelation(String md, String target, String anchor) {
        Matcher matcher = DEFINITION_LINE.matcher(md);
        while (matcher.find()) {
            Map<String, String> fields = fields(matcher.group().strip());
            if (!"이동".equals(fields.get("구분")) || !anchor.equals(fields.get("앵커"))) continue;
            for (String link : LINK_FIELDS) {
                if (target.equals(fields.get(link))) return new Match(matcher.start(), matcher.end());
            }
        }
        return null;
    }

    private boolean declaresAnchor(String md, String anchor) {
        Matcher matcher = DEFINITION_LINE.matcher(md);
        while (matcher.find()) {
            if (anchor.equals(fields(matcher.group().strip()).get("앵커"))) return true;
        }
        return false;
    }

    private Map<String, String> fields(String line) {
        String content = line.startsWith("- ") ? line.substring(2) : line;
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : content.split(" / ")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            result.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
        }
        return result;
    }

    private String requiredScreenId(String value) {
        String id = value == null ? "" : value.strip();
        if (id.isBlank() || id.length() > 255 || id.contains("/") || id.contains("\\")) {
            throw new IllegalArgumentException("연결할 화면을 확인해 주세요.");
        }
        return id;
    }

    private String requiredAnchor(String value) {
        String anchor = value == null ? "" : value.strip();
        if (anchor.length() > 255 || !SAFE_ANCHOR.matcher(anchor).matches()) {
            throw new IllegalArgumentException("클릭 요소를 선택해 주세요.");
        }
        return anchor;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Match(int start, int end) { }
}
