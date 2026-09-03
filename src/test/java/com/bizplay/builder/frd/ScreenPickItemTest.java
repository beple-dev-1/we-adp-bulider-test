package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.ClaudeRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.claude.ClaudeAccountLocks;
import com.bizplay.builder.claude.ClaudeCredentialFile;
import com.bizplay.builder.claude.ClaudeCredentialService;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요구사항 <b>항목마다</b> 판정이 앉는다 — 조용한 누락을 드러내는 자리다.
 *
 * <p>⭐ <b>2026-08-18 실측이 이 표를 낳았다.</b> 웹뷰 4건 + 지급시스템 2건짜리 요구사항이
 * 화면 1장으로 끝났는데, 나머지 다섯이 <b>아무 말 없이 사라졌다.</b> 화면 목록만 받으면
 * 「AI 가 못 찾은 것」과 「화면 일이 아닌 것」과 「아직 추출 안 된 화면」이 구별되지 않는다.
 */
class ScreenPickItemTest extends AbstractDbTest {

    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdItemMapper items;
    @Autowired ScreenPickReader reader;
    @Autowired ScreenPickService picks;
    @Autowired ClaudeCredentialService credentials;
    @Autowired ClaudeCredentialFile credentialFile;
    @Autowired ClaudeAccountLocks accountLocks;
    @Autowired BuilderProperties properties;
    @Autowired RequirementAnalysisProperties analysisProperties;
    @Autowired ProjectPaths paths;
    @Autowired com.bizplay.builder.ai.AiProgress progress;
    @Autowired AccountMapper accounts;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    private final Deque<ClaudeResult> answers = new ArrayDeque<>();

    @org.springframework.beans.factory.annotation.Autowired
    com.bizplay.builder.solution.SolutionScreenReader solutionScreens;

    private ScreenPickWorker worker;
    private Account planner;

    @BeforeEach
    void setUp() {
        var credentialRunner = new ClaudeCredentialRunner(
                new FakeRunner(), credentials, credentialFile, accountLocks);
        worker = new ScreenPickWorker(frds, picks, reader, credentialRunner, properties,
                analysisProperties, paths, progress, solutionScreens);
        planner = someone();
    }

    /**
     * ⭐ <b>실물이 낸 그 사고를 못박는다 (2026-08-18 네 번째 실측).</b>
     *
     * <p>「FAQ 삭제처리」는 <b>이미 있는 삭제 버튼</b>으로 끝나는 운영 일이다. 화면은 짚혀야 하지만
     * (운영자가 어디서 지우나) <b>작업 단위로 승격되면 안 된다</b> — 승격되면 고칠 것이 없는
     * 화면의 to-be 목업을 AI 가 만든다.
     *
     * <p>⛔ <b>이것이 성격 축을 낸 까닭 전부다.</b> 이 시험이 빨개지면 축이 도로 무너진 것이다.
     */
    @Test
    void 운영_항목의_화면은_근거로만_남고_작업_단위로_승격되지_않는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가 피해지원금 종료");
        answers.add(success("""
                {"title":"고유가 피해지원금 종료처리",
                 "items":[
                   {"requirement":"자주하는 질문 : FAQ 삭제처리","nature":"OPERATE","verdict":"NO_SCREEN",
                    "screens":[{"screenId":"bo-front-faq-list","system":"backoffice",
                                "screenName":"FAQ 목록","reason":"운영자가 여기서 지운다"}],
                    "note":"삭제 버튼이 이미 있다"},
                   {"requirement":"가맹점 찾기 : 안내 문구 원복처리","nature":"DEVELOP","verdict":"SCREEN",
                    "screens":[{"screenId":"wv-modal-store-detail","system":"webview",
                                "screenName":"가맹점 상세","reason":"문구가 정적으로 박혀 있다"}]},
                   {"requirement":"결제통지 신청 종료처리","nature":"OUTSIDE","verdict":"NO_SCREEN",
                    "screens":[],"note":"별도 지급시스템 일이다"}]}"""));

        worker.pick(frdId);

        List<FrdItem> saved = items.selectByFrdId(frdId);
        assertThat(saved).extracting(FrdItem::nature).containsExactly(
                FrdItem.Nature.OPERATE, FrdItem.Nature.DEVELOP, FrdItem.Nature.OUTSIDE);
        assertThat(saved.get(0).screenIdList())
                .as("운영 항목도 일이 일어나는 화면은 근거로 남는다")
                .containsExactly("bo-front-faq-list");
        assertThat(screens.selectByFrdId(frdId))
                .as("작업 단위로는 개발 항목의 화면만 앉는다")
                .extracting(FrdScreen::screenId)
                .containsExactly("wv-modal-store-detail");
    }

    /**
     * ⚠ <b>같은 화면이 운영 항목과 개발 항목에 같이 걸리면 승격된다.</b> 운영 쪽이 먼저 와서
     * 승격을 막아 버리면 고쳐야 할 화면이 조용히 사라진다 — 필터가 <b>항목마다</b> 도는 까닭이다.
     */
    @Test
    void 운영과_개발에_같이_걸린_화면은_승격된다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가 피해지원금 종료");
        answers.add(success("""
                {"title":"고유가 피해지원금 종료처리",
                 "items":[
                   {"requirement":"FAQ 삭제처리","nature":"OPERATE","verdict":"NO_SCREEN",
                    "screens":[{"screenId":"bo-front-faq-list","system":"backoffice",
                                "screenName":"FAQ 목록","reason":"여기서 지운다"}]},
                   {"requirement":"FAQ 목록에 안내 배너를 없앤다","nature":"DEVELOP","verdict":"SCREEN",
                    "screens":[{"screenId":"bo-front-faq-list","system":"backoffice",
                                "screenName":"FAQ 목록","reason":"배너가 화면에 박혀 있다"}]}]}"""));

        worker.pick(frdId);

        assertThat(screens.selectByFrdId(frdId)).extracting(FrdScreen::screenId)
                .containsExactly("bo-front-faq-list");
    }

    /**
     * ⚠ <b>성격이 없으면 개발이다 — 옛 계약의 뜻과 정확히 같다.</b> 성격 축이 없던 때는
     * 짚힌 화면이 모두 작업 대상으로 승격됐다. 값을 지어내는 것이 아니라 옛 뜻을 그대로 두는 것이다.
     */
    @Test
    void 성격이_없는_옛_모양은_개발로_읽힌다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가 피해지원금 종료");
        answers.add(success("""
                {"title":"고유가 피해지원금 종료처리",
                 "items":[{"requirement":"전체 메뉴 히든처리","verdict":"SCREEN",
                           "screens":[{"screenId":"wv-modal-all-menu","system":"webview",
                                       "screenName":"전체메뉴","reason":"경로가 살아 있다"}]}]}"""));

        worker.pick(frdId);

        assertThat(items.selectByFrdId(frdId).get(0).nature()).isEqualTo(FrdItem.Nature.DEVELOP);
        assertThat(screens.selectByFrdId(frdId)).extracting(FrdScreen::screenId)
                .containsExactly("wv-modal-all-menu");
    }

    /** ⭐ 항목 셋이 판정 셋으로 갈라져 앉는다 — 하나도 사라지지 않는다. */
    @Test
    void 항목마다_판정이_순서대로_앉는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가 피해지원금 종료");
        answers.add(success("""
                {"title":"고유가 피해지원금 종료처리",
                 "items":[
                   {"requirement":"전체 메뉴 : 고유가 피해지원금 경로 히든처리","verdict":"SCREEN",
                    "screenIds":["wv-modal-all-menu"],"note":"e16 앵커가 살아 있다"},
                   {"requirement":"결제통지 신청자 알림톡 중단 처리","verdict":"NO_SCREEN",
                    "screenIds":[],"note":"dino-api-lspn-api 의 발송 규칙이다"},
                   {"requirement":"가맹점 찾기 : 안내 문구 원복처리","verdict":"NOT_INDEXED",
                    "screenIds":[],"note":"wv-merc-search-main 이 색인에 없다"}],
                 "screens":[{"screenId":"wv-modal-all-menu","system":"webview",
                             "screenName":"전체메뉴","reason":"경로가 살아 있다"}]}"""));

        worker.pick(frdId);

        List<FrdItem> saved = items.selectByFrdId(frdId);
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).verdict()).isEqualTo(FrdItem.Verdict.SCREEN);
        assertThat(saved.get(0).requirement()).contains("전체 메뉴");
        assertThat(saved.get(0).screenIdList()).containsExactly("wv-modal-all-menu");
        assertThat(saved.get(1).verdict()).isEqualTo(FrdItem.Verdict.NO_SCREEN);
        assertThat(saved.get(2).verdict()).isEqualTo(FrdItem.Verdict.NOT_INDEXED);
        assertThat(saved.get(2).note()).contains("색인에 없다");
    }

    /**
     * ⭐ <b>실물이 낸 그 모양이 실행 경로 끝까지 앉는다 (2026-08-18 두 번째 실측).</b>
     * 화면은 항목 안에 중첩되고 항목 이름은 {@code title} 이다.
     */
    @Test
    void 실물이_낸_중첩_모양이_화면과_항목으로_갈라져_앉는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가 피해지원금 종료");
        answers.add(success("""
                {"title":"고유가 피해지원금 종료처리 과업",
                 "items":[
                   {"title":"전체 메뉴 : 고유가 피해지원금 경로 히든처리","verdict":"SCREEN",
                    "screens":[{"screenId":"wv-modal-all-menu","system":"webview",
                                "screenName":"전체메뉴 (전체메뉴 모달)",
                                "reason":"e16 앵커가 lspn 경로를 가리킨다"}]},
                   {"title":"자주하는 질문 : FAQ 삭제처리","verdict":"SCREEN",
                    "screens":[{"screenId":"bo-front-webfaq-list","system":"backoffice",
                                "screenName":"웹 FAQ 관리","reason":"여기서 지운다"}]},
                   {"title":"결제통지 신청자 알림톡 중단 처리","verdict":"NO_SCREEN",
                    "screens":[],"note":"dino-api-lspn-api 의 발송 규칙이다"},
                   {"title":"가맹점 찾기 : 안내 문구 원복처리","verdict":"NOT_INDEXED",
                    "screens":[],"note":"wv-merc-search-main 이 색인에 없다"}]}"""));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.PICKED);
        assertThat(items.selectByFrdId(frdId)).hasSize(4);
        assertThat(items.selectByFrdId(frdId).get(0).requirement()).contains("전체 메뉴");
        assertThat(screens.selectByFrdId(frdId))
                .extracting(FrdScreen::screenId, FrdScreen::systemCode)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("wv-modal-all-menu", "webview"),
                        org.assertj.core.groups.Tuple.tuple("bo-front-webfaq-list", "backoffice"));
        // ⚠ 시스템이 걸치니 FRD 의 칸은 비운다.
        assertThat(frds.selectById(frdId).systemCode()).isNull();
    }

    /** ⛔ 다시 짚으면 항목은 <b>통째로 갈아 낀다</b> — 화면과 달리 사람이 손볼 것이 아니다. */
    @Test
    void 다시_짚으면_항목은_통째로_갈아_낀다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가");
        answers.add(success("""
                {"title":"첫 판","items":[{"requirement":"옛 항목","verdict":"NO_SCREEN",
                                           "screenIds":[],"note":"옛 근거"}],"screens":[]}"""));
        worker.pick(frdId);

        answers.add(success("""
                {"title":"둘째 판","items":[{"requirement":"새 항목","verdict":"NO_SCREEN",
                                             "screenIds":[],"note":"새 근거"}],"screens":[]}"""));
        worker.pick(frdId);

        assertThat(items.selectByFrdId(frdId)).singleElement()
                .satisfies(item -> assertThat(item.requirement()).isEqualTo("새 항목"));
    }

    /** ⭐ 화면마다 시스템이 앉는다 — 하나의 요구사항이 웹뷰와 백오피스에 같이 걸리는 것이 정상이다. */
    @Test
    void 시스템이_다른_화면들이_한_FRD_에_같이_앉는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가");
        answers.add(success("""
                {"title":"고유가 피해지원금 종료처리",
                 "items":[{"requirement":"전체 메뉴 히든","verdict":"SCREEN",
                           "screenIds":["wv-modal-all-menu","bo-front-webfaq-list"],"note":"둘 다다"}],
                 "screens":[{"screenId":"wv-modal-all-menu","system":"webview",
                             "screenName":"전체메뉴","reason":"경로가 살아 있다"},
                            {"screenId":"bo-front-webfaq-list","system":"backoffice",
                             "screenName":"웹 FAQ 관리","reason":"FAQ 를 여기서 지운다"}]}"""));

        worker.pick(frdId);

        assertThat(screens.selectByFrdId(frdId))
                .extracting(FrdScreen::screenId, FrdScreen::systemCode)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("wv-modal-all-menu", "webview"),
                        org.assertj.core.groups.Tuple.tuple("bo-front-webfaq-list", "backoffice"));
    }

    /**
     * ⛔ <b>시스템이 걸치면 FRD 의 시스템 칸을 비운다.</b> 하나를 골라 적으면 다른 하나가
     * 화면에서 거짓말이 된다 — 설계서가 「{@code system_code} 가 비는 것은 정상」이라고 적어 뒀다.
     */
    @Test
    void 시스템이_걸치면_FRD_의_시스템_칸을_비운다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가");
        answers.add(success("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN",
                                          "screenIds":[],"note":"근거"}],
                 "screens":[{"screenId":"wv-a","system":"webview","screenName":"ㄱ"},
                            {"screenId":"bo-b","system":"backoffice","screenName":"ㄴ"}]}"""));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).systemCode()).isNull();
    }

    /** ⚠ 한 시스템뿐이면 그것을 적는다 — 목록의 「업무 · 시스템」 칸이 그 값을 쓴다. */
    @Test
    void 한_시스템뿐이면_FRD_의_시스템_칸에_적는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "고유가");
        answers.add(success("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN",
                                          "screenIds":[],"note":"근거"}],
                 "screens":[{"screenId":"wv-a","system":"webview","screenName":"ㄱ",
                              "reason":"첫 화면의 표시 내용을 바꾼다"},
                            {"screenId":"wv-b","system":"webview","screenName":"ㄴ",
                              "reason":"둘째 화면의 표시 내용을 바꾼다"}]}"""));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).systemCode()).isEqualTo("webview");
    }

    /**
     * ⭐ <b>실패하면 AI 가 뭐라고 냈는지 남긴다 (2026-08-18 실측).</b> 실행 자리는 지워지고
     * 출력은 어디에도 안 남아, 「화면ID 가 빈 줄이 있다」 한 줄만 들고 원인을 가릴 길이 없었다.
     */
    @Test
    void 결과가_망가지면_AI_가_뭐라고_냈는지_까닭에_남는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(success("""
                {"title":"제목","items":[{"requirement":"무엇","verdict":"SCREEN","screenIds":[]}],
                 "screens":{"wv-appr-write":{"screenName":"객체로 왔다"}}}"""));

        worker.pick(frdId);

        Frd frd = frds.selectById(frdId);
        assertThat(frd.state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
        assertThat(frd.failure())
                .as("무엇이 틀렸는지 말한다").contains("배열")
                .as("AI 출력을 곁들인다").contains("wv-appr-write");
    }

    /**
     * ⛔ <b>claude 가 뜨자마자 죽었을 때 까닭이 남아야 한다 (2026-08-18 실측).</b>
     * {@code builder:unparsable} 한 줄만 남으면 <b>왜 못 읽었는지</b> 알 길이 없다 —
     * 실제로 그것 때문에 스키마가 못 서는 것을 한 판 놓쳤다.
     */
    @Test
    void claude_가_뜨자마자_죽으면_그_까닭이_남는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(new ClaudeResult(1, true, "builder:unparsable", null,
                "error: unknown option '--없는플래그'"));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).failure())
                .contains("builder:unparsable")
                .as("stderr 의 까닭을 곁들인다").contains("unknown option");
    }

    /** ⛔ 자격은 남기지 않는다 — 출력을 곁들이되 가리개를 지난다. */
    @Test
    void 실패_까닭에_토큰이_섞여_나오지_않는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(success("Authorization: Bearer sk-ant-비밀토큰값 이라 실패했다"));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).failure()).doesNotContain("sk-ant-비밀토큰값");
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private void withCredential() {
        credentials.store(planner.getId(), """
                {"claudeAiOauth": {"accessToken": "시험용", "refreshToken": "시험용", "expiresAt": 1}}""");
    }

    private static ClaudeResult success(String body) {
        return new ClaudeResult(0, false, "completed", null, body);
    }

    private String seedFrd(Project project, String title) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(new Frd(id, project.getId(), frds.allocateNumber(project.getId()), title, null,
                Frd.SourceKind.PASTED, null, title + " 요구사항 본문", null, null,
                Frd.State.ANALYZING, null, planner.getId(), null, null));
        return id;
    }

    private Project readyProjectWithClone(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/" + name + ".git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        Project project = projects.selectById(id).orElseThrow();
        try {
            Files.createDirectories(paths.cloneDir(project.getId()));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return project;
    }

    private Account someone() {
        var account = Account.create(ids.next(IdSequence.Kind.ACCOUNT),
                "planner-" + ids.next(IdSequence.Kind.ACCOUNT), "기획자",
                "planner@example.com", "해시", false);
        accounts.insert(account);
        return account;
    }

    private final class FakeRunner implements ClaudeRunner {

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                List<String> extraArgs, String instruction, Consumer<Process> onStarted) {
            return run(credentialDir, workDir, timeout, instruction, onStarted);
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                String instruction, Consumer<Process> onStarted) {
            ClaudeResult queued = answers.poll();
            return queued != null ? queued : success("{}");
        }
    }
}
