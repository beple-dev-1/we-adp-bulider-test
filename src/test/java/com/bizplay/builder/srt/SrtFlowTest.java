package com.bizplay.builder.srt;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.devrequest.DevelopmentRequestService;
import com.bizplay.builder.frd.FrdService;
import com.bizplay.builder.frd.FrdFacetMapper;
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
