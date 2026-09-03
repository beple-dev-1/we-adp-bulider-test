package com.bizplay.builder.intake;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.ClaudeRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.claude.ClaudeCredentialService;
import com.bizplay.builder.claude.ClaudeCredentialFile;
import com.bizplay.builder.claude.ClaudeAccountLocks;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.config.RequirementAnalysisProperties;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요구사항 분석 — <b>사람이 누를 때만</b> 돌고, 초안이 요구사항 표에 앉는다.
 *
 * <p>여기서 재는 것은 <b>언제 열리나</b>(조건 넷) · <b>결과가 어디에 앉나</b> ·
 * <b>번호가 어떻게 서나</b>다. 실물 {@code claude} 는 안 부른다.
 *
 * <p>⚠ <b>{@link RequirementAnalysisWorker} 를 {@code @Autowired} 로 받지 마라</b> —
 * 주입받는 것은 프록시라 {@code @Async} 가 발동해 바로 아래 줄의 검사가 경합이 된다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequirementAnalysisTest extends AbstractDbTest {

    @Autowired IntakeService intakeService;
    @Autowired IntakeMapper intakes;
    @Autowired ReceivedDocumentMapper documents;
    @Autowired RequirementMapper requirements;
    @Autowired DocumentProcessingRunMapper processingRuns;
    @Autowired DocumentProcessingService processing;
    @Autowired RequirementAnalysisService analysis;
    @Autowired RequirementDraftReader reader;
    @Autowired ClaudeCredentialService credentials;
    @Autowired ClaudeCredentialFile credentialFile;
    @Autowired ClaudeAccountLocks accountLocks;
    @Autowired BuilderProperties properties;
    @Autowired RequirementAnalysisProperties analysisProperties;
    @Autowired ProjectPaths paths;
    @Autowired AccountMapper accounts;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    private FakeRunner runner;
    private RequirementAnalysisWorker worker;
    private Project project;
    private Account person;

    @BeforeEach
    void setUp() throws Exception {
        runner = new FakeRunner();
        var credentialRunner = new ClaudeCredentialRunner(
                runner, credentials, credentialFile, accountLocks);
        worker = new RequirementAnalysisWorker(processing, analysis, reader, credentialRunner,
                properties, analysisProperties, paths);
        project = readyProject();
        person = someone();
        // ⚠ 「기획 저장소 사본이 서버에 있나」가 실행 조건 하나다 — 빈 폴더로 그것을 만족시킨다.
        Files.createDirectories(paths.cloneDir(project.getId()));
    }

    @Test
    void 요구사항_분석이_초안을_앉히고_검토_필요로_넘긴다() {
        withClaudeCredential();
        runner.next(success("""
                {"requirement":{"title":"결재 문서 작성 내용 보존",
                  "body":"사용자가 결재 문서 작성 중인 내용을 보존하고 이후 이어서 작성할 수 있어야 한다.",
                  "screens":["wv-appr-write"]}}"""));
        String intakeId = readyIntake("상신할 때 임시저장이 됐으면 좋겠다");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.RUNNING);
        worker.analyze(intakeId);

        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.REVIEW_REQUIRED);
        List<Requirement> saved = requirements.selectByIntakeId(intakeId);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).code()).isEqualTo("REQ-001");
        assertThat(saved.get(0).screenHints()).isEqualTo("wv-appr-write");
        assertThat(saved.get(0).reviewState()).isEqualTo(Requirement.ReviewState.DRAFTED);
    }

    /** ⛔ 원문은 어떤 경우에도 안 바뀐다 — 분석은 읽기만 한다. */
    @Test
    void 분석해도_원문과_문서_내용이_그대로다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("상신할 때 임시저장이 됐으면 좋겠다");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        ReceivedDocument document = documents.selectByIntakeId(intakeId).orElseThrow();
        assertThat(document.typedContent()).isEqualTo("상신할 때 임시저장이 됐으면 좋겠다");
        assertThat(document.documentContent()).isEqualTo("상신할 때 임시저장이 됐으면 좋겠다");
        assertThat(document.contentState()).isEqualTo(ReceivedDocument.ContentState.READY);
    }

    /** ⛔ 등록 완료가 아닌 문서에서는 시작할 수 없다 — 읽은 글이 없는데 분석이 돌면 안 된다. */
    @Test
    void 등록_완료가_아니면_요구사항_분석을_시작할_수_없다() {
        withClaudeCredential();
        String intakeId = queuedIntake();

        assertThatThrownBy(() ->
                intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("문서 내용이 확정된 뒤에");
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.NOT_STARTED);
    }

    /** ⛔ 같은 문서의 분석을 동시에 두 번 시작할 수 없다 — 판정은 조건부 UPDATE 가 한다. */
    @Test
    void 같은_문서의_요구사항_분석을_동시에_두_번_시작할_수_없다() {
        withClaudeCredential();
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());

        assertThatThrownBy(() ->
                intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 돌고 있");
    }

    @Test
    void 기존_요구사항이_있으면_삭제하기_전에는_다시_분석하지_않는다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("내용");
        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThatThrownBy(() ->
                intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기존 요구사항을 삭제한 뒤");
        assertThat(requirements.selectByIntakeId(intakeId)).hasSize(1);
    }

    @Test
    void 기존_요구사항을_삭제하고_다시_분석해도_REQ_번호는_재사용하지_않는다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"처음\",\"body\":\"처음 본문\"}}"));
        runner.next(success("{\"requirement\":{\"title\":\"다시\",\"body\":\"다시 본문\"}}"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);
        assertThat(requirements.selectByIntakeId(intakeId).get(0).code()).isEqualTo("REQ-001");

        intakeService.deleteRequirementsForReanalysis(project.getId(), intakeId);
        assertThat(requirements.selectByIntakeId(intakeId)).isEmpty();
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.NOT_STARTED);

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);
        assertThat(requirements.selectByIntakeId(intakeId).get(0).code()).isEqualTo("REQ-002");
    }

    @Test
    void 받은_문서_한_건을_요구사항_한_건으로_해석하는_프롬프트를_실행하고_로그에_남긴다(CapturedOutput output) {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(runner.lastInstruction)
                .contains("받은 문서와 기획 저장소의 지식베이스를 함께 분석")
                .contains("받은 문서 1건에서 요구사항 1건만 작성")
                .contains("세부 요구로 나누는 일은 요구사항정의서의 역할")
                .contains("현재 동작이나 기술 사실을 그대로 요구사항으로 복사하지 마라")
                .contains("관련 화면 ID를 `screens`에 함께 적어라")
                .doesNotContain("일부만 추출")
                .doesNotContain("50건");
        assertThat(output)
                .contains("요구사항 분석 실행 프롬프트")
                .contains("받은 문서와 기획 저장소의 지식베이스를 함께 분석")
                .contains("문서 전체의 핵심 업무 목적·기대 결과·주요 범위를 통합한 요구 본문");
    }

    /** Claude 자격이 없으면 시작 자체가 안 열린다 — 돌려 놓고 실패시키지 않는다. */
    @Test
    void Claude_자격이_없으면_시작이_안_열린다() {
        String intakeId = readyIntake("내용");

        assertThatThrownBy(() ->
                intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claude 계정을 먼저 연결");
    }

    /** 실패하면 오류로 앉고, <b>다시 시작할 수 있다.</b> ⛔ RUNNING 으로 굳으면 영영 못 누른다. */
    @Test
    void 실패해도_다시_분석할_수_있다() {
        withClaudeCredential();
        runner.next(new ClaudeResult(1, true, "api_error", 500, ""));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.FAILED);
        assertThat(requirements.selectByIntakeId(intakeId)).isEmpty();

        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.REVIEW_REQUIRED);
        assertThat(requirements.selectByIntakeId(intakeId)).hasSize(1);
        var document = documents.selectByIntakeId(intakeId).orElseThrow();
        assertThat(processingRuns.selectByDocumentId(document.id()))
                .as("⛔ 재시도가 앞의 시도를 덮지 않는다").hasSize(2);
    }

    @Test
    void API_오류의_상태와_안전한_요약을_로그와_실행_이력에_남긴다(CapturedOutput output) {
        withClaudeCredential();
        runner.next(new ClaudeResult(1, true, "api_error", 529,
                "Overloaded error · Bearer secret-access-token"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(output)
                .contains("apiStatus=529")
                .contains("detail=Overloaded error")
                .doesNotContain("secret-access-token");
        var document = documents.selectByIntakeId(intakeId).orElseThrow();
        DocumentProcessingRun failed = processingRuns.selectByDocumentId(document.id()).get(0);
        assertThat(failed.errorMessage())
                .contains("apiStatus=529")
                .contains("detail=Overloaded error")
                .doesNotContain("secret-access-token");
    }

    @Test
    void Claude가_실행_중_갱신한_OAuth_자격을_DB에_보존한다() {
        withClaudeCredential();
        runner.refreshCredential("""
                {"claudeAiOauth":{"accessToken":"새 액세스 토큰","refreshToken":"새 갱신 토큰",
                  "expiresAt":999,"refreshTokenExpiresAt":1999},
                 "mcpOAuth":{"example":{"accessToken":"저장하면 안 되는 토큰"}}}
                """);
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(credentials.tokenOf(person.getId()).orElseThrow())
                .contains("새 액세스 토큰")
                .contains("새 갱신 토큰")
                .contains("refreshTokenExpiresAt")
                .doesNotContain("mcpOAuth")
                .doesNotContain("저장하면 안 되는 토큰");
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.REVIEW_REQUIRED);
    }

    /**
     * ⛔ {@code --model} 은 {@code --add-dir} <b>앞</b>에 서야 한다 — 저쪽은 값을 여러 개 받는 꼴이라
     * 뒤에 두면 {@code --model} 과 그 값이 그 목록으로 빨려 들어간다({@code CliClaudeRunner.command}).
     */
    @Test
    void 요구사항_분석은_설정한_모델로_돌고_그_플래그가_add_dir_앞에_선다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(runner.lastExtraArgs).containsSequence("--model", analysisProperties.model());
        assertThat(runner.lastExtraArgs.indexOf("--model"))
                .as("⛔ --add-dir 이 뒤에 와야 한다")
                .isLessThan(runner.lastExtraArgs.indexOf("--add-dir"));
    }

    /**
     * ⛔ 사람이 실행 중에 계정을 다시 연결하면 <b>그것이 이긴다.</b> 끝나는 실행이 제가 들고 있던
     * 낡은 자격으로 덮으면, 방금 손으로 살려 놓은 연결이 조용히 죽는다.
     */
    @Test
    void 실행_중_사람이_다시_연결하면_그_자격이_살아남는다() {
        withClaudeCredential();
        runner.duringRun(() -> credentials.store(person.getId(), """
                {"claudeAiOauth": {"accessToken": "사람이 다시 연결한 것", "refreshToken": "새것",
                  "expiresAt": 777}}"""));
        runner.refreshCredential("""
                {"claudeAiOauth":{"accessToken":"실행이 갱신한 낡은 것","refreshToken":"낡은것",
                  "expiresAt":111}}
                """);
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(credentials.tokenOf(person.getId()).orElseThrow())
                .contains("사람이 다시 연결한 것")
                .doesNotContain("실행이 갱신한 낡은 것");
    }

    /** ⛔ 모양이 다르면 반쯤 건져 저장하지 않는다 — 번호는 타면 되돌릴 수 없다. */
    @Test
    void 결과_모양이_다르면_하나도_저장하지_않는다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"제목만 있다\"}}"));
        String intakeId = readyIntake("내용");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(requirements.selectByIntakeId(intakeId)).isEmpty();
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.FAILED);
    }

    @Test
    void 여러_요구사항을_돌려주면_문서당_한_건_계약을_어긴_결과로_거절한다() {
        withClaudeCredential();
        runner.next(success("""
                {"requirements":[{"title":"ㄱ","body":"ㄴ"},{"title":"ㄷ","body":"ㄹ"}]}"""));
        String intakeId = readyIntake("서로 다른 세부 내용이 함께 있는 문서");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        assertThat(requirements.selectByIntakeId(intakeId)).isEmpty();
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.FAILED);
    }

    /** ⛔ 번호는 프로젝트마다 1번부터이고 재사용하지 않는다. */
    @Test
    void 요구사항_번호는_프로젝트마다_이어서_붙는다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        runner.next(success("{\"requirement\":{\"title\":\"ㄷ\",\"body\":\"ㄹ\"}}"));
        String first = readyIntake("첫 문서");
        String second = readyIntake("둘째 문서");

        intakeService.startRequirementAnalysis(project.getId(), first, person.getId());
        worker.analyze(first);
        intakeService.startRequirementAnalysis(project.getId(), second, person.getId());
        worker.analyze(second);

        assertThat(requirements.selectByIntakeId(first).get(0).number()).isEqualTo(1);
        assertThat(requirements.selectByIntakeId(second).get(0).number()).isEqualTo(2);
    }

    /** 목록의 「요구사항 현황」이 읽는 건수 — 하드코딩이 아니라 실제 표를 센다. */
    @Test
    void 접수별_요구사항_건수를_실제_표에서_센다() {
        withClaudeCredential();
        runner.next(success("{\"requirement\":{\"title\":\"ㄱ\",\"body\":\"ㄴ\"}}"));
        String intakeId = readyIntake("내용");
        String untouched = readyIntake("안 건드린 문서");

        intakeService.startRequirementAnalysis(project.getId(), intakeId, person.getId());
        worker.analyze(intakeId);

        var counts = requirements.countByIntakeIdIn(List.of(intakeId, untouched));
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).intakeId()).isEqualTo(intakeId);
        assertThat(counts.get(0).total()).isEqualTo(1);
        assertThat(counts.get(0).confirmed()).isZero();
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private void withClaudeCredential() {
        credentials.store(person.getId(), """
                {"claudeAiOauth": {"accessToken": "시험용", "refreshToken": "시험용", "expiresAt": 1}}""");
    }

    private static ClaudeResult success(String body) {
        return new ClaudeResult(0, false, "completed", null, body);
    }

    /** 직접 입력으로 등록 — 등록 즉시 완료라 요구사항 분석이 바로 열린다. */
    private String readyIntake(String typed) {
        return intakeService.register(project.getId(), person.getId(),
                new IntakeService.RegisterRequest("8/13 운영회의 회의록",
                        ReceivedDocument.DocumentType.MEETING_MINUTES, List.of(),
                        null, typed, null, null)).intakeId();
    }

    /** 글자가 안 나오는 PDF — 멀티모달을 기다리는 자리다. */
    private String queuedIntake() {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "회의록.pdf",
                "application/pdf", "%PDF-1.4\n   ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        return intakeService.register(project.getId(), person.getId(),
                new IntakeService.RegisterRequest("스캔 회의록",
                        ReceivedDocument.DocumentType.MEETING_MINUTES, List.of(),
                        file, null, null, null)).intakeId();
    }

    private Project readyProject() {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "탐나는전-" + id,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account someone() {
        var account = Account.create(ids.next(IdSequence.Kind.ACCOUNT),
                "planner-" + ids.next(IdSequence.Kind.ACCOUNT), "기획자",
                "planner@example.com", "해시", false);
        accounts.insert(account);
        return account;
    }

    /**
     * 실물 {@code claude} 를 안 띄운다.
     *
     * <p>⚠ 프로세스를 안 띄우므로 {@code onStarted} 를 부르지 않는다 —
     * 요구사항 분석은 그만두기 손잡이가 없어서 그것으로 잃는 것이 없다.
     */
    private static final class FakeRunner implements ClaudeRunner {

        private final Deque<ClaudeResult> queued = new ArrayDeque<>();
        private String lastInstruction;
        private List<String> lastExtraArgs = List.of();
        private String refreshedCredential;
        private Runnable duringRun;

        void next(ClaudeResult result) {
            queued.add(result);
        }

        void refreshCredential(String credential) {
            refreshedCredential = credential;
        }

        /** 실행이 도는 <b>동안</b> 끼어드는 일. 사람이 그 사이에 재연결하는 자리를 재는 데 쓴다. */
        void duringRun(Runnable interleaved) {
            duringRun = interleaved;
        }

        /**
         * ⚠ 조각을 <b>기억하려고</b> 이것을 덮어쓴다. 기본 구현은 조각을 버리므로
         * {@code --model} 같은 플래그가 붙었는지를 시험이 볼 수 없다.
         */
        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                List<String> extraArgs, String instruction, Consumer<Process> onStarted) {
            lastExtraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            return run(credentialDir, workDir, timeout, instruction, onStarted);
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                String instruction, Consumer<Process> onStarted) {
            lastInstruction = instruction;
            if (duringRun != null) {
                Runnable interleaved = duringRun;
                duringRun = null;
                interleaved.run();
            }
            if (refreshedCredential != null) {
                try {
                    Files.writeString(credentialDir.resolve(".credentials.json"),
                            refreshedCredential, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                refreshedCredential = null;
            }
            ClaudeResult queuedResult = queued.poll();
            return queuedResult != null ? queuedResult : success("{\"requirement\":{}}");
        }
    }
}
