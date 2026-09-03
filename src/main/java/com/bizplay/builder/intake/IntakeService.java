package com.bizplay.builder.intake;

import com.bizplay.builder.claude.ClaudeCredentialService;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ReceivedDocument.ContentState;
import com.bizplay.builder.intake.ReceivedDocument.DocumentIntakePlan;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 받은 문서를 올리고, 내용을 확인하고, 요구사항 분석을 <b>집는다</b>.
 *
 * <p><b>2026-08-15 에 「AI 가 늘 정리한다」가 폐기됐다.</b> 등록하는 그 자리에서 상태가 정해진다 —
 * <pre>
 * 직접 입력              → 등록 완료 (AI 를 아예 안 부른다)
 * 첨부 · 서버 추출 성공  → 등록 완료 (AI 를 아예 안 부른다)
 * 첨부 · 글자가 안 나옴  → 내용 분석 대기 (멀티모달이 읽는다)
 * 첨부 · 읽을 수 없는 종류 → 문서 처리 오류
 * </pre>
 *
 * <p>⛔ <b>못 읽는 문서도 올라간다.</b> 원본 보존이 규칙이다(→ {@code intake}).
 * 판정이 막는 것은 <b>다음 걸음</b>뿐이고, 등록 자체는 늘 성공한다.
 *
 * <p>⚠ <b>2026-08-15 에 데이터 접근이 JPA 에서 MyBatis 로 바뀌었다.</b> 그러면서 엔티티가 지고 있던
 * 상태 규칙이 이리로 왔다 — 더티 체킹이 없어져 엔티티에 고치는 메서드를 두면 저장된 줄 알고
 * DB 는 안 바뀌기 때문이다. 규칙은 그대로다.
 */
@Service
public class IntakeService {

    private final IntakeMapper intakes;
    private final ReceivedDocumentMapper documents;
    private final DocumentProcessingRunMapper processingRuns;
    private final RequirementMapper requirements;
    private final ProjectFacetMapper projectFacets;
    private final IntakeFacetMapper intakeFacets;
    private final ProjectMapper projects;
    private final ClaudeCredentialService credentials;
    private final IdSequence ids;
    private final ProjectPaths paths;
    private final DocumentReadCheck readCheck;
    private final DocumentTextExtractor extractor;

    public IntakeService(IntakeMapper intakes, ReceivedDocumentMapper documents,
                         DocumentProcessingRunMapper processingRuns,
                         RequirementMapper requirements,
                         ProjectFacetMapper projectFacets, IntakeFacetMapper intakeFacets,
                         ProjectMapper projects, ClaudeCredentialService credentials,
                         IdSequence ids, ProjectPaths paths,
                         DocumentReadCheck readCheck, DocumentTextExtractor extractor) {
        this.intakes = intakes;
        this.documents = documents;
        this.processingRuns = processingRuns;
        this.requirements = requirements;
        this.projectFacets = projectFacets;
        this.intakeFacets = intakeFacets;
        this.projects = projects;
        this.credentials = credentials;
        this.ids = ids;
        this.paths = paths;
        this.readCheck = readCheck;
        this.extractor = extractor;
    }

    /**
     * 등록의 결과.
     *
     * @param needsContentAnalysis 멀티모달이 읽어야 하나. ⚠ <b>참일 때만</b> 일꾼을 깨운다 —
     *                             직접 입력까지 깨우면 AI 실행기의 자리를 아무 일도 없이 차지한다
     */
    public record RegisterResult(String intakeId, boolean needsContentAnalysis) {
    }

    /**
     * @param request 적용 구분은 <b>있는</b> 프로젝트에서는 하나 이상이어야 하고,
     *                없는 프로젝트에서는 비어 있어야 한다 — 화면에 뜨지도 않기 때문이다.
     */
    @Transactional
    public RegisterResult register(String projectId, String uploaderId, RegisterRequest request) {
        String title = trimmed(request.title());
        if (title.isEmpty()) {
            throw new IllegalArgumentException("문서명을 입력해 주세요.");
        }
        boolean hasFile = request.file() != null && !request.file().isEmpty();
        boolean hasTypedContent = request.typedContent() != null && !request.typedContent().isBlank();
        if (!hasFile && !hasTypedContent) {
            throw new IllegalArgumentException("첨부파일 또는 문서 내용을 하나 이상 입력해 주세요.");
        }
        Set<String> available = availableFacets(projectId);
        List<String> chosen = request.facets() == null ? List.of() : request.facets();
        checkFacets(available, chosen);

        String intakeId = ids.next(IdSequence.Kind.INTAKE);
        intakes.insert(Intake.create(intakeId, projectId, title, uploaderId));
        for (String name : chosen) {
            intakeFacets.insert(IntakeFacet.create(intakeId, projectId, name));
        }

        String serverPath = null;
        String originalName = null;
        Long byteSize = null;
        if (hasFile) {
            originalName = request.file().getOriginalFilename();
            Path seat = storeFile(projectId, intakeId, originalName, request.file());
            serverPath = seat.toString();
            byteSize = request.file().getSize();
        }

        DocumentIntakePlan plan = hasFile
                ? planForFile(Path.of(serverPath))
                : DocumentIntakePlan.typedOnly(request.typedContent());

        documents.insert(ReceivedDocument.create(
                ids.next(IdSequence.Kind.RECEIVED_DOCUMENT), intakeId, request.documentType(),
                originalName, serverPath, byteSize, request.typedContent(),
                request.meetingAt(), request.attendees(), plan));

        return new RegisterResult(intakeId, plan.state() == ContentState.QUEUED);
    }

    /** 요구사항 분석을 한 번도 시작하지 않은 받은 문서만 지운다. */
    @Transactional
    public void delete(String projectId, String intakeId) {
        Intake intake = intakes.selectById(intakeId)
                .orElseThrow(() -> new IllegalArgumentException("받은 문서가 없습니다."));
        if (!intake.projectId().equals(projectId)) {
            throw new IllegalArgumentException("받은 문서가 없습니다.");
        }
        if (intake.requirementState() != Intake.RequirementState.NOT_STARTED) {
            throw new IllegalStateException("요구사항으로 넘긴 문서는 삭제할 수 없습니다.");
        }
        ReceivedDocument document = documentOf(intakeId);
        if (intakes.deleteNotStarted(projectId, intakeId) == 0) {
            throw new IllegalStateException("요구사항으로 넘긴 문서는 삭제할 수 없습니다.");
        }
        deleteStoredFileAfterCommit(projectId, document.serverPath());
    }

    /**
     * 기존 요구사항을 지워 재분석할 수 있는 상태로 되돌린다.
     *
     * <p>현재 요구사항정의서 생성 기능은 아직 열리지 않아 저장된 요구사항은 모두 정의서 전 단계다.
     * 정의서가 구현되면 연결된 요구사항을 여기서 거절하는 판정을 반드시 먼저 추가한다.
     * ⛔ 요구사항 행만 지우고 프로젝트의 번호 카운터는 되돌리지 않는다.
     */
    @Transactional
    public int deleteRequirementsForReanalysis(String projectId, String intakeId) {
        Intake intake = intakes.selectByIdForUpdate(intakeId)
                .orElseThrow(() -> new IllegalArgumentException("받은 문서가 없습니다."));
        if (!intake.projectId().equals(projectId)) {
            throw new IllegalArgumentException("받은 문서가 없습니다.");
        }
        if (intake.requirementState() == Intake.RequirementState.RUNNING) {
            throw new IllegalStateException("요구사항 분석 중에는 기존 요구사항을 삭제할 수 없습니다.");
        }
        if (requirements.countAllByIntakeId(intakeId) == 0) {
            throw new IllegalStateException("삭제할 요구사항이 없습니다.");
        }
        int deleted = requirements.deleteForReanalysis(intakeId);
        intakes.updateRequirementState(intakeId, Intake.RequirementState.NOT_STARTED);
        return deleted;
    }

    private void deleteStoredFileAfterCommit(String projectId, String serverPath) {
        if (serverPath == null) {
            return;
        }
        Path file = Path.of(serverPath).toAbsolutePath().normalize();
        Path receivedRoom = paths.receivedDir(projectId).toAbsolutePath().normalize();
        if (!file.startsWith(receivedRoom)) {
            throw new IllegalStateException("원본 파일 경로가 받은 문서 보관 위치를 벗어났습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException failed) {
                    // DB 삭제는 이미 확정됐다. 남은 파일은 운영 로그로 찾아 정리한다.
                    org.slf4j.LoggerFactory.getLogger(IntakeService.class)
                            .warn("삭제한 받은 문서의 원본 파일을 지우지 못했다 path={}", file);
                }
            }
        });
    }

    /**
     * 올린 파일을 어떻게 다룰지 정한다.
     *
     * <p>⛔ <b>판정이 터져도 등록을 되돌리지 않는다.</b> 원본은 이미 앉았고 보존이 규칙이다 —
     * 못 재면 「모르겠다」가 아니라 <b>못 읽는다</b>로 적어 다음 걸음만 닫는다.
     *
     * <p>⛔ <b>여기서 AI 를 부르지 마라.</b> 등록 요청이 그동안 막힌다. 멀티모달이 필요하면
     * 줄에 세워 두고({@link ContentState#QUEUED}) {@link DocumentProcessingWorker} 가 가져간다.
     */
    private DocumentIntakePlan planForFile(Path seat) {
        DocumentReadCheck.ReadVerdict verdict;
        try {
            verdict = readCheck.inspect(seat);
        } catch (IOException unreadable) {
            return DocumentIntakePlan.unreadable("올린 파일을 서버가 열지 못했다 — 다시 올려 주세요");
        }
        if (verdict.needsUnderstanding()) {
            return DocumentIntakePlan.needsUnderstanding(verdict.reason());
        }
        if (!verdict.readable()) {
            return DocumentIntakePlan.unreadable(verdict.reason());
        }
        try {
            // ⚠ 판정에서 이미 한 번 뽑아 봤지만 그때는 「나오나」만 봤다. 여기서 실제로 담는다.
            return DocumentIntakePlan.extracted(extractor.extract(seat));
        } catch (IOException noText) {
            return DocumentIntakePlan.unreadable(noText.getMessage());
        }
    }

    /**
     * 「내용 분석 다시 시도」를 열어도 되나.
     *
     * <p>⛔ 도는 중에는 안 연다 — 같은 문서에 시도가 둘 겹치면 나중 것이 앞의 결과를 덮는다.
     * ⛔ 이미 분석이 끝난 문서에도 안 연다 — 확정된 분석 결과가 날아간다.
     *
     * <p>⭐ <b>「오류」에는 갈래가 둘이고 그중 하나만 다시 시도할 수 있다.</b>
     * ① 한컴·오피스 압축 문서처럼 <b>멀티모달로도 못 읽는 종류</b> — 다시 눌러야 같은 자리로 온다
     * ② 멀티모달이 <b>돌다 실패한</b> 문서 — 저쪽 사정일 수 있어 다시 시도가 뜻이 있다.
     * 가르는 표는 <b>내용 분석 시도가 한 번이라도 있었나</b>다: ①은 등록에서 바로 오류라 시도가 0건이다.
     * ⛔ 이 판정을 지우고 오류 전체에 버튼을 열지 마라 — 사람이 눌러도 아무것도 안 바뀌는 자리가 생긴다.
     */
    @Transactional(readOnly = true)
    public boolean canRetryUnderstanding(String intakeId) {
        ReceivedDocument document = documentOf(intakeId);
        return document.hasFile()
                && document.contentState() == ContentState.FAILED
                && processingRuns.selectByDocumentId(document.id()).stream()
                        .anyMatch(run -> run.kind() == DocumentProcessingRun.Kind.UNDERSTAND);
    }

    /**
     * 「내용 분석 다시 시도」 — 다시 줄에 세운다.
     *
     * @return 다시 줄에 섰나. 거짓이면 부르는 쪽이 사람에게 「지금은 안 된다」고 말한다
     */
    @Transactional
    public boolean retryUnderstanding(String intakeId) {
        if (!canRetryUnderstanding(intakeId)) {
            return false;
        }
        documents.updateRequeued(documentOf(intakeId).id());
        return true;
    }

    /**
     * 요구사항 분석을 <b>집는다</b>. 조건을 다 지나면 {@code RUNNING} 으로 앉히고 참을 돌려준다.
     *
     * <p>조건 넷(→ 받은 문서 개편 §7) —
     * ① 문서 상태가 등록 완료 ② 같은 문서의 분석이 실행 중이 아님
     * ③ 프로젝트의 기획 저장소가 쓸 수 있는 상태 ④ 실행하는 사람의 Claude 자격이 연결돼 있음.
     *
     * <p>⛔ <b>②는 자바로 재지 마라.</b> 읽고 쓰는 사이에 남이 들어오면 두 실행이 같이 뜬다 —
     * 조건이 {@link IntakeMapper#updateRequirementStateToRunning} 의 {@code where} 에 있다.
     *
     * @throws IllegalStateException 조건이 안 맞을 때. 사람이 읽는 한글로 던진다
     */
    @Transactional
    public void startRequirementAnalysis(String projectId, String intakeId, String accountId) {
        ReceivedDocument document = documentOf(intakeId);
        if (!document.readyForRequirements()) {
            throw new IllegalStateException("문서 내용이 확정된 뒤에 요구사항을 분석할 수 있습니다.");
        }
        if (requirements.countAllByIntakeId(intakeId) > 0) {
            throw new IllegalStateException(
                    "이미 생성된 요구사항이 있습니다. 기존 요구사항을 삭제한 뒤 다시 분석해 주세요.");
        }
        var project = projects.selectById(projectId)
                .orElseThrow(() -> new IllegalStateException("그런 프로젝트가 없습니다."));
        if (project.getState() != ProjectState.READY) {
            throw new IllegalStateException("기획 저장소가 아직 준비되지 않았습니다.");
        }
        if (!Files.isDirectory(paths.cloneDir(projectId))) {
            throw new IllegalStateException("서버에 기획 저장소 사본이 없습니다 — 관리자에게 문의해 주세요.");
        }
        if (!credentials.isConnected(accountId)) {
            throw new IllegalStateException("Claude 계정을 먼저 연결해 주세요.");
        }
        if (intakes.updateRequirementStateToRunning(intakeId) == 0) {
            throw new IllegalStateException("이 문서의 요구사항 분석이 이미 돌고 있습니다.");
        }
    }

    private ReceivedDocument documentOf(String intakeId) {
        return documents.selectByIntakeId(intakeId)
                .orElseThrow(() -> new IllegalStateException("받은 문서가 없는 접수다: " + intakeId));
    }

    private void checkFacets(Set<String> available, List<String> chosen) {
        if (available.isEmpty()) {
            if (!chosen.isEmpty()) {
                // 화면에 뜨지도 않는 값이 왔다 — 주소를 손으로 친 것이다.
                throw new IllegalArgumentException("이 프로젝트에는 적용 구분이 없습니다.");
            }
            return;
        }
        if (chosen.isEmpty()) {
            throw new IllegalArgumentException("이 문서가 해당하는 적용 구분을 하나 이상 선택해 주세요.");
        }
        // ⚠ DB FK 가 같은 것을 막지만 여기서 먼저 잡아 사람이 읽는 말로 돌려준다.
        for (String name : chosen) {
            if (!available.contains(name)) {
                throw new IllegalArgumentException("이 프로젝트의 적용 구분이 아닙니다: " + name);
            }
        }
    }

    private Path storeFile(String projectId, String intakeId, String originalName, MultipartFile file) {
        try {
            Path room = paths.receivedDir(projectId);
            Files.createDirectories(room);
            Path seat = room.resolve(intakeId + "-" + safeFileName(originalName));
            try (var in = file.getInputStream()) {
                Files.copy(in, seat, StandardCopyOption.REPLACE_EXISTING);
            }
            return seat;
        } catch (IOException failed) {
            throw new UncheckedIOException("올린 파일을 서버에 두지 못했다", failed);
        }
    }

    /**
     * ⛔ <b>원본 파일 이름을 그대로 경로에 쓰지 마라.</b> 경로 구분자와 {@code ..} 를 걷어낸다 —
     * 안 걷어내면 클론 폴더 밖을 가리키는 자리가 난다.
     */
    static String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "문서";
        }
        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);          // 경로를 통째로 떨군다
        name = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");         // 남은 특수문자를 밀어낸다
        name = name.replaceAll("^[._]+", "");                      // 앞의 점은 '..' 과 숨김 파일 둘 다다
        return name.isBlank() ? "문서" : name;
    }

    private Set<String> availableFacets(String projectId) {
        return projectFacets.selectByProjectId(projectId).stream()
                .map(ProjectFacet::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    /** 등록 화면이 보내는 것. 파일과 직접 입력 중 하나 이상이고 둘 다 와도 된다. */
    public record RegisterRequest(String title,
                                  ReceivedDocument.DocumentType documentType,
                                  List<String> facets,
                                  MultipartFile file,
                                  String typedContent,
                                  Instant meetingAt,
                                  String attendees) {
    }
}
