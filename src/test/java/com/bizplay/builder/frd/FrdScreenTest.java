package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.devrequest.DevelopmentRequest;
import com.bizplay.builder.devrequest.DevelopmentRequestMapper;
import com.bizplay.builder.devrequest.DevRequestDeliveryAttempt;
import com.bizplay.builder.devrequest.DevRequestDeliveryMapper;
import com.bizplay.builder.devrequest.DeliveryOutcome;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectSystem;
import com.bizplay.builder.project.ProjectSystemMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.PlanningRepositoryUpdater;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FRD 작업 화면 — 목록(계획 8 Task 3), 마법사(Task 5)와 작업대(Task 6)가 뒤이어 몫을 더한다.
 *
 * <p>정본: 목업 {@code docs/mockups/05-frds.html}·{@code 05a-frd-workbench.html}·
 * 설계는 {@code docs/superpowers/specs/2026-08-18-frd-fast-track-design.md}.
 *
 * <p>도움 메서드 모양은 {@code RequirementScreenTest} 를 그대로 베꼈다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class FrdScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdScreenHistoryMapper screenHistories;
    @Autowired FrdScreenIaPlacementMapper iaPlacements;
    @Autowired ScreenMockupService screenMockups;
    @Autowired FrdScreenChatService screenChats;
    @Autowired FrdScreenChatMapper screenChatMessages;
    @Autowired FrdItemMapper items;
    @Autowired com.bizplay.builder.ai.AiProgress progress;
    @Autowired FrdService frdService;
    @Autowired FrdInterviewService interviewService;
    @Autowired FrdInterviewReader interviewReader;
    @Autowired FrdFacetMapper frdFacets;
    @Autowired FrdBackendChangeMapper backendChanges;
    @Autowired FrdAnalysisNoteMapper analysisNotes;
    @Autowired DevelopmentRequestMapper developmentRequests;
    @Autowired DevRequestDeliveryMapper deliveryAttempts;
    @Autowired ScreenStandardIdMapper standardIds;
    @Autowired com.bizplay.builder.intake.ProjectFacetMapper projectFacets;
    @Autowired SolutionScreenReader solutions;
    @Autowired ProjectPaths paths;
    @Autowired ProjectSystemMapper projectSystems;
    @MockitoBean FrdWorkspace workspaces;
    @MockitoBean PlanningRepositoryUpdater repositoryUpdater;
    @MockitoBean ScreenMockupBatchWorker mockupBatchWorker;

    /** 로그인한 기획자. ⚠ Task 5·6 의 마법사·작업대 시험이 {@code user(planner)} 로 그대로 쓴다. */
    private BuilderUser planner;

    @BeforeEach
    void signInPlanner() {
        planner = planner();
        given(workspaces.ensure(anyString(), anyString())).willAnswer(invocation -> {
            String frdId = invocation.getArgument(1);
            return new FrdWorkspace.Prepared(Path.of("test", "clone"), Path.of("test", "frd-" + frdId),
                    FrdWorkspace.branch(frdId), false, false);
        });
        given(repositoryUpdater.withLatest(anyString(), any())).willAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        given(workspaces.syncWithCloneDetails(anyString(), anyString())).willReturn(
                new FrdWorkspace.SyncResult(FrdWorkspace.Sync.UP_TO_DATE, "test-head", List.of()));
    }

    @Test
    void 완료_전_FRD_상세에만_삭제_버튼이_보인다() throws Exception {
        Project project = readyProject("삭제 버튼");
        String frdId = seedScopeReviewFrd(project);

        String detail = mvc.perform(get("/projects/%s/artifacts/frds/%s/pick"
                        .formatted(project.getId(), frdId)).with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(detail)
                .contains("data-frd-delete-open")
                .contains("/projects/%s/artifacts/frds/%s/delete".formatted(project.getId(), frdId))
                .contains("data-submit-loading=\"FRD 작업 삭제 중\"")
                .contains("data-submit-loading-tone=\"dark\"")
                .contains("인터뷰 내용, 분석 결과, 화면 초안과 작업공간이 모두 삭제되며 복구할 수 없습니다.");
        assertThat(list(project.getId())).doesNotContain("/artifacts/frds/%s/delete".formatted(frdId));
    }

    @Test
    void 요구사항_인터뷰_중에는_삭제_버튼과_레이어를_보이지_않는다() throws Exception {
        Project project = readyProject("인터뷰 삭제 버튼 제외");
        String frdId = seedFrd(project, "인터뷰 진행 중 삭제 버튼을 숨긴다");
        frds.updateState(frdId, Frd.State.WAITING_ANSWER);

        String interview = mvc.perform(get("/projects/%s/artifacts/frds/%s/pick"
                        .formatted(project.getId(), frdId)).with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(interview)
                .contains("class=\"fi-steps\"")
                .contains("class=\"fic-chat-layout\"")
                .doesNotContain("data-frd-delete-open")
                .doesNotContain("id=\"frd-delete-dialog\"");
    }

    @Test
    void 전체_맵에서도_완료_전_FRD를_삭제할_수_있다() throws Exception {
        Project project = readyProject("전체 맵 삭제 버튼");
        String frdId = seedDraftingFrd(project);

        String canvas = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(canvas)
                .contains("data-frd-delete-open")
                .contains("class=\"pop rq-head__overflow\"")
                .containsSubsequence("FRD 내용 보기", "FRD 작업 완료", "aria-label=\"기타 작업\"")
                .contains("/projects/%s/artifacts/frds/%s/delete".formatted(project.getId(), frdId))
                .contains("data-submit-loading=\"FRD 작업 삭제 중\"")
                .contains("data-submit-loading-tone=\"dark\"");
    }

    @Test
    void 완료_전_FRD를_삭제하면_연결_데이터와_함께_목록에서_사라진다() throws Exception {
        Project project = readyProject("FRD 삭제");
        String frdId = seedScopeReviewFrd(project);
        assertThat(screens.selectByFrdId(frdId)).isNotEmpty();

        mvc.perform(post("/projects/%s/artifacts/frds/%s/delete".formatted(project.getId(), frdId))
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/%s/artifacts/frds".formatted(project.getId())))
                .andExpect(flash().attribute("message", "FRD-001 작업을 삭제했습니다."));

        assertThat(frds.selectById(frdId)).isNull();
        assertThat(screens.selectByFrdId(frdId)).isEmpty();
    }

    @Test
    void 완료된_FRD는_직접_요청해도_삭제하지_않는다() throws Exception {
        Project project = readyProject("완료 FRD");
        String frdId = seedScopeReviewFrd(project);
        frds.updateState(frdId, Frd.State.REVIEW);

        mvc.perform(post("/projects/%s/artifacts/frds/%s/delete".formatted(project.getId(), frdId))
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/%s/artifacts/frds/%s".formatted(project.getId(), frdId)))
                .andExpect(flash().attribute("error", "완료된 FRD 작업은 삭제할 수 없습니다."));

        assertThat(frds.selectById(frdId)).isNotNull();
    }

    // ── 목록 ──────────────────────────────────────────────────────────────

    @Test
    void 목록이_껍데기_계약을_지키고_제_열쇠에_지금_표시를_받는다() throws Exception {
        Project p = readyProject("탐나는전");
        seedFrd(p, "전자결재 상신 임시저장 지원");

        String html = list(p.getId());

        assertThat(html).contains("<title>FRD 작업 · 빌더</title>");
        assertThat(html)
                .contains("FRD 작업 만들기")
                .contains("/projects/" + p.getId() + "/artifacts/frds/new");
        assertThat(com.bizplay.builder.shell.ShellContractTest.markedLinkHref(html))
                .isEqualTo("/projects/" + p.getId() + "/artifacts/frds");
    }

    @Test
    void 목록에_데이터가_없으면_공통_빈_결과를_보여준다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = list(p.getId());

        assertThat(html)
                .contains("state-panel list-empty-state", "조회된 내용이 없습니다.")
                .contains("FRD 작업 만들기", "empty-state__action")
                .doesNotContain("class=\"data-table");
    }

    @Test
    void FRD_작업_목록을_개발요청서와_같이_페이지로_나눈다() throws Exception {
        Project p = readyProject("탐나는전");
        for (int number = 1; number <= 21; number++) {
            seedFrd(p, "페이징 확인 " + number);
        }

        String html = mvc.perform(get("/projects/{p}/artifacts/frds", p.getId())
                        .param("page", "2")
                        .param("pageSize", "20")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("페이징 확인 1")
                .doesNotContain("페이징 확인 2")
                .contains("aria-label=\"FRD 작업 페이지 이동\"")
                .contains("aria-current=\"page\">2</a>")
                .contains("name=\"pageSize\"")
                .contains("10개씩")
                .contains("20개씩")
                .contains("50개씩")
                .contains("100개씩");
    }

    @Test
    void 목록이_합의한_열과_작업_범위를_적는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "전자결재 상신 임시저장 지원");
        screens.insert(FrdScreen.drafted(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "tmp-0000001", "결재 문서 작성", "등록", null, "webview"));
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                frdId, 1, null, FrdBackendChange.Category.API, "결재 API", "임시저장을 지원한다.",
                null, null, true, null));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));
        frdFacets.insert(FrdFacet.create(frdId, p.getId(), "익산"));

        String html = list(p.getId());

        assertThat(html)
                .contains("<th scope=\"col\">FRD</th>")
                .contains("<th scope=\"col\">요구사항</th>")
                .contains("<th scope=\"col\">적용 대상</th>")
                .contains("<th scope=\"col\">시스템</th>")
                .contains("<th scope=\"col\">작업 범위</th>")
                .contains("<th scope=\"col\">상태</th>")
                .contains("<th scope=\"col\">담당자</th>")
                .contains("<th scope=\"col\">생성일시</th>")
                .contains("<th scope=\"col\">완료일시</th>")
                .contains("FRD-001")
                .contains("전자결재 상신 임시저장 지원")
                .contains("익산").contains("webview")
                .contains("화면 1개 · 신규 1개 · 백엔드 1건")
                .contains("요구사항 분석 중")
                .doesNotContain("<th scope=\"col\">다음 작업</th>")
                .doesNotContain("생성 방식")
                .contains("/projects/" + p.getId() + "/artifacts/frds/" + frdId);
    }

    @Test
    void FRD_목록에_검색과_상태_담당자_시스템_필터를_둔다() throws Exception {
        Project p = readyProject("탐나는전");
        seedFrd(p, "전자결재 상신 임시저장 지원", planner.accountId());

        String html = list(p.getId());

        assertThat(html)
                .contains("data-list-loading-region")
                .contains("data-list-loading-overlay")
                .contains("role=\"search\"")
                .contains("for=\"frd-search\">FRD 검색</label>")
                .contains("name=\"query\"")
                .contains("for=\"frd-state\">상태</label>")
                .contains("name=\"state\"")
                .contains("for=\"frd-owner\">담당자</label>")
                .contains("name=\"owner\"")
                .contains("for=\"frd-system\">시스템</label>")
                .contains("name=\"system\"")
                .contains(">검색</button>")
                .containsSubsequence("for=\"frd-system\"", "for=\"frd-owner\"",
                        "for=\"frd-state\"", "for=\"frd-search\"")
                .doesNotContain("name=\"facet\"");
    }

    @Test
    void FRD_번호_요구사항명과_요구사항_원문으로_검색한다() throws Exception {
        Project p = readyProject("탐나는전");
        String firstId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(firstId, p.getId(), frds.allocateNumber(p.getId()),
                "급여 조회 개선", "급여 명세서를 월별로 내려받을 수 있어야 한다.", planner.accountId()));
        String secondId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(secondId, p.getId(), frds.allocateNumber(p.getId()),
                "전자결재 개선", "결재 문서를 임시 저장할 수 있어야 한다.", planner.accountId()));

        String byNumber = filteredList(p.getId(), "FRD-001", "", "", "");
        String byTitle = filteredList(p.getId(), "전자결재", "", "", "");
        String bySource = filteredList(p.getId(), "명세서를 월별로", "", "", "");

        assertThat(byNumber).contains("급여 조회 개선").doesNotContain("전자결재 개선");
        assertThat(byTitle).contains("전자결재 개선").doesNotContain("급여 조회 개선");
        assertThat(bySource).contains("급여 조회 개선").doesNotContain("전자결재 개선");
    }

    @Test
    void FRD_상세에서_목록으로_돌아갈_때_검색_조건을_유지한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "전자결재 개선", planner.accountId());
        frds.updateState(frdId, Frd.State.DRAFTING);

        String listHtml = mvc.perform(get("/projects/{p}/artifacts/frds", p.getId())
                        .param("query", "FRD-001")
                        .param("state", "DRAFTING")
                        .param("owner", planner.accountId())
                        .param("page", "1")
                        .param("pageSize", "20")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(listHtml).contains("/projects/" + p.getId() + "/artifacts/frds/" + frdId
                + "?query=FRD-001&amp;state=DRAFTING&amp;owner=" + planner.accountId()
                + "&amp;system=&amp;page=1&amp;pageSize=20");

        String detailHtml = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .param("query", "FRD-001")
                        .param("state", "DRAFTING")
                        .param("owner", planner.accountId())
                        .param("page", "1")
                        .param("pageSize", "20")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(detailHtml).contains("/projects/" + p.getId()
                + "/artifacts/frds?query=FRD-001&amp;state=DRAFTING&amp;owner=" + planner.accountId()
                + "&amp;system=&amp;page=1&amp;pageSize=20\">목록으로</a>");
    }

    @Test
    void 분석_중인_FRD도_상세를_거쳐_목록_검색_조건을_유지한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "전자결재 개선", planner.accountId());

        var redirected = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .param("query", "FRD-001")
                        .param("state", "ANALYZING")
                        .param("owner", planner.accountId())
                        .param("page", "1")
                        .param("pageSize", "20")
                        .with(user(superUser())))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirected)
                .contains("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick?")
                .contains("query=FRD-001", "state=ANALYZING", "owner=" + planner.accountId(),
                        "page=1", "pageSize=20");

        String wizardHtml = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .param("query", "FRD-001")
                        .param("state", "ANALYZING")
                        .param("owner", planner.accountId())
                        .param("page", "1")
                        .param("pageSize", "20")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(wizardHtml).contains("/projects/" + p.getId()
                + "/artifacts/frds?query=FRD-001&amp;state=ANALYZING&amp;owner=" + planner.accountId()
                + "&amp;system=&amp;page=1&amp;pageSize=20\">목록으로</a>");
    }

    @Test
    void 상태_담당자_시스템을_함께_골라_FRD를_거른다() throws Exception {
        Project p = readyProject("탐나는전");
        String matchedId = seedFrd(p, "조건에 맞는 작업", planner.accountId());
        screens.insert(FrdScreen.drafted(ids.next(IdSequence.Kind.FRD_SCREEN), matchedId,
                "tmp-0000101", "결재 작성", "등록", null, "webview"));
        frds.updateState(matchedId, Frd.State.DRAFTING);

        String otherId = seedFrd(p, "조건에 맞지 않는 작업");
        screens.insert(FrdScreen.drafted(ids.next(IdSequence.Kind.FRD_SCREEN), otherId,
                "tmp-0000102", "관리자 설정", "등록", null, "backoffice"));

        String html = filteredList(p.getId(), "", "DRAFTING", planner.accountId(), "webview");

        assertThat(html)
                .contains("조건에 맞는 작업")
                .doesNotContain("조건에 맞지 않는 작업")
                .contains("value=\"DRAFTING\" selected=\"selected\"")
                .contains("value=\"" + planner.accountId() + "\" selected=\"selected\"")
                .contains("value=\"webview\" selected=\"selected\"");
    }

    /** 분석 중이고 아직 범위가 잡히지 않은 FRD를 완료된 무화면 작업처럼 보이지 않게 한다. */
    @Test
    void 분석_중이고_범위가_아직_없으면_확인_중으로_표시한다() throws Exception {
        Project p = readyProject("탐나는전");
        seedFrd(p, "야간 정산 배치 주기 변경");   // seedFrd 는 기본이 ANALYZING 이다 — 화면 0장이 정상이다

        String html = list(p.getId());

        assertThat(html).contains("확인 중").contains("요구사항 분석 중");
    }

    @Test
    void 남의_프로젝트_FRD_는_목록에_안_나온다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("지역화폐");
        seedFrd(other, "남의 것");

        assertThat(list(mine.getId())).doesNotContain("남의 것");
    }

    // ── 화면 진행 · 남은 작업의 다섯 갈래 (목업 05-frds.html 실측, 2026-08-18 리뷰) ──────────

    /** ⚠ 순서가 중요하다 — {@code ANALYSIS_FAILED} 는 화면 수와 무관하게 이 둘을 이긴다. */
    @Test
    void 분석_실패_상태는_화면_수와_무관하게_분석_오류를_적는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "정산 대사 화면 개선");
        frds.updateAfterPick(frdId, "정산 대사 화면 개선", null, null, Frd.State.ANALYSIS_FAILED, "분석 실패");

        String html = list(p.getId());

        assertThat(html).contains("분석 오류");
    }

    /** 화면을 아직 안 찾은(ANALYZING) FRD 는 화면 수는 세되 남은 작업은 「—」다 — 있을지조차 모른다. */
    @Test
    void 분석_중인_FRD도_확인된_화면_범위를_표시한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "공지 게시 기능 개선");
        screens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-notice-list", "공지 목록", "wv-notice-list", null, "게시 기간 칸이 없다"));
        screens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-notice-write", "공지 작성", "wv-notice-write", null, "예약 게시가 없다"));

        String html = list(p.getId());

        assertThat(html).contains("화면 2개 · 수정 2개 · 백엔드 없음")
                .contains("요구사항 분석 중");
    }

    /** 화면이 있는데 그 프로젝트가 PICKED 로 확정하고도 하나도 안 만들었으면 화면 없음이 아니라 화면 수다. */
    @Test
    void 확정된_FRD에_화면이_없으면_프론트와_백엔드_범위를_분리해_적는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "야간 정산 배치 주기 변경");
        frds.updateAfterPick(frdId, "야간 정산 배치 주기 변경", null,
                "배치 일이라 화면이 없다", Frd.State.PICKED, null);

        String html = list(p.getId());

        assertThat(html).contains("프론트 없음 · 백엔드 없음")
                .contains("분석 결과 확인");
    }

    /** 최종 반영 상태도 FRD 완료로 보이며 완료일시를 유지한다. */
    @Test
    void 최종_반영된_FRD는_완료일시와_완료_상태를_적는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "공지 게시 기능 개선");
        String screenId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(screenId, frdId,
                "wv-notice-list", "공지 목록", "wv-notice-list", null, "게시 기간 칸이 없다"));
        screens.updateGenerated(screenId, "<article>완료</article>", null, Instant.now());
        frds.updateAfterPick(frdId, "공지 게시 기능 개선", null, null, Frd.State.DONE, null);

        String html = list(p.getId());

        assertThat(frds.selectById(frdId).completedAt()).isNotNull();
        assertThat(html).contains("화면 1개 · 수정 1개 · 백엔드 없음")
                .contains("status-badge--complete").contains(">완료</span>");
    }

    /** 개발요청서를 만든 REVIEW 상태는 FRD 관점에서 이미 완료다. */
    @Test
    void 개발요청서를_만든_FRD는_완료일시와_완료_상태를_적는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "전자결재 상신 임시저장 지원");
        String done = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(done, frdId,
                "wv-appr-write", "결재 문서 작성", "wv-appr-write", null, "버튼이 없다"));
        screens.updateGenerated(done, "<article>완료</article>", null, Instant.now());
        screens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-appr-list", "임시저장 문서 목록", "wv-appr-list", null, "목록에 상태 열이 없다"));
        screens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-appr-detail", "결재 문서 상세", "wv-appr-detail", null, "상세에 임시저장 표시가 없다"));
        frds.updateAfterPick(frdId, "전자결재 상신 임시저장 지원", null, null, Frd.State.REVIEW, null);

        String html = list(p.getId());

        assertThat(frds.selectById(frdId).completedAt()).isNotNull();
        assertThat(html).contains("화면 3개 · 수정 3개 · 백엔드 없음")
                .contains("status-badge--complete").contains(">완료</span>");

        frds.updateState(frdId, Frd.State.DRAFTING);
        assertThat(frds.selectById(frdId).completedAt()).isNull();
    }

    /** 계정을 안 앉힌 FRD 는 담당이 널이라 「—」로만 뜬다. */
    @Test
    void 담당이_없는_FRD_는_담당_칸에_대시가_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        seedFrd(p, "전자결재 상신 임시저장 지원");

        assertThat(list(p.getId())).contains(">—</td>");
    }

    /**
     * ⭐ [F10 이어받기] 담당 열은 계정ID(일곱 자리 숫자)가 아니라 계정 이름을 보여야 한다.
     *
     * <p>⚠ 계정ID 가 화면에 아예 없다고 재지 않는다 — 프로젝트ID 도 같은 일곱 자리 채번이라
     * 우연히 같은 숫자가 나올 수 있다. 여기서 재는 것은 「이름이 뜬다」이지 「ID 가 안 뜬다」가 아니다.
     */
    @Test
    void 담당이_있으면_계정ID_가_아니라_이름이_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        seedFrd(p, "전자결재 상신 임시저장 지원", planner.accountId());

        assertThat(list(p.getId())).contains("이영희");
    }

    // ── 마법사 두 걸음 ────────────────────────────────────────────────────

    /** 걸음 1 — FRD 가 아직 없을 때(${frd == null}) 요구사항 입력 칸이 뜬다. */
    @Test
    void 걸음_하나가_요구사항_입력_칸을_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/new", p.getId())
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("frd-source").contains("요구사항 분석 준비")
                .contains("수정할 솔루션 목업")
                .contains("<header class=\"fi-setup-section__head\"><div><h2 id=\"setup-screen-title\"")
                .contains("class=\"button button--small\" id=\"open-analysis-screen\"")
                .doesNotContain("fi-setup-screen-empty__icon")
                .contains("document.getElementById('analysis-screen-dialog').showModal()")
                .contains("id=\"analysis-screen-dialog\"")
                .contains("화면명, 시스템, 화면 유형만 입력해 주세요.")
                .contains("name=\"newScreenSystem\"").contains("name=\"newScreenType\"")
                .doesNotContain("name=\"newScreenPlacementMode\"")
                .doesNotContain("name=\"newScreenAnchor\"")
                .doesNotContain("name=\"newScreenMenuPath\"")
                .doesNotContain("name=\"newScreenKind\"")
                .contains("적용 대상").contains("익산").contains("제주")
                .containsSubsequence("<legend>적용 대상</legend>",
                        "<span class=\"field__label\">요구사항 내용</span>")
                .contains("AI 분석 기준").contains("기능 범위").contains("완료 기준")
                .doesNotContain("분석 입력 요약");
        assertThat(html).contains("name=\"facet\" value=\"__ALL__\"")
                .contains("data-frd-setup-facet-all checked")
                .contains("모든 적용 대상");
    }

    @Test
    void 선택한_적용_대상이_FRD_분석_조건으로_저장된다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "상신 화면에서 임시 저장할 수 있어야 한다.")
                        .param("facet", "제주"))
                .andExpect(status().is3xxRedirection());

        String frdId = frds.selectByProjectId(p.getId()).get(0).id();
        assertThat(frdFacets.selectByFrdId(frdId)).singleElement()
                .extracting(FrdFacet::name).isEqualTo("제주");
    }

    @Test
    void 적용_대상을_고르지_않으면_현재_프로젝트의_전체_대상을_저장한다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "상신 화면에서 임시 저장할 수 있어야 한다."))
                .andExpect(status().is3xxRedirection());

        String frdId = frds.selectByProjectId(p.getId()).get(0).id();
        assertThat(frdFacets.selectByFrdId(frdId)).extracting(FrdFacet::name)
                .containsExactly("익산", "제주");
        assertThat(list(p.getId())).contains("<span class=\"badge badge--outline\">전체</span>");
    }

    @Test
    void 전체_적용_대상을_선택해도_현재_프로젝트의_모든_대상을_저장한다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "전체 적용 대상에서 임시 저장할 수 있어야 한다.")
                        .param("facet", "__ALL__"))
                .andExpect(status().is3xxRedirection());

        String frdId = frds.selectByProjectId(p.getId()).get(0).id();
        assertThat(frdFacets.selectByFrdId(frdId)).extracting(FrdFacet::name)
                .containsExactly("익산", "제주");
    }

    @Test
    void 사용자가_먼저_고른_솔루션_화면이_FRD에_사용자_선택으로_저장된다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));
        seedSolutionScreen(p.getId());

        String setupHtml = mvc.perform(get("/projects/{p}/artifacts/frds/new", p.getId())
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(setupHtml)
                .contains("결재 문서 작성")
                .contains("backoffice/pages/bo-appr-write.html")
                .contains("data-screen-id=\"bo-appr-write\"")
                .contains("data-preview-src=")
                .contains("fi-screen-preview-loading")
                .contains("IntersectionObserver")
                .contains("document.createElement('iframe')")
                .doesNotContain("<iframe")
                .doesNotContain("data-screen-facet=\"");

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "결재 작성 화면에 임시 저장을 추가한다.")
                        .param("facet", "제주")
                        .param("screenId", "bo-appr-write")
                        .param("screenName", "결재 문서 작성")
                        .param("baseScreenId", "bo-appr-write"))
                .andExpect(status().is3xxRedirection());

        String frdId = frds.selectByProjectId(p.getId()).get(0).id();
        assertThat(screens.selectByFrdId(frdId)).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("bo-appr-write");
            assertThat(screen.facet()).isEqualTo("제주");
            assertThat(screen.systemCode()).isEqualTo("backoffice");
            assertThat(screen.isUserSelected()).isTrue();
        });
    }

    @Test
    void 신규_화면도_요구사항_분석을_시작할_때_사용자_선택으로_저장된다() throws Exception {
        Project p = readyProject("탐나는전");
        projectSystems.insert(ProjectSystem.create(p.getId(), "backoffice", "백오피스"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "폐업 가맹점 상세 화면을 새로 만든다.")
                        .param("facet", "익산", "제주")
                        .param("newScreenName", "폐업 가맹점 상세")
                        .param("newScreenType", "상세")
                        .param("newScreenSystem", "backoffice"))
                .andExpect(status().is3xxRedirection());

        String frdId = frds.selectByProjectId(p.getId()).get(0).id();
        assertThat(screens.selectByFrdId(frdId)).singleElement().satisfies(screen -> {
            assertThat(screen.isNewScreen()).isTrue();
            assertThat(screen.isUserSelected()).isTrue();
            assertThat(screen.screenName()).isEqualTo("폐업 가맹점 상세");
            assertThat(screen.screenType()).isEqualTo("상세");
            assertThat(screen.systemCode()).isEqualTo("backoffice");
        });
    }

    @Test
    void 신규_화면은_복잡한_IA_입력_없이_분석_대기로_저장한다() throws Exception {
        Project p = readyProject("탐나는전");
        projectSystems.insert(ProjectSystem.create(p.getId(), "backoffice", "백오피스"));

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "결재 상세 화면을 새로 만든다.")
                        .param("newScreenName", "결재 상세")
                        .param("newScreenType", "상세")
                        .param("newScreenSystem", "backoffice"))
                .andExpect(status().is3xxRedirection());

        String frdId = frds.selectByProjectId(p.getId()).get(0).id();
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        assertThat(iaPlacements.selectByScreenId(screen.id())).satisfies(placement -> {
            assertThat(placement.placementMode()).isEqualTo(FrdScreenIaPlacement.PlacementMode.UNRESOLVED);
            assertThat(placement.screenKind()).isEqualTo(FrdScreenIaPlacement.ScreenKind.SCREEN);
            assertThat(placement.source()).isEqualTo(FrdScreenIaPlacement.Source.AI);
            assertThat(placement.status()).isEqualTo(FrdScreenIaPlacement.Status.PROPOSED);
        });
    }

    @Test
    void 요구사항을_붙여넣으면_FRD_가_앉고_화면_찾는_중이_된다() throws Exception {
        Project p = readyProject("탐나는전");

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "상신 화면에서 임시 저장할 수 있어야 한다."))
                .andExpect(status().is3xxRedirection());

        var all = frds.selectByProjectId(p.getId());
        assertThat(all).singleElement().satisfies(frd -> {
            assertThat(frd.state()).isEqualTo(Frd.State.ANALYZING);
            assertThat(frd.sourceKind()).isEqualTo(Frd.SourceKind.PASTED);
            assertThat(frd.sourceText()).contains("임시 저장");
        });
    }

    /**
     * ⭐ [F13] {@code open} 이 담당자를 반드시 앉힌다 — {@link ScreenPickWorker} 가 이 사람의
     * Claude 자격으로 화면 짚기를 돌린다. 담당이 널이면 자격 검사에서 바로 실패한다.
     */
    @Test
    void 붙여넣기로_연_FRD_의_담당은_로그인한_사람이다() throws Exception {
        Project p = readyProject("탐나는전");

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf())
                        .param("sourceText", "상신 화면에서 임시 저장할 수 있어야 한다."))
                .andExpect(status().is3xxRedirection());

        assertThat(frds.selectByProjectId(p.getId())).singleElement()
                .extracting(Frd::ownerAccountId).isEqualTo(planner.accountId());
    }

    @Test
    void 빈_요구사항은_거절하고_FRD_를_안_만든다() throws Exception {
        Project p = readyProject("탐나는전");

        mvc.perform(post("/projects/{p}/artifacts/frds", p.getId())
                        .with(user(planner)).with(csrf()).param("sourceText", "   "))
                .andExpect(status().is3xxRedirection());

        assertThat(frds.selectByProjectId(p.getId())).isEmpty();
    }

    @Test
    void AI가_질문을_만들면_답변_필요가_되고_직접_입력할_수_있다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "임시저장 화면 추가");

        interviewService.saveQuestion(frdId, new FrdInterviewReader.Question(
                "기존 작성 화면과 저장 API를 확인했습니다.",
                "작성 화면을 확인했어요. 화면 구성을 정하려면 한 가지만 더 확인할게요.", "신규 화면 여부",
                "기존 작성 화면에 기능을 추가하는 것인가요?",
                "요구사항만으로 별도 화면 여부를 확정할 수 없습니다.",
                java.util.List.of("기존 화면에 추가", "새 화면 추가", "아직 결정하지 않음")));

        assertThat(interviewService.currentQuestionRound(frdId)).isEqualTo(1);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.WAITING_ANSWER);
        assertThat(html).contains("<h1>요구사항 인터뷰</h1>").contains("답변 필요")
                .contains("기존 작성 화면에 기능을 추가하는 것인가요?")
                .contains("작성 화면을 확인했어요. 화면 구성을 정하려면 한 가지만 더 확인할게요.")
                .contains("fic-request-bubble").contains("입력한 요구사항").contains("전체 보기")
                .contains("기존 화면에 추가").contains("직접 입력")
                .contains("AI 인터뷰").contains("답변 제출")
                .contains("data-submit-loading=\"답변을 반영하는 중\"")
                .doesNotContain("현재 내용으로 범위 정리")
                .contains("현재까지 확인한 내용")
                .contains("기존 작성 화면과 저장 API를 확인했습니다.")
                .doesNotContain("궁금한 내용이나 추가 조건을 입력하세요")
                .doesNotContain("분석 대상").doesNotContain("<legend>답변 선택</legend>");

        FrdInterviewMessage question = interviewService.messages(frdId).stream()
                .filter(message -> message.kind() == FrdInterviewMessage.Kind.QUESTION)
                .findFirst().orElseThrow();
        interviewService.answer(frdId, question.id(), "목록과 분리된 새 작성 화면으로 만들어 주세요.");

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYZING);
        assertThat(interviewService.transcript(frdId)).contains("새 작성 화면으로 만들어 주세요");

        String analyzing = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(analyzing).contains("fic-history-question")
                .contains("기존 작성 화면에 기능을 추가하는 것인가요?")
                .contains("fic-answer-bubble").contains("내 답변")
                .contains("목록과 분리된 새 작성 화면으로 만들어 주세요.");
    }

    @Test
    void 인터뷰_답변은_페이지_이동_없이_비동기로_반영할_수_있다() throws Exception {
        Project p = readyProject("전자여전");
        String frdId = seedFrd(p, "임시저장 화면 추가");
        interviewService.saveQuestion(frdId, new FrdInterviewReader.Question(
                "관련 화면을 확인했습니다.", "반영 방식을 확인할게요.", "반영 방식",
                "기존 화면에 추가할까요?", "화면 범위를 확정해야 합니다.",
                java.util.List.of("기존 화면에 추가", "신규 화면 추가")));
        FrdInterviewMessage question = interviewService.messages(frdId).stream()
                .filter(message -> message.kind() == FrdInterviewMessage.Kind.QUESTION)
                .findFirst().orElseThrow();

        String result = mvc.perform(post("/projects/{p}/artifacts/frds/{f}/interview/answers/async",
                        p.getId(), frdId).with(user(planner)).with(csrf())
                        .param("questionId", question.id())
                        .param("answerType", "OPTION")
                        .param("answer", "신규 화면 추가"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(result).contains("\"accepted\":true");
        assertThat(interviewService.transcript(frdId)).contains("신규 화면 추가");

        String fragment = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview/fragment",
                        p.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(fragment).contains("class=\"fi-page\"")
                .doesNotContain("<!doctype html>");
    }

    @Test
    void 사용자는_질문에_더_답하지_않고_현재_내용으로_범위를_정리할_수_있다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "임시저장 화면 추가");
        interviewService.saveQuestion(frdId, new FrdInterviewReader.Question(
                "기존 작성 화면을 확인했습니다.", "반영 방식을 확인할게요.", "반영 방식",
                "기존 화면에 추가할까요?", "화면 범위를 확정해야 합니다.",
                java.util.List.of("기존 화면에 추가", "새 화면 추가")));
        FrdInterviewMessage question = interviewService.messages(frdId).stream()
                .filter(message -> message.kind() == FrdInterviewMessage.Kind.QUESTION)
                .findFirst().orElseThrow();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/interview/finish", p.getId(), frdId)
                        .with(user(planner)).with(csrf()).param("questionId", question.id()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));

        assertThat(interviewService.transcript(frdId)).contains("현재 내용으로 범위 정리해 주세요.");
    }

    @Test
    void 실행_내용은_시간순으로_아래에_붙고_채팅은_맨_아래를_따라간다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "임시저장 화면 추가");
        String progressKey = ScreenPickWorker.progressKey(frdId);
        progress.add(progressKey, new com.bizplay.builder.ai.ClaudeRunner.Progress(
                com.bizplay.builder.ai.ClaudeRunner.Progress.Kind.TOOL, "첫 번째 조사"));
        progress.add(progressKey, new com.bizplay.builder.ai.ClaudeRunner.Progress(
                com.bizplay.builder.ai.ClaudeRunner.Progress.Kind.TOOL, "두 번째 조사"));

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html.indexOf("첫 번째 조사")).isLessThan(html.indexOf("두 번째 조사"));
        assertThat(html)
                .contains("interviewLog.scrollTop = interviewLog.scrollHeight")
                .contains("/js/frd-interview.js")
                .contains("data-events-url")
                .contains("data-progress-url")
                .doesNotContain("frd-analysis-elapsed")
                .doesNotContain("fic-analysis-meter")
                .contains("fic-progress__bar").contains("인터뷰 진행률 25퍼센트")
                .doesNotContain("seconds + '초 경과'")
                .doesNotContain("setTimeout(poll")
                .doesNotContain("setTimeout(function () { location.reload(); }, 3000)");

        String live = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview/progress",
                        p.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(live).contains("\"state\":\"ANALYZING\"");
        assertThat(live.indexOf("첫 번째 조사")).isLessThan(live.indexOf("두 번째 조사"));
    }

    @Test
    void 최종_결과는_수정이_필요한_프론트와_백엔드만_채팅_패널에_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "결재 문서 임시저장");
        FrdInterviewReader.Turn read = interviewReader.read("""
                {"type":"RESULT","analysisSummary":"작성 화면과 저장 API를 함께 수정하는 작업으로 분석했습니다.",
                 "assistantMessage":"말씀해 주신 내용을 반영해 프론트와 백엔드 범위를 정리했어요.",
                 "title":"결재 문서 임시저장",
                 "items":[{"requirement":"작성 중인 문서를 임시 저장한다","nature":"DEVELOP",
                   "verdict":"SCREEN","screens":[{"screenId":"wv-appr-write","system":"webview",
                   "screenName":"결재 문서 작성","reason":"임시저장 버튼을 추가한다"}],"note":"작성 화면 변경"}],
                 "backendChanges":[{"requirementSeq":1,"category":"API","target":"임시저장 API",
                   "changeDetail":"작성 중인 문서를 저장하고 다시 조회한다","evidence":"도메인 문서에 API가 없다","required":true},
                   {"requirementSeq":1,"category":"NOTIFICATION","target":"알림","changeDetail":"알림 변경 없음",
                   "evidence":"임시저장은 알림을 보내지 않는다","required":false}],
                 "acceptanceCriteria":["임시 저장한 문서를 다시 열 수 있다"],
                 "openIssues":["임시 저장 보관 기간 확인"],"noScreenReason":null}
                """);
        interviewService.saveResult(frdId, (FrdInterviewReader.Result) read);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("수정이 필요한 범위만 정리했습니다.")
                .contains("frd-result fic-chat-layout")
                .contains("범위 정리 완료")
                .contains("인터뷰 진행률 75퍼센트")
                .contains("이번 인터뷰").contains("정리된 범위").contains("다음 단계")
                .contains("말씀해 주신 내용을 반영해 프론트와 백엔드 범위를 정리했어요.")
                .contains("수정할 프론트").contains("결재 문서 작성")
                .contains("수정할 내용")
                .contains("임시저장 버튼을 추가한다")
                .contains("수정할 백엔드").contains("임시저장 API")
                .contains("빠진 조건이나 수정할 범위를 입력해 주세요")
                .contains("분석 결과 승인").contains("내용 보완하기")
                .contains("id=\"frd-result-actions\"")
                .contains("resultMessage.focus()")
                .contains("resultActions.hidden = true")
                .doesNotContain("<footer class=\"fi-approval\">")
                .doesNotContain("/interview/continue")
                .doesNotContain("추가 질문에 답변드립니다.")
                .doesNotContain("fi-general-response")
                .doesNotContain("알림 변경 없음")
                .doesNotContain("화면 직접 고르기");
    }

    @Test
    void 내용_보완_메시지는_분석_중에도_내_답변으로_남는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "폐업 가맹점 전용 상세 화면을 추가한다");
        FrdInterviewReader.Result result = (FrdInterviewReader.Result) interviewReader.read("""
                {"type":"RESULT","analysisSummary":"백엔드 범위를 정리했습니다.",
                 "assistantMessage":"범위를 정리했습니다.","title":"폐업 가맹점 관리",
                 "items":[{"requirement":"조회 API를 만든다","nature":"DEVELOP","verdict":"NO_SCREEN",
                 "screens":[],"note":"API 작업"}],"backendChanges":[],"acceptanceCriteria":[],
                 "openIssues":[],"workMode":"FRD","workModeReason":"확인이 필요합니다.","noScreenReason":null}
                """);
        interviewService.saveResult(frdId, result);
        interviewService.continueWithMessage(frdId, "신규 상세 화면도 프론트 범위에 포함해 주세요.");

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("내 답변")
                .contains("신규 상세 화면도 프론트 범위에 포함해 주세요.");
    }

    @Test
    void 수정할_대상이_없으면_안내와_승인_버튼만_있는_작은_패널을_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "기존 공지사항 게시");
        FrdInterviewReader.Turn read = interviewReader.read("""
                {"type":"RESULT","analysisSummary":"기존 운영 기능으로 게시할 수 있습니다.",
                 "assistantMessage":"확인해 보니 기존 기능으로 처리할 수 있어 별도 개발은 필요하지 않아요.",
                 "title":"기존 공지사항 게시",
                 "items":[{"requirement":"공지사항을 게시한다","nature":"OPERATE",
                   "verdict":"NO_SCREEN","screens":[],"note":"기존 등록 기능을 사용한다"}],
                 "backendChanges":[{"requirementSeq":1,"category":"OTHER","target":"공지사항 데이터",
                   "changeDetail":"기존 등록 기능을 사용한다","evidence":"등록 화면이 있다","required":false}],
                 "acceptanceCriteria":[],"openIssues":[],"noScreenReason":"개발 변경 없음"}
                """);
        interviewService.saveResult(frdId, (FrdInterviewReader.Result) read);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("확인해 보니 기존 기능으로 처리할 수 있어 별도 개발은 필요하지 않아요.")
                .contains("요구사항에 해당하는 수정 작업이 확인되지 않았습니다.")
                .contains("결과 확인 화면에서 대상 화면을 추가하여 작업 범위를 보완할 수 있습니다.")
                .contains("분석 결과 승인").contains("내용 보완하기")
                .doesNotContain("수정이 필요한 범위만 정리했습니다.");
    }

    @Test
    void 분석_결과에서_기획자_메시지를_남기면_대화에_저장하고_재분석_상태로_바뀐다() {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        interviewService.continueWithMessage(frdId, "지급 시스템의 알림톡 중단도 백엔드 범위인가요?");

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYZING);
        assertThat(interviewService.currentQuestionRound(frdId)).isZero();
        assertThat(interviewService.messages(frdId)).last()
                .extracting(FrdInterviewMessage::role, FrdInterviewMessage::kind)
                .containsExactly(FrdInterviewMessage.Role.USER, FrdInterviewMessage.Kind.MESSAGE);
        assertThat(interviewService.transcript(frdId))
                .contains("사용자 · MESSAGE: 지급 시스템의 알림톡 중단도 백엔드 범위인가요?");
    }

    @Test
    void 걸음_둘이_짚은_화면과_까닭과_승인_출구를_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);   // PICKED + 화면 둘

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("wv-appr-write").contains("상단에 임시저장 버튼이 없습니다")
                .contains("수정이 필요한 범위만 정리했습니다.")
                .contains("분석 결과 승인").contains("내용 보완하기")
                .contains("<div class=\"fi-step is-current\"><i>2</i><span>요구사항 인터뷰</span></div>")
                .doesNotContain("fi-general-response")
                .doesNotContain("화면 직접 고르기").doesNotContain("화면 없는 요건입니다");
    }

    @Test
    void 체크를_끈_화면은_확정에서_빠지고_상태가_수정_중이_된다() throws Exception {
        Project p = readyProject("탐나는전");
        seedSolutionScreens(p.getId(), "wv-appr-write");
        String frdId = seedPickedFrd(p);
        String keep = screens.selectByFrdId(frdId).stream()
                .filter(screen -> "wv-appr-write".equals(screen.screenId()))
                .findFirst().orElseThrow().id();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()).param("keep", keep))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));

        assertThat(screens.selectByFrdId(frdId)).singleElement()
                .satisfies(screen -> assertThat(screen.id()).isEqualTo(keep));
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.SCOPE_REVIEW);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("<div class=\"fi-step is-current\"><i>3</i><span>개발 범위 확인</span></div>")
                .contains("fi-page fi-page--result")
                .contains("개발 범위 확인").contains("FRD 작업하기")
                .contains("다시 인터뷰하기")
                .contains("class=\"pop rq-head__overflow fi-scope-head__overflow\"")
                .containsSubsequence("목록으로", "다시 인터뷰하기", "FRD 작업하기", "aria-label=\"기타 작업\"")
                .contains("/interview/reopen")
                .doesNotContain("id=\"open-analysis-screen\"")
                .doesNotContain("id=\"analysis-screen-dialog\"")
                .doesNotContain("개발요청서로 이동")
                .contains("fi-result-board").contains("fi-result-scope").contains("fi-work-stack")
                .contains("fi-scope-context").contains("인터뷰 정리")
                .contains("요구사항 요약").contains("입력한 요구사항 보기")
                .contains("확인된 요구사항").contains("완료 기준").contains("확인 필요")
                .contains("fi-scope-counts").contains("프론트").contains("백엔드")
                .contains("프론트 화면").contains("백엔드 작업").contains("fi-screen-system")
                .contains("fi-screen-thumbnail").doesNotContain(">현재 화면</span>")
                .contains("/artifacts/solution-mockups/files/webview/pages/wv-appr-write.html")
                .doesNotContain("개발 구분").doesNotContain("다음 단계")
                .doesNotContain("fi-result-links").doesNotContain("fi-result-issues")
                .doesNotContain("fi-result-summary").doesNotContain("fi-result-criteria")
                .doesNotContain("status-badge status-badge--review\">수정 중")
                .contains("badge badge--outline\">기존 화면")
                .doesNotContain("분석 결과 승인");

        mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/start", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId));
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.DRAFTING);
        verify(workspaces).ensure(p.getId(), frdId);
        verify(mockupBatchWorker, never()).generate(frdId);
    }

    @Test
    void 워크트리_생성에_실패하면_개발_범위_확인에_남는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedScopeReviewFrd(p);
        given(workspaces.ensure(p.getId(), frdId))
                .willThrow(new IllegalStateException("기획 저장소가 준비되지 않았습니다."));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/start", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"))
                .andExpect(flash().attribute("error", "기획 저장소가 준비되지 않았습니다."));

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.SCOPE_REVIEW);
    }

    @Test
    void 개발_범위_화면_카드에는_전체_요구사항이_아니라_화면별_수정_내용을_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedScopeReviewFrd(p);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("fi-screen-row__requirements")
                .contains("수정할 내용")
                .contains("상단에 임시저장 버튼이 없습니다")
                .doesNotContain("연결된 요구사항");
    }

    @Test
    void 개발_범위_확인에서_다시_인터뷰하면_기존_결과를_유지한_채_인터뷰로_돌아간다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedScopeReviewFrd(p);
        int screenCount = screens.selectByFrdId(frdId).size();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/interview/reopen", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.PICKED);
        assertThat(screens.selectByFrdId(frdId)).hasSize(screenCount);
    }

    @Test
    void 화면_없는_요건으로_확정하면_화면_0장으로_열린다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))   // keep 을 하나도 안 보낸다
                .andExpect(status().is3xxRedirection());

        assertThat(screens.selectByFrdId(frdId)).isEmpty();
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.SCOPE_REVIEW);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("개발요청서 바로 만들기")
                .contains("FRD 작업으로 진행")
                .contains("프론트 화면 작업 없음")
                .doesNotContain(">FRD 작업하기</button>");

        var result = mvc.perform(post("/projects/{p}/artifacts/frds/{f}/fast-track", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        DevelopmentRequest request = developmentRequests.selectByFrdId(frdId);
        assertThat(request).isNotNull();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(
                "/projects/" + p.getId() + "/artifacts/dev-requests/" + request.id());
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.REVIEW);
        verify(workspaces, never()).ensure(p.getId(), frdId);
    }

    /**
     * ⭐ <b>화면이 있으면 빠른 진행이 닫힌다 (2026-09-02 병주 확정).</b>
     *
     * <p>종전에는 AI 가 간단 변경(FAST_TRACK)으로 권장한 기존 화면 한 장은 워크트리 없이
     * 개발요청서로 갔다. 그 몫은 이제 FRD 밖의 <b>SRT(빠른 개발요청)</b> 메뉴가 받는다 —
     * FRD 에서 화면 작업은 <b>언제나 FRD 작업대</b>에서 구체화하고, 빠른 진행은
     * <b>백엔드 변경만 있을 때</b> 하나다.
     */
    @Test
    void AI가_빠른_진행을_권장했어도_화면이_있으면_바로_만들기가_닫힌다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "결재 문서 작성 안내 문구 변경");
        FrdInterviewReader.Result result = (FrdInterviewReader.Result) interviewReader.read("""
                {"type":"RESULT","analysisSummary":"기존 작성 화면 한 장의 안내 문구만 변경합니다.",
                 "assistantMessage":"작업 범위가 명확해 바로 개발요청서로 진행할 수 있어요.",
                 "title":"결재 문서 작성 안내 문구 변경",
                 "items":[{"requirement":"작성 화면 안내 문구를 변경한다","nature":"DEVELOP",
                   "verdict":"SCREEN","screens":[{"screenId":"wv-appr-write","system":"webview",
                   "screenName":"결재 문서 작성","reason":"기존 안내 문구를 교체한다"}],"note":"문구 변경"}],
                 "backendChanges":[],"acceptanceCriteria":["변경된 안내 문구가 표시된다"],"openIssues":[],
                 "workMode":"FAST_TRACK","workModeReason":"기존 화면 한 장의 문구만 변경하고 미확정 사항이 없습니다.",
                 "noScreenReason":null}
                """);
        interviewService.saveResult(frdId, result);
        String keep = screens.selectByFrdId(frdId).get(0).id();

        // ⚠ 변경 보고 화면 계약은 그대로다 — 백엔드 변경이 없으면 한 칸짜리 표다.
        String interview = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/interview", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(interview).contains("fi-change-report__stats")
                .contains("프론트").contains("1개 화면")
                .contains("백엔드").contains("변경 없음")
                .contains("fi-change-columns--single")
                .doesNotContain("id=\"back-change-title\"");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()).param("keep", keep))
                .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("인터뷰 정리")
                .contains("기존 작성 화면 한 장의 안내 문구만 변경합니다.")
                .doesNotContain("개발요청서 바로 만들기")
                .doesNotContain("바로 진행할 수 있습니다")
                .contains(">FRD 작업하기</button>");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/fast-track", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));

        assertThat(developmentRequests.selectByFrdId(frdId)).isNull();
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.SCOPE_REVIEW);
        verify(workspaces, never()).ensure(p.getId(), frdId);
    }

    @Test
    void 확인할_내용이_남은_작업은_빠른_진행을_허용하지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "배치 실행 시간 변경");
        FrdInterviewReader.Result result = (FrdInterviewReader.Result) interviewReader.read("""
                {"type":"RESULT","analysisSummary":"백엔드 배치 설정 변경입니다.",
                 "title":"배치 실행 시간 변경",
                 "items":[{"requirement":"배치 실행 시간을 변경한다","nature":"DEVELOP",
                   "verdict":"NO_SCREEN","screens":[],"note":"배치 설정 변경"}],
                 "backendChanges":[{"category":"BATCH","target":"정산 배치","changeDetail":"실행 시간 변경",
                   "evidence":"요구사항","required":true}],
                 "acceptanceCriteria":[],"openIssues":["적용할 실행 시간을 확인해야 합니다."],
                 "workMode":"FAST_TRACK","workModeReason":"화면 작업이 없습니다.",
                 "noScreenReason":"백엔드 배치 설정만 변경합니다."}
                """);
        interviewService.saveResult(frdId, result);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("개발요청서 바로 만들기").contains("FRD 작업하기");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/fast-track", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));
        assertThat(developmentRequests.selectByFrdId(frdId)).isNull();
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.SCOPE_REVIEW);
    }

    @Test
    void 개발_범위_화면은_AI와_사용자_선택을_구분해_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        seedSolutionScreens(p.getId(), "wv-appr-write", "wv-appr-manual");
        String frdId = seedPickedFrd(p);
        String aiScreen = screens.selectByFrdId(frdId).stream()
                .filter(screen -> "wv-appr-write".equals(screen.screenId()))
                .findFirst().orElseThrow().id();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()).param("keep", aiScreen))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenId", "wv-appr-manual")
                        .param("screenName", "사용자 추가 화면")
                        .param("baseScreenId", "wv-appr-manual"))
                .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("AI 선택").contains("사용자 선택").contains("작업 대상에서 제외");
    }

    @Test
    void 개발_범위에서_프론트_화면_하나를_작업_대상에서_제외한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);
        List<String> keep = screens.selectByFrdId(frdId).stream().map(FrdScreen::id).toList();
        String deletedScreen = keep.get(0);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()).param("keep", keep.toArray(String[]::new)))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/delete",
                        p.getId(), frdId, deletedScreen).with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));

        assertThat(screens.selectByFrdId(frdId))
                .hasSize(keep.size() - 1)
                .noneMatch(screen -> screen.id().equals(deletedScreen));
    }

    @Test
    void 시스템이_하나인_새_화면은_시스템을_자동_지정하고_빌더가_이름을_짓는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);
        seedSolutionScreens(p.getId(), "wv-appr-write");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenName", "임시저장 문서 작성")
                        .param("screenType", "목록"))   // 화면ID 도 기준 화면도 안 보낸다
                .andExpect(status().is3xxRedirection());

        assertThat(screens.selectByFrdId(frdId))
                .filteredOn(FrdScreen::isNewScreen)
                .singleElement()
                .satisfies(screen -> {
                    assertThat(screen.screenId()).isEqualTo(TemporaryScreenId.of(screen.id()));
                    assertThat(screen.screenName()).isEqualTo("임시저장 문서 작성");
                    assertThat(screen.screenType()).isEqualTo("목록");
                    assertThat(screen.systemCode()).isEqualTo("webview");
                    // ⭐ 기준 화면은 비어 있다 — 목업을 만들 때 AI 가 같은 유형에서 고른다.
                    assertThat(screen.baseScreenId()).isNull();
                });
    }

    @Test
    void 시스템이_여러_개인_새_화면은_시스템_선택이_필수다() throws Exception {
        Project p = readyProject("탐나는전");
        projectSystems.insert(ProjectSystem.create(p.getId(), "webview", "웹뷰"));
        projectSystems.insert(ProjectSystem.create(p.getId(), "backoffice", "백오피스"));
        String frdId = seedPickedFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenName", "임시저장 문서 작성")
                        .param("screenType", "목록"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "신규 화면의 시스템을 선택해 주세요."));

        assertThat(screens.selectByFrdId(frdId)).noneMatch(FrdScreen::isNewScreen);
    }

    /** 유형도 기준 화면도 없으면 무엇을 만들지 알 수 없다 — 거절하고 화면을 안 앉힌다. */
    @Test
    void 유형_없는_새_화면_요청은_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenName", "임시저장 문서 작성"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(screens.selectByFrdId(frdId)).noneMatch(FrdScreen::isNewScreen);
    }

    /** 규격에 없는 유형은 안 받는다 — 그 글자가 목업 지시문으로 그대로 흘러간다. */
    @Test
    void 규격에_없는_화면_유형은_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenName", "임시저장 문서 작성")
                        .param("screenType", "달력"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(screens.selectByFrdId(frdId)).noneMatch(FrdScreen::isNewScreen);
    }

    /**
     * ⛔ 화면ID 는 빌더가 짓는다 (2026-08-22 병주 확정) — 폼이 그 칸을 안 보내지만, 옛 칸이
     * 되살아나거나 밖에서 직접 POST 하는 길을 서버가 막는다.
     * 정본: {@code docs/superpowers/specs/2026-08-22-new-screen-id-design.md}.
     */
    @Test
    void 사람이_적은_새_화면ID_는_거절하고_화면을_안_앉힌다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);
        seedSolutionScreens(p.getId(), "wv-appr-write");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenId", "wv-appr-draft-write")
                        .param("screenName", "임시저장 문서 작성")
                        .param("baseScreenId", "wv-appr-write")
                        .param("screenType", "목록"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(screens.selectByFrdId(frdId))
                .noneMatch(screen -> "wv-appr-draft-write".equals(screen.screenId()));
    }

    /**
     * ⭐ [Task 6 리뷰] 「화면 직접 고르기」가 앉히는 화면은 새 화면이 아니다 —
     * {@code screenId == baseScreenId} 로 보내면 {@code isNewScreen()} 이 거짓이어야 한다.
     * 지금은 템플릿이 숨은 필드로 이것을 보장하는데, 템플릿이 바뀌어도 잡히게 서버 쪽에서도 잰다.
     */
    @Test
    void 화면_직접_고르기는_베이스가_같아_새_화면이_아니다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);
        seedSolutionScreens(p.getId(), "wv-appr-detail");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenId", "wv-appr-detail")
                        .param("screenName", "결재 문서 상세")
                        .param("baseScreenId", "wv-appr-detail"))
                .andExpect(status().is3xxRedirection());

        assertThat(screens.selectByFrdId(frdId))
                .filteredOn(screen -> "wv-appr-detail".equals(screen.screenId()))
                .singleElement()
                .satisfies(screen -> assertThat(screen.isNewScreen()).isFalse());
    }

    /**
     * ⭐ [리뷰 ③] 베이스 화면ID 가 없으면 「직접 고르기」와 갈릴 길이 없어져 새 화면이 조용히
     * 기존 화면 취급을 받는다 — 그래서 새 화면 요청은 베이스가 필수다.
     */
    @Test
    void 베이스_없는_새_화면_요청은_거절하고_화면을_안_앉힌다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenId", "wv-appr-draft-write")
                        .param("screenName", "임시저장 문서 작성"))   // baseScreenId 를 안 보낸다
                .andExpect(status().is3xxRedirection());

        assertThat(screens.selectByFrdId(frdId))
                .noneMatch(screen -> "wv-appr-draft-write".equals(screen.screenId()));
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 C3] 색인에 없는 베이스 화면ID 는 거절한다 — 그러지 않으면
     * {@code ScreenMockupWorker} 가 그 값으로 클론 경로를 지어 「../../..」 꼴로 클론 밖(남의
     * 프로젝트 포함)을 읽을 수 있다. ⚠ 이 프로젝트는 클론 색인을 아예 안 심었다 — 색인이 없으면
     * {@code SolutionScreenReader.read} 가 빈 목록을 주므로 어떤 베이스도 못 찾는다.
     */
    @Test
    void 색인에_없는_베이스_화면ID_는_거절하고_화면을_안_앉힌다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenId", "wv-appr-draft-write")
                        .param("screenName", "임시저장 문서 작성")
                        .param("baseScreenId", "../../../etc/passwd"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(screens.selectByFrdId(frdId))
                .noneMatch(screen -> "wv-appr-draft-write".equals(screen.screenId()));
    }

    /** 아직 ANALYZING 인데 확정을 누르면 500 이 아니라 플래시로 거절하고 상태를 그대로 둔다. */
    @Test
    void 분석_중인_FRD_에_확정을_누르면_거절하고_상태를_그대로_둔다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "전자결재 상신 임시저장 지원");   // seedFrd 는 기본이 ANALYZING 이다

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYZING);
    }

    /** ⛔ 남의 프로젝트 FRD 는 마법사 걸음 둘도 주소를 알아도 안 연다. */
    @Test
    void 남의_프로젝트_FRD_는_마법사_걸음_둘도_안_연다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("지역화폐");
        String frdId = seedPickedFrd(other);

        mvc.perform(get("/projects/{p}/artifacts/frds/{f}/pick", mine.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isNotFound());
    }

    /** ⛔ 확정 길도 남의 프로젝트 FRD 는 주소를 알아도 안 연다(리뷰 ③ — POST 길 고리를 닫는다). */
    @Test
    void 남의_프로젝트_FRD_는_확정_길도_안_연다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("지역화폐");
        String frdId = seedPickedFrd(other);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick", mine.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ── 「다시 분석하기」(리뷰 ①·②) ────────────────────────────────────────

    /** 성공 갈래 — ANALYSIS_FAILED 인 FRD 에서 다시 분석을 누르면 상태가 ANALYZING 이 된다. */
    @Test
    void 분석_오류에서_다시_분석하면_상태가_다시_화면_찾는_중이_된다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedFrd(p, "정산 대사 화면 개선");
        frds.updateAfterPick(frdId, "정산 대사 화면 개선", null, null, Frd.State.ANALYSIS_FAILED, "분석 실패");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick/retry", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.ANALYZING);
    }

    /**
     * 거절 갈래 — ANALYSIS_FAILED 가 아닌데(여기서는 PICKED) 다시 분석을 누르면 상태가
     * 안 바뀌고 500 이 아니라 플래시로 거절된다. ⚠ 일꾼을 직접 부르지 않는다 — MockMvc 로
     * 상태만 잰다({@code ScreenPickWorker} 를 {@code @Autowired} 로 받으면 {@code @Async} 가
     * 발동해 검사가 경합이 된다).
     */
    @Test
    void 확정된_FRD_에서_다시_분석을_누르면_거절하고_상태를_그대로_둔다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);   // PICKED

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/pick/retry", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.PICKED);
    }

    // ── 대기줄이 차면(리뷰 I1) ────────────────────────────────────────────

    /**
     * ⭐ [2026-08-18 최종 리뷰 I1] {@code dispatchPick} 이 {@code TaskRejectedException} 을 로그만
     * 남기고 삼키면 FRD 가 {@code ANALYZING} + 일꾼 없음으로 영원히 굳는다 — 「다시 분석하기」 문은
     * {@code ANALYSIS_FAILED} 에서만 열리기 때문이다. 서블릿 계층 없이 컨트롤러를 직접 불러
     * 거절 갈래만 짧게 잰다({@code picker.pick} 을 거절하는 가짜로 바꿔 끼운다).
     */
    @Test
    void 화면_짚기_대기줄이_차면_분석_오류로_닫혀_다시_분석_문이_산다() {
        Project p = readyProject("탐나는전");
        ScreenPickWorker refusing = new ScreenPickWorker(null, null, null, null, null, null, null, null, null) {
            @Override
            public void pick(String frdId) {
                throw new TaskRejectedException("시험 — 대기줄이 찼다");
            }
        };
        FrdWizardController controller = new FrdWizardController(frdService, screens, items, refusing, solutions, progress);
        var flash = new RedirectAttributesModelMap();

        controller.open(p.getId(), "상신 화면에서 임시 저장할 수 있어야 한다.", planner, flash);

        assertThat(frds.selectByProjectId(p.getId())).singleElement().satisfies(frd -> {
            assertThat(frd.state()).isEqualTo(Frd.State.ANALYSIS_FAILED);
            assertThat(frd.failure()).isNotBlank();
        });
    }

    // ── 작업대(Task 6) ────────────────────────────────────────────────────

    @Test
    void 작업대_상단은_등록_정보를_간결하게_보여주고_요구사항을_바로_연다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("<dl class=\"rq-meta\">")
                .contains("적용 대상").contains("<dt>담당자</dt>").contains("생성일")
                .contains("id=\"open-frd-requirement\"").contains("요구사항 전체 보기")
                .contains("openOverview('frd-overview-requirement-title', requirementButton)")
                .doesNotContain("<dt>생성 방식</dt>").doesNotContain("<dt>등록 방식</dt>")
                .doesNotContain("<dt>화면 진행</dt>").doesNotContain("<dt>만든 때</dt>");
    }

    @Test
    void 작업대가_화면_목록과_상태를_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));
        seedSolutionScreens(p.getId(), "wv-appr-write", "wv-appr-list");
        String frdId = seedDraftingFrd(p);
        items.insert(FrdItem.of(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1,
                "작성 중인 결재 문서를 임시 저장한다.", FrdItem.Nature.DEVELOP,
                FrdItem.Verdict.SCREEN, List.of("wv-appr-write"), "저장 버튼과 복원 흐름을 추가합니다."));
        frdFacets.insert(FrdFacet.create(frdId, p.getId(), "제주"));
        String generatedScreenId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(generatedScreenId, "<article>AI 초안</article>",
                "임시 저장 버튼을 추가했습니다.\n저장 완료 안내를 추가했습니다.", Instant.now());
        screenHistories.insert(generatedScreenId, "<article>AI 초안</article>",
                "임시 저장 버튼을 추가했습니다.\n저장 완료 안내를 추가했습니다.");
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-appr-manual", "사용자 추가 화면", "wv-appr-manual", null, null, "webview"));

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("wv-appr-write").contains("결재 문서 작성")
                .contains("wm-screen-origin").contains("기존 화면")
                .contains("wm-screen-name").contains("wm-screen-modified-dot")
                .contains("wm-screen-meta").contains("wm-screen-id")
                .doesNotContain("wm-ai-draft-mark")
                .contains("aria-label=\"AI 초안 생성 가능\"")
                .contains("data-ai-draft-eligible=\"true\"")
                .contains("data-ai-draft-eligible=\"false\"")
                .contains("data-new-screen=").contains("data-generate-url=")
                .doesNotContain("초안 확인 필요")
                .contains("id=\"ai-draft-form\"").doesNotContain("id=\"ai-draft-button\"")
                .contains("id=\"ai-draft-dialog\"").contains("data-auto-open=\"true\"")
                .contains("data-required-draft=\"false\"")
                .contains("화면 초안을 만드시겠습니까?")
                .contains("이번에는 건너뛰기").contains("wm-draft-dialog__body")
                .contains("id=\"confirm-ai-draft\"").contains("id=\"ai-draft-layer-log\"")
                .contains("화면 분석을 시작했습니다.").contains("화면 초안을 만들었습니다.")
                .doesNotContain("id=\"create-new-screen-draft\"")
                .contains("신규 화면입니다. 확인한 초안 준비 작업을 시작해 주세요.")
                .contains("screenAddQuery.get('addScreen')").contains("openScreenDialog")
                .contains("data-status-url=")
                .contains("id=\"ai-draft-progress\"").contains("AI 초안을 만들지 못했습니다")
                .contains("id=\"ai-draft-progress-actions\" hidden")
                .contains("closeDraftProgressButton.parentElement.hidden = finished < links.length")
                .contains("if (draftProgressActive || draftDialog.dataset.requiredDraft === 'true') event.preventDefault()")
                .contains("progressPanel.hidden = state !== 'FAILED'")
                .doesNotContain("id=\"ai-draft-changes\"").doesNotContain("AI 수정 내용")
                .contains("id=\"screen-history-list\"").contains("refreshHistory(link, status)")
                .contains("dialog dialog--layer wm-history-dialog")
                .contains("id=\"open-frd-ai-chat\"").contains("AI와 화면 대화")
                .contains("id=\"open-screen-document\"").contains("aria-label=\"기능정의서\"")
                .contains("dialog dialog--layer wm-screen-document-dialog")
                .contains("id=\"screen-document-dialog\"").contains("화면에 연결된 기능과 처리 기준")
                .contains("id=\"screen-document-reader\"").contains("id=\"screen-document-toc\"")
                .contains("id=\"screen-document-content\"").contains("renderScreenDocument")
                .contains("wm-screen-document__section").contains("wm-screen-document__facts")
                .contains("data-document-target")
                .contains("documentContent.scrollTop = 0")
                .doesNotContain("기능정의서 저장")
                .contains("data-document-url=")
                .contains("id=\"open-screen-memo\"").contains("aria-label=\"메모\"")
                .contains("dialog dialog--layer wm-screen-memo-dialog")
                .contains("id=\"screen-memo-dialog\"")
                .doesNotContain("화면에 의견과 참고사항을 남깁니다")
                .contains("class=\"dialog__close\"").contains("class=\"dialog__meta num\"")
                .contains("class=\"wm-screen-memo__context\"").contains("대상 화면")
                .contains("id=\"screen-memo-list\"").contains("id=\"screen-memo-count\"")
                .contains("아직 등록된 메모가 없습니다")
                .contains("id=\"screen-memo-content\"").contains("새 메모").contains("메모 등록")
                .contains("memoContent.disabled = false")
                .contains("memoItem").contains("memo.authorName").contains("memo.createdAt")
                .contains("memoCount.textContent = `${memos.length}건`")
                .contains("data-memo-url=")
                .contains("id=\"frd-ai-chat\"").contains("현재 화면을 질문하거나 수정·신규 화면을 요청해 주세요")
                .contains("class=\"wm-ai-chat__reference-button\"").contains("참고 이미지")
                .contains("id=\"frd-ai-chat-reference-input\"")
                .contains("id=\"frd-ai-chat-suggestion-toggle\"").contains("AI 제안")
                .contains("id=\"frd-ai-chat-suggestion-menu\"")
                .contains("디자인 규칙 검토", "사용성 개선 제안", "화면 연결 검토")
                .contains("data-screen-chat-suggestion")
                .contains("chatMessage.value = suggestion")
                .contains("window.addEventListener('drop'")
                .contains("chatPanel?.contains(event.target)")
                .contains("items.some(item => item.kind === 'file')")
                .contains("body.append('referenceImage', chatReferenceImage, chatReferenceImage.name)")
                .contains("data-chat-url=").contains("id=\"expand-frd-ai-chat\"")
                .contains("chatHandle?.addEventListener('pointerdown'")
                .contains("popup=yes,width=860,height=900")
                .contains("chatScreenName.textContent = link.dataset.screenName")
                .contains("if (!chatPanel?.hidden) loadChat(link)")
                .contains("chatActiveLink || chatViewingLink || currentLink()")
                .contains("finishedMessage?.state === 'DONE'")
                .doesNotContain("followActive")
                .contains("frd-screen-chat-ready").contains("chatPopupScreenId")
                .contains("showSelectionInChat")
                .contains("data-chat-status-url=").contains("data-chat-send-url=")
                .contains("data-chat-events-url=").contains("new EventSource")
                .doesNotContain("const chatStatusTimer")
                .contains("id=\"frd-ai-chat-send\"").contains("loadChat(link)")
                .contains("event.altKey").contains("chatForm?.requestSubmit()")
                .contains("변경사항 없음")
                .doesNotContain("id=\"preview-version-switch\"")
                .doesNotContain("data-preview-version=\"source\"")
                .contains("data-history-preview").contains("미리보기").contains("작업 시작 전")
                .contains("item.dataset.historyMarkerUrl").contains("markerReadOnly")
                .contains("item.dataset.historyCurrent === 'true'").contains("현재 화면으로 돌아가기")
                .contains("이 버전에 저장된 마커입니다.")
                .contains("window.setInterval").contains("refreshStatus(link)")
                .contains("적용 대상").contains("제주")
                .contains("<dt>담당자</dt>").contains("요구사항").contains("생성일")
                .contains("id=\"open-frd-requirement\"").contains("요구사항 전체 보기")
                .contains("openOverview('frd-overview-requirement-title', requirementButton)")
                .doesNotContain("<dt>생성 방식</dt>").doesNotContain("<dt>등록 방식</dt>")
                .doesNotContain("<dt>화면 진행</dt>").doesNotContain("<dt>만든 때</dt>")
                .contains("class=\"frd-workbench-page\"")
                .contains("wm-canvas-view-switch").containsSubsequence("상세 화면", "전체 맵")
                .doesNotContain("전체 화면 캔버스")
                .contains("id=\"add-work-screen\"").contains("FRD 화면 추가")
                .contains("id=\"analysis-screen-dialog\"")
                .contains("name=\"systemCode\"").contains("name=\"screenType\"")
                .doesNotContain("name=\"iaPlacementMode\"")
                .doesNotContain("name=\"iaAnchorScreenId\"")
                .doesNotContain("name=\"iaMenuPathKey\"")
                .doesNotContain("name=\"screenKind\"")
                .contains("class=\"wm-canvas\"").contains("id=\"preview-canvas-sheet\"")
                .contains("id=\"preview-frame\"").contains("scrolling=\"no\"")
                .contains("id=\"preview-loading\"").contains("화면을 불러오는 중입니다.")
                .contains("const loadCanvasPreview = url =>").contains("화면을 불러오는 데 시간이 걸리고 있습니다.")
                .contains("event.stopPropagation()")
                .contains("const resizePreviewFrame = () =>")
                .contains("const resetPreviewViewport = () =>")
                .contains("frame.style.removeProperty('height')")
                .contains("canvas.scrollTop = 0")
                .contains("id=\"toggle-frd-focus\"").contains("aria-label=\"작업 화면 확대\"")
                .contains("document.body.classList.toggle('is-frd-focus', active)")
                .contains("document.querySelector('[data-nav-toggle]')")
                .contains("document.body.classList.remove('is-nav-collapsed')")
                .contains("event.key === 'Escape'")
                .contains("id=\"select-edit-region\"").contains("aria-label=\"수정 영역 선택\"")
                .contains("aria-pressed=\"false\"")
                .contains("id=\"frd-ai-chat-selection\"").contains("수정 영역")
                .contains("id=\"clear-frd-ai-chat-selection\"")
                .contains("body.append('selectedRegion', JSON.stringify(selectedRegion))")
                .contains("builder-region-selected").contains("regionSelector")
                .contains("startRegionDrag").contains("selectDraggedRegion")
                .contains("selectionType: 'RECTANGLE'")
                .contains("id=\"add-execution-marker\"").contains("aria-label=\"실행 마커 추가\"")
                .contains("data-marker-url=").contains("id=\"screen-marker-dialog\"")
                .contains("id=\"screen-marker-description\"").contains("AI 수정에 사용")
                .contains("startMarkerMode").contains("renderMarkers").contains("loadMarkers")
                .contains("cache: 'no-store'")
                .contains("detachMarkerMode();").contains("currentMarkers = [];")
                .contains("selectionType: 'MARKER'")
                .contains("id=\"edit-screen-directly\"").contains("aria-label=\"직접 수정\"")
                .contains("data-direct-edit-url=").contains("id=\"screen-direct-edit-status\"")
                .contains("startDirectEditMode").contains("isDirectEditable").contains("saveInlineDirectEdit")
                .contains("element.setAttribute('contenteditable', 'plaintext-only')")
                .contains("handleInlineDirectEditKeyUp").contains("event.isComposing")
                .contains("body.append('expectedText', target.expectedText)")
                .doesNotContain("id=\"screen-direct-edit-dialog\"")
                .doesNotContain("FRD 화면 저장").doesNotContain("설명 마커 추가")
                .contains("rq-head__actions").contains("작업공간 초기화")
                .contains("id=\"open-frd-overview\"").contains("FRD 내용 보기")
                .contains("id=\"complete-frd-work\"").contains("FRD 작업 완료")
                .contains("id=\"frd-overview-dialog\"").contains("FRD 내용")
                .contains("wm-frd-overview__toc").contains("FRD 내용 목차")
                .contains("data-frd-overview-target=\"frd-overview-summary-title\"")
                .contains("id=\"frd-overview-content\"").contains("작업 개요")
                .contains("작성 중인 결재 문서를 임시 저장한다.")
                .contains("저장 버튼과 복원 흐름을 추가합니다.")
                .contains("화면별 수정 기록")
                // ⛔ 내용 보기는 개발요청서로 실려 나갈 것을 훑는 자리다 — AI 초안 어휘를 여기 쓰지 마라.
                //    사람이 더한 화면(wv-appr-manual)은 초안 대상이 아닌데도 「AI 초안 생성 가능」이
                //    붙었고, WAITING 과 GENERATED 가 한 말로 뭉쳐 다 된 화면이 구별되지 않았다.
                .contains("status-badge--complete").contains(">완료</span>")
                .contains("status-badge--waiting").contains(">미작업</span>")
                .contains("임시 저장 버튼을 추가했습니다.")
                .contains("저장 완료 안내를 추가했습니다.")
                .contains("id=\"close-frd-overview\"").contains("overviewDialog.showModal()")
                .contains("frdOverviewTarget").contains("overviewContent.scrollTop = 0")
                .doesNotContain("aria-label=\"작업 초기화\"")
                .contains("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/reset")
                .contains("작업 중인 모든 화면 변경이 삭제됩니다")
                .contains("data-source-preview-url=\"/projects/" + p.getId()
                        + "/artifacts/solution-mockups/files/webview/pages/wv-appr-write.html?skin=jeju\"")
                .contains("id=\"frd-workbench-script\"")
                .contains("const show = (link, refresh = true) =>")
                .contains("if (current) show(current);")
                .doesNotContain("show(link, false);")
                .doesNotContain("show(targets[0], false)")
                .doesNotContain("show(generating || current)")
                .doesNotContain("AI 로 고치기");
    }

    @Test
    void 작업대가_초안_완료_후_좌측_수정_마킹을_갱신한다() throws Exception {
        Project project = readyProject("수정 마킹 갱신");
        String frdId = seedDraftingFrd(project);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("updateModifiedMark(link, status.changes)")
                .contains("mark.setAttribute('aria-label', '수정 있음')")
                .contains("if (status.state === 'GENERATED') updateModifiedMark");
    }

    @Test
    void 상세_화면_캔버스에서도_선택한_화면의_변경_내용을_비교한다() throws Exception {
        Project project = readyProject("상세 화면 비교");
        String frdId = seedDraftingFrd(project);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("id=\"open-screen-compare\"").contains("aria-label=\"변경 내용 비교\"")
                .contains("dialog dialog--layer wm-compare-dialog")
                .contains("id=\"screen-compare-frame\"").contains("화면 변경 비교")
                .contains("data-compare-url=")
                .contains("url.searchParams.set('screenRowId', link.dataset.screenId)")
                .contains("url.searchParams.set('embedded', 'true')")
                .contains("frd-canvas-compare-close");
    }

    @Test
    void 화면_문서는_HTML과_짝지어진_기존_MD를_그대로_읽는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        Path document = paths.frdWorktree(p.getId(), frdId)
                .resolve("core/webview/pages").resolve(screen.screenId() + ".md");
        Files.createDirectories(document.getParent());
        Files.writeString(document, """
                --- 꼬리표 ---
                id: %s / system: webview / 기능: 결재 > 작성 / 과업: []

                --- 화면명세 ---
                화면명: %s
                목적: 결재 문서를 작성한다.
                """.formatted(screen.screenId(), screen.screenName()), StandardCharsets.UTF_8);

        String loaded = mvc.perform(get(
                        "/projects/{p}/artifacts/frds/{f}/screens/{s}/document",
                        p.getId(), frdId, screen.id()).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(loaded)
                .contains("\"screenName\":\"" + screen.screenName() + "\"")
                .contains("\"exists\":true")
                .contains("--- 화면명세 ---")
                .contains("목적: 결재 문서를 작성한다.");
        assertThat(Files.readString(document, StandardCharsets.UTF_8))
                .doesNotContain("builder-screen-document");
    }

    @Test
    void 화면_메모는_작성자와_작성_시각을_보존하며_순서대로_쌓인다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);

        String first = mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/memo",
                        p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("content", "지급액 = 결제금액 × 지급률\n상품권 결제 금액 제외"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        BuilderUser reviewer = superUser();
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/memo",
                        p.getId(), frdId, screen.id()).with(user(reviewer)).with(csrf())
                        .param("content", "지급률 변경 시 소수점 이하는 버림 처리"))
                .andExpect(status().isOk());

        String loaded = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/memo",
                        p.getId(), frdId, screen.id()).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(loaded)
                .contains("\"screenId\":\"" + screen.screenId() + "\"")
                .contains("\"comments\":[")
                .contains("\"authorName\":\"" + planner.name() + "\"")
                .contains("\"authorName\":\"" + reviewer.name() + "\"")
                .contains("\"createdAt\":")
                .contains("지급액 = 결제금액 × 지급률")
                .contains("상품권 결제 금액 제외");
        assertThat(loaded.indexOf("지급액 = 결제금액 × 지급률"))
                .isLessThan(loaded.indexOf("지급률 변경 시 소수점 이하는 버림 처리"));
        assertThat(first).contains("\"authorName\":\"" + planner.name() + "\"")
                .contains("\"createdAt\":");
    }

    @Test
    void 빈_화면_메모는_등록하지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/memo",
                        p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("content", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 실행_마커를_작성자와_요소_기준_위치로_저장하고_수정한_뒤_삭제한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        String url = "/projects/{p}/artifacts/frds/{f}/screens/{s}/markers";

        String created = mvc.perform(post(url, p.getId(), frdId, screen.id())
                        .with(user(planner)).with(csrf())
                        .param("selector", "#search-area")
                        .param("elementLabel", "검색 조건 영역")
                        .param("relativeX", "0.75").param("relativeY", "0.25")
                        .param("documentX", "0.5").param("documentY", "0.2")
                        .param("description", "조회 조건 초기화 버튼을 추가해 주세요."))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String markerId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        assertThat(created).contains("\"markerNo\":1")
                .contains("\"selector\":\"#search-area\"")
                .contains("\"elementLabel\":\"검색 조건 영역\"")
                .contains("\"authorName\":\"" + planner.name() + "\"")
                .contains("조회 조건 초기화 버튼을 추가해 주세요.");

        String updated = mvc.perform(post(url + "/{m}", p.getId(), frdId, screen.id(), markerId)
                        .with(user(planner)).with(csrf())
                        .param("description", "검색 조건을 모두 지우는 버튼을 추가해 주세요."))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(updated).contains("검색 조건을 모두 지우는 버튼을 추가해 주세요.");

        String loaded = mvc.perform(get(url, p.getId(), frdId, screen.id()).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(loaded).contains("\"id\":\"" + markerId + "\"")
                .contains("\"relativeX\":0.75").contains("\"documentY\":0.2");

        mvc.perform(post(url + "/{m}/delete", p.getId(), frdId, screen.id(), markerId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().isNoContent());
        String empty = mvc.perform(get(url, p.getId(), frdId, screen.id()).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(empty).isEqualTo("[]");
    }

    @Test
    void 최신_화면_버전은_마커_변경을_반영하고_이전_버전은_그대로_보존한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        String markerUrl = "/projects/{p}/artifacts/frds/{f}/screens/{s}/markers";

        screenMockups.markGenerated(screen.id(), new ScreenMockupReader.Mockup(
                "<html><body><section id=\"search-area\">검색</section></body></html>", List.of("첫 화면 수정")));
        long firstHistoryId = screenHistories.selectLatestByScreenId(screen.id()).id();

        String created = mvc.perform(post(markerUrl, p.getId(), frdId, screen.id())
                        .with(user(planner)).with(csrf())
                        .param("selector", "#search-area").param("elementLabel", "검색 조건 영역")
                        .param("relativeX", "0.75").param("relativeY", "0.25")
                        .param("documentX", "0.5").param("documentY", "0.2")
                        .param("description", "이 버전에서 검색 버튼을 확인해 주세요."))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String markerId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        String firstMarkersAfterAdd = historyMarkers(p.getId(), frdId, firstHistoryId);
        assertThat(firstMarkersAfterAdd).contains("\"id\":\"" + markerId + "\"")
                .contains("이 버전에서 검색 버튼을 확인해 주세요.");

        screenMockups.markGenerated(screen.id(), new ScreenMockupReader.Mockup(
                "<html><body><section id=\"search-area\">검색 결과</section></body></html>", List.of("두 번째 화면 수정")));
        long secondHistoryId = screenHistories.selectLatestByScreenId(screen.id()).id();

        String workbench = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(workbench).contains("data-history-current=\"true\"")
                .contains(">현재 화면으로 돌아가기</button>");

        mvc.perform(post(markerUrl + "/{m}", p.getId(), frdId, screen.id(), markerId)
                        .with(user(planner)).with(csrf())
                        .param("description", "현재 화면에서만 바뀐 설명입니다."))
                .andExpect(status().isOk());

        assertThat(historyMarkers(p.getId(), frdId, firstHistoryId))
                .contains("이 버전에서 검색 버튼을 확인해 주세요.")
                .doesNotContain("현재 화면에서만 바뀐 설명입니다.");
        assertThat(historyMarkers(p.getId(), frdId, secondHistoryId))
                .contains("현재 화면에서만 바뀐 설명입니다.");
    }

    private String historyMarkers(String projectId, String frdId, long historyId) throws Exception {
        return mvc.perform(get("/projects/{p}/artifacts/frds/{f}/history/{h}/markers",
                        projectId, frdId, historyId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 직접_수정은_선택한_문구만_바꾸고_마커와_함께_새_버전으로_남긴다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        Path page = paths.frdWorktree(p.getId(), frdId)
                .resolve("core/webview/pages").resolve(screen.screenId() + ".html");
        Files.createDirectories(page.getParent());
        String original = """
                <!doctype html><html lang="ko"><body><main>
                  <h1 id="title">결재 문서 작성</h1>
                  <button id="save" class="primary" data-action="save">저장</button>
                </main></body></html>
                """;
        Files.writeString(page, original, StandardCharsets.UTF_8);

        String marker = mvc.perform(post(
                        "/projects/{p}/artifacts/frds/{f}/screens/{s}/markers",
                        p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("selector", "#save").param("elementLabel", "저장 버튼")
                        .param("relativeX", "0.5").param("relativeY", "0.5")
                        .param("documentX", "0.5").param("documentY", "0.5")
                        .param("description", "저장 동작을 확인해 주세요."))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String markerId = com.jayway.jsonpath.JsonPath.read(marker, "$.id");

        String result = mvc.perform(post(
                        "/projects/{p}/artifacts/frds/{f}/screens/{s}/direct-text",
                        p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("selector", "#save").param("expectedText", "저장")
                        .param("newText", "임시 저장"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Number historyId = com.jayway.jsonpath.JsonPath.read(result, "$.historyId");

        assertThat(result).contains("문구 직접 수정").contains("임시 저장");
        assertThat(Files.readString(page, StandardCharsets.UTF_8))
                .isEqualTo(original.replace(">저장</button>", ">임시 저장</button>"));
        String preview = mvc.perform(get(
                        "/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screen.id()).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(preview).contains("임시 저장");
        assertThat(historyMarkers(p.getId(), frdId, historyId.longValue()))
                .contains("\"id\":\"" + markerId + "\"");
    }

    @Test
    void 직접_수정은_중첩_DOM을_보존하며_선택한_텍스트_조각만_바꾼다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        Path page = paths.frdWorktree(p.getId(), frdId)
                .resolve("core/webview/pages").resolve(screen.screenId() + ".html");
        Files.createDirectories(page.getParent());
        String original = "<html><body><p id=\"notice\"><strong>중요</strong> 안내</p>"
                + "<button id=\"save\">저장</button></body></html>";
        Files.writeString(page, original, StandardCharsets.UTF_8);
        String url = "/projects/{p}/artifacts/frds/{f}/screens/{s}/direct-text";

        mvc.perform(post(url, p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("selector", "#notice").param("expectedText", "중요 안내")
                        .param("newText", "새 안내"))
                .andExpect(status().isConflict());
        mvc.perform(post(url, p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("selector", "#save").param("expectedText", "예전 저장")
                        .param("newText", "임시 저장"))
                .andExpect(status().isConflict());
        mvc.perform(post(url, p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("selector", "#notice").param("expectedText", "안내")
                        .param("newText", "새 안내"))
                .andExpect(status().isOk());

        assertThat(Files.readString(page, StandardCharsets.UTF_8))
                .isEqualTo(original.replace("</strong> 안내", "</strong> 새 안내"));
        assertThat(screenHistories.selectLatestByScreenId(screen.id())).isNotNull();
    }

    @Test
    void 화면_밖의_실행_마커_위치는_저장하지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/markers",
                        p.getId(), frdId, screen.id()).with(user(planner)).with(csrf())
                        .param("selector", "#search-area").param("elementLabel", "검색 조건 영역")
                        .param("relativeX", "1.5").param("relativeY", "0.25")
                        .param("documentX", "0.5").param("documentY", "0.2")
                        .param("description", "설명"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 작업대에서_공통_화면_추가_팝업으로_화면을_더하면_작업대로_돌아온다() throws Exception {
        Project p = readyProject("탐나는전");
        seedSolutionScreens(p.getId(), "wv-appr-detail");
        String frdId = seedDraftingFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .param("screenId", "wv-appr-detail")
                        .param("screenName", "결재 문서 상세")
                        .param("baseScreenId", "wv-appr-detail"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId));

        assertThat(screens.selectByFrdId(frdId))
                .filteredOn(screen -> "wv-appr-detail".equals(screen.screenId()))
                .singleElement()
                .extracting(FrdScreen::systemCode).isEqualTo("webview");
    }

    /** ⛔ 아직 확정 전(PICKED)인 FRD 는 작업대가 아니라 마법사로 돌아간다 — 빈 작업대를 보여주지 않는다. */
    @Test
    void 확정_전_FRD_는_작업대_대신_마법사로_돌아간다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedPickedFrd(p);

        mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/pick"));
    }

    @Test
    void 남의_프로젝트_FRD_는_작업대도_안_연다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("지역화폐");
        String frdId = seedDraftingFrd(other);

        mvc.perform(get("/projects/{p}/artifacts/frds/{f}", mine.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 미리보기는_원본_화면의_자산_기준_경로를_붙인다() throws Exception {
        Project p = readyProject("탐나는전");
        seedSolutionScreens(p.getId(), "wv-appr-write");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId,
                "<html><head><link rel=\"stylesheet\" href=\"../assets/css/style.css\"></head>"
                        + "<body><article>완료된 목업</article></body></html>", null, Instant.now());

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("<base href=\"/projects/" + p.getId()
                        + "/artifacts/solution-mockups/files/webview/pages/\">")
                .contains("href=\"../assets/css/style.css\"")
                .contains("완료된 목업");
    }

    @Test
    void 미리보기는_DB_스냅샷보다_FRD_워크트리의_수정_파일을_우선한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen selected = screens.selectByFrdId(frdId).get(0);
        String screenRowId = selected.id();
        screens.updateGenerated(screenRowId,
                "<html><head></head><body>예전 DB 초안</body></html>", null, Instant.now());
        String systemCode = selected.systemCode() == null
                ? frds.selectById(frdId).systemCode() : selected.systemCode();
        Path worktreeFile = paths.frdWorktree(p.getId(), frdId)
                .resolve("core").resolve(systemCode).resolve("pages")
                .resolve(selected.screenId() + ".html");
        Files.createDirectories(worktreeFile.getParent());
        Files.writeString(worktreeFile,
                "<html><head></head><body>워크트리 직접 수정</body></html>", StandardCharsets.UTF_8);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("워크트리 직접 수정").doesNotContain("예전 DB 초안");
    }

    @Test
    void 적용_대상이_여럿인_미리보기는_요청값이_없어도_첫_대상_화면을_연다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));
        String frdId = seedDraftingFrd(p);
        frdFacets.insert(FrdFacet.create(frdId, p.getId(), "익산"));
        frdFacets.insert(FrdFacet.create(frdId, p.getId(), "제주"));
        FrdScreen selected = screens.selectByFrdId(frdId).get(0);
        String screenRowId = selected.id();
        screens.updateGenerated(screenRowId,
                "<html><head></head><body>예전 DB 초안</body></html>", null, Instant.now());
        Path systemRoot = paths.frdWorktree(p.getId(), frdId)
                .resolve("core").resolve("webview");
        Path iksan = systemRoot.resolve("variants-iksan").resolve(selected.screenId() + ".html");
        Path jeju = systemRoot.resolve("variants-jeju").resolve(selected.screenId() + ".html");
        Files.createDirectories(iksan.getParent());
        Files.createDirectories(jeju.getParent());
        Files.writeString(iksan,
                "<html><head></head><body>익산 워크트리 화면</body></html>", StandardCharsets.UTF_8);
        Files.writeString(jeju,
                "<html><head></head><body>제주 워크트리 화면</body></html>", StandardCharsets.UTF_8);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("익산 워크트리 화면").doesNotContain("제주 워크트리 화면");
    }

    @Test
    void 화면_상태는_새로고침_없이_수정_내용과_함께_확인한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId, "<html><head></head><body>초안</body></html>",
                "기간 입력 추가\n저장 버튼 문구 변경", Instant.now());
        screenHistories.insert(screenRowId, "<html><head></head><body>초안</body></html>",
                "기간 입력 추가\n저장 버튼 문구 변경");

        String json = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/status",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains("\"state\":\"GENERATED\"")
                .contains("\"stateLabel\":\"AI 초안 생성 가능\"")
                .contains("기간 입력 추가").contains("저장 버튼 문구 변경");

        Long historyId = screenHistories.selectLatestByScreenId(screenRowId).id();
        String historyJson = mvc.perform(get(
                        "/projects/{p}/artifacts/frds/{f}/screens/{s}/history/latest",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(historyJson).contains("\"historyId\":" + historyId);

        String historyHtml = mvc.perform(get(
                        "/projects/{p}/artifacts/frds/{f}/history/{h}/preview",
                        p.getId(), frdId, historyId).with(user(planner)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-same-origin"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(historyHtml).contains("<body>초안</body>");
    }

    @Test
    void 화면_상태는_AI가_지금_처리하는_내용을_함께_알린다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateState(screenRowId, FrdScreen.State.GENERATING);
        progress.add(ScreenMockupWorker.progressKey(screenRowId),
                new com.bizplay.builder.ai.ClaudeRunner.Progress(
                        com.bizplay.builder.ai.ClaudeRunner.Progress.Kind.TOOL,
                        "화면 관례 확인 · wv-appr-list.html"));

        String json = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/status",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains("\"state\":\"GENERATING\"")
                .contains("\"progress\":\"화면 관례 확인 · wv-appr-list.html\"");
        progress.clear(ScreenMockupWorker.progressKey(screenRowId));
    }

    @Test
    void AI_대화를_현재_화면의_별도_창으로_연다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/chat",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Claude Code")
                .contains("화면 대화")
                .contains("현재 화면을 질문하거나 수정·신규 화면을 요청해 주세요")
                .contains("id=\"frd-chat-window-form\"")
                .contains("id=\"frd-chat-window-send\"")
                .contains("class=\"wm-ai-chat__reference-button\"").contains("참고 이미지")
                .contains("id=\"frd-chat-window-reference-input\"")
                .contains("id=\"frd-chat-window-reference\"")
                .contains("id=\"frd-chat-window-suggestion-toggle\"").contains("AI 제안")
                .contains("id=\"frd-chat-window-suggestion-menu\"")
                .contains("디자인 규칙 검토", "사용성 개선 제안", "화면 연결 검토")
                .contains("data-screen-chat-suggestion")
                .contains("accept=\"image/png,image/jpeg,image/webp\"")
                .contains("data-events-url=")
                .contains("id=\"frd-chat-window-selection\"")
                .contains("/js/frd-chat-window.js")
                .contains("window.close()");
        String popupScript = Files.readString(
                Path.of("src/main/resources/static/js/frd-chat-window.js"), StandardCharsets.UTF_8);
        assertThat(popupScript).contains("event.altKey").contains("form.requestSubmit()")
                .contains("suggestionToggle").contains("suggestionMenu")
                .contains("message.value = suggestion")
                .contains("window.addEventListener('drop'")
                .contains("panel.contains(event.target)")
                .contains("items.some(item => item.kind === 'file')")
                .contains("body.append('referenceImage', referenceImage, referenceImage.name)")
                .contains("frd-screen-selection-changed")
                .contains("body.append('selectedRegion', JSON.stringify(selectedRegion))")
                .contains("event.key !== 'Escape'")
                .contains("/cancel");
    }

    @Test
    void 화면별_대화를_저장하고_실행_중인_화면과_진행을_조회한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);

        FrdScreenChatMessage running = screenChats.start(frdId, screen.id(),
                "조회 버튼 아래에 안내 문구를 추가해 줘");
        progress.add(FrdScreenChatWorker.progressKey(running.id()),
                new com.bizplay.builder.ai.ClaudeRunner.Progress(
                        com.bizplay.builder.ai.ClaudeRunner.Progress.Kind.TOOL, "화면 파일을 확인하고 있습니다."));

        String json = mvc.perform(get(
                        "/projects/{p}/artifacts/frds/{f}/screens/{s}/chat/messages",
                        p.getId(), frdId, screen.id()).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains("조회 버튼 아래에 안내 문구를 추가해 줘")
                .contains("\"role\":\"USER\"")
                .contains("\"state\":\"RUNNING\"")
                .contains("\"id\":\"" + running.id() + "\"")
                .contains("화면 파일을 확인하고 있습니다.");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        screenChats.start(frdId, screen.id(), "한 번 더 수정해 줘"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("화면 요청을 AI가 처리하고 있습니다");
    }

    @Test
    void 완료한_화면_대화의_Claude_세션을_다음_대화에서_찾는다() {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        String sessionId = "79a07238-1ceb-4b01-bfdd-241183d0686b";

        FrdScreenChatMessage running = screenChats.start(frdId, screen.id(), "현재 화면을 설명해 줘");
        screenChats.complete(running.id(), "현재 화면을 설명했습니다.", sessionId);

        assertThat(screenChatMessages.selectById(running.id()).sessionId()).isEqualTo(sessionId);
        assertThat(screenChatMessages.selectLatestSessionId(screen.id())).isEqualTo(sessionId);
    }

    @Test
    void 상세_화면_대화를_Esc_중단_API로_종료한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        FrdScreen screen = screens.selectByFrdId(frdId).get(0);
        FrdScreenChatMessage running = screenChats.start(frdId, screen.id(), "잘못 보낸 화면 수정 요청");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/chat/messages/{m}/cancel",
                        p.getId(), frdId, screen.id(), running.id()).with(user(planner)).with(csrf()))
                .andExpect(status().isAccepted());

        assertThat(screenChatMessages.selectById(running.id()).state())
                .isEqualTo(FrdScreenChatMessage.State.FAILED);
        assertThat(screenChatMessages.selectById(running.id()).failure()).contains("중단");
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 C1] {@code SolutionMockupScreenTest.미리보기는_같은_출처_iframe_에_뜬다}
     * 와 같은 회귀 시험이다 — 스프링 시큐리티 기본값 {@code X-Frame-Options: DENY} 가 FRD 미리보기
     * 주소에도 걸리면 작업대의 iframe 이 빈 칸이 된다(서버는 200 을 내는데 브라우저가 안 그린다).
     */
    @Test
    void 미리보기는_같은_출처_iframe_에_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId, "<article>완료된 목업</article>", null, Instant.now());

        var preview = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(preview.getHeader("X-Frame-Options"))
                .as("⛔ DENY 가 붙으면 작업대의 미리보기 칸이 빈다")
                .isEqualTo("SAMEORIGIN");
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 C2] 여기 내용물은 사람이 붙여넣은 요구사항을 재료로 Claude 가 새로
     * 지어낸 html 이다 — {@code SolutionPreviewController} 와 같은 두 헤더가 있어야 새 창으로
     * 열어도(iframe sandbox 가 그때 없다) 우리 출처에서 스크립트가 안 돈다.
     */
    @Test
    void 미리보기에_nosniff_와_CSP_sandbox_가_붙는다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId, "<article>완료된 목업</article>", null, Instant.now());

        var preview = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(preview.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(preview.getHeader("Content-Security-Policy")).isEqualTo("sandbox allow-same-origin");
    }

    /** ⛔ 아직 안 만든 화면(html 이 없다)의 미리보기는 지어내지 않고 404 다. */
    @Test
    void 아직_안_만든_화면의_미리보기는_404다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();

        mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isNotFound());
    }

    /** ⛔ 남의 프로젝트 화면은 주소를 알아도 미리보기가 안 연다. */
    @Test
    void 남의_프로젝트_화면의_미리보기는_안_연다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("지역화폐");
        String frdId = seedDraftingFrd(other);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId, "<article>남의 것</article>", null, Instant.now());

        mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        mine.getId(), frdId, screenRowId).with(user(planner)))
                .andExpect(status().isNotFound());
    }

    /**
     * 「목업 만들기」를 누르면 작업대로 돌아간다. ⚠ 일꾼을 직접 부르지 않는다 — MockMvc 로
     * 문(routing)·자격만 잰다. 실제 만들기 로직은 {@link ScreenMockupTest} 가 잰다
     * ({@link ScreenMockupWorker} 를 {@code @Autowired} 로 받으면 {@code @Async} 가 발동해 경합이 된다).
     */
    @Test
    void 목업_만들기를_누르면_작업대로_돌아간다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/generate",
                        p.getId(), frdId, screenRowId).with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId));
    }

    @Test
    void 사용자가_선택한_화면은_AI_초안_요청을_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(screenRowId, frdId,
                "wv-appr-manual", "사용자 추가 화면", "wv-appr-manual", null, null, "webview"));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/generate",
                        p.getId(), frdId, screenRowId).with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "사용자가 선택한 화면은 AI와 화면 대화에서 작업해 주세요."));

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.WAITING);
    }

    @Test
    void 신규_화면은_사람이_등록해도_AI가_첫_초안을_만든다() {
        FrdScreen screen = FrdScreen.drafted("frd-screen-1", "frd-1", "tmp-0000049",
                "폐업 가맹점 조회", "목록", null, "backoffice");

        assertThat(screen.isUserSelected()).isTrue();
        assertThat(screen.isNewScreen()).isTrue();
        assertThat(screen.isAiDraftEligible()).isTrue();
        assertThat(screen.canGenerateDraft()).isTrue();
    }

    @Test
    void 작업대는_신규_화면의_초안_확인_레이어를_자동으로_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String screenId = TemporaryScreenId.of(screenRowId);
        screens.insert(FrdScreen.drafted(screenRowId, frdId, screenId,
                "폐업 가맹점 조회", "목록", null, "backoffice"));

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("data-screen-code=\"" + screenId + "\"")
                .contains("data-new-screen=\"true\" data-ai-draft-eligible=\"true\"")
                .contains("data-generate-url=")
                .contains("id=\"ai-draft-dialog\"")
                .contains("data-auto-open=\"true\"")
                .contains("data-required-draft=\"true\"")
                .contains("신규 화면은 초안을 만든 뒤 상세 작업을 시작할 수 있습니다.")
                .doesNotContain("id=\"ai-draft-button\"")
                .doesNotContain("id=\"create-new-screen-draft\"");
        assertThat(Pattern.compile("<button(?=[^>]*id=\"cancel-ai-draft\")(?=[^>]*hidden)[^>]*>")
                .matcher(html).find()).isTrue();
    }

    /**
     * ⭐ [2026-08-18 최종 리뷰 I2] {@code GENERATING} 인 화면에 또 눌러도(이중 제출·묵은 탭·주소
     * 직접 호출) 제출되면 안 된다 — {@code retryPick}·{@code confirmPick} 과 같은 문지기를 세운다.
     */
    @Test
    void 만드는_중인_화면에_목업_만들기를_또_누르면_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateState(screenRowId, FrdScreen.State.GENERATING);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/generate",
                        p.getId(), frdId, screenRowId).with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(screens.selectById(screenRowId).state()).isEqualTo(FrdScreen.State.GENERATING);
    }

    @Test
    void 없는_화면에_목업_만들기를_누르면_404다() throws Exception {
        Project p = readyProject("탐나는전");
        String frdId = seedDraftingFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/generate",
                        p.getId(), frdId, "없는화면아이디").with(user(planner)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** ⛔ 남의 프로젝트 화면은 주소를 알아도 만들기 요청이 안 먹는다. */
    @Test
    void 남의_프로젝트_화면에_목업_만들기를_누르면_404다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("지역화폐");
        String frdId = seedDraftingFrd(other);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/generate",
                        mine.getId(), frdId, screenRowId).with(user(planner)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    @Test
    void 수정된_파일이_있고_AI_작업이_없으면_FRD_작업_완료가_활성화된다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        given(workspaces.hasChanges(p.getId(), frdId)).willReturn(true);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String json = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/completion-status",
                        p.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"complete-frd-work\"")
                .contains("data-submit-loading=\"FRD 작업을 완료하고 개발요청서를 만드는 중\"")
                .contains("data-work-modified=\"true\"")
                .contains("data-completion-status-url=")
                .contains("/projects/" + p.getId() + "/artifacts/frds/" + frdId + "/complete")
                .contains("수정한 내용을 커밋하고 FRD 작업을 완료할까요?");
        var completionButton = Pattern.compile("<button[^>]*id=\"complete-frd-work\"[^>]*>")
                .matcher(html);
        assertThat(completionButton.find()).isTrue();
        assertThat(completionButton.group()).doesNotContain("disabled");
        assertThat(json).contains("\"modified\":true")
                .contains("\"busy\":false")
                .contains("\"canComplete\":true");
    }

    @Test
    void 개발요청서에서_돌아온_완료_커밋이_남아도_FRD_작업_완료가_활성화된다() throws Exception {
        Project p = readyProject("전자결재-재작업");
        String frdId = seedDraftingFrd(p);
        given(workspaces.hasChanges(p.getId(), frdId)).willReturn(false);
        given(workspaces.hasCompletionToReopen(anyString(), anyString(), anyString())).willReturn(true);

        String json = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/completion-status",
                        p.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains("\"modified\":true")
                .contains("\"busy\":false")
                .contains("\"canComplete\":true")
                .contains("개발요청서에서 돌아온 FRD 작업을 다시 완료합니다.");
    }

    @Test
    void AI가_화면을_수정하는_동안에는_FRD_작업_완료가_비활성화된다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        given(workspaces.hasChanges(p.getId(), frdId)).willReturn(true);
        screens.updateState(screens.selectByFrdId(frdId).get(0).id(), FrdScreen.State.GENERATING);

        String json = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/completion-status",
                        p.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains("\"modified\":true")
                .contains("\"busy\":true")
                .contains("\"canComplete\":false");
    }

    @Test
    void FRD_작업을_완료하면_변경을_커밋하고_검토_필요로_전환한다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                p.getId(), "wv-appr-write", "PS-BO-BIZ-020-D01", ScreenStandardId.Origin.S, 1));
        items.insert(FrdItem.of(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1,
                "작성 중인 결재 문서를 임시 저장한다.", FrdItem.Nature.DEVELOP,
                FrdItem.Verdict.SCREEN, List.of("wv-appr-write"), "저장 버튼을 추가합니다."));
        analysisNotes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), frdId, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION,
                "임시 저장한 문서를 다시 열 수 있습니다.", Instant.now()));
        String frdScreenId = screens.selectByFrdId(frdId).get(0).id();
        screenHistories.insert(frdScreenId,
                "<!doctype html><html><body>수정 화면</body></html>", "임시 저장 버튼을 추가했습니다.");
        Path workspace = Path.of("test", "frd-" + frdId);
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willReturn(new FrdWorkspace.Commit(workspace, "before", "after"));

        var result = mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "FRD 작업을 완료하고 개발요청서를 만들었습니다."))
                .andReturn();

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.REVIEW);
        DevelopmentRequest request = developmentRequests.selectByFrdId(frdId);
        assertThat(request).isNotNull();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(
                "/projects/" + p.getId() + "/artifacts/dev-requests/" + request.id());
        verify(workspaces).commitChanges(p.getId(), frdId, "docs: FRD-001 작업 완료");

        String completedFrd = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(completedFrd)
                .contains("id=\"complete-frd-work\"")
                .contains("data-work-modified=\"true\"")
                .doesNotContain("FRD 작업 재개");

        String detail = mvc.perform(get("/projects/{p}/artifacts/dev-requests/{r}", p.getId(), request.id())
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).contains("임시 저장 버튼을 추가했습니다.")
                .contains("개발 범위")
                .contains("화면별 변경 내용")
                .contains("화면 ID").contains("PS-BO-BIZ-020-D01-S")
                .contains("dev-request-implementation dev-request-implementation--single")
                .doesNotContain("화면 외 구현</h2>")
                .contains("완료 조건")
                .contains("담당자")
                .contains("이영희")
                .contains("<button class=\"button button--primary\" type=\"submit\">개발요청 보내기</button>")
                .contains("FRD 작업 재개")
                .contains("dev-delivery-dialog")
                .contains("첨부파일")
                .doesNotContain("기준 FRD 보기")
                .doesNotContain("변경 예정 기능정의서 만들기")
                .doesNotContain("확인 필요 1건</span>");
        assertThat(detail.indexOf("dev-request-meta-grid"))
                .isLessThan(detail.indexOf("dev-request-source"));
    }

    @Test
    void 최신_CSS가_반영되면_개발요청서를_만들기_전에_화면_비교로_보낸다() throws Exception {
        Project p = readyProject("전자결재-최신화");
        String frdId = seedDraftingFrd(p);
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        given(workspaces.syncWithCloneDetails(p.getId(), frdId)).willReturn(
                new FrdWorkspace.SyncResult(FrdWorkspace.Sync.MERGED, "new-head",
                        List.of("design-guide/styles/webview.css")));
        given(workspaces.pendingLatestReview(p.getId(), frdId)).willReturn(
                new FrdWorkspace.PendingReview("new-head", screenRowId));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message",
                        "최신 기획 저장소 내용이 화면에 반영되었습니다. 변경된 화면을 확인해 주세요."))
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId
                        + "/canvas?comparisonScreenRowId=" + screenRowId + "&confirmedCloneHead=new-head"));

        verify(workspaces).requireLatestReview(p.getId(), frdId, "new-head", screenRowId);
        verify(workspaces, never()).commitChanges(anyString(), anyString(), anyString());
    }

    @Test
    void FRD_작업_완료가_실패하면_같은_화면에서_이유를_보여준다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willThrow(new IllegalStateException("완료 커밋을 만들지 못했습니다."));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId))
                .andExpect(flash().attribute("error", "완료 커밋을 만들지 못했습니다."));

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .flashAttr("error", "완료 커밋을 만들지 못했습니다.")
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("role=\"alert\"")
                .contains("완료 커밋을 만들지 못했습니다.");
    }

    @Test
    void 개발요청_전송은_레이어에서_전달사항과_첨부파일을_받아_전송중으로_옮긴다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        // ⭐ 완료 전에 채운다 — 개발요청서 본문은 완료 시점에 굳는 스냅샷이라
        //    나중에 넣은 것은 계약서에 안 실리고 게이트가 「개발 범위 0건」으로 막는다.
        passDeliveryGate(p, frdId);
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willReturn(new FrdWorkspace.Commit(Path.of("test", "frd-" + frdId), "before", "after"));
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                .with(user(planner)).with(csrf())).andExpect(status().is3xxRedirection());
        DevelopmentRequest request = developmentRequests.selectByFrdId(frdId);
        var attachment = new MockMultipartFile("attachment", "검토 자료.pdf", "application/pdf",
                "%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1));

        mvc.perform(multipart("/projects/{p}/artifacts/dev-requests/{r}/send", p.getId(), request.id())
                        .param("developmentCompletedOn", "2026-09-10")
                        .param("deploymentOn", "2026-09-09")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "배포일은 개발 완료일과 같거나 이후여야 합니다."));
        assertThat(developmentRequests.selectById(request.id()).deliveryState())
                .isEqualTo(DevelopmentRequest.DeliveryState.NOT_SENT);

        mvc.perform(multipart("/projects/{p}/artifacts/dev-requests/{r}/send", p.getId(), request.id())
                        .file(attachment).param("plannerComment", "월요일 배포 전에 확인해 주세요.")
                        .param("developmentCompletedOn", "2026-09-08")
                        .param("deploymentOn", "2026-09-10")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "개발요청을 접수했습니다."));

        DevelopmentRequest sending = developmentRequests.selectById(request.id());
        assertThat(sending.deliveryState()).isEqualTo(DevelopmentRequest.DeliveryState.SENDING);
        assertThat(sending.plannerComment()).isEqualTo("월요일 배포 전에 확인해 주세요.");
        assertThat(sending.attachmentName()).isEqualTo("검토_자료.pdf");
        assertThat(sending.attachmentSize()).isEqualTo(8L);
        assertThat(sending.developmentCompletedOn()).isEqualTo(java.time.LocalDate.of(2026, 9, 8));
        assertThat(sending.deploymentOn()).isEqualTo(java.time.LocalDate.of(2026, 9, 10));
        assertThat(Files.readString(Path.of(sending.attachmentPath()), StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-1.4");
    }

    // ── 개발요청서 목록 ────────────────────────────────────────────────────

    /**
     * ⭐ <b>담당은 기준 FRD 에서 온다</b> — 개발요청서에 담당 열이 따로 없다. 「대기」인 개발요청서를
     * 누가 밀어야 하는지가 목록에서 안 보이면, 서버 한 대에 여럿이 붙는 이 제품에서 아무도 안 민다.
     */
    @Test
    void 개발요청서_목록이_범위와_담당과_기준_FRD와_두_시각을_적는다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p, planner.accountId());
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willReturn(new FrdWorkspace.Commit(Path.of("test", "frd-" + frdId), "before", "after"));
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                .with(user(planner)).with(csrf())).andExpect(status().is3xxRedirection());
        DevelopmentRequest request = developmentRequests.selectByFrdId(frdId);
        developmentRequests.updatePrecheck(request.id(), """
                {"blocking":[{"subject":"전체","message":"확인 필요","fix":null,"detail":null}],
                 "warnings":[],"checking":false,"notes":[]}""");

        String html = devRequestList(p.getId());

        assertThat(html)
                .contains("href=\"/projects/" + p.getId() + "/artifacts/dev-requests/" + request.id() + "\"")
                .contains(">" + request.label() + "</a>")
                .contains("<th scope=\"col\">개발요청서</th>")
                .contains("<th scope=\"col\">개발 범위</th>")
                .contains("화면 2 · 화면 외 0")
                .contains("<th scope=\"col\">담당자</th>")
                .contains("이영희")
                .contains("<th scope=\"col\">기준 FRD</th>")
                .contains("FRD-001")
                .contains("<th scope=\"col\">생성일시</th>")
                .contains("<th scope=\"col\">요청일시</th>")
                .contains("전송 전 확인 1건")
                .contains("aria-label=\"개발요청서 페이지 이동\"")
                .contains("name=\"pageSize\"")
                .doesNotContain("<th scope=\"col\">번호</th>");
    }

    @Test
    void 개발요청서_목록의_요청일시는_실제_전송_시도에서_온다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p, planner.accountId());
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willReturn(new FrdWorkspace.Commit(Path.of("test", "frd-" + frdId), "before", "after"));
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                .with(user(planner)).with(csrf())).andExpect(status().is3xxRedirection());
        DevelopmentRequest request = developmentRequests.selectByFrdId(frdId);
        String attemptId = ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY);
        deliveryAttempts.insert(new DevRequestDeliveryAttempt(attemptId, request.id(), "DRK-목록시험",
                "a".repeat(64), DeliveryOutcome.SENDING, null, null, null,
                planner.accountId(), null, null));
        deliveryAttempts.finish(attemptId, DeliveryOutcome.SENT, 201,
                "https://gitlab.example.test/team/project/-/issues/18", null);
        deliveryAttempts.moveFromSending(request.id(), DeliveryOutcome.SENT);

        String html = devRequestList(p.getId());

        assertThat(html)
                .contains("<th scope=\"col\">요청일시</th>")
                .doesNotContain("GitLab #18", "개발 이슈 #18");
        assertThat(html).containsPattern("(?s)요청일시</th>.*?class=\"num nowrap\">\\d{2}-\\d{2} \\d{2}:\\d{2}</td>");
    }

    /**
     * ⛔ <b>적용 대상을 안 쓰는 프로젝트에 「적용 대상」 열을 그리지 않는다</b>
     * (FRD 목록이 2026-08-18 리뷰에서 같은 이유로 뺐다 — 언제나 비는 열은 화면이 거짓말하는 것이다).
     */
    @Test
    void 적용_대상이_없는_프로젝트의_개발요청서_목록에는_적용_대상_열이_없다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willReturn(new FrdWorkspace.Commit(Path.of("test", "frd-" + frdId), "before", "after"));
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                .with(user(planner)).with(csrf())).andExpect(status().is3xxRedirection());

        String html = devRequestList(p.getId());

        assertThat(html).contains("DR-001").doesNotContain("적용 대상");
    }

    /** 적용 대상을 쓰는 프로젝트에서는 그 열이 뜨고, 값 하나하나가 배지다. */
    @Test
    void 적용_대상이_있으면_개발요청서_목록이_적용_대상을_배지로_적는다() throws Exception {
        Project p = readyProject("전자결재");
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(com.bizplay.builder.intake.ProjectFacet.create(p.getId(), "jeju", "제주"));
        String frdId = seedDraftingFrd(p);
        frdFacets.insert(FrdFacet.create(frdId, p.getId(), "익산"));
        frdFacets.insert(FrdFacet.create(frdId, p.getId(), "제주"));
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willReturn(new FrdWorkspace.Commit(Path.of("test", "frd-" + frdId), "before", "after"));
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                .with(user(planner)).with(csrf())).andExpect(status().is3xxRedirection());

        String html = devRequestList(p.getId());

        assertThat(html).contains("<th scope=\"col\">적용 대상</th>")
                .contains("<span class=\"badge badge--outline\">익산</span>")
                .contains("<span class=\"badge badge--outline\">제주</span>");
    }

    @Test
    void FRD_작업_커밋에_실패하면_수정_중_상태를_유지한다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        given(workspaces.commitChanges(anyString(), anyString(), anyString()))
                .willThrow(new IllegalStateException("FRD 작업 커밋을 만들지 못했습니다."));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId))
                .andExpect(flash().attribute("error", "FRD 작업 커밋을 만들지 못했습니다."));

        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.DRAFTING);
    }

    @Test
    void 검토_상태의_FRD는_작업공간_초기화를_거절한다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);
        frds.transitionState(frdId, Frd.State.DRAFTING, Frd.State.REVIEW);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/reset", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId))
                .andExpect(flash().attribute("error", "수정 중인 FRD만 작업을 초기화할 수 있습니다."));

        verify(workspaces, never()).reset(anyString(), anyString());
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.REVIEW);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        var resetButton = Pattern.compile("<button[^>]*>작업공간 초기화</button>").matcher(html);
        assertThat(resetButton.find()).isTrue();
        assertThat(resetButton.group()).contains("disabled");
        assertThat(html)
                .contains("data-workbench-editable=\"false\"")
                .contains("완료된 FRD에서는 화면을 추가할 수 없습니다.")
                .contains("완료된 FRD에서는 AI 수정을 사용할 수 없습니다.")
                .contains("완료된 FRD에서는 메모를 사용할 수 없습니다.")
                .contains("완료된 FRD에서는 변경 히스토리를 사용할 수 없습니다.")
                .contains("id=\"save-screen-memo\" type=\"submit\" disabled")
                .contains("id=\"confirm-ai-draft\" type=\"button\" disabled")
                .contains("data-auto-open=\"false\"")
                .doesNotContain("검토 필요 · 읽기 전용");

        int screenCountBefore = screens.selectByFrdId(frdId).size();
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/memo",
                        p.getId(), frdId, screenRowId)
                        .param("content", "완료 뒤에는 등록되면 안 된다")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().isConflict());

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .param("screenId", "screen-after-complete")
                        .param("screenName", "완료 뒤 추가 화면")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "완료된 FRD에서는 화면을 추가할 수 없습니다."));

        assertThat(screens.selectByFrdId(frdId)).hasSize(screenCountBefore);
    }

    @Test
    void 내용_보완_입력은_엔터로_전송하고_알트_엔터로_줄을_바꾼다() throws Exception {
        String template = Files.readString(Path.of(
                "src/main/resources/templates/artifacts/frd-wizard.html"), StandardCharsets.UTF_8);
        String script = Files.readString(Path.of(
                "src/main/resources/static/js/frd-interview.js"), StandardCharsets.UTF_8);

        assertThat(template).contains("title=\"Enter 전송 · Alt+Enter 줄바꿈 · Esc 취소\"");
        assertThat(script)
                .contains("message?.addEventListener('keydown'")
                .contains("event.key === 'Escape' && !composer.hidden")
                .contains("message.value = ''")
                .contains("composer.hidden = true")
                .contains("if (actions) actions.hidden = false")
                .contains("event.isComposing || event.keyCode === 229")
                .contains("if (event.altKey)")
                .contains("message.setRangeText('\\n'")
                .contains("composer.requestSubmit()");
    }

    @Test
    void 담당자가_아닌_사용자가_FRD_작업에_들어오면_모든_버튼을_비활성화한다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p, planner.accountId());
        BuilderUser otherUser = superUser();

        String detail = mvc.perform(get("/projects/{p}/artifacts/frds/{f}", p.getId(), frdId)
                        .with(user(otherUser)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String canvas = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", p.getId(), frdId)
                        .with(user(otherUser)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(detail)
                .contains("class=\"frd-workbench-page frd-owner-scope\"")
                .contains("disabled=\"disabled\"")
                .contains("담당자만 FRD 작업을 수정할 수 있습니다.")
                .contains("data-workbench-editable=\"false\"");
        assertThat(canvas)
                .contains("class=\"frd-canvas-page frd-owner-scope\"")
                .contains("disabled=\"disabled\"")
                .contains("담당자만 FRD 작업을 수정할 수 있습니다.")
                .contains("data-canvas-editable=\"false\"");

        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens/{s}/memo",
                        p.getId(), frdId, screenRowId)
                        .param("content", "담당자가 아닌 사용자의 메모")
                        .with(user(otherUser)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/screens", p.getId(), frdId)
                        .param("screenId", "unauthorized-screen")
                        .param("screenName", "권한 없는 화면 추가")
                        .with(user(otherUser)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/complete", p.getId(), frdId)
                        .with(user(otherUser)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 작업공간을_초기화하면_초안_확인_레이어를_다시_연다() throws Exception {
        Project p = readyProject("전자결재");
        String frdId = seedDraftingFrd(p);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/reset", p.getId(), frdId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds/" + frdId))
                .andExpect(flash().attribute("message", "작업을 초기화했습니다."));

        verify(workspaces).reset(p.getId(), frdId);
    }

    private String devRequestList(String projectId) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/dev-requests")
                        .with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String list(String projectId) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/frds")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String filteredList(String projectId, String query, String state,
                                String owner, String system) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/frds")
                        .param("query", query)
                        .param("state", state)
                        .param("owner", owner)
                        .param("system", system)
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 클론에 색인만 심는다 — {@code FrdService.addScreen} 이 베이스 화면ID 를 대조할 재료다.
     * md·html 은 안 만든다 — {@code SolutionScreenReader.read} 가 화면ID 를 찾는 데는 색인이면
     * 충분하고, 화면명은 md 가 없으면 화면ID 로 대신한다.
     */
    private void seedSolutionScreens(String projectId, String... screenIds) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < screenIds.length; i++) {
            if (i > 0) {
                entries.append(",\n    ");
            }
            entries.append("\"%s\": {\"system\": \"webview\", \"ia\": {\"종류\": \"화면\"}}"
                    .formatted(screenIds[i]));
        }
        try {
            Path clone = paths.cloneDir(projectId);
            Files.createDirectories(clone);
            Files.writeString(clone.resolve("index.json"),
                    "{\n  \"schema\": \"we-adk-index/3\",\n  \"screens\": {\n    %s\n  }\n}\n"
                            .formatted(entries));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private String seedFrd(Project project, String title) {
        return seedFrd(project, title, null);
    }

    private String seedFrd(Project project, String title, String ownerAccountId) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                title, "상신 화면에서 작성 중인 문서를 임시 저장할 수 있어야 한다.", ownerAccountId));
        return id;
    }

    /**
     * PICKED + 화면 둘로 연 FRD. ⚠ Task 5 의 마법사 걸음 2·확정 시험이 쓴다.
     * 화면 하나는 {@code wv-appr-write} 로 고정한다 — 그 시험이 화면ID 와 짚은 까닭 문구를 직접 잰다.
     */
    private String seedPickedFrd(Project project) {
        return seedPickedFrd(project, null);
    }

    private String seedPickedFrd(Project project, String ownerAccountId) {
        String id = seedFrd(project, "전자결재 상신 임시저장 지원", ownerAccountId);
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), id,
                "wv-appr-write", "결재 문서 작성", "wv-appr-write", null,
                "상단에 임시저장 버튼이 없습니다", "webview"));
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), id,
                "wv-appr-list", "임시저장 문서 목록", "wv-appr-list", null,
                "목록에 상태 열이 없습니다", "webview"));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        return id;
    }

    /** PICKED + 화면 둘로 연 뒤 확정까지 지난 FRD — Task 6 의 작업대 시험이 쓴다. */
    private String seedDraftingFrd(Project project) {
        return seedDraftingFrd(project, null);
    }

    /** 담당이 있는 것으로 여는 갈래 — 개발요청서 목록의 담당 열이 이것을 잰다. */
    private String seedDraftingFrd(Project project, String ownerAccountId) {
        String id = seedPickedFrd(project, ownerAccountId);
        frds.updateState(id, Frd.State.DRAFTING);
        return id;
    }

    private String seedScopeReviewFrd(Project project) {
        String id = seedPickedFrd(project);
        frds.updateState(id, Frd.State.SCOPE_REVIEW);
        return id;
    }

    /**
     * 짚힌 화면 한 장을 손으로 앉힌다. ⚠ Task 6 의 목업 만들기 시험이 쓴다.
     *
     * <p>{@code screenName}·{@code pickReason} 은 널이다 — Task 6 이 재는 것은 목업 만들기가
     * {@code baseScreenId} 로 as-is html 을 읽어 오는지이지, 화면명·짚은 까닭의 표시가 아니다.
     */
    private String seedScreen(String frdId, String screenId, String baseScreenId) {
        String id = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.picked(id, frdId, screenId, null, baseScreenId, null, null));
        return id;
    }

    /**
     * 전송 게이트(계획 9 Task 7)가 요구하는 것을 채운다 — 개발 범위 한 줄 · 화면마다 목업과
     * 표준 화면ID.
     *
     * <p>⚠ <b>이 시험의 관심은 전송 레이어의 폼</b>이지 게이트가 아니다. 게이트 자체는
     * {@code DevRequestPrecheckTest} 가 잰다.
     *
     * <p>⛔ 게이트를 약하게 만들어 이 시험을 통과시키지 마라 — 임시 화면ID·미작업 화면이
     * 계약에 실려 나가는 것을 막는 자리다.
     */
    private void passDeliveryGate(Project project, String frdId) {
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1,
                "임시저장을 지원한다", FrdItem.Nature.DEVELOP, FrdItem.Verdict.SCREEN,
                null, null, null));
        int order = 0;
        for (FrdScreen screen : screens.selectByFrdId(frdId)) {
            screenMockups.markGenerated(screen.id(), new ScreenMockupReader.Mockup(
                    "<!doctype html><html lang=\"ko\"><body>바뀐 화면</body></html>",
                    List.of("임시저장 버튼을 추가한다")));
            standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                    project.getId(), screen.screenId(),
                    "PS-WV-" + screen.screenId().toUpperCase().replace('-', '_'),
                    ScreenStandardId.Origin.S, ++order));
        }
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/" + name + ".git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private void seedSolutionScreen(String projectId) {
        Path clone = paths.cloneDir(projectId);
        try {
            Files.createDirectories(clone.resolve("core/backoffice/pages"));
            Files.writeString(clone.resolve("index.json"), """
                    {"schema":"we-adk-index/3",
                     "screens":{"bo-appr-write":{"system":"backoffice","ia":{"종류":"화면"}}},
                     "facetIndex":{"jeju":["bo-appr-write"]}}
                    """, StandardCharsets.UTF_8);
            Files.writeString(clone.resolve("core/backoffice/pages/bo-appr-write.md"), """
                    --- 꼬리표 ---
                    id: bo-appr-write / system: backoffice / 기능: 결재 > 작성 / 과업: []

                    --- 화면명세 ---
                    화면명: 결재 문서 작성
                    목적: 결재 문서를 작성한다.
                    """, StandardCharsets.UTF_8);
            Files.writeString(clone.resolve("core/backoffice/pages/bo-appr-write.html"),
                    "<!doctype html><html lang=\"ko\"><body>결재 문서 작성</body></html>",
                    StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    /** ⚠ 비밀번호를 바꿔 둬야 한다 — 안 그러면 {@code FirstLoginFilter} 가 관문에서 /password 로 되튕긴다. */
    private BuilderUser planner() {
        var account = accounts.selectByLoginId("frdplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "frdplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
