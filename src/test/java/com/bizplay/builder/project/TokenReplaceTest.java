package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.git.RepoProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TokenReplaceTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectService projects;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @MockitoBean RepoProbe probe;

    @Test
    void 새_토큰이_맞으면_상세로_돌아간다() throws Exception {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        Project p = projects.register("토큰갈이", "https://host/x.git", "main", "옛토큰");

        mvc.perform(post("/admin/projects/{id}/token", p.getId())
                        .with(user(superUser())).with(csrf())
                        .param("token", "새토큰"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/projects/" + p.getId()));

        assertThat(projects.tokenOf(p.getId())).isEqualTo("새토큰");
    }

    @Test
    void 새_토큰도_틀리면_안_갈리고_그_프로젝트_상세에_이유가_뜬다() throws Exception {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        Project p = projects.register("토큰갈이2", "https://host/x.git", "main", "옛토큰");

        when(probe.probe(any(), any(), any()))
                .thenReturn(new RepoProbe.ProbeResult(false, "토큰이 맞지 않는다"));

        mvc.perform(post("/admin/projects/{id}/token", p.getId())
                        .with(user(superUser())).with(csrf())
                        .param("token", "또틀린토큰"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                // 목록이 아니라 이 프로젝트의 상세다 — 이름과 상세 전용 문구가 같이 뜬다
                .andExpect(content().string(containsString("토큰갈이2")))
                .andExpect(content().string(containsString("저장소 및 적용 구분")))
                // 실패 이유가 화면에 보인다
                .andExpect(content().string(containsString("토큰이 맞지 않는다")))
                // 토큰 폼이 hidden 으로 다시 접히지 않고 열려 있다 (다른 폼의 정적 hidden 은 그대로다)
                .andExpect(content().string(containsString(
                        "<form id=\"token-form\" method=\"post\" action=\"/admin/projects/" + p.getId() + "/token\"")))
                .andExpect(content().string(not(containsString(
                        "<form id=\"token-form\" method=\"post\" action=\"/admin/projects/" + p.getId()
                                + "/token\" class=\"stack stack--tight\" hidden=\"hidden\">"))));

        assertThat(projects.tokenOf(p.getId())).isEqualTo("옛토큰");
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
