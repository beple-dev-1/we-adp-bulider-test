package com.bizplay.builder.srt;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.devrequest.DevelopmentRequestService;
import com.bizplay.builder.frd.FrdService;
import com.bizplay.builder.frd.FrdFacetMapper;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** SRT 직접 입력이 실제 DB에서 개발요청서까지 이어지는지 확인한다. */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class SrtFlowTest extends AbstractDbTest {
    @Autowired MockMvc mvc;
    @Autowired SrtMapper srts;
    @Autowired FrdService frds;
    @Autowired FrdFacetMapper frdFacets;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired DevelopmentRequestService requests;
    @Autowired ProjectMapper projects;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretSealer sealer;
    @Autowired IdSequence ids;
    @MockitoBean SrtAiAnalyzer analyzer;
    @MockitoBean SolutionScreenReader solutionScreens;
    @Autowired FrdScreenMapper frdScreens;
    @MockitoBean(name = "aiExecutor") TaskExecutor aiExecutor;

    @BeforeEach
    void runAiTasksInsideTestTransaction() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(aiExecutor).execute(any(Runnable.class));
    }
    @Test
    void 직접_입력은_등록_뒤_대기하고_생성_요청은_즉시_상세로_돌아온다() throws Exception {
        Project project = readyProject("SRT 시험");
        BuilderUser planner = planner();
        org.mockito.BDDMockito.given(analyzer.analyze(org.mockito.ArgumentMatchers.any(Srt.class)))
                .willReturn(new SrtAiAnalysis(true, null, "버튼 명칭을 명확하게 바꾸는 요청입니다.",
                        java.util.List.of("확인 버튼의 명칭을 등록으로 변경한다."),
                        java.util.List.of("화면에 등록 버튼이 표시된다.")));
        var registration = mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts")
                        .param("source", "direct")
                        .param("title", "버튼명 변경")
                        .param("content", "확인 버튼을 등록 버튼으로 바꾼다.")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Srt srt = srts.selectByProjectId(project.getId()).get(0);
        assertThat(registration.getResponse().getRedirectedUrl()).isEqualTo(
                "/projects/" + project.getId() + "/artifacts/srts?selected=" + srt.id());
        waitForAnalysis(srt.id());
        assertThat(srt.devRequestId()).isNull();
        assertThat(frds.list(project.getId())).isEmpty();
        assertThat(requests.list(project.getId())).isEmpty();

        String html = mvc.perform(get("/projects/" + project.getId() + "/artifacts/srts")
                        .param("selected", srt.id()).with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("SRT-001", "버튼명 변경", "확인 버튼을 등록 버튼으로 바꾼다.",
                "AI 분석", "버튼 명칭을 명확하게 바꾸는 요청입니다.", "정리된 요구사항", "완료 조건");

        var result = mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/dev-request")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(
                "/projects/" + project.getId() + "/artifacts/srts?selected=" + srt.id());
        Srt completed = srts.selectById(srt.id());
        assertThat(completed.devRequestId()).isNotNull();
        assertThat(requests.read(project.getId(), completed.devRequestId()).content().interviewSummary())
                .isEqualTo(completed.analysisMessage());
    }

    @Test
    void 비동기_등록은_분석_상태와_상세_주소를_JSON으로_돌려준다() throws Exception {
        Project project = readyProject("SRT 등록 분석 시험");
        BuilderUser planner = planner();
        org.mockito.BDDMockito.given(analyzer.analyze(org.mockito.ArgumentMatchers.any(Srt.class)))
                .willReturn(new SrtAiAnalysis(true, null, "검색 조건의 기본값을 바꾸는 요청입니다.",
                        java.util.List.of("검색 조건을 변경한다."),
                        java.util.List.of("변경된 검색 조건이 표시된다.")));

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts")
                        .param("source", "direct")
                        .param("title", "검색 조건 변경")
                        .param("content", "검색 조건의 기본값을 변경한다.")
                        .accept(MediaType.APPLICATION_JSON)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ANALYZING"))
                .andExpect(jsonPath("$.detailUrl").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").isNotEmpty());
    }

    @Test
    void 등록에서_선택한_적용_구분은_내부_FRD에_보존한다() throws Exception {
        Project project = readyProject("SRT 적용 구분 시험");
        projectFacets.insert(ProjectFacet.create(project.getId(), "jeju", "제주"));
        projectFacets.insert(ProjectFacet.create(project.getId(), "iksan", "익산"));
        BuilderUser planner = planner();
        org.mockito.BDDMockito.given(analyzer.analyze(org.mockito.ArgumentMatchers.any(Srt.class)))
                .willReturn(new SrtAiAnalysis(true, null, "제주 적용 요청입니다.",
                        java.util.List.of("제주 환경에 변경을 적용한다."),
                        java.util.List.of("제주 환경에서 변경 내용이 표시된다.")));

        String registerHtml = mvc.perform(get("/projects/" + project.getId() + "/artifacts/srts")
                        .param("register", "direct").with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(registerHtml).contains("적용 구분", "value=\"제주\"", "value=\"익산\"");

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts")
                        .param("source", "direct").param("title", "제주 버튼 변경")
                        .param("content", "제주 화면의 버튼명을 변경한다.").param("facet", "제주")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Srt srt = srts.selectByProjectId(project.getId()).get(0);
        assertThat(frdFacets.selectByFrdId(srt.bridgeFrdId()))
                .extracting(com.bizplay.builder.frd.FrdFacet::name)
                .containsExactly("제주");

        String listHtml = mvc.perform(get("/projects/" + project.getId() + "/artifacts/srts")
                        .with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(listHtml).contains("적용 대상", "제주");
    }

    @Test
    void 비동기_생성_요청은_레이어가_사용할_진행_상태를_JSON으로_돌려준다() throws Exception {
        Project project = readyProject("SRT 비동기 생성 시험");
        BuilderUser planner = planner();
        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts")
                        .param("source", "direct")
                        .param("title", "검색 조건 변경")
                        .param("content", "검색 조건의 기본값을 변경한다.")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Srt srt = srts.selectByProjectId(project.getId()).get(0);

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/dev-request")
                        .accept(MediaType.APPLICATION_JSON)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ANALYZING"))
                .andExpect(jsonPath("$.requestUrl").doesNotExist());
    }

    @Test
    void 개발요청서_생성_전에는_SRT를_수정하고_삭제할_수_있다() throws Exception {
        Project project = readyProject("SRT 수정 삭제 시험");
        BuilderUser planner = planner();
        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts")
                        .param("source", "direct").param("title", "기존 제목").param("content", "기존 내용")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Srt srt = srts.selectByProjectId(project.getId()).get(0);

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/update")
                        .param("title", "수정 제목").param("content", "수정 내용")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(srts.selectById(srt.id()).title()).isEqualTo("수정 제목");
        assertThat(requests.list(project.getId())).isEmpty();

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/delete")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(srts.selectById(srt.id())).isNull();
        assertThat(frds.list(project.getId())).isEmpty();
    }

    private static SolutionScreen indexed(String screenId, String screenName) {
        return new SolutionScreen(screenId, screenName, "EXW", "화면", null, null, "회원 > 가입", null,
                null, null, java.util.List.of(), java.util.List.of(), null, java.util.List.of(), false, null);
    }

    private Srt registered(Project project, BuilderUser planner, SrtAiAnalysis analysis) throws Exception {
        org.mockito.BDDMockito.given(analyzer.analyze(any(Srt.class))).willReturn(analysis);
        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts")
                        .param("source", "direct").param("title", "이름입력시 한글만")
                        .param("content", "회원정보의 이름은 한글만 가능하도록 수정")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Srt srt = srts.selectByProjectId(project.getId()).get(0);
        waitForAnalysis(srt.id());
        return srts.selectById(srt.id());
    }

    private String detailHtml(Project project, BuilderUser planner, Srt srt) throws Exception {
        return mvc.perform(get("/projects/" + project.getId() + "/artifacts/srts")
                        .param("selected", srt.id()).with(user(planner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * ⭐ <b>AI 가 채우고 사람은 생성 전에 확인만 한다</b> (2026-09-24 사용자 확정 · 목업 13).
     * 고칠 화면의 시스템은 색인이 정하고, 사람이 바꾼 답은 권장안이 아니라 기획자 답으로 나간다.
     * 화면은 작업대 없이 as-is 만 싣는다 — to-be 목업·기능정의서를 막는 항목이 없다.
     */
    @Test
    void AI가_짚은_고칠_화면과_권장안을_확인하고_바꾼_답은_기획자_답으로_개발요청서에_싣는다() throws Exception {
        Project project = readyProject("SRT 화면 시험");
        BuilderUser planner = planner();
        org.mockito.BDDMockito.given(solutionScreens.read(project.getId())).willReturn(java.util.List.of(
                indexed("EXW-UWV-70-30-10-C", "에이블리 회원가입"),
                indexed("EXW-UWV-70-30-20-C", "에이블리 회원정보 수정")));
        Srt srt = registered(project, planner, new SrtAiAnalysis(true, null, "이름 입력 검증을 더하는 요청입니다.",
                java.util.List.of("이름 입력 칸에 한글만 받는다."), java.util.List.of("영문을 넣으면 안내가 뜬다."),
                true, java.util.List.of(new SrtAiAnalysis.Target("EXW-UWV-70-30-10-C", "이름 칸에 검증을 더한다"),
                        new SrtAiAnalysis.Target("NOT-IN-INDEX", "지어낸 화면")),
                java.util.List.of(new FrdAnalysisNote.Decision("띄어쓰기를 허용할지", "허용하지 않는다", true))));

        java.util.List<FrdScreen> picked = frdScreens.selectByFrdId(srt.bridgeFrdId());
        assertThat(picked).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("EXW-UWV-70-30-10-C");
            assertThat(screen.systemCode()).isEqualTo("EXW");
        });
        assertThat(detailHtml(project, planner, srt)).contains("고칠 화면", "에이블리 회원가입", "AI 선택",
                "다른 화면으로 바꾸기", "고칠 화면 고르기", "확인 필요", "띄어쓰기를 허용할지", "AI 권장안",
                "name=\"answer.1\"", "허용하지 않는다");

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/screen")
                        .param("screenId", "EXW-UWV-70-30-20-C").param("replace", picked.get(0).id())
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/dev-request")
                        .param("answer.1", "앞뒤 공백만 지우고 가운데 띄어쓰기는 허용한다")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Srt completed = srts.selectById(srt.id());
        assertThat(completed.devRequestId()).isNotNull();
        var view = requests.read(project.getId(), completed.devRequestId());
        assertThat(view.content().screens()).singleElement().satisfies(screen -> {
            assertThat(screen.screenId()).isEqualTo("EXW-UWV-70-30-20-C");
            assertThat(screen.systemCode()).isEqualTo("EXW");
        });
        assertThat(view.content().decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.answer()).isEqualTo("앞뒤 공백만 지우고 가운데 띄어쓰기는 허용한다");
            assertThat(decision.recommended()).isFalse();
        });
        assertThat(view.asIsOnly()).isTrue();
        assertThat(requests.precheck(project.getId(), completed.devRequestId()).blocking())
                .extracting(com.bizplay.builder.devrequest.DevRequestPrecheck.Item::message)
                .doesNotContain("수정한 화면이 아직 없습니다.", "변경 예정 기능정의서를 만들어야 합니다.");
    }

    /** ⭐ 화면을 고쳐야 하는데 AI 가 못 짚었으면 생성을 막고, 사람이 고르면 풀린다. */
    @Test
    void 화면을_고쳐야_하는데_못_짚으면_생성을_막고_고르면_풀린다() throws Exception {
        Project project = readyProject("SRT 화면 못 짚음");
        BuilderUser planner = planner();
        org.mockito.BDDMockito.given(solutionScreens.read(project.getId())).willReturn(java.util.List.of(
                indexed("EXW-UWV-70-30-10-C", "에이블리 회원가입")));
        Srt srt = registered(project, planner, new SrtAiAnalysis(true, null, "이름 입력 검증을 더하는 요청입니다.",
                java.util.List.of("이름 입력 칸에 한글만 받는다."), java.util.List.of("영문을 넣으면 안내가 뜬다."),
                true, java.util.List.of(), java.util.List.of()));

        assertThat(detailHtml(project, planner, srt)).contains("AI가 고칠 화면을 찾지 못했습니다", "고칠 화면을 먼저 골라 주세요.");
        var refused = mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/dev-request")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(srts.selectById(srt.id()).devRequestId()).isNull();
        assertThat(refused.getFlashMap().get("error")).isEqualTo("고칠 화면을 골라야 개발요청서를 생성할 수 있습니다.");

        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/screen")
                        .param("screenId", "EXW-UWV-70-30-10-C")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/projects/" + project.getId() + "/artifacts/srts/" + srt.id() + "/dev-request")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(srts.selectById(srt.id()).devRequestId()).isNotNull();
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-SRT시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/srt.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser planner() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    private void waitForAnalysis(String srtId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (srts.selectById(srtId).analysisState() == Srt.AnalysisState.COMPLETE) return;
            Thread.sleep(20);
        }
        throw new AssertionError("SRT AI 분석이 제한 시간 안에 끝나지 않았습니다.");
    }
}
