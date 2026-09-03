package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.ClaudeRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.ai.AiProgress;
import com.bizplay.builder.claude.ClaudeCredentialService;
import com.bizplay.builder.claude.ClaudeCredentialFile;
import com.bizplay.builder.claude.ClaudeAccountLocks;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 목업 만들기 — AI 자리 ②. 실물 {@code claude} 는 안 부른다.
 *
 * <p>가짜 {@code ClaudeRunner} 배선은 {@link ScreenPickTest} 와 같은 모양이다.
 *
 * <p>⚠ {@link ScreenMockupWorker} 를 {@code @Autowired} 로 받지 않는다 — 주입받는 것은 프록시라
 * {@code @Async} 가 발동해 바로 아래 줄의 검사가 경합이 된다. 손으로 새로 만들어 쓴다.
 */
class ScreenMockupTest extends AbstractDbTest {

    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdItemMapper items;
    @Autowired FrdBackendChangeMapper backendChanges;
    @Autowired FrdScreenHistoryMapper histories;
    @Autowired ScreenMockupReader reader;
    @Autowired ScreenMockupService mockups;
    @Autowired SolutionScreenReader solutions;
    @Autowired FrdFacetMapper frdFacets;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired FrdScreenFiles screenFiles;
    @Autowired ClaudeCredentialService credentials;
    @Autowired ClaudeCredentialFile credentialFile;
    @Autowired ClaudeAccountLocks accountLocks;
    @Autowired BuilderProperties properties;
    @Autowired ProjectPaths paths;
    @Autowired AccountMapper accounts;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired AiProgress progress;
    @Autowired ScreenPickService picks;

    /** ⚠ 여기 바로 넣는다 — 시험 메서드가 {@code runner.answers} 가 아니라 {@code answers} 로 쓴다. */
    private final Deque<ClaudeResult> answers = new ArrayDeque<>();

    private FakeRunner runner;
    private ScreenMockupWorker worker;
    private Account planner;
    private Project project;
    private String frdId;
    private String screenRowId;

    @BeforeEach
    void setUp() {
        runner = new FakeRunner();
        var credentialRunner = new ClaudeCredentialRunner(
                runner, credentials, credentialFile, accountLocks);
        worker = new ScreenMockupWorker(frds, screens, items, backendChanges, mockups, reader, credentialRunner,
                properties, paths, solutions, screenFiles, progress, picks);
        planner = someone();
        withCredential();
        project = readyProjectWithClone("탐나는전");
        frdId = seedFrd(project, "임시저장");
        screenRowId = seedScreen(frdId, "wv-appr-write", "wv-appr-write");
    }

    @Test
    void 목업이_나오면_html_과_바뀐_것이_앉고_완료가_된다() {
        // 클론에 화면 html 을 심는다 — 실물 배치와 같은 자리다.
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        answers.add(success("""
                {"html":"<html><head></head><body><article>to-be</article></body></html>","changes":["임시저장 버튼 추가"]}"""));

        worker.generate(screenRowId);

        FrdScreen after = screens.selectById(screenRowId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(after.html()).contains("to-be");
        assertThat(after.changes()).contains("임시저장 버튼 추가");
        assertThat(after.generatedAt()).isNotNull();
        assertThat(worktreeScreenFile(project, "webview", "wv-appr-write"))
                .content().contains("to-be");
        assertThat(runner.lastArgs)
                .containsSubsequence("--permission-mode", "dontAsk")
                .containsSubsequence("--model", "sonnet")
                .containsSubsequence("--effort", "medium")
                .anySatisfy(arg -> assertThat(arg)
                        .contains("Edit(/core/webview/pages/wv-appr-write.html)"));
    }

    @Test
    void 갈래_화면은_FRD_적용_대상의_html을_기준으로_초안을_만든다() throws IOException {
        projectFacets.insert(ProjectFacet.create(project.getId(), "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(project.getId(), "jeju", "제주"));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "제주"));
        seedVariantScreen(project, "webview", "wv-appr-write",
                "<html><head></head><body>익산 원본</body></html>",
                "<html><head></head><body>제주 원본</body></html>");
        answers.add(success("""
                {"html":"<html><head></head><body>제주 수정안</body></html>","changes":["외부 링크 변경"]}"""));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(runner.asIsHistory)
                .as("FRD 적용 대상과 같은 제주 갈래를 기준 화면으로 읽는다")
                .containsExactly("<html><head></head><body>제주 원본</body></html>");
        Path jeju = paths.frdWorktree(project.getId(), frdId).resolve(
                "core/webview/variants-jeju/wv-appr-write.html");
        assertThat(Files.readString(jeju,
                StandardCharsets.UTF_8)).contains("제주 수정안");
        assertThat(worktreeScreenFile(project, "webview", "wv-appr-write"))
                .as("기관별 화면을 공통 pages에 복제하지 않는다")
                .doesNotExist();
    }

    @Test
    void 제주와_익산에_함께_적용하는_화면은_기관별_html을_차례로_수정한다() throws IOException {
        projectFacets.insert(ProjectFacet.create(project.getId(), "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(project.getId(), "jeju", "제주"));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "익산"));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "제주"));
        seedVariantScreen(project, "webview", "wv-appr-write",
                "<html><head></head><body>익산 원본</body></html>",
                "<html><head></head><body>제주 원본</body></html>");
        answers.add(success("""
                {"html":"<html><head></head><body>익산 수정안</body></html>","changes":["익산 변경"]}"""));
        answers.add(success("""
                {"html":"<html><head></head><body>제주 수정안</body></html>","changes":["제주 변경"]}"""));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(runner.asIsHistory).containsExactly(
                "<html><head></head><body>익산 원본</body></html>",
                "<html><head></head><body>제주 원본</body></html>");
        assertThat(paths.frdWorktree(project.getId(), frdId)
                .resolve("core/webview/variants-iksan/wv-appr-write.html"))
                .content().contains("익산 수정안");
        assertThat(paths.frdWorktree(project.getId(), frdId)
                .resolve("core/webview/variants-jeju/wv-appr-write.html"))
                .content().contains("제주 수정안");
    }

    @Test
    void 적용_대상이_여럿이어도_공통_pages_화면은_한_번만_수정한다() {
        projectFacets.insert(ProjectFacet.create(project.getId(), "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(project.getId(), "jeju", "제주"));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "익산"));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "제주"));
        String original = "<html><head></head><body>공통 원본</body></html>";
        seedCloneScreen(project, "webview", "wv-appr-write", original);
        answers.add(success("""
                {"html":"<html><head></head><body>공통 수정안</body></html>","changes":["공통 변경"]}"""));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(runner.asIsHistory).containsExactly(original);
        assertThat(runner.modelHistory).containsExactly("sonnet");
        assertThat(worktreeScreenFile(project, "webview", "wv-appr-write"))
                .content().contains("공통 수정안");
    }

    @Test
    void 사용자가_선택한_화면은_초안_작업을_시작하지_않는다() {
        String userScreenId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(userScreenId, frdId, "wv-appr-manual", "사용자 선택 화면",
                "wv-appr-manual", null, null));

        worker.generate(userScreenId);

        assertThat(screens.selectById(userScreenId).state()).isEqualTo(FrdScreen.State.WAITING);
        assertThat(runner.lastInstruction).isNull();
    }

    @Test
    /** 2026-08-27 병주 확정: 고치기도 sonnet 한 판이다 — head 를 건드리면 안전벨트가 원본 head 를 되살린다. */
    void 고치기는_Sonnet_한_판이고_head가_바뀌면_안전벨트가_원본_head를_되살린다() {
        String original = "<html><head><title>ORIGINAL-TITLE</title></head><body><article>ORIGINAL</article></body></html>";
        seedCloneScreen(project, "webview", "wv-appr-write", original);
        answers.add(success("""
                {"html":"<html><head><title>CHANGED-TITLE</title></head><body><article>EDITED</article></body></html>","changes":["본문 변경"]}"""));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(worktreeScreenFile(project, "webview", "wv-appr-write")).content()
                .contains("EDITED").contains("<title>ORIGINAL-TITLE</title>").doesNotContain("CHANGED-TITLE");
        assertThat(runner.modelHistory).containsExactly("sonnet");
        assertThat(runner.asIsHistory).containsExactly(original);
    }

    @Test
    void 고치기_결과가_문서가_아니면_다른_모델로_다시_돌지_않고_실패로_닫는다() {
        String original = "<html><head></head><body><article>ORIGINAL</article></body></html>";
        seedCloneScreen(project, "webview", "wv-appr-write", original);
        answers.add(success("{\"html\":\"<article>BROKEN</article>\",\"changes\":[\"본문 변경\"]}"));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(worktreeScreenFile(project, "webview", "wv-appr-write")).content().isEqualTo(original);
        assertThat(runner.modelHistory).containsExactly("sonnet");
    }

    @Test
    void Claude_로그인_만료는_계정_재연결을_안내한다() {
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        answers.add(new ClaudeResult(1, true, "api_error", null, "Not logged in"));

        worker.generate(screenRowId);

        FrdScreen after = screens.selectById(screenRowId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(after.failure())
                .isEqualTo("Claude 계정 연결이 만료되었습니다. Claude 계정을 다시 연결한 뒤 다시 시도해 주세요.");
    }

    @Test
    void 구분할_수_없는_AI_오류는_재시도와_계정_재연결을_안내한다() {
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        answers.add(new ClaudeResult(1, true, "api_error", null, "일시적인 API 오류"));

        worker.generate(screenRowId);

        FrdScreen after = screens.selectById(screenRowId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(after.failure())
                .isEqualTo("AI 화면 초안을 만들지 못했습니다. 잠시 후 다시 시도해 주세요. "
                        + "계속 실패하면 Claude 계정을 다시 연결해 주세요.")
                .doesNotContain("api_error", "exitCode");
    }

    @Test
    void 기준_화면이_없는_신규_화면은_Sonnet으로_한번만_만든다() throws IOException {
        String newScreenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.drafted(newScreenRowId, frdId, "tmp-0000042",
                "임시 저장 문서함", "목록", null, "webview"));
        Files.deleteIfExists(worktreeScreenFile(project, "webview", "tmp-0000042"));
        answers.add(success("""
                {"html":"<html><head></head><body><main>신규 화면</main></body></html>","changes":["신규 화면 구성"]}"""));

        worker.generateNow(newScreenRowId);

        assertThat(screens.selectById(newScreenRowId).state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(runner.modelHistory).containsExactly("sonnet");
        assertThat(runner.lastArgs)
                .anySatisfy(arg -> assertThat(arg)
                        .contains("Write(/core/webview/pages/tmp-0000042.html)"));
    }

    @Test
    void 과거에_기존화면으로_잘못_저장된_인터뷰_신규화면은_TMP로_복구해_초안생성을_시작한다() {
        String legacyRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(legacyRowId, frdId, "bo-report-register", "보고서 등록",
                "bo-report-register", null, "등록 화면을 새로 만든다", "webview"));
        items.insert(FrdItem.of(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1, "보고서를 등록한다",
                FrdItem.Nature.DEVELOP, FrdItem.Verdict.SCREEN,
                List.of("bo-report-register"), "신규 등록 화면"));
        answers.add(success("""
                {"html":"<html><head></head><body><main>신규 등록 화면</main></body></html>",
                 "changes":["신규 등록 화면 구성"]}
                """));

        worker.generateNow(legacyRowId);

        FrdScreen recovered = screens.selectById(legacyRowId);
        assertThat(recovered.screenId()).isEqualTo(TemporaryScreenId.of(legacyRowId));
        assertThat(recovered.baseScreenId()).isNull();
        assertThat(recovered.screenType()).isEqualTo("등록");
        assertThat(items.selectByFrdId(frdId)).singleElement()
                .satisfies(item -> assertThat(item.screenIdList()).containsExactly(recovered.screenId()));
        assertThat(runner.lastArgs).anySatisfy(argument -> assertThat(argument)
                .contains("Write(/core/webview/pages/" + recovered.screenId() + ".html)"));
    }

    @Test
    void 파일과_변경_요약이_모두_그대로면_변경사항_없는_완료로_저장한다() {
        String original = "<html><head></head><body><article>이미 충족</article></body></html>";
        seedCloneScreen(project, "webview", "wv-appr-write", original);
        answers.add(success("{\"changes\":[]}"));

        worker.generate(screenRowId);

        FrdScreen after = screens.selectById(screenRowId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(histories.selectByFrdId(after.frdId())).singleElement().satisfies(history -> {
            assertThat(history.frdScreenId()).isEqualTo(screenRowId);
            assertThat(history.html()).isEqualTo(original);
            assertThat(history.changeList()).isEmpty();
        });
        assertThat(after.html()).isEqualTo(original);
        assertThat(after.changes()).isNull();
        assertThat(runner.modelHistory).containsExactly("sonnet");
    }

    @Test
    void 수정된_html이_깨지면_워크트리를_직전_내용으로_되돌린다() {
        String original = "<html><head></head><body><article>ORIGINAL</article></body></html>";
        seedCloneScreen(project, "webview", "wv-appr-write", original);
        answers.add(success("{\"html\":\"<article>BROKEN</article>\",\"changes\":[\"본문 변경\"]}"));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(worktreeScreenFile(project, "webview", "wv-appr-write"))
                .content().isEqualTo(original);
    }

    @Test
    void 서버가_다시_뜨면_멈춘_초안은_실패로_닫는다() {
        screens.updateState(screenRowId, FrdScreen.State.GENERATING);

        mockups.recoverInterruptedGenerations();

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(screens.selectById(screenRowId).failure()).contains("서버가 다시 시작");
    }

    @Test
    void 한_화면이_실패해도_그_화면만_실패다() {
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        answers.add(success("이것은 JSON 이 아니다"));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(frds.selectById(frdId).state())
                .as("FRD 자체는 안 넘어진다").isEqualTo(Frd.State.DRAFTING);
    }

    @Test
    void 다시_만들면_덮어쓴다() {
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        answers.add(success("""
                {"html":"<html><head></head><body><article>첫판</article></body></html>"}"""));
        worker.generate(screenRowId);
        answers.add(success("""
                {"html":"<html><head></head><body><article>둘째판</article></body></html>"}"""));

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).html())
                .as("이번 판에 버전이 없다 — 덮어쓰는 것이 설계다")
                .contains("둘째판").doesNotContain("첫판");
    }

    @Test
    void 베이스가_다른_새_화면은_베이스_html_을_읽는다() {
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>베이스다</article>");
        deleteCloneScreen(project, "webview", "wv-appr-draft-write");
        String newScreenRowId = seedScreen(frdId, "wv-appr-draft-write", "wv-appr-write");
        answers.add(success("""
                {"html":"<html><head></head><body><article>새 화면</article></body></html>"}"""));

        worker.generate(newScreenRowId);

        // ⚠ [2026-08-18 리뷰 ①] as-is html 은 이제 지시문에 안 담긴다 — 실행 전용 파일에 앉는다.
        //   그 파일에 베이스 화면의 내용이 써졌는지로 「베이스로 읽는다」는 보장을 그대로 잰다.
        assertThat(runner.lastAsIsFileContent).contains("베이스다");
    }

    /**
     * ⛔ [2026-08-18 리뷰 ①] as-is html 을 지시문에 인라인하면 argv 상한(~32KB, 기획 저장소
     * 화면 html 최대 67KB 실측)을 넘는 화면에서 실행 자체가 깨진다 — 지시문은 파일 경로만
     * 가리키고, 내용은 실행 전용 파일로 앉힌다({@link ScreenPickWorker} 가 원문에 대해 이미
     * 고친 것과 같은 이유다).
     */
    @Test
    void 지시문이_as_is_내용을_담지_않고_파일을_가리킨다() {
        String asIsMarker = "이문장은로그나명령줄에새면안되는화면원문이다";
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>" + asIsMarker + "</article>");
        answers.add(success("""
                {"html":"<html><head></head><body><article>to-be</article></body></html>"}"""));

        worker.generate(screenRowId);

        assertThat(runner.lastInstruction)
                .doesNotContain(asIsMarker)
                .contains("core/webview/pages/wv-appr-write.html");
        assertThat(runner.lastAsIsFileContent).contains(asIsMarker);
    }

    /**
     * ⛔ [자기 검토 ③, 2026-08-18 리뷰 Minor] 시스템이 없으면 경로가 {@code "null/pages/....html"}
     * 로 지어져 실패 사유가 「파일 없음」이 되어 헷갈린다 — 널을 먼저 보고 말이 되는 사유로 닫는다.
     */
    @Test
    void 시스템이_없으면_claude_를_부르지_않고_말이_되는_사유로_닫는다() {
        String noSystemFrdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(new Frd(noSystemFrdId, project.getId(), frds.allocateNumber(project.getId()),
                "시스템 없는 요구사항", null, Frd.SourceKind.PASTED, null, "본문", null, null,
                Frd.State.DRAFTING, null, planner.getId(), null, null));
        String noSystemScreenId = seedScreen(noSystemFrdId, "wv-appr-write", "wv-appr-write");

        worker.generate(noSystemScreenId);

        FrdScreen after = screens.selectById(noSystemScreenId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(after.failure()).contains("시스템");
        assertThat(runner.lastInstruction).as("claude 를 아예 안 불렀다").isNull();
    }

    /**
     * ⛔ [자기 검토] as-is 파일이 없으면 지어내게 두지 않는다 — 실패로 닫고 claude 를 아예 부르지 않는다.
     */
    @Test
    void 베이스_화면_파일이_없으면_claude_를_부르지_않고_실패로_닫는다() {
        // ⚠ 클론에 화면 html 을 심지 않는다 — 파일이 없는 자리다.
        // ⛔ data-root 가 고정된 공유 임시 폴더라(AbstractDbTest) 예전 실행이 남긴 같은 번호
        //   프로젝트의 파일이 있을 수 있다 — 확실히 지우고 시작한다.
        deleteCloneScreen(project, "webview", "wv-appr-write");

        worker.generate(screenRowId);

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(screens.selectById(screenRowId).failure()).isNotBlank();
        assertThat(runner.lastInstruction).as("claude 를 아예 안 불렀다").isNull();
    }

    /**
     * ⛔ [자기 검토] Claude 자격이 없으면 「AI 초안 만드는 중」에서 멈추지 않고 실패로 닫는다.
     */
    @Test
    void 자격이_없으면_실패로_닫힌다() throws IOException {
        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        // ⚠ seedFrd 는 planner 계정을 담당으로 쓴다 — 자격 없음을 재려면 계정을 아예 다르게 둔다.
        String bareAccountId = ids.next(IdSequence.Kind.ACCOUNT);
        accounts.insert(Account.create(bareAccountId, "planner-" + bareAccountId, "무자격자",
                "bare@example.com", "해시", false));
        String noCredentialFrdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(new Frd(noCredentialFrdId, project.getId(), frds.allocateNumber(project.getId()),
                "다른 요구사항", "webview", Frd.SourceKind.PASTED, null, "다른 요구사항 본문", null, null,
                Frd.State.DRAFTING, null, bareAccountId, null, null));
        String bareScreenId = seedScreen(noCredentialFrdId, "wv-appr-write", "wv-appr-write");
        Path bareTarget = paths.frdWorktree(project.getId(), noCredentialFrdId)
                .resolve("core/webview/pages/wv-appr-write.html");
        Files.createDirectories(bareTarget.getParent());
        Files.writeString(bareTarget, "<article>as-is</article>", StandardCharsets.UTF_8);

        worker.generate(bareScreenId);

        FrdScreen after = screens.selectById(bareScreenId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(after.failure())
                .isEqualTo("Claude 계정 연결이 필요합니다. Claude 계정을 연결한 뒤 다시 시도해 주세요.");
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 C3] {@code baseScreenId} 는 사람이 자유롭게 적는 값이다 —
     * {@code FrdService.addScreen} 이 색인 대조로 대부분 막지만, 여기 안전벨트도 실경로로
     * 다시 잰다. {@code core/} 위(클론 자체) 에 진짜 파일을 둬서, 안전벨트가 없으면 이 시험이
     * 「막았다」가 아니라 「파일이 없어서 우연히 실패했다」가 되는 것을 막는다.
     */
    @Test
    void 베이스_화면ID_가_클론_밖_경로를_가리키면_읽지_않고_실패로_닫는다() throws IOException {
        Path core = paths.cloneDir(project.getId()).resolve("core");
        Files.createDirectories(core);
        Files.writeString(paths.cloneDir(project.getId()).resolve("evil.html"),
                "<article>클론 밖 비밀</article>", StandardCharsets.UTF_8);
        String traversalScreenId = seedScreen(frdId, "wv-appr-outside", "../../../evil");

        worker.generate(traversalScreenId);

        FrdScreen after = screens.selectById(traversalScreenId);
        assertThat(after.state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(after.failure()).isNotBlank();
        assertThat(runner.lastInstruction)
                .as("claude 를 아예 안 불렀다 — 경계를 넘은 값은 읽기 전에 막는다").isNull();
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 I4] {@code finally} 의 {@code deleteRecursively} 가 이 계획의 보안
     * 보장 중 유일하게 안 재던 것이다 — 성공 한 판과 실패 한 판 뒤에 실행 자리(자격·요구사항·as-is
     * 사본)가 남아 있지 않은지를 잰다. 자리 이름은 {@code ScreenMockupWorker} 의
     * {@code frd-mockup-runs/<frdScreenId>-<UUID>} 다.
     */
    @Test
    void 성공해도_실패해도_실행_자리를_지운다() {
        Path runsRoot = properties.dataRoot().resolve("frd-mockup-runs");

        seedCloneScreen(project, "webview", "wv-appr-write", "<article>as-is</article>");
        answers.add(success("""
                {"html":"<html><head></head><body><article>to-be</article></body></html>"}"""));
        worker.generate(screenRowId);
        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(anyRunDirRemains(runsRoot, screenRowId + "-"))
                .as("성공 뒤에도 실행 자리가 남으면 안 된다").isFalse();

        String failingScreenId = seedScreen(frdId, "wv-appr-list", "wv-appr-write");
        answers.add(success("이것은 JSON 이 아니다"));
        worker.generate(failingScreenId);
        assertThat(screens.selectById(failingScreenId).state()).isEqualTo(FrdScreen.State.FAILED);
        assertThat(anyRunDirRemains(runsRoot, failingScreenId + "-"))
                .as("실패 뒤에도 실행 자리가 남으면 안 된다").isFalse();
    }

    /** 그 실행이 남긴 실행 자리(runDir)가 있나 — 이름이 {@code <prefix><UUID>} 꼴이다. */
    private boolean anyRunDirRemains(Path runsRoot, String prefix) {
        if (!Files.isDirectory(runsRoot)) {
            return false;
        }
        try (var listing = Files.list(runsRoot)) {
            return listing.anyMatch(entry -> entry.getFileName().toString().startsWith(prefix));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private void withCredential() {
        credentials.store(planner.getId(), """
                {"claudeAiOauth": {"accessToken": "시험용", "refreshToken": "시험용", "expiresAt": 1}}""");
    }

    private static ClaudeResult success(String body) {
        return new ClaudeResult(0, false, "completed", null, body);
    }

    /** ⚠ Frd 는 DRAFTING 으로 바로 앉힌다 — 작업대는 확정 뒤에만 연다. */
    private String seedFrd(Project project, String title) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(new Frd(id, project.getId(), frds.allocateNumber(project.getId()), title, "webview",
                Frd.SourceKind.PASTED, null, title + " 요구사항 본문", null, null,
                Frd.State.DRAFTING, null, planner.getId(), null, null));
        return id;
    }

    private String seedScreen(String frdId, String screenId, String baseScreenId) {
        String id = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(id, frdId, screenId, null, baseScreenId, null,
                "시험용 AI 선택 근거"));
        return id;
    }

    /** 원본 클론과 FRD 워크트리에 같은 시작 화면을 심는다. */
    private void seedCloneScreen(Project project, String system, String screenId, String html) {
        try {
            for (Path root : List.of(paths.cloneDir(project.getId()),
                    paths.frdWorktree(project.getId(), frdId))) {
                Files.deleteIfExists(root.resolve("core").resolve(system)
                        .resolve("variants-iksan").resolve(screenId + ".html"));
                Files.deleteIfExists(root.resolve("core").resolve(system)
                        .resolve("variants-jeju").resolve(screenId + ".html"));
            }
            Files.createDirectories(cloneScreenFile(project, system, screenId).getParent());
            Files.writeString(cloneScreenFile(project, system, screenId), html, StandardCharsets.UTF_8);
            Files.createDirectories(worktreeScreenFile(project, system, screenId).getParent());
            Files.writeString(worktreeScreenFile(project, system, screenId), html, StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** 갈래 화면 색인과 기관별 HTML을 원본 클론·FRD 워크트리에 함께 심는다. */
    private void seedVariantScreen(Project project, String system, String screenId,
                                   String iksanHtml, String jejuHtml) {
        try {
            Path clone = paths.cloneDir(project.getId());
            Files.writeString(clone.resolve("index.json"), """
                    {
                      "schema": "we-adk-index/3",
                      "screens": {
                        "%s": {"system": "%s", "ia": {"종류": "화면"}}
                      },
                      "variantIndex": {"iksan": ["%s"], "jeju": ["%s"]}
                    }
                    """.formatted(screenId, system, screenId, screenId), StandardCharsets.UTF_8);
            Path markdown = clone.resolve("core").resolve(system).resolve("pages")
                    .resolve(screenId + ".md");
            Files.createDirectories(markdown.getParent());
            Files.writeString(markdown, "화면명: 갈래 시험 화면\n", StandardCharsets.UTF_8);

            seedVariantHtml(clone, system, "iksan", screenId, iksanHtml);
            seedVariantHtml(clone, system, "jeju", screenId, jejuHtml);
            Path worktree = paths.frdWorktree(project.getId(), frdId);
            seedVariantHtml(worktree, system, "iksan", screenId, iksanHtml);
            seedVariantHtml(worktree, system, "jeju", screenId, jejuHtml);
            Files.deleteIfExists(worktreeScreenFile(project, system, screenId));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private void seedVariantHtml(Path root, String system, String variant,
                                 String screenId, String html) throws IOException {
        Path file = root.resolve("core").resolve(system).resolve("variants-" + variant)
                .resolve(screenId + ".html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, html, StandardCharsets.UTF_8);
    }

    /**
     * 「파일이 없다」를 재는 시험이 확실히 없는 자리에서 출발하게 한다.
     *
     * <p>⛔ {@code AbstractDbTest} 의 data-root 는 고정된 공유 임시 폴더다 — 매 실행마다 프로젝트
     * 번호가 1 부터 다시 매겨져, 예전 실행이 남긴 같은 번호의 클론 파일이 「없다」고 믿은 자리에
     * 그대로 남아 있을 수 있다.
     */
    private void deleteCloneScreen(Project project, String system, String screenId) {
        try {
            Files.deleteIfExists(cloneScreenFile(project, system, screenId));
            Files.deleteIfExists(worktreeScreenFile(project, system, screenId));
            for (Path root : List.of(paths.cloneDir(project.getId()),
                    paths.frdWorktree(project.getId(), frdId))) {
                Files.deleteIfExists(root.resolve("core").resolve(system)
                        .resolve("variants-iksan").resolve(screenId + ".html"));
                Files.deleteIfExists(root.resolve("core").resolve(system)
                        .resolve("variants-jeju").resolve(screenId + ".html"));
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private Path cloneScreenFile(Project project, String system, String screenId) {
        return paths.cloneDir(project.getId()).resolve("core").resolve(system)
                .resolve("pages").resolve(screenId + ".html");
    }

    private Path worktreeScreenFile(Project project, String system, String screenId) {
        return paths.frdWorktree(project.getId(), frdId).resolve("core").resolve(system)
                .resolve("pages").resolve(screenId + ".html");
    }

    private Project readyProjectWithClone(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/" + name + ".git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        Project created = projects.selectById(id).orElseThrow();
        try {
            Files.createDirectories(paths.cloneDir(created.getId()));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return created;
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
     * <p>⚠ 프로세스를 안 띄우므로 {@code onStarted} 를 부르지 않는다.
     */
    private final class FakeRunner implements ClaudeRunner {

        /** ⚠ 조각을 기억하려고 이것을 둔다 — 마지막으로 실제 넘어간 지시문을 시험이 들여다본다. */
        private String lastInstruction;

        private List<String> lastArgs = List.of();
        private final List<String> modelHistory = new ArrayList<>();
        private final List<String> asIsHistory = new ArrayList<>();

        /**
         * ⚠ [2026-08-18 리뷰 ①] as-is html 이 이제 지시문에 없다 — {@code --add-dir} 로 넘어온
         * 자리에서 {@code run()} 이 도는 동안(= {@code finally} 가 runDir 을 지우기 전에)
         * 미리 읽어 둔다.
         */
        private String lastAsIsFileContent;

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                List<String> extraArgs, String instruction, Consumer<Process> onStarted) {
            lastInstruction = instruction;
            lastArgs = List.copyOf(extraArgs);
            int modelAt = extraArgs.indexOf("--model");
            modelHistory.add(modelAt >= 0 && modelAt + 1 < extraArgs.size()
                    ? extraArgs.get(modelAt + 1) : "default");
            ClaudeResult queued = answers.poll();
            ClaudeResult answer = queued != null ? queued
                    : new ClaudeResult(1, true, "시험 응답 없음", null, "");
            Path target = targetFile(workDir, extraArgs);
            if (target != null) {
                try {
                    lastAsIsFileContent = Files.exists(target)
                            ? Files.readString(target, StandardCharsets.UTF_8)
                            : "";
                    asIsHistory.add(lastAsIsFileContent);
                    var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(answer.body());
                    if (root.path("html").isTextual()) {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, root.path("html").asText(), StandardCharsets.UTF_8);
                        var response = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                        response.set("changes", root.path("changes"));
                        answer = new ClaudeResult(answer.exitCode(), answer.isError(), answer.terminalReason(),
                                answer.apiStatus(), response.toString());
                    }
                } catch (IOException ignored) {
                    // 깨진 응답 시험은 실제 파일을 건드리지 않고 그대로 일꾼에게 돌려준다.
                }
            }
            return answer;
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                String instruction, Consumer<Process> onStarted) {
            return run(credentialDir, workDir, timeout, List.of(), instruction, onStarted);
        }

        private Path targetFile(Path workDir, List<String> extraArgs) {
            int at = extraArgs.indexOf("--allowed-tools");
            if (at < 0 || at + 1 >= extraArgs.size()) return null;
            String allowed = extraArgs.get(at + 1);
            int starts = allowed.indexOf("Edit(/");
            int ends = allowed.indexOf(')', starts);
            if (starts < 0 || ends < 0) return null;
            return workDir.resolve(allowed.substring(starts + "Edit(/".length(), ends)
                    .replace('/', java.io.File.separatorChar));
        }
    }

    /**
     * ⭐ [2026-08-22 병주 확정] 사람이 만든 신규 화면은 기준 화면이 없다 — 지시문이 「고쳐라」가 아니라
     * 「같은 유형 화면들의 관례를 읽고 만들어라」가 되어야 한다.
     */
    @Test
    void 기준_화면이_없으면_같은_유형_화면을_읽고_만들라고_시킨다() {
        String prompt = ScreenMockupWorker.instruction("/실행자리/요구사항.md",
                "core/webview/pages/tmp-0000042.html", "tmp-0000042", "임시 저장 문서함", "—",
                "목록", "- `core/webview/pages/wv-card-list.html` — 카드 목록");

        assertThat(prompt)
                .contains("아직 없는 화면을 새로 만든다")
                .contains("화면 유형: **목록**")
                .contains("core/webview/pages/wv-card-list.html")
                .contains("Write 도구로 대상 파일에 써라")
                .contains("한 장을 통째로 베끼지 마라")
                .contains("다른 기관 CSS로 바꾸지 마라")
                .contains("core/webview/styleguide.md")
                .contains("실제로 있는 CSS 클래스만 써라");
    }

    @Test
    void 신규_화면_초안에는_연결된_화면_요구사항과_백엔드_계약을_함께_전달한다() {
        FrdScreen screen = FrdScreen.drafted("screen-1", "frd-1", "tmp-0000042",
                "가맹점 폐업 목록", "목록", null, "backoffice");
        FrdItem item = FrdItem.of("item-1", "frd-1", 1,
                "폐업 가맹점을 조회하고 목록에서 상세 화면으로 이동한다.",
                FrdItem.Nature.DEVELOP, FrdItem.Verdict.SCREEN, List.of("tmp-0000042"),
                "가맹점명·사업자번호·폐업일·상태로 조회하고, 결과에는 가맹점명·사업자번호·폐업일·상태를 표시한다. 행을 누르면 마스터 가맹점 상세로 이동한다.");
        FrdBackendChange backend = new FrdBackendChange("backend-1", "frd-1", 1, 1,
                FrdBackendChange.Category.API, "폐업 가맹점 목록 조회 API",
                "가맹점명·사업자번호·폐업일·상태 조건으로 목록을 조회한다.", null, null, true, null);

        String context = ScreenMockupWorker.screenAnalysisContext(screen, List.of(item), List.of(backend), false);
        String prompt = ScreenMockupWorker.instruction("/실행자리/요구사항.md", "/실행자리/화면-분석-내용.md",
                "core/backoffice/pages/tmp-0000042.html", "tmp-0000042", "가맹점 폐업 목록", "—",
                "목록", "- `core/backoffice/pages/bo-merc-list.html` — 가맹점 목록");

        assertThat(context)
                .contains("이 화면에 연결된 프론트 요구사항")
                .contains("가맹점명·사업자번호·폐업일·상태로 조회")
                .contains("행을 누르면 마스터 가맹점 상세로 이동")
                .contains("폐업 가맹점 목록 조회 API");
        assertThat(prompt)
                .contains("/실행자리/화면-분석-내용.md")
                .contains("화면별 분석 정보를 반드시 읽어라")
                .contains("조회 조건·표시 항목·버튼·행동으로 옮기고");
    }

    /** 기준 화면이 있는 기존 흐름은 그대로 「고쳐라」다. */
    @Test
    void 기준_화면이_있으면_고치라고_시킨다() {
        String prompt = ScreenMockupWorker.instruction("/실행자리/요구사항.md",
                "core/webview/pages/wv-appr-write.html", "wv-appr-write", "결재 작성", "—",
                null, null);

        assertThat(prompt)
                .contains("Edit 도구로 직접 수정")
                .doesNotContain("아직 없는 화면을 새로 만든다");
    }
}
