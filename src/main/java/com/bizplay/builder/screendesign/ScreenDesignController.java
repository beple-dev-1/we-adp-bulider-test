package com.bizplay.builder.screendesign;

import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 화면설계서 목록·상세·생성 상태와 불변 캡처 파일을 제공한다. */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/screen-designs")
public class ScreenDesignController {

    private static final String ARTIFACT_KEY = "screen-designs";
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int PAGE_WINDOW = 10;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SolutionMockupService solutions;
    private final ProjectSystemService projectSystems;
    private final ScreenStandardIdMapper standardIds;
    private final ScreenDesignMapper designs;
    private final ScreenDesignWorker worker;
    private final ScreenDesignContentReader contentReader;
    private final ScreenDesignBundleStore bundles;
    private final ScreenDesignRenderer renderer;

    public ScreenDesignController(SolutionMockupService solutions, ProjectSystemService projectSystems,
                                  ScreenStandardIdMapper standardIds,
                                  ScreenDesignMapper designs, ScreenDesignWorker worker,
                                  ScreenDesignContentReader contentReader, ScreenDesignBundleStore bundles,
                                  ScreenDesignRenderer renderer) {
        this.solutions = solutions;
        this.projectSystems = projectSystems;
        this.standardIds = standardIds;
        this.designs = designs;
        this.worker = worker;
        this.contentReader = contentReader;
        this.bundles = bundles;
        this.renderer = renderer;
    }

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String system,
                       @RequestParam(required = false) String design,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       @RequestParam(required = false) String selectedSystem,
                       @RequestParam(required = false) String selectedScreen,
                       Model model) {
        List<SolutionScreen> all = solutions.screens(projectId);
        SystemLabels labels = projectSystems.labels(projectId);
        Map<String, ScreenDesignCurrent> currents = designs.selectByProject(projectId).stream()
                .collect(Collectors.toMap(current -> key(current.systemCode(), current.screenId()),
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, ScreenDesignRevision> revisions = designs.selectCurrentRevisionsByProject(projectId).stream()
                .collect(Collectors.toMap(revision -> key(revision.systemCode(), revision.screenId()),
                        Function.identity(), (left, right) -> left));
        Map<String, String> managementNumbers = standardIds.selectByProject(projectId).stream()
                .collect(Collectors.toMap(item -> item.screenId(),
                        item -> StandardScreenIdFormat.display(item.standardId(), item.origin()),
                        (left, right) -> left));
        List<Row> allRows = all.stream().map(screen -> row(screen, labels,
                        managementNumbers.getOrDefault(screen.screenId(), "—"),
                        currents.get(key(screen.system(), screen.screenId())),
                        revisions.get(key(screen.system(), screen.screenId()))))
                .sorted(Comparator.comparing(row -> row.screen().screenId())).toList();
        List<Row> matched = allRows.stream().filter(row -> matches(row, query, system, design)).toList();
        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int currentPage = Math.max(1, Math.min(page, pageCount));
        model.addAttribute("rows", matched.stream().skip((long) (currentPage - 1) * size).limit(size).toList());
        model.addAttribute("totalCount", allRows.size());
        model.addAttribute("matchedCount", matched.size());
        model.addAttribute("page", currentPage);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(currentPage, pageCount));
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("systems", all.stream().map(screen -> new SystemOption(screen.system(), labels.label(screen.system())))
                .distinct().sorted(Comparator.comparing(SystemOption::label)).toList());
        model.addAttribute("designStates", allRows.stream().map(Row::statusLabel).distinct().sorted().toList());
        model.addAttribute("query", query);
        model.addAttribute("systemFilter", system);
        model.addAttribute("designFilter", design);
        model.addAttribute("layerOpen", false);
        model.addAttribute("layerSystemCode", "");
        model.addAttribute("layerScreenId", "");
        model.addAttribute("screenTitle", "");
        model.addAttribute("systemLabel", "");
        model.addAttribute("managementNumber", "—");
        model.addAttribute("documentState", DocumentState.PREPARING);
        model.addAttribute("hasDocument", false);
        model.addAttribute("polling", false);
        model.addAttribute("downloadable", false);
        model.addAttribute("failureMessage", "");
        if (set(selectedSystem) && set(selectedScreen)) {
            populateLayer(projectId, selectedSystem, selectedScreen, model);
        }
        shell(model, "화면설계서");
        return "artifacts/screen-designs";
    }

    /** 이전 상세 주소는 목록 위 레이어를 여는 주소로 바꾼다. */
    @GetMapping("/{systemCode}/{screenId}")
    public String detail(@PathVariable String projectId, @PathVariable String systemCode,
                         @PathVariable String screenId, RedirectAttributes redirect) {
        screenOf(projectId, systemCode, screenId);
        redirect.addAttribute("selectedSystem", systemCode);
        redirect.addAttribute("selectedScreen", screenId);
        return "redirect:/projects/" + projectId + "/artifacts/screen-designs";
    }

    /** 목록의 현재 상태를 유지한 채 화면설계서 레이어만 채운다. */
    private void populateLayer(String projectId, String systemCode, String screenId, Model model) {
        SolutionScreen screen = screenOf(projectId, systemCode, screenId);
        worker.requestIfNeeded(projectId, systemCode, screenId);
        DocumentView view = view(projectId, systemCode, screenId);
        ScreenDesignContent content = view.revision() == null ? null : contentReader.read(view.revision().contentJson());
        List<CaptureView> captures = content == null ? List.of() : content.captures().stream()
                .map(capture -> new CaptureView(capture.name(), capture.label(), capture.imageFile(),
                        captureUrl(projectId, systemCode, screenId, view.revision().revisionId(),
                                capture.imageFile()))).toList();
        model.addAttribute("layerOpen", true);
        model.addAttribute("layerSystemCode", systemCode);
        model.addAttribute("layerScreenId", screenId);
        model.addAttribute("screen", screen);
        model.addAttribute("screenTitle", displayName(screen));
        model.addAttribute("systemLabel", projectSystems.labels(projectId).label(systemCode));
        model.addAttribute("managementNumber", managementNumber(projectId, screenId));
        LocalDate sourceDate = screen.history() == null ? null : screen.history().lastDate();
        model.addAttribute("sourceDate", sourceDate == null ? "—" : sourceDate.toString());
        model.addAttribute("documentDate", view.revision() == null || view.revision().createdAt() == null ? "—"
                : LocalDate.ofInstant(view.revision().createdAt(), SEOUL).toString());
        model.addAttribute("statusLabel", documentStatusLabel(view.state()));
        model.addAttribute("documentState", view.state());
        model.addAttribute("document", view.revision());
        model.addAttribute("screenDesignContent", content);
        model.addAttribute("documentBody", content == null ? "" : renderer.renderBody(content));
        model.addAttribute("hasDocument", view.revision() != null && content != null);
        model.addAttribute("polling", view.state() == DocumentState.PREPARING
                || view.state() == DocumentState.REFRESHING);
        model.addAttribute("downloadable", view.state() == DocumentState.READY);
        model.addAttribute("failureMessage", failureMessage(view.current()));
        model.addAttribute("captures", captures);
        model.addAttribute("downloadUrl", view.revision() == null ? ""
                : downloadUrl(projectId, systemCode, screenId, view.revision().revisionId()));
    }

    @GetMapping("/{systemCode}/{screenId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String projectId,
                                                       @PathVariable String systemCode,
                                                       @PathVariable String screenId) {
        screenOf(projectId, systemCode, screenId);
        DocumentView view = view(projectId, systemCode, screenId);
        return ResponseEntity.ok(Map.of("state", view.state().name(), "complete",
                view.state() != DocumentState.PREPARING && view.state() != DocumentState.REFRESHING));
    }

    @GetMapping("/{systemCode}/{screenId}/revisions/{revisionId}/captures/{name}")
    public ResponseEntity<FileSystemResource> capture(@PathVariable String projectId,
                                                       @PathVariable String systemCode,
                                                       @PathVariable String screenId,
                                                       @PathVariable String revisionId,
                                                       @PathVariable String name) {
        ScreenDesignRevision revision = revision(projectId, systemCode, screenId, revisionId);
        ScreenDesignContent content = contentReader.read(revision.contentJson());
        boolean registeredImage = name.toLowerCase(Locale.ROOT).endsWith(".png")
                && content.captures().stream().anyMatch(item -> name.equals(item.imageFile()));
        if (!registeredImage) {
            throw new ResponseStatusException(NOT_FOUND, "화면설계서 캡처 파일을 찾을 수 없습니다.");
        }
        return file(revision, name, false);
    }

    @GetMapping("/{systemCode}/{screenId}/revisions/{revisionId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String projectId,
                                                        @PathVariable String systemCode,
                                                        @PathVariable String screenId,
                                                        @PathVariable String revisionId) {
        ScreenDesignRevision revision = revision(projectId, systemCode, screenId, revisionId);
        ScreenDesignCurrent current = designs.selectCurrent(projectId, systemCode, screenId).orElse(null);
        if (current == null || current.state() != ScreenDesignState.DONE
                || !revisionId.equals(current.currentRevisionId())) {
            throw new ResponseStatusException(CONFLICT, "최신 화면설계서가 준비된 뒤 내려받을 수 있습니다.");
        }
        return file(revision, "screen-design.pdf", true);
    }

    private ResponseEntity<FileSystemResource> file(ScreenDesignRevision revision, String name, boolean attachment) {
        try {
            Path file = bundles.file(revision.bundlePath(), name);
            MediaType type = name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    ? MediaType.APPLICATION_PDF : MediaType.IMAGE_PNG;
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(type);
            if (attachment) {
                response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("screen-design.pdf", StandardCharsets.UTF_8).build().toString());
            }
            return response.body(new FileSystemResource(file));
        } catch (IOException missing) {
            throw new ResponseStatusException(NOT_FOUND, "화면설계서 캡처 파일을 찾을 수 없습니다.");
        }
    }

    private ScreenDesignRevision revision(String projectId, String systemCode,
                                          String screenId, String revisionId) {
        screenOf(projectId, systemCode, screenId);
        ScreenDesignRevision revision = designs.selectRevision(revisionId).orElseThrow(() ->
                new ResponseStatusException(NOT_FOUND, "화면설계서 개정판을 찾을 수 없습니다."));
        if (!projectId.equals(revision.projectId()) || !systemCode.equals(revision.systemCode())
                || !screenId.equals(revision.screenId())) {
            throw new ResponseStatusException(NOT_FOUND, "화면설계서 개정판을 찾을 수 없습니다.");
        }
        return revision;
    }

    private DocumentView view(String projectId, String systemCode, String screenId) {
        ScreenDesignCurrent current = designs.selectCurrent(projectId, systemCode, screenId).orElse(null);
        ScreenDesignRevision revision = current == null || !current.hasRevision() ? null
                : designs.selectRevision(current.currentRevisionId()).orElse(null);
        if (current == null) return new DocumentView(DocumentState.PREPARING, null, null);
        if (current.state() == ScreenDesignState.RUNNING) {
            return new DocumentView(revision == null ? DocumentState.PREPARING : DocumentState.REFRESHING,
                    current, revision);
        }
        if (current.state() == ScreenDesignState.FAILED) {
            return new DocumentView(revision == null ? DocumentState.FAILED_EMPTY : DocumentState.FAILED_STALE,
                    current, revision);
        }
        return new DocumentView(revision == null ? DocumentState.PREPARING : DocumentState.READY,
                current, revision);
    }

    private SolutionScreen screenOf(String projectId, String systemCode, String screenId) {
        return solutions.screens(projectId).stream()
                .filter(screen -> systemCode.equals(screen.system()) && screenId.equals(screen.screenId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "그 시스템에 해당 화면이 없습니다."));
    }

    private Row row(SolutionScreen screen, SystemLabels labels, String managementNumber,
                    ScreenDesignCurrent current,
                    ScreenDesignRevision revision) {
        String status = statusLabel(current, revision);
        boolean ready = current != null && current.state() == ScreenDesignState.DONE && revision != null;
        LocalDate source = screen.history() == null ? null : screen.history().lastDate();
        String sourceDate = source == null ? "—" : source.toString();
        String documentDate = revision == null || revision.createdAt() == null ? "—"
                : LocalDate.ofInstant(revision.createdAt(), SEOUL).toString();
        return new Row(screen, displayName(screen), managementNumber, labels.label(screen.system()), status,
                sourceDate, documentDate, revision != null, ready);
    }

    private String managementNumber(String projectId, String screenId) {
        return standardIds.selectByProject(projectId).stream()
                .filter(item -> screenId.equals(item.screenId()))
                .map(item -> StandardScreenIdFormat.display(item.standardId(), item.origin()))
                .findFirst().orElse("—");
    }

    private boolean matches(Row row, String query, String system, String design) {
        if (set(system) && !system.equals(row.screen().system())) return false;
        if (set(design) && !design.equals(row.statusLabel())) return false;
        if (!set(query)) return true;
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return lower(row.displayName()).contains(needle) || lower(row.screen().screenId()).contains(needle)
                || lower(row.managementNumber()).contains(needle)
                || lower(row.screen().menuPath()).contains(needle);
    }

    private static String statusLabel(ScreenDesignCurrent current, ScreenDesignRevision revision) {
        if (current == null) return "미생성";
        if (current.state() == ScreenDesignState.RUNNING) return revision == null ? "준비 중" : "업데이트 중";
        if (current.state() == ScreenDesignState.FAILED) return revision == null ? "생성 실패" : "업데이트 실패";
        return revision == null ? "미생성" : "완료";
    }

    private static String failureMessage(ScreenDesignCurrent current) {
        if (current == null || current.state() != ScreenDesignState.FAILED) return "";
        return switch (current.failedReason() == null ? "" : current.failedReason()) {
            case "MISSING_MD" -> "화면 명세가 없어 화면설계서를 만들지 못했습니다. 기획 저장소를 갱신해 주세요.";
            case "MISSING_HTML" -> "화면 파일이 없어 화면설계서를 만들지 못했습니다. 기획 저장소를 갱신해 주세요.";
            case "INPUT_TOO_LARGE" -> "화면 자료가 허용 범위를 넘어 만들지 못했습니다. 기획 저장소의 화면 파일을 확인해 주세요.";
            case "BROWSER_UNAVAILABLE" -> "화면 캡처 브라우저가 설치되지 않아 만들지 못했습니다. 서버 설치 상태를 확인해 주세요.";
            case "SOURCE_CHANGED" -> "생성 중 화면 자료가 바뀌었습니다. 잠시 뒤 다시 열어 주세요.";
            default -> "화면설계서를 만들지 못했습니다. 잠시 뒤 다시 열어 주세요.";
        };
    }

    private static String documentStatusLabel(DocumentState state) {
        return switch (state) {
            case PREPARING -> "작성 중";
            case REFRESHING -> "새 문서 작성 중";
            case READY -> "최신";
            case FAILED_EMPTY -> "생성 실패";
            case FAILED_STALE -> "최근 갱신 실패";
        };
    }

    private static List<Integer> pageNumbers(int page, int count) {
        int first = Math.max(1, Math.min(page - PAGE_WINDOW / 2, count - PAGE_WINDOW + 1));
        int last = Math.min(count, first + PAGE_WINDOW - 1);
        return java.util.stream.IntStream.rangeClosed(first, last).boxed().toList();
    }

    private static String key(String systemCode, String screenId) {
        return systemCode + "\u0000" + screenId;
    }

    private static String displayName(SolutionScreen screen) {
        return ScreenDesignMaterialService.displayName(screen);
    }

    private static boolean set(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String captureUrl(String projectId, String systemCode, String screenId,
                                     String revisionId, String name) {
        return UriComponentsBuilder.fromPath("/projects/{projectId}/artifacts/screen-designs/{systemCode}/{screenId}/revisions/{revisionId}/captures/{name}")
                .buildAndExpand(projectId, systemCode, screenId, revisionId, name).encode().toUriString();
    }

    private static String downloadUrl(String projectId, String systemCode, String screenId,
                                      String revisionId) {
        return UriComponentsBuilder.fromPath("/projects/{projectId}/artifacts/screen-designs/{systemCode}/{screenId}/revisions/{revisionId}/download")
                .buildAndExpand(projectId, systemCode, screenId, revisionId).encode().toUriString();
    }

    private static void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }

    public enum DocumentState { PREPARING, REFRESHING, READY, FAILED_EMPTY, FAILED_STALE }

    public record Row(SolutionScreen screen, String displayName, String managementNumber, String systemLabel,
                      String statusLabel, String sourceDate, String documentDate,
                      boolean hasDocument, boolean ready) { }

    public record CaptureView(String name, String label, String imageFile, String imageUrl) { }

    public record SystemOption(String code, String label) { }

    private record DocumentView(DocumentState state, ScreenDesignCurrent current,
                                ScreenDesignRevision revision) { }
}
