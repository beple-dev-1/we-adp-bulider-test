package com.bizplay.builder.usermanual;

import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.project.SystemLabels;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.screenid.StandardScreenIdFormat;
import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionPreviewController;
import com.bizplay.builder.solution.SolutionScreen;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 사용자 매뉴얼 목록과 화면별 확인 작업대. */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/user-manual")
public class UserManualController {

    private static final String ARTIFACT_KEY = "user-manual";
    public static final String URL_PATTERN = "/projects/*/artifacts/user-manual/preview/**";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int PAGE_WINDOW = 10;

    private final SolutionMockupService solutions;
    private final ProjectSystemService projectSystems;
    private final UserManualMapper manuals;
    private final UserManualWorker worker;
    private final UserManualReader documents;
    private final UserManualCaptureStore captureStore;
    private final ScreenStandardIdMapper standardIds;

    public UserManualController(SolutionMockupService solutions, ProjectSystemService projectSystems,
                                UserManualMapper manuals, UserManualWorker worker,
                                UserManualReader documents, UserManualCaptureStore captureStore,
                                ScreenStandardIdMapper standardIds) {
        this.solutions = solutions;
        this.projectSystems = projectSystems;
        this.manuals = manuals;
        this.worker = worker;
        this.documents = documents;
        this.captureStore = captureStore;
        this.standardIds = standardIds;
    }

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String system,
                       @RequestParam(required = false) String manual,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       @RequestParam(required = false) String selectedSystem,
                       @RequestParam(required = false) String selectedScreen,
                       @AuthenticationPrincipal BuilderUser me, Model model) {
        List<SolutionScreen> all = solutions.screens(projectId);
        SystemLabels labels = projectSystems.labels(projectId);
        Map<String, UserManual> savedByScreen = new LinkedHashMap<>();
        for (UserManual saved : manuals.selectByProject(projectId)) {
            savedByScreen.put(key(saved.systemCode(), saved.screenId()), saved);
        }
        Map<String, String> managementNumbers = standardIds.selectByProject(projectId).stream()
                .collect(java.util.stream.Collectors.toMap(row -> row.screenId(),
                        row -> StandardScreenIdFormat.display(row.standardId(), row.origin())));

        List<Row> allRows = all.stream()
                .map(screen -> {
                    UserManual saved = savedByScreen.get(key(screen.system(), screen.screenId()));
                    return new Row(screen, labels.label(screen.system()),
                            managementNumbers.getOrDefault(screen.screenId(), "—"), saved,
                            saved == null || worker.isCurrent(projectId, all, screen, saved));
                })
                .toList();
        long available = allRows.stream().filter(Row::hasManual).count();
        model.addAttribute("totalCount", allRows.size());
        model.addAttribute("writtenCount", available);
        model.addAttribute("outdatedCount", allRows.stream().filter(Row::outdated).count());
        model.addAttribute("missingCount", allRows.size() - available);

        String normalizedSystem = normalizeSystem(all, system);
        String normalizedManual = normalizeState(allRows, manual);
        List<Row> matched = allRows.stream()
                .filter(row -> matchesQuery(row, query))
                .filter(row -> matchesChoice(row.screen().system(), normalizedSystem))
                .filter(row -> matchesChoice(row.manualState(), normalizedManual))
                .sorted(Comparator.comparing(row -> row.screen().screenId()))
                .toList();

        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);
        model.addAttribute("rows", matched.stream().skip((long) (current - 1) * size).limit(size).toList());
        model.addAttribute("matchedCount", matched.size());
        model.addAttribute("page", current);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(current, pageCount));
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);

        model.addAttribute("systems", all.stream()
                .map(screen -> new SystemOption(screen.system(), labels.label(screen.system())))
                .distinct().sorted(Comparator.comparing(SystemOption::label)).toList());
        model.addAttribute("manualStates", allRows.stream().map(Row::manualState).distinct()
                .sorted().toList());
        model.addAttribute("query", query);
        model.addAttribute("systemFilter", normalizedSystem);
        model.addAttribute("manualFilter", normalizedManual);
        model.addAttribute("hasActiveFilters", isSet(query) || isSet(normalizedSystem) || isSet(normalizedManual));
        model.addAttribute("layerOpen", false);
        if (isSet(selectedSystem) && isSet(selectedScreen)) {
            populateLayer(projectId, selectedSystem, selectedScreen, me, model);
        }
        shell(model, "사용자 매뉴얼");
        return "artifacts/user-manuals";
    }

    /** 이전 상세 주소는 목록 위 레이어를 여는 주소로 바꾼다. */
    @GetMapping("/{systemCode}/{screenId}")
    public String detail(@PathVariable String projectId, @PathVariable String systemCode,
                         @PathVariable String screenId,
                         @RequestParam(required = false) String query,
                         @RequestParam(required = false) String system,
                         @RequestParam(required = false) String manual,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "10") int pageSize,
                         RedirectAttributes redirect) {
        screenOf(projectId, systemCode, screenId);
        addListState(redirect, query, system, manual, page, pageSize);
        redirect.addAttribute("selectedSystem", systemCode);
        redirect.addAttribute("selectedScreen", screenId);
        return "redirect:/projects/" + projectId + "/artifacts/user-manual";
    }

    /** 검증·선점·대기열 등록을 워커 한 문에서 처리한다. */
    @PostMapping("/{systemCode}/{screenId}")
    public String generate(@PathVariable String projectId, @PathVariable String systemCode,
                           @PathVariable String screenId, @AuthenticationPrincipal BuilderUser me,
                           @RequestParam(required = false) String query,
                           @RequestParam(required = false) String system,
                           @RequestParam(required = false) String manual,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int pageSize,
                           RedirectAttributes flash) {
        screenOf(projectId, systemCode, screenId);
        UserManualWorker.RequestResult result = worker.request(projectId, systemCode, screenId, me.accountId());
        flash.addFlashAttribute(result.accepted() ? "notice" : "error", result.message());
        addListState(flash, query, system, manual, page, pageSize);
        flash.addAttribute("selectedSystem", systemCode);
        flash.addAttribute("selectedScreen", screenId);
        return "redirect:/projects/" + projectId + "/artifacts/user-manual";
    }

    /** 목록의 검색·필터·페이지 상태를 유지한 채 사용자 매뉴얼 레이어를 채운다. */
    private void populateLayer(String projectId, String systemCode, String screenId,
                               BuilderUser me, Model model) {
        SolutionScreen screen = screenOf(projectId, systemCode, screenId);
        worker.requestIfNeeded(projectId, systemCode, screenId, me == null ? null : me.accountId());
        UserManual saved = manuals.selectOne(projectId, systemCode, screenId).orElse(null);
        String managementNumber = managementNumber(projectId, screenId);
        Row row = new Row(screen, projectSystems.labels(projectId).label(systemCode), managementNumber, saved,
                saved == null || worker.isCurrent(projectId, solutions.screens(projectId), screen, saved));
        model.addAttribute("layerOpen", true);
        model.addAttribute("layerSystemCode", systemCode);
        model.addAttribute("layerScreenId", screenId);
        model.addAttribute("screen", screen);
        model.addAttribute("screenTitle", row.displayName());
        model.addAttribute("systemLabel", row.systemLabel());
        model.addAttribute("managementNumber", row.managementNumber());
        model.addAttribute("sourceDate", row.sourceDate());
        model.addAttribute("manualDate", row.manualDate());
        model.addAttribute("statusLabel", row.manualState());
        model.addAttribute("hasManual", row.hasManual());
        model.addAttribute("running", row.running());
        model.addAttribute("failed", row.failed());
        model.addAttribute("outdated", row.outdated());
        model.addAttribute("missing", saved == null);
        model.addAttribute("failedMessage", failedMessage(saved));
    }

    private static void addListState(RedirectAttributes redirect, String query, String system,
                                     String manual, int page, int pageSize) {
        if (isSet(query)) redirect.addAttribute("query", query);
        if (isSet(system)) redirect.addAttribute("system", system);
        if (isSet(manual)) redirect.addAttribute("manual", manual);
        redirect.addAttribute("page", page);
        redirect.addAttribute("pageSize", pageSize);
    }

    /** Builder가 만든 안전한 독립 문서를 iframe과 새 창에 낸다. */
    @GetMapping("/preview/{systemCode}/{screenId}")
    public ResponseEntity<String> preview(@PathVariable String projectId,
                                          @PathVariable String systemCode,
                                          @PathVariable String screenId) throws IOException {
        screenOf(projectId, systemCode, screenId);
        UserManualArtifact manual = artifactWithBody(projectId, systemCode, screenId);
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox "
                        + SolutionPreviewController.SANDBOX)
                .header("Content-Type", "text/html; charset=utf-8")
                .body(renderManual(projectId, manual));
    }

    /** 마지막 정상본을 시스템별 또는 전체 ZIP으로 내려준다. */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String projectId,
                                                          @RequestParam(required = false) String system) {
        List<SolutionScreen> screens = solutions.screens(projectId);
        String normalizedSystem = normalizeSystem(screens, system);
        Set<String> currentScreens = screens.stream()
                .map(screen -> key(screen.system(), screen.screenId())).collect(java.util.stream.Collectors.toSet());
        List<UserManualArtifact> picked = manuals.selectArtifactsByProject(projectId).stream()
                .filter(saved -> currentScreens.contains(key(saved.systemCode(), saved.screenId())))
                .filter(saved -> matchesChoice(saved.systemCode(), normalizedSystem))
                .sorted(Comparator.comparing(UserManualArtifact::systemCode)
                        .thenComparing(UserManualArtifact::screenId))
                .toList();
        if (picked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "내려받을 매뉴얼이 없습니다");
        }
        StreamingResponseBody body = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (UserManualArtifact saved : picked) {
                    ZipEntry item = new ZipEntry(safeSegment(saved.systemCode()) + "/"
                            + safeSegment(saved.screenId()) + ".html");
                    item.setTime(0L);
                    zip.putNextEntry(item);
                    zip.write(renderManual(projectId, saved).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"user-manual.zip\"")
                .header("Content-Type", "application/zip")
                .body(body);
    }

    /** 화면 한 장의 마지막 정상본을 독립 문서로 내려준다. */
    @GetMapping("/download/{systemCode}/{screenId}")
    public ResponseEntity<byte[]> downloadOne(@PathVariable String projectId,
                                              @PathVariable String systemCode,
                                              @PathVariable String screenId) throws IOException {
        screenOf(projectId, systemCode, screenId);
        UserManualArtifact manual = artifactWithBody(projectId, systemCode, screenId);
        byte[] body = renderManual(projectId, manual).getBytes(StandardCharsets.UTF_8);
        return downloadResponse(body, safeSegment(systemCode) + "-" + safeSegment(screenId) + ".html",
                "text/html; charset=utf-8");
    }

    private ResponseEntity<byte[]> downloadResponse(byte[] body, String fileName, String contentType) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Content-Type", contentType)
                .body(body);
    }

    private UserManualArtifact artifactWithBody(String projectId, String systemCode, String screenId) {
        return manuals.selectArtifact(projectId, systemCode, screenId)
                .filter(saved -> saved.html() != null && !saved.html().isBlank())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "그 화면의 정상 매뉴얼이 없습니다"));
    }

    /** 마지막 정상본과 같은 세대의 캡처만 읽어 독립 문서에 넣는다. */
    private String renderManual(String projectId, UserManualArtifact manual) throws IOException {
        LocalDate createdDate = manual.createdAt() == null
                ? LocalDate.now(SEOUL) : LocalDate.ofInstant(manual.createdAt(), SEOUL);
        UserManualReader.StandaloneMeta meta = new UserManualReader.StandaloneMeta(
                projectSystems.labels(projectId).label(manual.systemCode()),
                managementNumber(projectId, manual.screenId()), manual.screenId(), createdDate);
        UserManualCapture capture = manual.capture();
        if (capture == null) return documents.renderStandalone(manual.html(), meta);
        byte[] png = Files.readAllBytes(captureStore.file(capture.bundlePath(), capture.fileName()));
        if (!sha256(png).equals(capture.sha256())) {
            throw new IOException("사용자 매뉴얼 화면 캡처의 무결성을 확인할 수 없습니다.");
        }
        UserManualReader.VerifiedCapture verified = new UserManualReader.VerifiedCapture(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
                capture.width(), capture.height(), capture.label());
        return documents.renderStandalone(manual.html(), meta, verified);
    }

    private String managementNumber(String projectId, String screenId) {
        return standardIds.selectByProject(projectId).stream()
                .filter(item -> item.screenId().equals(screenId))
                .map(item -> StandardScreenIdFormat.display(item.standardId(), item.origin()))
                .findFirst().orElse("—");
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("화면 캡처 무결성을 확인할 수 없습니다.", unavailable);
        }
    }

    private SolutionScreen screenOf(String projectId, String systemCode, String screenId) {
        return solutions.screens(projectId).stream()
                .filter(screen -> screen.system().equals(systemCode))
                .filter(screen -> screen.screenId().equals(screenId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "그 시스템에 해당 화면이 없습니다"));
    }

    private boolean matchesQuery(Row row, String query) {
        if (!isSet(query)) return true;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return lower(row.screen().screenId()).contains(needle)
                || lower(row.screen().screenName()).contains(needle)
                || lower(row.screen().menuPath()).contains(needle)
                || lower(row.managementNumber()).contains(needle);
    }

    private static boolean matchesChoice(String actual, String chosen) {
        return !isSet(chosen) || chosen.equals(actual);
    }

    private String normalizeSystem(List<SolutionScreen> screens, String chosen) {
        if (!isSet(chosen)) return null;
        return screens.stream().map(SolutionScreen::system).anyMatch(chosen::equals) ? chosen : null;
    }

    private String normalizeState(List<Row> rows, String chosen) {
        if (!isSet(chosen)) return null;
        return rows.stream().map(Row::manualState).anyMatch(chosen::equals) ? chosen : null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    private static String key(String systemCode, String screenId) {
        return systemCode + "/" + screenId;
    }

    private static boolean hasBody(UserManual manual) {
        return manual.html() != null && !manual.html().isBlank();
    }

    private static String safeSegment(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() || safe.equals(".") || safe.equals("..") ? "manual" : safe;
    }

    private static String failedMessage(UserManual manual) {
        if (manual == null || manual.state() != UserManualState.FAILED) return "";
        return switch (manual.failedReason() == null ? "" : manual.failedReason()) {
            case "NO_CREDENTIAL" -> "Claude 연결 정보가 없어 만들기를 시작하지 못했습니다. 계정 설정을 확인한 뒤 다시 만들어 주세요.";
            case "MISSING_MD" -> "화면 명세가 없어 매뉴얼의 근거를 확인할 수 없습니다. 기획 저장소를 갱신한 뒤 다시 만들어 주세요.";
            case "MISSING_HTML" -> "운영 화면 파일이 없어 실제 화면을 확인할 수 없습니다. 기획 저장소를 갱신한 뒤 다시 만들어 주세요.";
            case "SOURCE_CHANGED" -> "만드는 동안 운영 화면 자료가 바뀌었습니다. 최신 자료로 다시 만들어 주세요.";
            case "QUEUE_UNAVAILABLE" -> "생성 요청을 대기열에 넣지 못했습니다. 잠시 뒤 다시 만들어 주세요.";
            case "INVALID_RESPONSE" -> "생성된 내용이 매뉴얼 형식과 맞지 않았습니다. 레이어를 닫았다가 다시 열면 자동으로 다시 만듭니다.";
            case "AI_EXECUTION_FAILED" -> "Claude 실행이 끝나지 못했습니다. 연결 상태를 확인한 뒤 다시 만들어 주세요.";
            case "BROWSER_UNAVAILABLE" -> "실제 화면을 캡처할 브라우저를 시작하지 못했습니다. Builder를 재시작한 뒤 다시 만들어 주세요.";
            case "CAPTURE_INTERRUPTED", "CAPTURE_FAILED" -> "실제 화면 캡처가 끝나지 못했습니다. 잠시 뒤 다시 만들어 주세요.";
            default -> "매뉴얼 만들기가 끝나지 못했습니다. 잠시 뒤 다시 만들어 주세요.";
        };
    }

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }

    public record SystemOption(String code, String label) { }

    /** 목록 한 줄. 마지막 정상본과 현재 생성 시도를 서로 다른 사실로 계산한다. */
    public record Row(SolutionScreen screen, String systemLabel, String managementNumber,
                      UserManual manual, boolean sourceCurrent) {
        public boolean hasManual() { return manual != null && hasBody(manual); }
        public boolean running() { return manual != null && manual.state() == UserManualState.RUNNING; }
        public boolean failed() { return manual != null && manual.state() == UserManualState.FAILED; }

        public boolean outdated() {
            LocalDate source = screen.history() == null ? null : screen.history().lastDate();
            LocalDate generated = manual == null || manual.createdAt() == null
                    ? null : LocalDate.ofInstant(manual.createdAt(), SEOUL);
            return hasManual() && (!sourceCurrent
                    || (source != null && generated != null && source.isAfter(generated)));
        }

        public boolean ready() { return hasManual() && !running() && !failed() && !outdated(); }

        public String manualState() {
            if (manual == null) return "미생성";
            if (running()) return hasManual() ? "업데이트 중" : "준비 중";
            if (failed()) return hasManual() ? "업데이트 실패" : "생성 실패";
            return hasManual() ? "완료" : "미생성";
        }

        public String sourceDate() {
            LocalDate date = screen.history() == null ? null : screen.history().lastDate();
            return date == null ? "—" : date.toString();
        }

        public String manualDate() {
            return !hasManual() || manual.createdAt() == null
                    ? "—" : LocalDate.ofInstant(manual.createdAt(), SEOUL).toString();
        }

        public String displayName() {
            return screen.screenName() == null || screen.screenName().isBlank()
                    ? screen.screenId() : screen.screenName();
        }
    }
}
