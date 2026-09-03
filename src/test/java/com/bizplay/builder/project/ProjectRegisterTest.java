package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.git.RepoProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ProjectRegisterTest extends AbstractDbTest {

    @Autowired ProjectService projects;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 재는 것은 그대로 「DB 에 뭐가 남았나」다. */
    @Autowired ProjectMapper repository;
    @Autowired com.bizplay.builder.intake.ProjectFacetMapper facets;
    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @MockitoBean RepoProbe probe;
    /** ⚠ 목으로 안 갈면 등록 POST 가 진짜 {@code @Async} 클론(git)을 실제로 돌린다. */
    @MockitoBean CloneWorker cloneWorker;

    @Test
    void 넷이_다_맞으면_저장되고_받는_중이_된다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project project = projects.register("경비체계",
                "https://gitlab.co/we/expense.git", "main", "glpat-맞는토큰");

        assertThat(project.getState()).isEqualTo(ProjectState.RECEIVING);
        assertThat(repository.selectById(project.getId())).isPresent();
    }

    @Test
    void 확인에_실패하면_등록이_저장되지_않는다() {
        when(probe.probe(any(), any(), any()))
                .thenReturn(new RepoProbe.ProbeResult(false, "토큰이 맞지 않는다"));

        assertThatThrownBy(() -> projects.register("경비체계",
                "https://gitlab.co/we/expense.git", "main", "glpat-틀린토큰"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("토큰이 맞지 않는다");

        assertThat(repository.selectAll()).isEmpty();
    }

    @Test
    void 토큰은_봉인돼_들어간다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project p = projects.register("경비체계2",
                "https://gitlab.co/we/expense.git", "main", "glpat-비밀토큰");

        Project stored = repository.selectById(p.getId()).orElseThrow();
        assertThat(new String(stored.getSealedToken())).doesNotContain("glpat-비밀토큰");
        assertThat(projects.tokenOf(p.getId())).isEqualTo("glpat-비밀토큰");
    }

    /**
     * ⛔ 화면의 {@code required} 는 <b>빈 칸만 든 이름을 통과시킨다.</b> 그것이 저장되면
     * 나중에 산출물·작업대 화면이 열릴 때마다 500 이 난다 — 껍데기 계약이 프로젝트 안 화면에
     * 빈 이름을 금지하기 때문이다. 그래서 <b>저장되기 전에</b> 막는다.
     *
     * <p>(2026-08-09 코덱스 적대검증 2회차. 껍데기 쪽 문을 조이면서 이 자리를 안 맞췄었다.)
     */
    @Test
    void 빈_칸만_든_이름은_등록되지_않고_앞뒤_빈_칸은_다듬어진다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        assertThatThrownBy(() -> projects.register("   ",
                "https://gitlab.co/we/a.git", "main", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어 있다");
        assertThat(repository.selectAll()).isEmpty();

        Project trimmed = projects.register("  경비체계3  ",
                "https://gitlab.co/we/a.git", "main", "t");
        assertThat(trimmed.getName()).isEqualTo("경비체계3");

        // 다듬은 뒤에 겹치는지 본다 — 안 그러면 「경비체계3」과 「 경비체계3 」이 둘 다 앉는다
        assertThatThrownBy(() -> projects.register("경비체계3",
                "https://gitlab.co/we/b.git", "main", "t"))
                .hasMessageContaining("같은 이름");
    }

    @Test
    void 같은_이름은_두_번_등록되지_않는다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        projects.register("겹치는이름", "https://gitlab.co/we/a.git", "main", "t");

        assertThatThrownBy(() -> projects.register("겹치는이름",
                "https://gitlab.co/we/b.git", "main", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 이름");
    }

    @Test
    void 적용_구분을_쉼표로_넣으면_다듬어져_따로따로_저장된다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project p = projects.register("지역화폐", "https://gitlab.co/we/local.git", "main", "t",
                List.of(" 익산 ", "제주", "익산", "  "));

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactly("익산", "제주");   // 다듬고 · 중복을 없애고 · 빈 것을 버린다
    }

    @Test
    void 적용_구분이_없으면_한_행도_안_생긴다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project p = projects.register("축 없는 사업", "https://gitlab.co/we/none.git", "main", "t",
                List.of());

        assertThat(facets.selectByProjectId(p.getId())).isEmpty();
    }

    @Test
    void 확인에_실패하면_적용_구분도_안_남는다() {
        when(probe.probe(any(), any(), any()))
                .thenReturn(new RepoProbe.ProbeResult(false, "토큰이 맞지 않는다"));

        assertThatThrownBy(() -> projects.register("실패사업",
                "https://gitlab.co/we/x.git", "main", "t", List.of("익산")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(facets.selectAll()).isEmpty();
    }

    @Test
    void 적용_구분_코드와_표시_이름을_나눠_등록한다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project p = projects.registerConfigured("기관코드사업", "https://gitlab.co/we/facet.git", "main", "t", "PS",
                List.of(new ProjectService.FacetSetting("jeju", "제주"),
                        new ProjectService.FacetSetting("iksan", "익산")));

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::code,
                        com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("jeju", "제주"),
                        org.assertj.core.groups.Tuple.tuple("iksan", "익산"));
    }

    @Test
    void 등록_화면이_슈퍼계정에게_뜨고_적용_구분을_선택해서_열_수_있다() throws Exception {
        mvc.perform(get("/admin/projects/new").with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("새 프로젝트 등록")))
                .andExpect(content().string(containsString("role=\"switch\"")))
                .andExpect(content().string(containsString("id=\"project-facet-settings\" hidden")))
                .andExpect(content().string(containsString("facet-setting-table__head")))
                .andExpect(content().string(containsString("name=\"facetCodes\"")))
                .andExpect(content().string(containsString("name=\"facetNames\"")))
                .andExpect(content().string(containsString("name=\"platformCode\" placeholder=\"PS\"")));
    }

    /**
     * ⚠ 표준 화면ID 의 첫 마디는 등록 시점에 사람이 넣는 값이다(2026-08-20 설계 §2).
     * 여기서는 등록 화면이 넘긴 값이 <b>그대로</b> {@code adk_builder_project.platform_code} 에
     * 앉는지를 잰다 — {@code any()} 는 null 도 통과시키는 무른 단정이라 실제 값으로 잰다.
     */
    @Test
    void 등록_화면이_보낸_플랫폼_코드가_그대로_저장된다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project p = projects.registerConfigured("플랫폼코드사업", "https://gitlab.co/we/platform.git", "main", "t",
                "KT2", List.of());

        assertThat(repository.selectById(p.getId()).orElseThrow().getPlatformCode()).isEqualTo("KT2");
    }

    @Test
    void 플랫폼_코드가_형식에_안_맞으면_등록이_거절된다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        assertThatThrownBy(() -> projects.registerConfigured("잘못된플랫폼사업",
                "https://gitlab.co/we/bad.git", "main", "t", "ps", List.of()))   // 소문자 — 규칙 위반
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("플랫폼 코드");
        assertThat(repository.selectAll()).isEmpty();
    }

    /**
     * 컨트롤러가 폼의 {@code platformCode} 를 서비스로 <b>그대로</b> 전달하는지 재는 자리다 —
     * 이 층까지 목으로 갈면 「컨트롤러가 값을 흘리지 않는지」는 아무도 안 잰다.
     */
    @Test
    void 등록_폼을_통해_넘어온_플랫폼_코드도_그대로_저장된다() throws Exception {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        mvc.perform(post("/admin/projects")
                        .param("name", "폼플랫폼코드사업")
                        .param("repoUrl", "https://gitlab.co/we/form-platform.git")
                        .param("defaultBranch", "main")
                        .param("platformCode", "AB3")
                        .param("token", "t")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Project stored = repository.selectByName("폼플랫폼코드사업").orElseThrow();
        assertThat(stored.getPlatformCode()).isEqualTo("AB3");
    }

    @Test
    void 폼의_플랫폼_코드가_형식에_안_맞으면_등록화면으로_되돌아오고_저장되지_않는다() throws Exception {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        mvc.perform(post("/admin/projects")
                        .param("name", "폼잘못된플랫폼사업")
                        .param("repoUrl", "https://gitlab.co/we/form-bad.git")
                        .param("defaultBranch", "main")
                        .param("platformCode", "ps")   // 소문자 — 규칙 위반
                        .param("token", "t")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("새 프로젝트 등록")));

        assertThat(repository.selectByName("폼잘못된플랫폼사업")).isEmpty();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
