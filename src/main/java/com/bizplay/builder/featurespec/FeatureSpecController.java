package com.bizplay.builder.featurespec;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.bizplay.builder.solution.SolutionScreen;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 기능명세서 목록, 자동 생성 상세, 상태 확인과 인쇄 문서. */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/functional-specs")
public class FeatureSpecController {
    private static final String ARTIFACT_KEY = "functional-specs";
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int PAGE_WINDOW = 10;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final FeatureSpecService screens;
    private final FeatureSpecMapper specs;
    private final FeatureSpecWorker worker;
    private final FeatureSpecRenderer renderer;
    private final ProjectSystemService projectSystems;
    private final ScreenStandardIdMapper standardIds;

    public FeatureSpecController(FeatureSpecService screens, FeatureSpecMapper specs,
                                 FeatureSpecWorker worker, FeatureSpecRenderer renderer,
                                 ProjectSystemService projectSystems, ScreenStandardIdMapper standardIds) {
        this.screens = screens; this.specs = specs; this.worker = worker; this.renderer = renderer;
        this.projectSystems = projectSystems; this.standardIds = standardIds;
    }

    @GetMapping
    public String list(@PathVariable String projectId, @RequestParam(required = false) String query,
                       @RequestParam(required = false) String system, @RequestParam(required = false) String spec,
                       @RequestParam(required = false) String selectedSystem,
                       @RequestParam(required = false) String selectedScreen,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       @AuthenticationPrincipal BuilderUser me, Model model) {
        List<SolutionScreen> all = screens.screens(projectId);
        SystemLabels labels = projectSystems.labels(projectId);
        Map<String, FeatureSpecCurrent> saved = specs.selectByProject(projectId).stream()
                .collect(Collectors.toMap(item -> key(item.systemCode(), item.screenId()), item -> item));
        Map<String, FeatureSpecRevision> revisions = specs.selectCurrentRevisionsByProject(projectId).stream()
                .collect(Collectors.toMap(item -> key(item.systemCode(), item.screenId()), item -> item));
        Map<String, String> managementNumbers = standardIds.selectByProject(projectId).stream()
                .collect(Collectors.toMap(row -> row.screenId(),
                        row -> StandardScreenIdFormat.display(row.standardId(), row.origin())));
        List<Row> allRows = all.stream().map(screen -> new Row(screen, labels.label(screen.system()),
                        managementNumbers.getOrDefault(screen.screenId(), "—"),
                        saved.get(key(screen.system(), screen.screenId())),
                        revisions.get(key(screen.system(), screen.screenId()))))
                .sorted(Comparator.comparing(row -> row.screen().screenId())).toList();
        List<Row> matched = allRows.stream().filter(row -> matchesQuery(row, query))
                .filter(row -> !set(system) || system.equals(row.screen().system()))
                .filter(row -> !set(spec) || spec.equals(row.statusLabel())).toList();
        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int currentPage = Math.min(Math.max(page, 1), pageCount);
        model.addAttribute("rows", matched.stream().skip((long) (currentPage - 1) * size).limit(size).toList());
        model.addAttribute("totalCount", allRows.size()); model.addAttribute("matchedCount", matched.size());
        model.addAttribute("readyCount", allRows.stream().filter(Row::hasRevision).count());
        model.addAttribute("page", currentPage); model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(currentPage, pageCount));
        model.addAttribute("pageSize", size); model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("systems", all.stream().map(screen -> new SystemOption(screen.system(), labels.label(screen.system())))
                .distinct().sorted(Comparator.comparing(SystemOption::label)).toList());
        model.addAttribute("specStates", allRows.stream().map(Row::statusLabel).distinct().sorted().toList());
        model.addAttribute("query", query); model.addAttribute("systemFilter", system); model.addAttribute("specFilter", spec);
        model.addAttribute("layerOpen", false);
        model.addAttribute("layerSystemCode", ""); model.addAttribute("layerScreenId", "");
        model.addAttribute("screenTitle", ""); model.addAttribute("systemLabel", ""); model.addAttribute("managementNumber", "-");
        model.addAttribute("documentState", DocumentState.PREPARING); model.addAttribute("hasDocument", false);
        model.addAttribute("polling", false); model.addAttribute("printable", false); model.addAttribute("failureMessage", "");
        if (set(selectedSystem) && set(selectedScreen)) {
            populateLayer(projectId, selectedSystem, selectedScreen, me, model);
        }
        shell(model, "기능명세서"); return "artifacts/feature-specs";
    }

    /** 이전 상세 주소는 목록 위 레이어를 여는 주소로 바꾼다. */
    @GetMapping("/{systemCode}/{screenId}")
    public String detail(@PathVariable String projectId, @PathVariable String systemCode,
                         @PathVariable String screenId, RedirectAttributes redirect) {
        screenOf(projectId, systemCode, screenId);
        redirect.addAttribute("selectedSystem", systemCode);
        redirect.addAttribute("selectedScreen", screenId);
        return "redirect:/projects/" + projectId + "/artifacts/functional-specs";
    }

    /** 예전 화면 ID 주소는 같은 ID가 프로젝트에서 하나일 때만 새 주소로 보낸다. */
    @GetMapping("/{screenId}")
    public String legacyDetail(@PathVariable String projectId, @PathVariable String screenId) {
        List<SolutionScreen> matched = screens.screens(projectId).stream()
                .filter(screen -> screenId.equals(screen.screenId())).toList();
        if (matched.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 화면을 하나로 식별할 수 없습니다.");
        return "redirect:/projects/" + projectId + "/artifacts/functional-specs"
                + "?selectedSystem=" + matched.get(0).system() + "&selectedScreen=" + screenId;
    }

    @GetMapping("/{systemCode}/{screenId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String projectId, @PathVariable String systemCode,
                                                       @PathVariable String screenId) {
        screenOf(projectId, systemCode, screenId); DocumentView view = view(projectId, systemCode, screenId);
        return ResponseEntity.ok(Map.of("state", view.state().name(), "complete",
                view.state() != DocumentState.PREPARING && view.state() != DocumentState.REFRESHING));
    }

    @GetMapping("/{systemCode}/{screenId}/print")
    public ResponseEntity<String> print(@PathVariable String projectId, @PathVariable String systemCode,
                                        @PathVariable String screenId) {
        SolutionScreen screen = screenOf(projectId, systemCode, screenId); DocumentView view = view(projectId, systemCode, screenId);
        if (view.state() != DocumentState.READY || view.revision() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "최신 기능명세서가 준비된 뒤 인쇄할 수 있습니다.");
        String meta = projectSystems.labels(projectId).label(systemCode) + " · " + screenId + " · 개정 " + view.revision().revisionNo();
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(renderer.renderStandalone(displayName(screen), meta, view.revision().documentHtml()));
    }

    private DocumentView view(String projectId, String systemCode, String screenId) {
        FeatureSpecCurrent current = specs.selectCurrent(projectId, systemCode, screenId).orElse(null);
        FeatureSpecRevision revision = current == null || !current.hasRevision() ? null : specs.selectRevision(current.currentRevisionId()).orElse(null);
        if (current == null) return new DocumentView(DocumentState.PREPARING, null, null);
        if (current.state() == FeatureSpecState.RUNNING)
            return new DocumentView(revision == null ? DocumentState.PREPARING : DocumentState.REFRESHING, current, revision);
        if (current.state() == FeatureSpecState.FAILED)
            return new DocumentView(revision == null ? DocumentState.FAILED_EMPTY : DocumentState.FAILED_STALE, current, revision);
        return new DocumentView(revision == null ? DocumentState.PREPARING : DocumentState.READY, current, revision);
    }

    /** 목록의 현재 검색·페이지 상태를 유지한 채 기능명세서 레이어만 채운다. */
    private void populateLayer(String projectId, String systemCode, String screenId, BuilderUser me, Model model) {
        SolutionScreen screen = screenOf(projectId, systemCode, screenId);
        worker.requestIfNeeded(projectId, systemCode, screenId, me == null ? null : me.accountId());
        DocumentView view = view(projectId, systemCode, screenId);
        model.addAttribute("layerOpen", true);
        model.addAttribute("layerSystemCode", systemCode); model.addAttribute("layerScreenId", screenId);
        model.addAttribute("screen", screen); model.addAttribute("screenTitle", displayName(screen));
        model.addAttribute("systemLabel", projectSystems.labels(projectId).label(systemCode));
        model.addAttribute("managementNumber", managementNumber(projectId, screenId));
        model.addAttribute("documentState", view.state()); model.addAttribute("document", view.revision());
        model.addAttribute("hasDocument", view.revision() != null);
        model.addAttribute("polling", view.state() == DocumentState.PREPARING || view.state() == DocumentState.REFRESHING);
        model.addAttribute("printable", view.state() == DocumentState.READY);
        model.addAttribute("failureMessage", failureMessage(view.current()));
    }

    private SolutionScreen screenOf(String projectId, String systemCode, String screenId) {
        return screens.screens(projectId).stream().filter(screen -> systemCode.equals(screen.system()) && screenId.equals(screen.screenId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그 시스템에 해당 화면이 없습니다."));
    }
    private String managementNumber(String projectId, String screenId) {
        return standardIds.selectByProject(projectId).stream()
                .filter(row -> screenId.equals(row.screenId()))
                .map(row -> StandardScreenIdFormat.display(row.standardId(), row.origin()))
                .findFirst().orElse("-");
    }
    private boolean matchesQuery(Row row, String query) {
        if (!set(query)) return true; String needle = query.strip().toLowerCase(Locale.ROOT);
        return lower(row.displayName()).contains(needle) || lower(row.screen().screenId()).contains(needle)
                || lower(row.screen().menuPath()).contains(needle) || lower(row.managementNumber()).contains(needle);
    }
    private List<Integer> pageNumbers(int page, int count) {
        int first = Math.max(1, Math.min(page - PAGE_WINDOW / 2, count - PAGE_WINDOW + 1));
        return IntStream.rangeClosed(first, Math.min(count, first + PAGE_WINDOW - 1)).boxed().toList();
    }
    private String failureMessage(FeatureSpecCurrent current) {
        if (current == null || current.state() != FeatureSpecState.FAILED) return "";
        return switch (current.failedReason() == null ? "" : current.failedReason()) {
            case "NO_CREDENTIAL" -> "문서 생성 연결을 사용할 수 없습니다. 관리자에게 Builder AI 연결 상태를 확인해 달라고 요청해 주세요.";
            case "MISSING_MD", "MISSING_HTML", "MISSING_SCREEN" -> "기능명세서 작성에 필요한 화면 자료가 없습니다. 기획 저장소 갱신 상태를 확인해 주세요.";
            case "SOURCE_CHANGED" -> "문서를 작성하는 동안 화면 자료가 변경되었습니다. 최신 자료로 다시 준비합니다.";
            default -> "기능명세서를 준비하지 못했습니다. 잠시 뒤 상세 화면을 다시 열어 주세요.";
        };
    }
    private void shell(Model model, String title) { model.addAttribute("title", title); model.addAttribute("shape", "산출물"); model.addAttribute("current", ARTIFACT_KEY); }
    private static String key(String system, String screen) { return system + "/" + screen; }
    private static boolean set(String value) { return value != null && !value.isBlank(); }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String displayName(SolutionScreen screen) { return screen.screenName() == null || screen.screenName().isBlank() ? screen.screenId() : screen.screenName(); }

    public enum DocumentState { READY, PREPARING, REFRESHING, FAILED_EMPTY, FAILED_STALE }
    private record DocumentView(DocumentState state, FeatureSpecCurrent current, FeatureSpecRevision revision) { }
    public record SystemOption(String code, String label) { }
    public record Row(SolutionScreen screen, String systemLabel, String managementNumber,
                      FeatureSpecCurrent saved, FeatureSpecRevision revision) {
        public String displayName() { return FeatureSpecController.displayName(screen); }
        public boolean hasRevision() { return saved != null && saved.hasRevision(); }
        public boolean ready() {
            return saved != null && saved.state() == FeatureSpecState.DONE && hasRevision();
        }
        public String statusLabel() {
            if (saved == null) return "미생성";
            if (saved.state() == FeatureSpecState.RUNNING) return hasRevision() ? "업데이트 중" : "준비 중";
            if (saved.state() == FeatureSpecState.FAILED) return hasRevision() ? "업데이트 실패" : "생성 실패";
            return hasRevision() ? "완료" : "미생성";
        }
        public String sourceDate() {
            LocalDate date = screen.history() == null ? null : screen.history().lastDate();
            return date == null ? "—" : date.toString();
        }
        public String documentDate() {
            return revision == null || revision.createdAt() == null
                    ? "—" : LocalDate.ofInstant(revision.createdAt(), SEOUL).toString();
        }
    }
}
