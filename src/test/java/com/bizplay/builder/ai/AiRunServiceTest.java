package com.bizplay.builder.ai;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.AiRun.CheckerResult;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.claude.ClaudeAccountLocks;
import com.bizplay.builder.claude.ClaudeCredentialFile;
import com.bizplay.builder.claude.ClaudeCredentialService;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 실행 한 건 — <b>끝 다섯</b> 갈래 중 하나로 끝난다(→ 계획 2 Task 3).
 *
 * <p>여기서 재는 것은 <b>판정과 경합</b>이다. 실물 {@code claude} 는 안 부른다 — 대역 러너가 결과를 내놓고,
 * 이 시험은 그 결과를 무엇으로 읽어 무엇으로 닫는지만 본다. 프로세스 조립은
 * {@link CliClaudeRunnerTest} 가 따로 잰다.
 *
 * <p>⛔ <b>「한도」 시험을 만들지 마라.</b> 상태 자체를 안 만든다 — 한도 초과를 갈라낼 수 있는지
 * 2026-08-14 스파이크가 <b>못 쟀고</b>, {@code api_error_status == 429} 는 추정이다.
 * 추정으로 분기를 만들면 아무도 안 걸리거나 엉뚱한 것이 걸린다.
 */
class AiRunServiceTest extends AbstractDbTest {

    @Autowired AiRunService service;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 재는 것은 그대로 「DB 에 뭐가 남았나」다. */
    @Autowired AiRunMapper repository;
    @Autowired AccountMapper accounts;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다. */
    @Autowired ProjectMapper projects;
    @Autowired ClaudeCredentialService credentials;
    @Autowired ClaudeCredentialFile credentialFile;
    @Autowired ClaudeAccountLocks accountLocks;
    @Autowired BuilderProperties properties;
    @Autowired ProjectPaths paths;
    @Autowired SecretSealer sealer;

    @TempDir Path workDir;

    private FakeRunner runner;
    private Project project;
    private Account person;
    private Path targetFile;

    @BeforeEach
    void setUp() throws IOException {
        runner = new FakeRunner();
        project = readyProject();
        person = someone();
        credentials.store(person.getId(), """
                {"claudeAiOauth": {"accessToken": "시험용", "refreshToken": "시험용", "expiresAt": 1}}""");
        targetFile = Files.writeString(workDir.resolve("brd.md"), "고치기 전");
    }

    /**
     * ⚠ <b>{@link AiRunWorker} 를 {@code @Autowired} 로 받지 마라.</b> 주입받는 것은 프록시라
     * {@code @Async("aiExecutor")} 가 그대로 발동해 다른 스레드로 넘어가고, 바로 아래 줄의 상태 검사가
     * <b>경합</b>이 된다. {@code CloneWorkerTest} 가 이미 그 방식이고 이유도 거기 적혀 있다.
     *
     * <p>⛔ <b>테스트에서만 동기 실행기를 끼우는 우회를 쓰지 마라</b> — 같은 스레드·같은 영속성 컨텍스트라
     * <b>틀린 이유로 초록불이 뜬다</b>(운영에서는 다른 스레드라 준영속이 된다).
     */
    private AiRunWorker worker() {
        var credentialRunner = new ClaudeCredentialRunner(
                runner, credentials, credentialFile, accountLocks);
        return new AiRunWorker(service, credentialRunner, properties, paths);
    }

    private AiRunRequest request(String instruction) {
        return request(instruction, "0000007");
    }

    private AiRunRequest request(String instruction, String number) {
        return new AiRunRequest(new WorkKey(project.getId(), WorkKey.INTAKE, number),
                AiRunKind.EXTRACT_REQUIREMENTS, project.getId(), person.getId(),
                workDir, List.of(targetFile), instruction);
    }

    /** 시작 → 일꾼을 손으로 돌린다 → DB 에서 다시 읽는다. <b>반환 엔티티를 믿지 않는다.</b> */
    private AiRun runAndReload(String instruction) {
        String runId = service.start(request(instruction));
        worker().run(runId);
        return repository.selectById(runId).orElseThrow();
    }

    // ── 끝 다섯 ──────────────────────────────────────────────────────────

    @Test
    void 시작한다는_끝_상태가_아니라_돌고있음_인_열쇠를_돌려준다() {
        String runId = service.start(request(null));

        // ⛔ 여기서 끝 상태를 단언하면 계약이 거꾸로다. 넣자마자 돌아오는 것이 이 메서드의 값이다.
        assertThat(repository.selectById(runId).orElseThrow().getState())
                .isEqualTo(AiRunState.RUNNING);
    }

    @Test
    void 다_잘_되면_성공으로_닫힌다() {
        runner.nextResult(new ClaudeResult(0, false, "completed", null, "뽑았다"));

        assertThat(runAndReload(null).getState()).isEqualTo(AiRunState.SUCCEEDED);
    }

    @Test
    void 자격이_끊기면_자격끊김으로_닫히고_다시해보라를_안_붙인다() {
        runner.nextResult(new ClaudeResult(1, true, "api_error", null, "Not logged in · Please run /login"));

        AiRun run = runAndReload(null);

        assertThat(run.getState()).isEqualTo(AiRunState.CREDENTIAL_LOST);
        assertThat(run.userMessage()).contains("Claude 연결이 끊겼다");
        // 다시 해봐야 똑같이 끊긴다 — 사람이 할 일은 재시도가 아니라 다시 잇는 것이다.
        assertThat(run.userMessage()).doesNotContain("다시 해보");
    }

    @Test
    void 로그인은_됐는데_다른_api_오류면_자격끊김이_아니라_실패다() {
        // 상태 없는 API 오류도 api_error + null 로 온다 — 본문까지 봐야 갈린다.
        runner.nextResult(new ClaudeResult(1, true, "api_error", null, "connection reset"));

        assertThat(runAndReload(null).getState()).isEqualTo(AiRunState.FAILED);
    }

    @Test
    void 종료코드가_0이_아니면_is_error가_false여도_성공이_아니다() {
        runner.nextResult(new ClaudeResult(2, false, null, null, "무언가"));

        assertThat(runAndReload(null).getState()).isEqualTo(AiRunState.FAILED);
    }

    /**
     * 죽이고 <b>끝난 것을 확인하는</b> 걸음은 {@link CliClaudeRunner} 안에 있다 —
     * 여기서 재는 것은 <b>상한을 넘긴 실행이 실패가 아니라 시간초과로 닫히나</b>다.
     * 둘을 가르지 않으면 사람이 「다시 해봐라」와 「지시를 줄여라」 중 무엇을 할지 못 고른다.
     */
    @Test
    void 시간_상한을_넘으면_죽이고_끝난_것을_확인한_뒤에_닫는다() {
        runner.nextResult(ClaudeResult.timedOut());

        AiRun run = runAndReload(null);

        assertThat(run.getState()).isEqualTo(AiRunState.TIMED_OUT);
        assertThat(run.userMessage()).contains("시간");
    }

    // ── 닫는 자리의 경합 ─────────────────────────────────────────────────

    @Test
    void 남이_이미_닫은_실행은_둘째_끝처리가_아무_것도_못_바꾼다() {
        // 조건부 UPDATE 라 둘째 호출은 0행이다.
        String runId = service.start(request(null));

        assertThat(service.finish(runId, AiRunState.FAILED, null, CheckerResult.NOT_RUN)).isTrue();
        assertThat(service.finish(runId, AiRunState.SUCCEEDED, null, CheckerResult.NOT_RUN)).isFalse();
        assertThat(repository.selectById(runId).orElseThrow().getState()).isEqualTo(AiRunState.FAILED);
    }

    /**
     * ⛔ <b>이 시험이 UPDATE 안의 {@code CASE} 를 지킨다.</b> 자바에서 취소 열을 먼저 읽어
     * 값을 고르는 구현으로 바꾸면 여기서 깨진다 — 읽는 시점과 쓰는 시점 사이에 취소가 들어오면
     * 성공이 이겨서 <b>사람이 그만두라 한 실행이 「성공」으로 뜬다.</b>
     */
    @Test
    void 취소를_눌러_뒀으면_일꾼이_성공을_넘겨도_그만둠으로_닫힌다() {
        String runId = service.start(request(null));
        service.cancel(runId);                    // cancel_requested_at 이 찍힌다

        assertThat(service.finish(runId, AiRunState.SUCCEEDED, null, CheckerResult.NOT_RUN)).isTrue();
        assertThat(repository.selectById(runId).orElseThrow().getState()).isEqualTo(AiRunState.CANCELLED);
    }

    @Test
    void 프로세스가_뜨기_전에_취소해도_그_실행은_안_돈다() {
        // 취소 요청은 메모리가 아니라 DB 에 남는다 — 일꾼이 뜨기 전에 눌러도 잡힌다.
        String runId = service.start(request(null));
        service.cancel(runId);

        worker().run(runId);

        assertThat(runner.runCount()).isZero();
        assertThat(repository.selectById(runId).orElseThrow().getState()).isEqualTo(AiRunState.CANCELLED);
    }

    // ── 한 일에 하나 ────────────────────────────────────────────────────

    /**
     * ⚠ <b>자바 쪽 검사만으로는 두 탭 경합을 못 막는다</b> — 마지막 방벽은 부분 유일 인덱스이고,
     * 자바는 그 제약 위반을 잡아 {@link AlreadyRunningException} 으로 바꿔 던진다.
     *
     * <p>⛔ <b>{@code project_id} 를 인덱스에서 빼면 다른 사업 담당끼리 서로를 막는다</b> —
     * 번호는 프로젝트마다 1번부터라({@code data-model} §4) 아래 둘째 단언이 그 자리를 지킨다.
     */
    @Test
    void 같은_일에_실행이_돌고_있으면_둘째를_서버가_거절한다() {
        service.start(request("첫째"));

        assertThatThrownBy(() -> service.start(request("둘째")))
                .isInstanceOf(AlreadyRunningException.class);
    }

    @Test
    void 갈래나_번호가_다르면_같은_일이_아니라서_동시에_돈다() {
        service.start(request(null, "0000007"));

        // 번호가 다르면 다른 일이다.
        assertThatNoException().isThrownBy(() -> service.start(request(null, "0000008")));
        // 갈래가 다르면 번호가 같아도 다른 일이다 — ⛔ 갈래를 안 붙이면 BRD:12 와 INTAKE:12 가 부딪힌다.
        assertThatNoException().isThrownBy(() -> service.start(
                new AiRunRequest(new WorkKey(project.getId(), WorkKey.BRD, "0000007"),
                        AiRunKind.DRAFT_BRD, project.getId(), person.getId(),
                        workDir, List.of(targetFile), null)));
    }

    /** ⛔ 끝난 실행은 그 일을 안 막는다 — 막으면 한 번 돌린 일을 영영 다시 못 돌린다. */
    @Test
    void 끝난_실행은_같은_일을_더는_막지_않는다() {
        String runId = service.start(request(null));
        service.finish(runId, AiRunState.FAILED, null, CheckerResult.NOT_RUN);

        assertThatNoException().isThrownBy(() -> service.start(request(null)));
    }

    // ── 재기동 청소 ─────────────────────────────────────────────────────

    /**
     * ⛔ <b>청소기는 세 번째 쓰는 놈이다</b> — 무조건 덮으면 마침 그때 끝난 일꾼의 결과를 지운다.
     * 그래서 닫을 때도 같은 조건부 UPDATE 를 쓴다.
     */
    @Test
    void 재기동_청소는_안_끝난_실행만_닫고_끝난_것은_안_건드린다() {
        String stuck = service.start(request(null, "0000007"));
        String alreadyDone = service.start(request(null, "0000008"));
        service.finish(alreadyDone, AiRunState.SUCCEEDED, null, CheckerResult.NOT_RUN);

        service.closeStuckRuns();

        assertThat(repository.selectById(stuck).orElseThrow().getState()).isEqualTo(AiRunState.FAILED);
        assertThat(repository.selectById(alreadyDone).orElseThrow().getState())
                .isEqualTo(AiRunState.SUCCEEDED);
    }

    /**
     * ⛔ <b>자격 자리는 사람마다가 아니라 실행마다다.</b> 같은 사람이 두 일을 동시에 돌릴 수 있는데
     * (잠기는 것은 사람이 아니라 「일」이다) 사람으로 자리를 잡으면 <b>먼저 끝난 실행의 finally 가
     * 아직 도는 실행의 자격 파일을 지운다.</b>
     */
    @Test
    void 자격_자리는_실행마다_갈린다() {
        assertThat(paths.runCredentialDir("0000001")).isNotEqualTo(paths.runCredentialDir("0000002"));
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    /**
     * 대역 러너. 넣어 둔 결과를 순서대로 내놓고, 없으면 성공을 내놓는다.
     *
     * <p>⚠ 실물 프로세스를 안 띄우므로 {@code onStarted} 를 부르지 않는다 — 그래서 이 시험의
     * 취소는 언제나 <b>DB 표시</b>만으로 갈린다. 프로세스를 실제로 죽이는 자리는
     * {@link CliClaudeRunnerTest} 가 잰다.
     */
    private static final class FakeRunner implements ClaudeRunner {

        private final Deque<ClaudeResult> queued = new ArrayDeque<>();
        private int runs;

        void nextResult(ClaudeResult result) {
            queued.add(result);
        }

        int runCount() {
            return runs;
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                String instruction, Consumer<Process> onStarted) {
            runs++;
            ClaudeResult queuedResult = queued.poll();
            return queuedResult != null
                    ? queuedResult
                    : new ClaudeResult(0, false, "completed", null, "됐다");
        }
    }

    /**
     * ⚠ 2026-08-15 에 <b>saveAndFlush 가 여기서 사라졌다.</b> 그것은 프로젝트가 JPA 라
     * 커밋 직전까지 INSERT 를 미뤄서(write-behind) 뒤따르는 MyBatis 의 AI 실행 INSERT 가
     * {@code adk_builder_ai_run.project_id} FK 를 못 채우던 것을 막던 장치였다.
     * <b>프로젝트도 MyBatis 가 되어 곧장 들어가므로 그 까닭이 사라졌다.</b>
     * ⛔ 되살리지 마라 — 매퍼에는 flush 라는 것 자체가 없다.
     *
     * <p>⚠ 아래 {@code someone()} 의 {@code saveAndFlush} 도 <b>같은 날 뒤이어 사라졌다</b> —
     * 계정이 마지막 JPA 였고 그것이 MyBatis 로 넘어오며 공존 기간이 끝났다.
     */
    private Project readyProject() {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "탐나는전",
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account someone() {
        // ⚠ 2026-08-15 에 여기서도 saveAndFlush 가 사라졌다. 그것은 계정이 JPA 라 커밋 직전까지
        //    INSERT 를 미뤄서(write-behind), 이 계정으로 클로드 자격을 앉히는 MyBatis INSERT 가
        //    FK(adk_builder_claude_credential.account_id)를 못 채우던 것을 막던 장치였다.
        //    계정도 MyBatis 가 되어 곧장 들어가므로 그 까닭이 사라졌다.
        // ⛔ 되살리지 마라 — 매퍼에는 flush 라는 것 자체가 없다.
        //    이것이 이 저장소의 마지막 saveAndFlush 였다. 공존 기간이 끝났다.
        var account = Account.create(ids.next(IdSequence.Kind.ACCOUNT),
                "planner", "기획자", "planner@example.com", "해시", false);
        accounts.insert(account);
        return account;
    }
}
