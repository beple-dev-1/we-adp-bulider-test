package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.ClaudeRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.businesslanguage.BusinessDocumentKind;
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
import com.bizplay.builder.solution.SolutionScreen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 짚기 — AI 자리 ①. 실물 {@code claude} 는 안 부른다.
 *
 * <p>가짜 {@code ClaudeRunner} 배선은 {@code RequirementAnalysisTest} 와 같은 모양이다
 * (자격을 심고, 결과를 큐에 넣어 돌려주는 방식).
 *
 * <p>⚠ {@link ScreenPickWorker} 를 {@code @Autowired} 로 받지 않는다 — 주입받는 것은 프록시라
 * {@code @Async} 가 발동해 바로 아래 줄의 검사가 경합이 된다. 손으로 새로 만들어 쓴다.
 */
class ScreenPickTest extends AbstractDbTest {

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
    @Autowired FrdInterviewReader interviewReader;
    @Autowired FrdInterviewService interviewService;
    @Autowired FrdInterviewMessageMapper interviewMessages;
    @Autowired FrdFacetMapper frdFacets;
    @Autowired FrdScreenIaPlacementService iaPlacements;
    @Autowired FrdScreenChatEvents liveEvents;
    @Autowired com.bizplay.builder.businesslanguage.BusinessLanguageContextWriter businessLanguage;
    @Autowired com.bizplay.builder.businesslanguage.BusinessDocumentMapper businessDocuments;
    @Autowired AccountMapper accounts;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    /** ⚠ 여기 바로 넣는다 — 시험 메서드가 {@code runner.answers} 가 아니라 {@code answers} 로 쓴다. */
    private final Deque<ClaudeResult> answers = new ArrayDeque<>();

    private FakeRunner runner;
    @org.springframework.beans.factory.annotation.Autowired
    com.bizplay.builder.solution.SolutionScreenReader solutionScreens;

    private ScreenPickWorker worker;
    private Account planner;

    @BeforeEach
    void setUp() {
        runner = new FakeRunner();
        var credentialRunner = new ClaudeCredentialRunner(
                runner, credentials, credentialFile, accountLocks);
        worker = new ScreenPickWorker(frds, picks, reader, credentialRunner, properties,
                analysisProperties, paths, progress, solutionScreens);
        planner = someone();
    }

    @Test
    void 짚기가_끝나면_화면들이_앉고_상태가_확인_필요로_바뀐다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(success("""
                {"title":"전자결재 상신 임시저장 지원",
                 "items":[{"requirement":"임시저장을 지원한다","verdict":"SCREEN",
                           "screenIds":["wv-appr-write"],"note":"작성 화면이다"}],
                 "screens":[{"screenId":"wv-appr-write","system":"webview","screenName":"결재 문서 작성",
                             "reason":"상단에 임시저장 버튼이 없습니다"}]}"""));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.PICKED);
        assertThat(frds.selectById(frdId).title()).isEqualTo("전자결재 상신 임시저장 지원");
        assertThat(screens.selectByFrdId(frdId)).singleElement()
                .satisfies(screen -> {
                    assertThat(screen.screenId()).isEqualTo("wv-appr-write");
                    assertThat(screen.state()).isEqualTo(FrdScreen.State.WAITING);
                    assertThat(screen.baseScreenId()).isEqualTo("wv-appr-write");
                });
    }

    @Test
    void 분석에서_확인한_시스템과_화면별_변경_내용을_신규_화면에도_반영한다() {
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "폐업 가맹점 조회 화면을 만든다");
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String temporaryScreenId = TemporaryScreenId.of(screenRowId);
        screens.insert(FrdScreen.drafted(screenRowId, frdId, temporaryScreenId,
                "폐업 가맹점 조회", "목록", null, null));

        ScreenPickReader.Pick pick = new ScreenPickReader.Pick("폐업 가맹점 관리", List.of(
                new ScreenPickReader.Item("폐업 가맹점을 조회한다", ScreenPickReader.Nature.DEVELOP,
                        ScreenPickReader.Verdict.SCREEN, List.of(temporaryScreenId), "백오피스 메뉴")),
                List.of(new ScreenPickReader.Picked(temporaryScreenId, null, "폐업 가맹점 조회",
                                "폐업 가맹점 조회 조건과 결과 목록을 신규 화면으로 구성한다"),
                        new ScreenPickReader.Picked("bo-merc-master-detail", "backoffice", "마스터 가맹점 상세",
                                "같은 백오피스 메뉴에서 연결한다")), null);

        picks.savePick(frdId, pick);

        assertThat(screens.selectByFrdId(frdId)).filteredOn(screen -> screen.screenId().equals(temporaryScreenId))
                .singleElement()
                .satisfies(screen -> {
                    assertThat(screen.systemCode()).isEqualTo("backoffice");
                    assertThat(screen.scopeChange()).isEqualTo("폐업 가맹점 조회 조건과 결과 목록을 신규 화면으로 구성한다");
                    assertThat(screen.pickReason()).isNull();
                    assertThat(screen.isUserSelected()).isTrue();
                });
    }

    @Test
    void 인터뷰에서_새로_찾은_화면은_TMP로_채번하고_항목도_TMP를_가리킨다() {
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "보고서 등록 화면을 새로 만든다");
        ScreenPickReader.Pick pick = new ScreenPickReader.Pick("보고서 관리", List.of(
                new ScreenPickReader.Item("보고서를 등록한다", ScreenPickReader.Nature.DEVELOP,
                        ScreenPickReader.Verdict.SCREEN, List.of("bo-report-register"), "신규 등록 화면")),
                List.of(new ScreenPickReader.Picked("bo-report-register", "backoffice", "보고서 등록",
                        "보고서 입력과 저장 기능을 새로 구성한다", true, "등록")), null);

        picks.savePick(frdId, pick);

        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        assertThat(screen.screenId()).startsWith("tmp-");
        assertThat(screen.baseScreenId()).isNull();
        assertThat(screen.screenType()).isEqualTo("등록");
        assertThat(items.selectByFrdId(frdId)).singleElement()
                .satisfies(item -> assertThat(item.screenIdList()).containsExactly(screen.screenId()));
    }

    @Test
    void 화면이_없다고_판단하면_까닭이_남고_화면은_0장이다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "배치 주기");
        answers.add(success("""
                {"title":"야간 정산 배치 주기 변경",
                 "items":[{"requirement":"배치 주기를 바꾼다","verdict":"NO_SCREEN",
                           "screenIds":[],"note":"설정값이다"}],
                 "screens":[],
                 "noScreenReason":"화면이 아니라 배치 주기 설정입니다"}"""));

        worker.pick(frdId);

        Frd frd = frds.selectById(frdId);
        assertThat(frd.state()).isEqualTo(Frd.State.PICKED);
        assertThat(frd.noScreenReason()).contains("배치");
        assertThat(screens.selectByFrdId(frdId)).isEmpty();
    }

    @Test
    void 결과가_망가지면_분석_오류로_닫히고_다시_누를_수_있다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(success("이것은 JSON 이 아니다"));

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
        assertThat(frds.selectById(frdId).failure()).isNotBlank();
    }

    @Test
    void 자격이_없으면_분석_오류로_닫힌다() {
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");   // 자격을 안 심는다

        worker.pick(frdId);

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
    }

    /**
     * ⭐ <b>저쪽 혼잡을 사람 손으로 갚게 하지 않는다 (2026-09-01 실측).</b>
     *
     * <p>FRD 0000069 가 204초를 <b>다 쓰고 마지막에</b> {@code apiStatus=529 (Overloaded)} 로 버려졌다.
     * 그때는 재시도가 자격끊김 하나뿐이라, 사람이 「다시 분석하기」를 눌러 그 204초를 처음부터 다시 냈다.
     */
    @Test
    void 저쪽이_붐벼_죽으면_스스로_다시_돌아_끝낸다() {
        withCredential();
        worker.busyBackoff = Duration.ofMillis(1);
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(busy(529));
        answers.add(success("""
                {"title":"전자결재 상신 임시저장 지원",
                 "items":[{"requirement":"임시저장을 지원한다","verdict":"SCREEN",
                           "screenIds":["wv-appr-write"],"note":"작성 화면이다"}],
                 "screens":[{"screenId":"wv-appr-write","system":"webview","screenName":"결재 문서 작성",
                             "reason":"상단에 임시저장 버튼이 없습니다"}]}"""));

        worker.pick(frdId);

        assertThat(runner.calls).as("붐빈 판 하나와 성공한 판 하나").isEqualTo(2);
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.PICKED);
    }

    /** ⛔ 끝없이 다시 돌지 않는다 — 셋에서 끊고 까닭을 사람에게 그대로 내민다. */
    @Test
    void 붐빔이_이어지면_세_판까지만_돌고_분석_오류로_닫힌다() {
        withCredential();
        worker.busyBackoff = Duration.ofMillis(1);
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(busy(529));
        answers.add(busy(529));
        answers.add(busy(529));
        answers.add(busy(529));

        worker.pick(frdId);

        assertThat(runner.calls).isEqualTo(3);
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
        assertThat(frds.selectById(frdId).failure()).contains("529");
    }

    /** ⛔ 붐빔이 아닌 실패를 다시 돌리면 같은 이유로 또 죽는다 — 시간만 곱절로 쓴다. */
    @Test
    void 붐빔이_아닌_실패는_다시_돌지_않는다() {
        withCredential();
        worker.busyBackoff = Duration.ofMillis(1);
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        answers.add(new ClaudeResult(1, true, "api_error", 400, "요청이 틀렸다"));

        worker.pick(frdId);

        assertThat(runner.calls).isEqualTo(1);
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
    }

    /**
     * ⭐ <b>붐빔에는 이어붙일 기억을 버리지 않는다 (2026-09-01).</b>
     *
     * <p>버리는 까닭은 「세션이 사라졌거나 깨졌다」인데 529 는 세션이 멀쩡한데 저쪽이 붐빈 것이다.
     * 버리면 60초짜리 답변 한 턴이 저장소를 처음부터 뒤지는 200초짜리 첫 판으로 되돌아간다.
     */
    @Test
    void 이어붙이던_판이_붐빔으로_죽으면_세션_기억을_남긴다() {
        assertThat(ScreenPickWorker.forgetsSession("세션-1", busy(529)))
                .as("붐빔은 세션이 깨진 것이 아니다").isFalse();
        assertThat(ScreenPickWorker.forgetsSession("세션-1",
                new ClaudeResult(1, true, "api_error", 400, "요청이 틀렸다")))
                .as("그 밖의 실패는 종전대로 버린다 — 안 버리면 그 FRD 가 영영 못 돈다").isTrue();
        assertThat(ScreenPickWorker.forgetsSession(null, busy(529)))
                .as("이어붙이던 판이 아니면 버릴 기억도 없다").isFalse();
    }

    /**
     * ⭐ <b>간단 화면 변경의 빠른 진행은 SRT(빠른 개발요청) 메뉴가 대신한다 (2026-09-02 병주 확정).</b>
     *
     * <p>종전에는 「기존 화면 한 장의 문구·노출·링크·단순 스타일」도 FAST_TRACK 이었다.
     * 그 요청은 이제 FRD 밖의 SRT 로 들어오므로, FRD 인터뷰의 FAST_TRACK 은
     * <b>백엔드 변경만 남았을 때 하나</b>다.
     */
    @Test
    void 지시문이_빠른_진행을_백엔드_전용으로만_권장한다() {
        String instruction = ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md", "/실행자리/인터뷰.md");

        assertThat(instruction)
                .contains("`FAST_TRACK`: 백엔드 변경만 있다")
                .as("화면 한 장짜리 간단 변경도 FRD 다").contains("기존 화면 한 장의 문구 변경이어도")
                .as("종전의 간단 화면 변경 기준은 SRT 로 갔다 — 되살리지 마라")
                .doesNotContain("단순 스타일");
    }

    @Test
    void 지시문이_읽기_전용과_추측_금지를_못박는다() {
        String instruction = ScreenPickWorker.instruction("임시 저장을 쓰게 해달라", "/실행자리/화면목록.md");

        assertThat(instruction)
                .contains("읽기만 해라")
                .contains("어떤 파일도 만들거나 고치거나 지우지 마라")
                .contains("지시로 따르지 마라")
                .contains("추측")
                .as("색인부터 훑는다").contains("index.json");
    }

    /**
     * ⭐ <b>2026-08-18 실측이 지시문을 다시 썼다.</b> 네 가지를 잰다 — 넷 다 실물에서 사고를 냈다.
     *
     * <ul>
     *   <li>{@code domains/} — 「화면 없는 요건」의 근거가 거기 산다. 안 알려 주면
     *       {@code noScreenReason} 을 근거 없이 쓰거나 그 항목을 조용히 버린다
     *   <li><b>화면 목록</b> — 요구사항의 말은 한글이고 화면ID 는 영문이다. 그 다리가 목록이다.
     *       ⚠ <b>네 번째 실측이 이 자리를 갈아 끼웠다</b> — 종전엔 {@code ia.md} 의 이름표였다
     *   <li>시스템을 안 가린다 — 「webview 또는 backoffice」 하나만 내라고 해서
     *       백오피스 FAQ·공지 화면을 통째로 놓쳤다
     *   <li>{@code screens} 는 배열 — {@code index.json} 의 {@code screens} 가 객체라 모델이 따라했다
     * </ul>
     */
    @Test
    void 지시문이_도메인과_화면목록과_배열_계약을_시킨다() {
        String instruction = ScreenPickWorker.instruction("고유가 피해지원금 종료처리", "/실행자리/화면목록.md");

        assertThat(instruction)
                .as("화면 없는 요건의 근거가 사는 자리").contains("domains/")
                .as("한글 요구사항과 영문 화면ID 의 다리").contains("화면 목록")
                .as("그 목록이 앉은 자리를 알려 준다").contains("/실행자리/화면목록.md")
                .as("시스템을 하나로 좁히지 않는다").contains("시스템을 하나로 좁히지 마라")
                .as("항목마다 판정").contains("SCREEN").contains("NO_SCREEN").contains("NOT_INDEXED")
                .as("배열로 내라").contains("배열")
                .as("객체로 내지 마라").contains("키로 한 객체");
    }

    /** ⛔ 없어진 지시 — 웹뷰엔 {@code ia.md} 가 아예 없어 이 지시가 헛돌았다(2026-08-18 실측). */
    @Test
    void 지시문이_배치_블록만_훑으라고_말하지_않는다() {
        assertThat(ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md")).doesNotContain("--- 배치 ---");
    }

    /**
     * ⛔ <b>{@code ia.md} 를 읽지 말라고 못박는다 (2026-08-18 네 번째 실측).</b>
     *
     * <p>메뉴구조도의 정본은 <b>빌더 DB</b> 이고 클론의 사본은 확정할 때만 다시 쓰인다 —
     * 즉 <b>「마지막 확정 시점」에 굳어 있다.</b> 파일이 없으면 건너뛰지만
     * <b>낡은 파일은 그대로 읽고 확신에 찬 답을 낸다.</b>
     */
    @Test
    void 지시문이_ia_md_를_읽지_말라고_말한다() {
        String instruction = ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md");

        assertThat(instruction)
                .as("읽지 말라고 못박는다").contains("`ia.md` 를 읽지 마라")
                .as("까닭까지 적는다 — 금지만 적으면 다음 사람이 되살린다").contains("정본은");
    }

    /**
     * ⭐ <b>성격을 화면보다 먼저 묻는다 (2026-08-18 네 번째 실측).</b>
     *
     * <p>「고칠 화면을 찾아라」를 먼저 시키면 <b>개발이 아닌 항목도 화면을 얻는다</b> —
     * 「FAQ 삭제처리」가 이미 있는 삭제 버튼을 찾아 놓고 화면 일이 된 자리다.
     */
    @Test
    void 지시문이_성격을_화면보다_먼저_묻는다() {
        String instruction = ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md");

        assertThat(instruction)
                .as("성격 값 셋").contains("DEVELOP").contains("OPERATE").contains("OUTSIDE")
                .as("가르는 질문 하나").contains("기능이 이미 있나")
                .as("화면 찾기보다 앞이다").contains("화면을 찾기 전이다")
                .as("찾았으니 개발이다로 가지 않는다").contains("개발이다」로 가지 마라");
        assertThat(instruction.indexOf("성격을 먼저 정하라"))
                .as("성격 판정이 화면 찾기보다 앞줄에 선다")
                .isLessThan(instruction.indexOf("화면을 찾아라"));
    }

    /**
     * ⭐ <b>모양은 스키마가 못박는다 (2026-08-18 세 번째 실측).</b> 프롬프트로 세 판을 시켰는데
     * 세 판 모두 다른 모양이 왔다 — 화면을 객체로, 항목을 {@code title} 로,
     * {@code sections} 로 한 겹 더 감싸고 화면을 {@code id}·{@code name} 으로.
     * <b>말로 시키는 것으로는 안 잡힌다.</b> {@code claude} 의 {@code --json-schema} 가
     * 파싱된 객체를 돌려주므로 모양이 흔들릴 자리가 원인부터 없어진다.
     *
     * <p>⛔ <b>{@code --add-dir} 보다 앞에 둔다</b> — 그쪽이 값을 여러 개 받는 꼴이라 뒤엣것을 삼킨다.
     */
    @Test
    void 실행_조각이_출력_스키마를_들고_간다() throws IOException {
        var args = ScreenPickWorker.claudeArgsFor(true, "sonnet", java.nio.file.Path.of("/tmp/일감"));

        assertThat(args).contains("--json-schema");
        String schema = args.get(args.indexOf("--json-schema") + 1);
        assertThat(schema)
                .as("항목과 판정이 필수다").contains("\"required\"").contains("requirement").contains("verdict")
                .as("판정은 셋뿐이다").contains("SCREEN").contains("NO_SCREEN").contains("NOT_INDEXED")
                .as("화면은 항목 안에 산다").contains("screenId").contains("screenName")
                .as("질문과 결과는 하나의 공통 봉투를 쓴다")
                .doesNotContain("\"oneOf\"").doesNotContain("\"allOf\"").doesNotContain("\"anyOf\"")
                .contains("\"type\":[\"object\",\"null\"]")
                .contains("backendChanges").contains("acceptanceCriteria");
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
        assertThat(parsed.path("required").toString())
                .as("분석 처리에 필요한 핵심 키는 반드시 내야 한다")
                .contains("type", "question", "items", "backendChanges", "noScreenReason")
                .as("사용자 안내 문구가 빠져도 서버 기본 문구로 보완한다")
                .doesNotContain("assistantMessage");
        assertThat(parsed.path("properties").has("assistantMessage"))
                .as("모델에는 사용자 안내 문구를 계속 요청한다")
                .isTrue();
        assertThat(parsed.path("properties").path("items").path("items")
                .path("properties").path("screens").path("items").path("required").toString())
                .as("작업 화면에는 화면별 수정 내용이 필수다")
                .contains("reason");
        assertThat(args.indexOf("--json-schema"))
                .as("⛔ --add-dir 뒤로 가면 그 목록에 삼켜진다")
                .isLessThan(args.indexOf("--add-dir"));
    }

    /** ⛔ 스키마와 리더가 갈리면 안 된다 — 스키마가 낸 모양을 리더가 그대로 읽는지 잰다. */
    @Test
    void 스키마가_말하는_모양을_리더가_그대로_읽는다() throws IOException {
        ScreenPickReader.Pick pick = new ScreenPickReader().read("""
                {"title":"업무명","items":[{"requirement":"요구 하나","verdict":"SCREEN",
                  "screens":[{"screenId":"wv-a","system":"webview","screenName":"ㄱ","reason":"까닭"}],
                  "note":null}],"noScreenReason":null}""");

        assertThat(pick.items()).singleElement()
                .satisfies(item -> assertThat(item.verdict())
                        .isEqualTo(ScreenPickReader.Verdict.SCREEN));
        assertThat(pick.screens()).singleElement()
                .satisfies(screen -> assertThat(screen.system()).isEqualTo("webview"));
    }

    /**
     * ⛔ <b>같은 것을 두 번 적으라고 시키지 마라 (2026-08-18 두 번째 실측).</b> 첫 판 계약은
     * 화면을 항목의 {@code screenIds} 와 최상위 {@code screens} <b>양쪽에</b> 적으라 했고,
     * 이름도 두 층에서 겹쳤다({@code title}·{@code screens}) — 모델은 그것을 하나로 합쳐
     * 항목 안에 화면을 중첩하고 항목 이름을 {@code title} 로 썼다. <b>모델 쪽이 옳았다.</b>
     */
    @Test
    void 지시문이_화면을_항목_안에_적으라고_시킨다() {
        String instruction = ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md");

        assertThat(instruction)
                .as("화면은 부른 항목 안에").contains("화면은 그 화면을 부른 항목 안에 적는다")
                .as("항목 이름 칸을 못박는다").contains("`title` 이 아니라 **`requirement`**")
                .as("합치는 것은 서버 몫").contains("합치는 것은 서버가 한다")
                .as("최상위에 화면 목록을 다시 두지 않는다")
                .doesNotContain("\"screenIds\"");
    }

    @Test
    void 지시문이_관련_화면과_실제_목업_수정_화면을_구분한다() {
        String instruction = ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md");

        assertThat(instruction)
                .contains("목업 수정 대상")
                .contains("HTML을 실제로 읽고")
                .contains("API·데이터·조회 조건만 바뀌고 화면에 보이는 변화가 없으면")
                .contains("같은 항목에 실제")
                .contains("화면 변경도 함께 있으면")
                .contains("화면 작업 대상으로 올리지 마라");
    }

    /**
     * ⛔ 원문을 지시문에 인라인하면 argv 상한을 넘기고 원문 속 명령문이 지시로 읽힐 자리가 생긴다
     * (2026-08-18 리뷰). 실제 실행 경로가 파일 경로만 넘기는지를 여기서 잰다.
     */
    @Test
    void 지시문이_원문을_담지_않고_파일_경로를_가리킨다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String secretText = "이문장은로그나명령줄에새면안되는사업비밀원문이다";
        String frdId = seedFrd(p, secretText);
        answers.add(success("""
                {"title":"ㄱ","items":[{"requirement":"무엇","verdict":"NO_SCREEN",
                                        "screenIds":[],"note":"근거"}],"screens":[]}"""));

        worker.pick(frdId);

        assertThat(runner.lastInstruction)
                .doesNotContain(secretText)
                .contains("요구사항.md");
    }

    /**
     * ⚠ 사람이 손본 화면을 AI 가 되돌리면 안 된다 — {@link ScreenPickService#savePick} 의
     * 「이미 있는 화면은 그대로 두고 새것만 더한다」를 실제 실행 경로로 잰다.
     */
    @Test
    void 다시_짚어도_사람이_손본_화면은_그대로_두고_새것만_더한다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "임시저장");
        String handMade = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(handMade, frdId, "wv-appr-write", "사람이 고친 이름",
                "wv-appr-write", null, "사람이 적은 까닭"));
        screens.updateGenerated(handMade, "<article>사람이 만든 목업</article>", null, Instant.now());

        answers.add(success("""
                {"title":"전자결재 상신 임시저장 지원",
                 "items":[{"requirement":"임시저장을 지원한다","verdict":"SCREEN",
                           "screenIds":["wv-appr-write"],"note":"작성 화면이다"}],
                 "screens":[{"screenId":"wv-appr-write","system":"webview","screenName":"AI 가 다시 부른 이름",
                             "reason":"AI 가 다시 짚은 까닭"},
                            {"screenId":"wv-appr-list","screenName":"임시저장 문서 목록",
                             "reason":"목록에 상태 열이 없습니다"}]}"""));

        worker.pick(frdId);

        List<FrdScreen> after = screens.selectByFrdId(frdId);
        assertThat(after).hasSize(2);
        FrdScreen kept = after.stream().filter(s -> s.screenId().equals("wv-appr-write"))
                .findFirst().orElseThrow();
        assertThat(kept.screenName()).isEqualTo("사람이 고친 이름");
        assertThat(kept.html()).isEqualTo("<article>사람이 만든 목업</article>");
        assertThat(kept.state()).isEqualTo(FrdScreen.State.GENERATED);
        FrdScreen added = after.stream().filter(s -> s.screenId().equals("wv-appr-list"))
                .findFirst().orElseThrow();
        assertThat(added.state()).isEqualTo(FrdScreen.State.WAITING);
    }

    @Test
    void 재분석에서_빠진_미작업_AI_화면만_정리한다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        String frdId = seedFrd(p, "API 조회 조건 변경");
        String staleAi = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(staleAi, frdId, "wv-old", "이전 AI 화면",
                "wv-old", null, "이전 분석이 고른 까닭", "webview"));
        String userSelected = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(userSelected, frdId, "wv-user", "사용자 선택 화면",
                "wv-user", null, null));
        String completed = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(completed, frdId, "wv-done", "작업 완료 화면",
                "wv-done", null, "이전 분석이 고른 까닭", "webview"));
        screens.updateGenerated(completed, "<main>사람이 확인한 목업</main>", null, Instant.now());
        answers.add(success("""
                {"title":"API 조회 조건 변경",
                 "items":[{"requirement":"API 조회 조건을 바꾼다","nature":"DEVELOP",
                           "verdict":"NO_SCREEN","screens":[],"note":"화면에 보이는 변경은 없다"}],
                 "screens":[]}"""));

        worker.pick(frdId);

        assertThat(screens.selectByFrdId(frdId)).extracting(FrdScreen::screenId)
                .containsExactlyInAnyOrder("wv-user", "wv-done")
                .doesNotContain("wv-old");
        assertThat(screens.selectIncludingExcludedById(staleAi)).isNotNull();

        answers.add(success("""
                {"title":"다시 확인한 범위","summary":"화면 변경을 다시 확인했다",
                 "items":[{"requirement":"이전 화면의 안내 문구를 바꾼다","nature":"DEVELOP",
                           "verdict":"SCREEN","screens":[{"screenId":"wv-old","system":"webview",
                           "screenName":"이전 AI 화면","reason":"안내 문구를 새 기준으로 바꾼다"}]}],
                 "screens":[]}"""));

        worker.pick(frdId);

        assertThat(screens.selectByFrdId(frdId)).extracting(FrdScreen::screenId)
                .containsExactlyInAnyOrder("wv-user", "wv-done", "wv-old");
        assertThat(screens.selectByFrdId(frdId).stream()
                .filter(screen -> screen.screenId().equals("wv-old"))
                .findFirst().orElseThrow().scopeChange())
                .isEqualTo("안내 문구를 새 기준으로 바꾼다");
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 I4] {@code finally} 의 {@code deleteRecursively} 가 이 계획의 보안
     * 보장 중 유일하게 안 재던 것이다 — 성공 한 판과 실패 한 판 뒤에 실행 자리(자격·요구사항 사본)가
     * 남아 있지 않은지를 잰다. 자리 이름은 {@code ScreenPickWorker} 의
     * {@code frd-runs/<frdId>-<UUID>} 다.
     */
    @Test
    void 성공해도_실패해도_실행_자리를_지운다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        Path runsRoot = properties.dataRoot().resolve("frd-runs");

        String okFrdId = seedFrd(p, "임시저장");
        answers.add(success("""
                {"title":"전자결재 상신 임시저장 지원",
                 "items":[{"requirement":"무엇","verdict":"NO_SCREEN",
                           "screenIds":[],"note":"근거"}],"screens":[]}"""));
        worker.pick(okFrdId);
        assertThat(frds.selectById(okFrdId).state()).isEqualTo(Frd.State.PICKED);
        assertThat(anyRunDirRemains(runsRoot, okFrdId + "-"))
                .as("성공 뒤에도 실행 자리가 남으면 안 된다").isFalse();

        String failingFrdId = seedFrd(p, "배치 주기");
        answers.add(success("이것은 JSON 이 아니다"));
        worker.pick(failingFrdId);
        assertThat(frds.selectById(failingFrdId).state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
        assertThat(anyRunDirRemains(runsRoot, failingFrdId + "-"))
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

    /** 저쪽이 붐벼 죽은 판. ⚠ 실측 그대로의 모양이다 (2026-09-01 FRD 0000069). */
    private static ClaudeResult busy(int status) {
        return new ClaudeResult(1, true, "api_error", status,
                "API Error: " + status + " Overloaded. This is a server-side issue, usually temporary");
    }

    /** ⚠ {@code Frd.pasted} 는 담당을 늘 널로 둔다 — 여기는 실행할 계정이 필요해 직접 앉힌다. */
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
        // ⚠ 「기획 저장소 사본이 서버에 있나」가 실행 조건 하나다 — 빈 폴더로 그것을 만족시킨다.
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

    /**
     * 실물 {@code claude} 를 안 띄운다.
     *
     * <p>⚠ 프로세스를 안 띄우므로 {@code onStarted} 를 부르지 않는다.
     */
    private final class FakeRunner implements ClaudeRunner {

        /** ⚠ 조각을 기억하려고 이것을 둔다 — 마지막으로 실제 넘어간 지시문을 시험이 들여다본다. */
        private String lastInstruction;

        /** 몇 판 돌았나 — 다시 돌기를 재는 시험이 이것을 본다. */
        private int calls;

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                List<String> extraArgs, String instruction, Consumer<Process> onStarted) {
            return run(credentialDir, workDir, timeout, instruction, onStarted);
        }

        @Override
        public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                                String instruction, Consumer<Process> onStarted) {
            lastInstruction = instruction;
            calls++;
            ClaudeResult queued = answers.poll();
            return queued != null ? queued : success("{}");
        }
    }

    /**
     * ⛔ <b>큰 파일을 통째로 읽지 말라고 못박는다 (2026-08-19 실측).</b>
     *
     * <p>진행 로그에서 {@code Read domains/app/dino-webview.md} 바로 뒤에 <b>추론 공백 65초</b>가
     * 났다. 그 파일이 116KB 이고 {@code domains/} 에는 <b>277KB 짜리도 있다</b> — 한 장을 통째로
     * 물면 그 뒤의 모든 생각이 그만큼 느려진다. 근거를 찾는 데 필요한 것은 <b>몇 줄</b>이다.
     */
    @Test
    void 지시문이_큰_파일을_통째로_읽지_말라고_말한다() {
        String instruction = ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md");

        assertThat(instruction)
                .as("domains 는 Grep 이 먼저다").contains("`domains/` 는 Grep 으로 먼저 좁혀라")
                .as("까닭까지 적는다 — 금지만 적으면 다음 사람이 되살린다").contains("한 장이 수십만 자")
                .as("index.json 도 통째로 읽지 않는다").contains("`index.json` 을 통째로 읽지 마라");
    }

    /**
     * ⭐ <b>턴마다 저장소를 처음부터 다시 뒤지지 않는다 (2026-08-19 실측).</b>
     *
     * <p>실측 로그에서 한 판 350초 중 <b>220초가 탐색</b>이었다. 답변 한 줄을 받을 때마다
     * 그 220초를 통째로 다시 냈다 — 앞판의 대화를 이어 붙이면 <b>다시 뒤질 것이 없다.</b>
     *
     * <p>⛔ <b>{@code --add-dir} 보다 앞에 둔다</b> — 그쪽이 값을 여러 개 받는 꼴이다.
     */
    @Test
    void 이어붙일_세션이_있으면_실행_조각이_그것을_들고_간다() {
        var fresh = ScreenPickWorker.claudeArgsFor(true, "sonnet", java.nio.file.Path.of("/tmp/일감"), null);
        assertThat(fresh).as("이어붙일 것이 없으면 붙이지 않는다").doesNotContain("--resume");

        var resumed = ScreenPickWorker.claudeArgsFor(
                true, "sonnet", java.nio.file.Path.of("/tmp/일감"), "79a07238-1ceb-4b01-bfdd-241183d0686b");

        assertThat(resumed).containsSequence("--resume", "79a07238-1ceb-4b01-bfdd-241183d0686b");
        assertThat(resumed.indexOf("--resume"))
                .as("⛔ --add-dir 뒤로 가면 그 목록에 삼켜진다")
                .isLessThan(resumed.indexOf("--add-dir"));
        assertThat(resumed).as("모양은 이어붙여도 스키마가 잡는다").contains("--json-schema");
    }

    /**
     * ⭐ <b>이어붙이는 판의 지시문은 짧다.</b> 전체 지시문을 다시 보내면 모델이 그것을
     * <b>새로 시키는 일로 읽고</b> 항목 쪼개기부터 다시 한다 — 이어붙이는 뜻이 없어진다.
     */
    @Test
    void 이어붙이는_지시문은_저장소를_다시_뒤지지_말라고_말한다() {
        String again = ScreenPickWorker.resumeInstruction("/실행자리/인터뷰.md", "/실행자리/분석조건.md");

        assertThat(again)
                .as("답변이 앉은 자리를 짚어 준다").contains("/실행자리/인터뷰.md")
                .as("사용자가 고른 화면도 다시 읽는다").contains("/실행자리/분석조건.md")
                .contains("이미 확정한 입력")
                .contains("이 `note`는 AI 화면 초안 입력으로 전달된다")
                .as("다시 뒤지지 않는다").contains("이미 읽은 것을 다시 뒤지지 마라")
                .as("모양은 앞판 그대로다").contains("앞에서 정한 그대로")
                .as("최상위 키 목록은 --json-schema 의 required 가 강제한다 — 지시문에 되풀이하지 않는다")
                .doesNotContain("type, analysisSummary, assistantMessage, question, title, items");
        assertThat(again.length())
                .as("전체 지시문을 다시 보내면 이어붙이는 뜻이 없다")
                .isLessThan(ScreenPickWorker.instruction("무엇", "/실행자리/화면목록.md").length() / 4);
    }

    @Test
    void 신규_화면이_요약에만_적히면_서버가_프론트_작업_범위로_보완한다() {
        FrdScreen newScreen = FrdScreen.drafted("0000001", "0000002", TemporaryScreenId.of("0000001"),
                "폐업 가맹점 전용 상세", "상세", null, "backoffice");
        ScreenPickReader.Pick backendOnly = new ScreenPickReader.Pick("폐업 가맹점 관리", List.of(
                new ScreenPickReader.Item("폐업 가맹점 조회 API를 만든다", ScreenPickReader.Nature.DEVELOP,
                        ScreenPickReader.Verdict.NO_SCREEN, List.of(), "API 작업")), List.of(), "프론트 변경 없음");
        FrdInterviewReader.Result analysis = new FrdInterviewReader.Result("신규 화면을 만든다", "정리했습니다.",
                backendOnly, List.of(), List.of(), List.of(), FrdInterviewReader.WorkMode.FAST_TRACK,
                "백엔드 작업만 있습니다.");

        FrdInterviewReader.Result retained = ScreenPickWorker.retainNewScreenScope(analysis, List.of(newScreen));

        assertThat(retained.pick().screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo(newScreen.screenId());
            assertThat(screen.screenName()).isEqualTo("폐업 가맹점 전용 상세");
        });
        assertThat(retained.pick().items()).anySatisfy(item -> {
            assertThat(item.nature()).isEqualTo(ScreenPickReader.Nature.DEVELOP);
            assertThat(item.verdict()).isEqualTo(ScreenPickReader.Verdict.SCREEN);
            assertThat(item.screenIds()).containsExactly(newScreen.screenId());
        });
        assertThat(retained.pick().noScreenReason()).isNull();
        assertThat(retained.workMode()).isEqualTo(FrdInterviewReader.WorkMode.FRD);
    }

    @Test
    void 신규_화면이_화면목록에만_있어도_요구사항_항목으로_보완한다() {
        FrdScreen newScreen = FrdScreen.drafted("0000001", "0000002", TemporaryScreenId.of("0000001"),
                "폐업 가맹점 조회", "목록", null, "backoffice");
        ScreenPickReader.Pick missingRequirementLink = new ScreenPickReader.Pick("폐업 가맹점 관리", List.of(
                new ScreenPickReader.Item("폐업 처리 API를 추가한다", ScreenPickReader.Nature.DEVELOP,
                        ScreenPickReader.Verdict.NO_SCREEN, List.of(), "백엔드 작업")), List.of(
                new ScreenPickReader.Picked(newScreen.screenId(), "backoffice", newScreen.screenName(),
                        "신규 화면으로 생성")), null);
        FrdInterviewReader.Result analysis = new FrdInterviewReader.Result("백엔드 범위만 정리했습니다.", "정리했습니다.",
                missingRequirementLink, List.of(), List.of(), List.of(), FrdInterviewReader.WorkMode.FAST_TRACK,
                "백엔드 작업만 있습니다.");

        FrdInterviewReader.Result retained = ScreenPickWorker.retainNewScreenScope(analysis, List.of(newScreen));

        assertThat(retained.pick().screens()).singleElement()
                .extracting(ScreenPickReader.Picked::screenId).isEqualTo(newScreen.screenId());
        assertThat(retained.pick().items()).hasSize(2).anySatisfy(item -> {
            assertThat(item.requirement()).isEqualTo("신규 화면 개발: 폐업 가맹점 조회");
            assertThat(item.nature()).isEqualTo(ScreenPickReader.Nature.DEVELOP);
            assertThat(item.verdict()).isEqualTo(ScreenPickReader.Verdict.SCREEN);
            assertThat(item.screenIds()).containsExactly(newScreen.screenId());
        });
        assertThat(retained.workMode()).isEqualTo(FrdInterviewReader.WorkMode.FRD);
    }

    @Test
    void 인터뷰는_필요한_질문을_다섯_번_안에_마치고_사용자가_원하면_바로_정리한다() {
        String first = ScreenPickWorker.interviewRoundInstruction(0);
        String second = ScreenPickWorker.interviewRoundInstruction(1);
        String finished = ScreenPickWorker.interviewRoundInstruction(5);

        assertThat(first)
                .contains("남은 질문 횟수: 5회")
                .contains("작업 목적과 큰 범위")
                .contains("이미 답한 내용은 확정된 입력")
                .contains("권장안 적용")
                .contains("현재 내용으로 범위 정리");
        assertThat(second)
                .contains("남은 질문 횟수: 4회")
                .contains("화면·백엔드·권한·완료 기준");
        assertThat(finished)
                .contains("남은 질문 횟수: 0회")
                .contains("질문을 더 만들지 마라")
                .contains("반드시 RESULT")
                .contains("openIssues", "확인 필요", "acceptanceCriteria");
    }

    @Test
    void 기본_결과_예시도_구조화_출력의_필수_최상위_키를_모두_쓴다() {
        String prompt = ScreenPickWorker.instruction("/실행자리/요구사항.md", "/실행자리/화면목록.md");

        assertThat(prompt)
                .contains("{\"type\":\"RESULT\"")
                .contains("\"analysisSummary\":")
                .contains("\"assistantMessage\":")
                .contains("\"question\":null")
                .contains("\"backendChanges\":[]")
                .contains("\"acceptanceCriteria\":[]")
                .contains("\"openIssues\":[]")
                .doesNotContain("{\"title\":\"업무명 한 줄\"");
    }

    @Test
    void 지시문이_사용자가_고른_적용_대상과_화면을_우선_분석하게_한다() {
        FrdScreen newScreen = FrdScreen.drafted("0000004", "0000001", TemporaryScreenId.of("0000004"),
                "임시 저장 문서함", "목록", "제주", "webview");
        String context = ScreenPickWorker.analysisContext(
                List.of(FrdFacet.create("0000001", "0000002", "제주")),
                List.of(FrdScreen.picked("0000003", "0000001", "wv-appr-write",
                        "결재 문서 작성", "wv-appr-write", "제주", null),
                        newScreen),
                List.of(new FrdScreenIaPlacement(newScreen.id(),
                        FrdScreenIaPlacement.PlacementMode.CHILD, null, null, "wv-appr-write",
                        FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                        FrdScreenIaPlacement.Source.USER, null, java.time.Instant.now(), null)));
        String prompt = ScreenPickWorker.analysisContextInstruction("/실행자리/분석조건.md");

        assertThat(context).contains("적용 대상").contains("제주")
                .contains("수정할 솔루션 목업").contains("결재 문서 작성").contains("wv-appr-write")
                .contains("# 신규 화면\n- 임시 저장 문서함")
                .contains("시스템 webview")
                .contains("화면 유형 목록")
                .contains("화면 종류 화면")
                .contains("IA 위치 기준 화면 아래 (wv-appr-write)");
        assertThat(prompt).contains("/실행자리/분석조건.md")
                .contains("요구사항보다 먼저 읽어라")
                .contains("결과의 screens에서 빠뜨리지 마라")
                .contains("`신규 화면`은 사용자가 새로 만들기로 정한 화면이다")
                .contains("시스템·화면 종류·IA 위치는 사용자가 정한 값이다")
                .contains("신규 화면을 이름·유형만 있는 빈 항목으로 끝내지 마라")
                .contains("조회 조건, 결과 목록 항목, 기본 정렬·페이지 처리")
                .contains("첫 화면을 만드는 입력으로 사용한다")
                .contains("등록·수정: 입력 항목, 필수·검증 조건, 저장·취소 뒤 처리")
                .as("화면 유형별 확인 항목은 한 벌만 있다 — 같은 목록을 두 번 적지 않는다")
                .containsOnlyOnce("- 안내: ")
                .contains("개발 대상이라고 단정하지 마라");
    }

    @Test
    void 화면을_고르지_않으면_선택_없음을_화면_작업_없음으로_판단하지_않는다() {
        String context = ScreenPickWorker.analysisContext(List.of(), List.of());
        String prompt = ScreenPickWorker.analysisContextInstruction("/실행자리/분석조건.md");

        assertThat(context).contains("# 수정할 솔루션 목업\n- 선택 없음")
                .contains("# 신규 화면\n- 선택 없음");
        assertThat(prompt)
                .contains("화면 작업이 없다는 뜻이 아니다")
                .contains("우선 확인할 화면을 지정하지 않은 상태")
                .contains("대상 화면을 직접 찾아라");
    }

    @Test
    void 인터뷰_출력은_내부_요약과_사용자에게_보일_대답을_나눈다() {
        String prompt = ScreenPickWorker.instruction("/실행자리/요구사항.md", "/실행자리/화면목록.md",
                "/실행자리/화면후보.md", "/실행자리/인터뷰.md", "/저장소",
                "builder-project-0000001", true);

        assertThat(prompt)
                .contains("`assistantMessage`에는 사용자의 가장 최근 말에 바로 답하는 자연스러운 한국어")
                .contains("조사 근거는 `analysisSummary`에만 적는다")
                .contains("\"assistantMessage\":\"말씀하신 내용을 반영해 수정 범위를 다시 정리했습니다.\"");
    }

    @Test
    void 첫_분석은_현재_프로젝트의_코드베이스_색인을_읽기_전용으로_쓴다() throws IOException {
        var args = ScreenPickWorker.claudeArgsFor(
                true, "sonnet", java.nio.file.Path.of("/tmp/일감"), null,
                true, "/실행자리/codebase-memory-mcp.json");

        assertThat(args).containsSequence("--mcp-config", "/실행자리/codebase-memory-mcp.json")
                .contains("--strict-mcp-config")
                .containsSequence("--effort", "low");
        String config = ScreenPickWorker.codebaseMemoryConfig(
                "codebase-memory-mcp", java.nio.file.Path.of("/기획저장소/clone"));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(config);
        assertThat(parsed.path("mcpServers").path("codebase_memory").path("command").asText())
                .isEqualTo("codebase-memory-mcp");
        var env = parsed.path("mcpServers").path("codebase_memory").path("env");
        assertThat(env.path("CBM_ALLOWED_ROOT").asText()).contains("기획저장소", "clone");

        String allowed = args.get(args.indexOf("--allowed-tools") + 1);
        assertThat(allowed)
                .contains("Read")
                .contains("mcp__codebase_memory__index_repository")
                .contains("mcp__codebase_memory__search_code")
                .doesNotContain("Grep", "Glob", "Bash", "mcp__codebase_memory__search_graph");
        assertThat(args.indexOf("--mcp-config"))
                .as("값을 여러 개 받는 --add-dir 앞에 MCP 설정을 둔다")
                .isLessThan(args.indexOf("--add-dir"));
    }

    @Test
    void 요구사항_인터뷰의_추론_수준은_설정에서_오고_이어붙이는_판은_따로_정한다() {
        var defaults = new RequirementAnalysisProperties(null, null, true, null, null, null);
        assertThat(defaults.effortFor(null)).as("코드에 high 를 박지 않는다 — 기본은 medium").isEqualTo("medium");
        assertThat(defaults.effortFor("session-1")).as("이어붙이는 판 값을 비우면 첫 판 값을 따른다").isEqualTo("medium");

        var tuned = new RequirementAnalysisProperties(null, "opus", true, null, "high", "low");
        assertThat(tuned.effortFor(null)).isEqualTo("high");
        assertThat(tuned.effortFor("session-1")).isEqualTo("low");

        var args = ScreenPickWorker.claudeArgsFor(
                true, "sonnet", java.nio.file.Path.of("/tmp/일감"), "session-1",
                false, null, tuned.effortFor("session-1"));
        assertThat(args)
                .containsSequence("--model", "sonnet")
                .containsSequence("--effort", "low")
                .doesNotContain("--mcp-config");
    }

    @Test
    void 첫_분석은_MCP로_한번에_후보를_좁히고_도구_턴에_상한을_둔다() {
        String instruction = ScreenPickWorker.instruction(
                "/실행자리/요구사항.md", "/실행자리/화면목록.md", "/실행자리/화면후보.md",
                "/실행자리/인터뷰.md",
                "C:/기획저장소/clone", "builder-project-0000001", true);

        assertThat(instruction)
                .contains("화면 후보 자료 `/실행자리/화면후보.md`를 먼저 한 번 읽는다")
                .contains("후보가 충분하면 해당 화면 `.md`만 읽고 MCP를 호출하지 않는다")
                .contains("`index_repository`")
                .contains("`search_code`")
                .contains("`mode=files`")
                .contains("둘로도 확정할 수 없을 때만 전체를 한 번 읽어라")
                .contains("builder-project-0000001")
                .contains("C:/기획저장소/clone")
                .contains("`Grep`, `Glob`, `Bash`, `search_graph`")
                .as("「읽지 마라」와 「확인할 때만 본다」가 한 지시문에 같이 서면 안 된다")
                .doesNotContain("목록에 없는 것을 확인할 때만 본다")
                .doesNotContain("화면 목록은 **한 번** 읽고 그것으로 후보를 다 뽑아라")
                .doesNotContain("`Glob`으로");
    }

    /**
     * ⭐ <b>이어붙이는 판에는 정책·표준용어 블록을 다시 싣지 않는다 (2026-09-02).</b>
     *
     * <p>앞판이 이미 읽어 대화에 들어 있고, 이어붙이는 지시문은 「이미 읽은 것을 다시 뒤지지 마라」다.
     * 블록을 다시 붙이면 그 금지와 부딪히고, 모델이 정책서(실측 14KB)·용어집을 답변 턴마다
     * 다시 읽게 부른다 — MCP 를 이어붙이는 판에 안 띄우는 것과 같은 까닭, 같은 길이다.
     */
    @Test
    void 이어붙이는_판에는_정책과_표준용어_블록을_다시_붙이지_않는다() {
        withCredential();
        Project p = readyProjectWithClone("탐나는전");
        businessDocuments.upsert(p.getId(), BusinessDocumentKind.POLICY, "## 정책", "[]", planner.getId());
        businessDocuments.upsert(p.getId(), BusinessDocumentKind.STANDARD_TERMS, "# 용어", "[]", planner.getId());
        String frdId = seedFrd(p, "임시저장");
        ScreenPickWorker full = new ScreenPickWorker(frds, picks, reader,
                new ClaudeCredentialRunner(runner, credentials, credentialFile, accountLocks),
                properties, analysisProperties, paths, progress, solutionScreens,
                interviewReader, interviewService, frdFacets, screens, iaPlacements, liveEvents);
        full.setBusinessLanguage(businessLanguage);
        // 시험 DB 는 판마다 초기화돼 FRD 번호가 재사용되는데 실행 자리는 디스크에 남는다 —
        // 지난 판의 세션 기억을 주워 첫 판부터 이어붙이지 않게 비우고 시작한다.
        full.release(frdId);

        answers.add(questionWithSession("세션-1"));
        full.pick(frdId);
        assertThat(runner.lastInstruction).as("첫 판은 정책·용어를 싣는다").contains("사업 정책과 표준용어");

        String questionId = interviewMessages.selectLatestQuestion(frdId).id();
        interviewService.answer(frdId, questionId, "그대로 진행해 주세요");
        answers.add(questionWithSession("세션-1"));
        full.pick(frdId);

        assertThat(runner.lastInstruction)
                .as("이어붙이는 판이 맞다").contains("사용자가 답했다")
                .as("정책·용어 블록을 다시 붙이지 않는다").doesNotContain("사업 정책과 표준용어");
    }

    /** 저쪽이 세션ID 를 실어 준 질문 한 판 — 다음 판이 이것으로 이어붙인다. */
    private static ClaudeResult questionWithSession(String sessionId) {
        return new ClaudeResult(0, false, "completed", null, """
                {"type":"QUESTION","analysisSummary":"확인한 사실",
                 "assistantMessage":"한 가지만 확인할게요.",
                 "question":{"topic":"범위","text":"어느 화면입니까?","reason":"화면을 못 좁혔습니다",
                             "options":["작성 화면","목록 화면"]},
                 "title":null,"items":[],"backendChanges":[],
                 "acceptanceCriteria":[],"openIssues":[],"workMode":null,
                 "workModeReason":null,"noScreenReason":null}""", sessionId, null);
    }

    @Test
    void Builder가_요구사항과_직접_겹치는_화면을_작은_후보_자료로_먼저_만든다() {
        List<SolutionScreen> screens = List.of(
                solutionScreen("wv-modal-all-menu", "전체 메뉴", "서비스 > 전체 메뉴"),
                solutionScreen("wv-merc-search-main", "가맹점 찾기", "가맹점 > 가맹점 찾기"),
                solutionScreen("bo-user-list", "사용자 목록", "사용자 관리 > 목록"));

        String evidence = ScreenPickWorker.screenCandidateEvidence(
                "전체 메뉴의 고유가 피해지원금 경로를 숨기고 가맹점 찾기를 변경한다", screens);

        assertThat(evidence)
                .contains("Builder가 먼저 좁힌 화면 후보")
                .contains("wv-modal-all-menu")
                .contains("wv-merc-search-main")
                .doesNotContain("bo-user-list")
                .contains("동의어 후보는 빠질 수 있다")
                .contains("화면목록.md");
    }

    private static SolutionScreen solutionScreen(String id, String name, String menuPath) {
        return new SolutionScreen(id, name, "webview", "화면", null, null, menuPath, null,
                null, null, List.of(), List.of(), null, List.of(), false, null);
    }
}
