package com.bizplay.builder.intake;

import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.intake.ReceivedDocument.ContentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 받은 문서 — 목록 · 등록 · 상세 · 요구사항 분석.
 *
 * <p><b>2026-08-15 에 「AI 가 늘 정리한다」가 폐기됐다.</b> 등록하면 문서 내용이 바로 서고,
 * 요구사항은 <b>사람이 누를 때만</b> 뽑는다. ⛔ 처리 방향(요구사항 대상 / 참고 문서)을 되살리지 마라 —
 * 참고 목적이면 아무것도 안 하면 된다.
 *
 * <p>⚠ <b>등록은 메뉴를 늘리지 않는다.</b> 목록의 「문서 등록」에서 하위 화면으로 가고,
 * 왼쪽 메뉴의 현재 위치는 그대로 {@code received-docs} 다(→ {@code ia}).
 *
 * <p>⚠ 프로젝트 이름·번호·알림은 <b>여기서 안 담는다</b> —
 * {@link com.bizplay.builder.web.ProjectContextInterceptor} 가 한 자리에서 얹는다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/received-docs")
public class IntakeController {

    private static final Logger log = LoggerFactory.getLogger(IntakeController.class);

    private static final String ARTIFACT_KEY = "received-docs";

    /** ⚠ 브라우저의 {@code datetime-local} 은 지역을 안 보낸다 — 사람이 친 시각의 뜻을 여기서 정한다. */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final IntakeService service;
    private final IntakeMapper intakes;
    private final ReceivedDocumentMapper documents;
    private final ProjectFacetMapper projectFacets;
    private final IntakeFacetMapper intakeFacets;
    private final RequirementMapper requirements;
    private final AccountMapper accounts;
    private final DocumentProcessingWorker processingWorker;
    private final RequirementAnalysisWorker analysisWorker;
    private final FlowPostGateway flowPosts;

    public IntakeController(IntakeService service, IntakeMapper intakes,
                            ReceivedDocumentMapper documents,
                            ProjectFacetMapper projectFacets,
                            IntakeFacetMapper intakeFacets,
                            RequirementMapper requirements,
                            AccountMapper accounts,
                            DocumentProcessingWorker processingWorker,
                            RequirementAnalysisWorker analysisWorker,
                            FlowPostGateway flowPosts) {
        this.service = service;
        this.intakes = intakes;
        this.documents = documents;
        this.projectFacets = projectFacets;
        this.intakeFacets = intakeFacets;
        this.requirements = requirements;
        this.accounts = accounts;
        this.processingWorker = processingWorker;
        this.analysisWorker = analysisWorker;
        this.flowPosts = flowPosts;
    }

    /** 목록 한 쪽에 몇 줄. 목업 `01` 의 「목록 크기」가 고르는 값이다. */
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);

    /** 받은 문서 목록의 상태 거르개. 요구사항 분석 상태는 이 축에 섞지 않는다. */
    private static final List<String> DOCUMENT_STATUSES = List.of(
            ContentState.QUEUED.label(),
            ContentState.PROCESSING.label(),
            ContentState.READY.label(),
            ContentState.FAILED.label());

    /** 쪽 번호를 몇 개까지 늘어놓나. 목업이 열 개를 그렸다. */
    private static final int PAGE_WINDOW = 10;

    /** 거르개의 「전체」. ⚠ 빈 문자열과 같은 뜻이다 — 브라우저가 안 고른 칸을 빈 값으로 보낸다. */
    private static final String ANY = "전체";

    @GetMapping
    public String list(@PathVariable String projectId,
                       @RequestParam(required = false) String query,
                       @RequestParam(required = false) String documentType,
                       @RequestParam(required = false) String documentStatus,
                       @RequestParam(required = false) String facet,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int pageSize,
                       Model model) {
        List<Intake> found = intakes.selectByProjectId(projectId);
        List<String> intakeIds = found.stream().map(Intake::id).toList();

        // ⛔ 빈 목록으로 부르지 마라 — 매퍼의 in 절이 `in ()` 이 되어 SQL 이 깨진다.
        Map<String, ReceivedDocument> documentByIntake = intakeIds.isEmpty() ? Map.of()
                : documents.selectByIntakeIdIn(intakeIds).stream()
                        .collect(Collectors.toMap(ReceivedDocument::intakeId, Function.identity()));
        Map<String, List<String>> facetsByIntake = intakeIds.isEmpty() ? Map.of()
                : intakeFacets.selectByIntakeIdIn(intakeIds).stream()
                        .collect(Collectors.groupingBy(IntakeFacet::intakeId,
                                Collectors.mapping(IntakeFacet::name, Collectors.toList())));
        /*
         * ⛔ 「미생성」을 하드코딩으로 되돌리지 마라 (2026-08-15). 실제 표를 센다 —
         *    0건인 접수는 결과에 안 나오므로 없는 것을 0 으로 읽는다.
         */
        Map<String, RequirementMapper.IntakeRequirementCount> countsByIntake = intakeIds.isEmpty()
                ? Map.of()
                : requirements.countByIntakeIdIn(intakeIds).stream()
                        .collect(Collectors.toMap(RequirementMapper.IntakeRequirementCount::intakeId,
                                Function.identity()));
        Map<String, String> uploaderNames = uploaderNames(found);

        List<Row> all = found.stream()
                .map(intake -> toRow(intake, documentByIntake.get(intake.id()),
                        facetsByIntake.getOrDefault(intake.id(), List.of()),
                        countsByIntake.get(intake.id()),
                        uploaderNames.getOrDefault(intake.uploadedBy(), intake.uploadedBy())))
                .toList();

        model.addAttribute("totalCount", all.size());

        List<Row> matched = all.stream()
                .filter(row -> matchesQuery(row, query))
                .filter(row -> matchesChoice(row.documentType(), documentType))
                .filter(row -> matchesChoice(row.documentStatus(), documentStatus))
                .filter(row -> matchesFacet(row, facet))
                .toList();

        int size = PAGE_SIZES.contains(pageSize) ? pageSize : PAGE_SIZES.get(0);
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int current = Math.min(Math.max(page, 1), pageCount);

        model.addAttribute("rows", matched.stream()
                .skip((long) (current - 1) * size)
                .limit(size)
                .toList());
        model.addAttribute("matchedCount", matched.size());
        model.addAttribute("page", current);
        model.addAttribute("pageCount", pageCount);
        model.addAttribute("pageNumbers", pageNumbers(current, pageCount));
        model.addAttribute("pageSize", size);
        model.addAttribute("pageSizes", PAGE_SIZES);

        List<String> availableFacets = projectFacets.selectByProjectId(projectId)
                .stream().map(ProjectFacet::name).toList();
        model.addAttribute("hasFacets", !availableFacets.isEmpty());
        model.addAttribute("availableFacets", availableFacets);
        model.addAttribute("documentTypes", ReceivedDocument.DocumentType.values());
        model.addAttribute("documentStatuses", DOCUMENT_STATUSES);
        model.addAttribute("query", query);
        model.addAttribute("documentTypeFilter", documentType);
        model.addAttribute("documentStatusFilter", documentStatus);
        model.addAttribute("facetFilter", facet);

        shell(model, "받은 문서");
        return "artifacts/received-docs";
    }

    /** ⚠ 접수는 계정 <b>번호</b>를 들고 있다 — 사람에게 보일 이름은 여기서 한 번에 끌어온다. */
    private Map<String, String> uploaderNames(List<Intake> found) {
        List<String> ids = found.stream().map(Intake::uploadedBy).distinct().toList();
        // ⛔ 이 이른 반환을 지우지 마라. selectByIdIn 은 `in (…)` 을 쓰는데 빈 목록이면
        //    `in ()` 이 되어 SQL 이 깨진다.
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new java.util.HashMap<>();
        accounts.selectByIdIn(ids).forEach(account -> names.put(account.getId(), account.getName()));
        return names;
    }

    private boolean matchesQuery(Row row, String query) {
        return query == null || query.isBlank()
                || row.title().toLowerCase().contains(query.trim().toLowerCase());
    }

    private boolean matchesChoice(String value, String chosen) {
        return chosen == null || chosen.isBlank() || chosen.equals(ANY) || chosen.equals(value);
    }

    private boolean matchesFacet(Row row, String chosen) {
        return chosen == null || chosen.isBlank() || chosen.equals(ANY) || row.facets().contains(chosen);
    }

    /** 지금 쪽을 가운데 두고 열 개를 낸다. 쪽이 열 개 안쪽이면 전부 낸다. */
    private List<Integer> pageNumbers(int current, int pageCount) {
        int first = Math.max(1, Math.min(current - PAGE_WINDOW / 2, pageCount - PAGE_WINDOW + 1));
        int last = Math.min(pageCount, first + PAGE_WINDOW - 1);
        return java.util.stream.IntStream.rangeClosed(first, last).boxed().toList();
    }

    @GetMapping("/register")
    public String registerForm(@PathVariable String projectId, Model model) {
        model.addAttribute("availableFacets", projectFacets.selectByProjectId(projectId)
                .stream().map(ProjectFacet::name).toList());
        model.addAttribute("documentTypes", ReceivedDocument.DocumentType.values());
        shell(model, "문서 등록");
        return "artifacts/received-doc-register";
    }

    @PostMapping
    public String register(@PathVariable String projectId,
                           @AuthenticationPrincipal BuilderUser me,
                           @RequestParam(required = false) String title,
                           @RequestParam ReceivedDocument.DocumentType documentType,
                           @RequestParam(required = false) List<String> facets,
                           @RequestParam(required = false) MultipartFile file,
                           @RequestParam(required = false) String typedContent,
                           @RequestParam(required = false) String postId,
                           @RequestParam(required = false) String meetingAt,
                           @RequestParam(required = false) String attendees,
                           Model model) {
        try {
            if (documentType == ReceivedDocument.DocumentType.FLOW) {
                String normalizedPostId = postId == null ? "" : postId.strip();
                if (!normalizedPostId.matches("[0-9]{1,15}")) {
                    throw new IllegalArgumentException("Flow 게시물 ID는 15자리 이하 숫자로 입력해 주세요.");
                }
                FlowPost post = flowPosts.get(normalizedPostId);
                title = post.title();
                typedContent = post.content();
                file = null;
                meetingAt = null;
                attendees = null;
            }
            var request = new IntakeService.RegisterRequest(title, documentType, facets, file,
                    typedContent, parseMeetingAt(meetingAt), attendees);
            IntakeService.RegisterResult registered = service.register(projectId, me.accountId(), request);
            /*
             * ⛔ 서비스 **밖**에서 깨운다. 등록 트랜잭션이 커밋된 뒤라야 일꾼이 그 줄을 본다 —
             * 안에서 부르면 아직 안 보이는 접수를 찾다가 「그런 접수가 없다」로 죽는다.
             * ⛔ 언제나 깨우지 마라 — 직접 입력과 서버 추출이 된 문서는 할 일이 없는데도
             *    AI 실행기의 자리를 차지한다. 멀티모달이 필요할 때만 깨운다.
             */
            if (registered.needsContentAnalysis()) {
                dispatchContentAnalysis(registered.intakeId());
            }
            return "redirect:/projects/%s/artifacts/received-docs/%s"
                    .formatted(projectId, registered.intakeId());
        } catch (IllegalArgumentException | FlowPostException rejected) {
            // ⛔ 500 을 내지 않는다. 사람이 고칠 수 있는 것이고, 친 값은 그대로 두고 다시 그린다.
            model.addAttribute("error", rejected.getMessage());
            model.addAttribute("typedTitle", title);
            model.addAttribute("typedContentValue", typedContent);
            model.addAttribute("typedFlowPostId", postId);
            model.addAttribute("selectedDocumentType", documentType);
            return registerForm(projectId, model);
        }
    }

    /**
     * 상세 — <b>기본은 한 칸짜리 문서 내용</b>이다.
     *
     * <p>⛔ 좌우 대조를 기본으로 되돌리지 마라 (2026-08-15). 원문과 대조할 일이 있는 것은
     * <b>멀티모달이 읽어 낸 문서뿐</b>이고, 그때만 확인 모드가 열린다.
     */
    @GetMapping("/{intakeId}")
    public String detail(@PathVariable String projectId, @PathVariable String intakeId, Model model) {
        Intake intake = intakeOf(projectId, intakeId);
        ReceivedDocument document = documents.selectByIntakeId(intakeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "받은 문서가 없다"));

        model.addAttribute("intake", intake);
        model.addAttribute("document", document);
        model.addAttribute("facets", intakeFacets.selectByIntakeId(intakeId).stream()
                .map(IntakeFacet::name).toList());
        model.addAttribute("sizeText", sizeText(document));
        // 무엇을 보여줄지는 상태가 정한다. 스크립트가 아니라 서버가 가른다.
        model.addAttribute("contentProcessing", document.contentState() == ContentState.PROCESSING);
        /*
         * ⛔ 도는 중에 버튼을 열어 두지 마라 (2026-08-16). 사람이 다시 누르면 서버는 「이미 돌고
         *    있습니다」로 막지만, 그 전까지 화면에 <b>돌고 있다는 표시가 하나도 없어</b>
         *    「눌리긴 한 건가」를 알 수 없었다. 막는 것과 알려 주는 것은 다른 일이다.
        */
        boolean running = intake.requirementState() == Intake.RequirementState.RUNNING;
        boolean hasGeneratedRequirements = requirements.countAllByIntakeId(intakeId) > 0;
        model.addAttribute("canAnalyze",
                document.readyForRequirements() && !running && !hasGeneratedRequirements);
        model.addAttribute("hasGeneratedRequirements", hasGeneratedRequirements);
        model.addAttribute("canDeleteRequirements", hasGeneratedRequirements && !running);
        model.addAttribute("canDelete",
                document.contentState() != ContentState.PROCESSING
                        && intake.requirementState() == Intake.RequirementState.NOT_STARTED);
        model.addAttribute("analysisTried",
                intake.requirementState() != Intake.RequirementState.NOT_STARTED);
        // ⛔ 오류 전체에 열지 않는다 — 「멀티모달로도 못 읽는 종류」는 눌러도 같은 자리로 온다.
        model.addAttribute("canRetry", service.canRetryUnderstanding(intakeId));
        int requirementCount = requirements.countByIntakeIdIn(List.of(intakeId)).stream()
                .findFirst()
                .map(RequirementMapper.IntakeRequirementCount::total)
                .orElse(0);
        model.addAttribute("requirementCount", requirementCount);
        shell(model, intake.title());
        return "artifacts/received-doc";
    }

    /**
     * 「내용 분석 다시 시도」 — 오류로 앉은 첨부를 다시 줄에 세운다.
     *
     * <p>⛔ 분석이 끝난 문서에는 안 열린다 — 확정된 분석 결과가 날아간다.
     */
    @PostMapping("/{intakeId}/reprocess")
    public String reprocess(@PathVariable String projectId, @PathVariable String intakeId,
                            RedirectAttributes flash) {
        documentOf(projectId, intakeId);
        if (!service.retryUnderstanding(intakeId)) {
            flash.addFlashAttribute("error", "지금은 내용 분석을 다시 시도할 수 없습니다.");
            return detailRedirect(projectId, intakeId);
        }
        // ⛔ 트랜잭션 **밖**에서 깨운다 — 커밋 전이면 일꾼이 아직 「대기」로 바뀐 줄을 못 본다.
        dispatchContentAnalysis(intakeId);
        return detailRedirect(projectId, intakeId);
    }

    /**
     * 일꾼을 깨운다.
     *
     * <p>⛔ <b>대기줄이 차서 거절돼도 사람에게 500 을 내지 마라.</b> 문서는 이미 앉았고 상태도
     * 「내용 분석 대기」 그대로다 — 잃는 것이 없다. 잊히지도 않는다:
     * {@link DocumentProcessingBootSweep} 이 재기동 때 줄 선 문서를 다시 데려간다.
     * <b>비동기로 넘긴다고 자원 상한이 없어지지 않는다</b>({@code AiRunService} 가 같은 함정을 적어 뒀다).
     */
    private void dispatchContentAnalysis(String intakeId) {
        try {
            processingWorker.process(intakeId);
        } catch (TaskRejectedException full) {
            log.warn("내용 분석을 제출하지 못했다 — 줄에 선 채로 둔다 intakeId={}", intakeId);
        }
    }

    /**
     * 「요구사항 분석」 — <b>사람이 누를 때만</b> 돈다.
     *
     * <p>⛔ 등록할 때 자동으로 부르지 마라 (2026-08-15). 받은 문서를 모두 요구사항으로 만들지 않는다.
     */
    @PostMapping("/{intakeId}/analyze-requirements")
    public String analyzeRequirements(@PathVariable String projectId, @PathVariable String intakeId,
                                      @AuthenticationPrincipal BuilderUser me,
                                      RedirectAttributes flash) {
        intakeOf(projectId, intakeId);
        try {
            service.startRequirementAnalysis(projectId, intakeId, me.accountId());
        } catch (IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return detailRedirect(projectId, intakeId);
        }
        // ⛔ 트랜잭션 **밖**에서 깨운다 — 안에서 부르면 일꾼이 아직 RUNNING 이 아닌 줄을 본다.
        analysisWorker.analyze(intakeId);
        return detailRedirect(projectId, intakeId);
    }

    /** 기존 요구사항을 지워 재분석을 준비한다. 받은 문서와 이미 쓴 REQ 번호는 그대로 둔다. */
    @PostMapping("/{intakeId}/delete-requirements")
    public String deleteRequirements(@PathVariable String projectId, @PathVariable String intakeId,
                                     RedirectAttributes flash) {
        intakeOf(projectId, intakeId);
        try {
            int deleted = service.deleteRequirementsForReanalysis(projectId, intakeId);
            flash.addFlashAttribute("notice", "기존 요구사항 %d건을 삭제했습니다. 다시 분석할 수 있습니다."
                    .formatted(deleted));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return detailRedirect(projectId, intakeId);
    }

    @PostMapping("/{intakeId}/delete")
    public String delete(@PathVariable String projectId, @PathVariable String intakeId,
                         RedirectAttributes flash) {
        intakeOf(projectId, intakeId);
        try {
            service.delete(projectId, intakeId);
            flash.addFlashAttribute("notice", "문서를 삭제했습니다.");
            return "redirect:/projects/%s/artifacts/received-docs".formatted(projectId);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
            return detailRedirect(projectId, intakeId);
        }
    }

    /**
     * 「원본 파일 열기」 — 올린 그대로 돌려준다.
     *
     * <p>⛔ 자리는 <b>DB 에 적힌 값</b>이지 주소에서 온 값이 아니다 — 사람이 경로를 못 민다.
     */
    @GetMapping("/{intakeId}/file")
    public ResponseEntity<Resource> originalFile(@PathVariable String projectId,
                                                 @PathVariable String intakeId) {
        ReceivedDocument document = documentOf(projectId, intakeId);
        if (!document.hasFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "올린 파일이 없다");
        }
        Path seat = Path.of(document.serverPath());
        if (!Files.isReadable(seat)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그 자리에 파일이 없다");
        }
        String name = URLEncoder.encode(document.originalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + name)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(seat));
    }

    private String detailRedirect(String projectId, String intakeId) {
        return "redirect:/projects/%s/artifacts/received-docs/%s".formatted(projectId, intakeId);
    }

    private Intake intakeOf(String projectId, String intakeId) {
        return intakes.selectById(intakeId)
                .filter(found -> found.projectId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 접수가 없다"));
    }

    private ReceivedDocument documentOf(String projectId, String intakeId) {
        intakeOf(projectId, intakeId);
        return documents.selectByIntakeId(intakeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "받은 문서가 없다"));
    }

    /** 서울로 읽는다 — 서버가 어느 지역에 서든 사람이 친 시각이 그 뜻이어야 한다. */
    private Instant parseMeetingAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value).atZone(SEOUL).toInstant();
    }

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }

    /** 받은 문서 목록이 읽는 줄 하나. 문서 상태와 생성된 요구사항 건수만 담는다. */
    public record Row(String intakeId, String title, String documentType, List<String> facets,
                      String documentStatus, boolean failed, boolean contentProcessing,
                      String size, String uploader, int requirementCount,
                      Instant uploadedAt) {
    }

    private Row toRow(Intake intake, ReceivedDocument document, List<String> facets,
                      RequirementMapper.IntakeRequirementCount count, String uploader) {
        ContentState state = document == null ? ContentState.FAILED : document.contentState();
        return new Row(
                intake.id(),
                intake.title(),
                document == null ? "" : document.documentType().label(),
                facets,
                state.label(),
                state == ContentState.FAILED,
                state == ContentState.PROCESSING,
                sizeText(document),
                uploader,
                count == null ? 0 : count.total(),
                intake.uploadedAt());
    }

    private String sizeText(ReceivedDocument document) {
        if (document == null || document.byteSize() == null) {
            return "직접 입력";
        }
        long bytes = document.byteSize();
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return "%.1f KB".formatted(bytes / 1024.0);
        }
        return "%.1f MB".formatted(bytes / (1024.0 * 1024));
    }
}
