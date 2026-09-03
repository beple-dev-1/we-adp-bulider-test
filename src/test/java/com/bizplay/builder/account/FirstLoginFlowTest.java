package com.bizplay.builder.account;

import com.bizplay.builder.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class FirstLoginFlowTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired Environment environment;

    @Test
    void 로그인_안_하면_로그인_화면으로_간다() throws Exception {
        mvc.perform(get("/projects"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    /**
     * 계획서에 없던 자리다 — {@code loginPage("/login")} 은 스프링이 만들어 주던 기본 로그인 화면을 끈다.
     * 그래서 이 매핑이 없으면 로그인 화면 자체가 404 이고, 아무도 들어올 수 없다.
     */
    @Test
    void 로그인_화면이_실제로_뜬다() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void 로그인_화면은_목업의_안내와_입력_구조를_그대로_보여준다() throws Exception {
        String html = mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("class=\"login-screen\"")
                .contains("class=\"login-brand__version\">v"
                        + environment.getRequiredProperty("builder.application-version") + "</small>")
                .contains("Builder 로그인")
                .contains("기획 산출물을 작성하고 관리하는 업무 공간입니다.")
                .contains("for=\"login-id\"")
                .contains("id=\"login-id\"")
                .contains("for=\"login-password\"")
                .contains("id=\"login-password\"")
                .contains("처음 로그인하시나요?")
                .contains("Claude Code 계정은 이어서 연결하거나 나중에 연결할 수 있습니다.");
    }

    @Test
    void 아이디와_비번이_맞으면_로그인된다() throws Exception {
        mvc.perform(formLogin("/login").user("admin").password("firstpass"))
                .andExpect(authenticated());
    }

    @Test
    void 최초_로그인이면_어디를_가도_비밀번호_화면으로_간다() throws Exception {
        mvc.perform(get("/projects").with(user(loadAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password"));
    }

    @Test
    void 최초_비밀번호_화면은_설정_단계와_입력_안내를_보여준다() throws Exception {
        String html = mvc.perform(get("/password").with(user(loadAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("class=\"setup-screen\"")
                .contains("최초 로그인 · 1단계")
                .contains("새 비밀번호 설정")
                .contains("aria-label=\"최초 설정 진행 단계\"")
                .contains("8자 이상이며 임시 비밀번호와 다르게 입력하세요.")
                .contains("비밀번호 설정 후 계속")
                .contains("data-submit-loading=\"비밀번호 설정 중\"")
                .contains("보안을 위해 임시 비밀번호는 다시 사용할 수 없습니다.")
                .doesNotContain("비밀번호 변경 취소");
    }

    @Test
    void 일반_비밀번호_변경에서는_FRD_작업_목록으로_돌아갈_수_있다() throws Exception {
        Account admin = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(admin.getId(), encoder.encode("바꾼비번1234"));
        BuilderUser changedUser = BuilderUser.of(accounts.selectById(admin.getId()).orElseThrow(), false);

        String html = mvc.perform(get("/password").with(user(changedUser)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("비밀번호 바꾸기")
                .contains("비밀번호 변경 취소")
                .contains("data-submit-loading=\"비밀번호 변경 중\"")
                .contains("href=\"/projects\"")
                .doesNotContain("최초 로그인 · 1단계");
    }

    @Test
    void 슈퍼관리자도_최초_비밀번호를_바꾸면_클로드_연결로_간다() throws Exception {
        // ① 진짜로 로그인해 **세션을 얻는다.**
        //    ⚠ `.with(user(...))` 로 이 시험을 쓰지 마라 — 요청마다 신원을 새로 꽂아 넣기 때문에
        //    세션에 저장이 됐는지 안 됐는지가 **아예 안 드러난다.** 저장 코드를 지워도 통과한다.
        MockHttpSession session = (MockHttpSession) mvc.perform(
                        formLogin("/login").user("admin").password("firstpass"))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession(false);

        mvc.perform(post("/password").session(session).with(csrf())
                        .param("newPassword", "새비밀번호1234")
                        .param("confirm", "새비밀번호1234"))
                .andExpect(redirectedUrl("/projects"));

        Account reloaded = accounts.selectByLoginId("admin").orElseThrow();
        assertThat(reloaded.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("새비밀번호1234", reloaded.getPasswordHash())).isTrue();

        // ② 갱신한 신원을 세션에 저장하지 않으면 다음 요청이 옛 신원(바꿔야 함)을 보고
        //    `/password` 로 되튕긴다. 슈퍼관리자도 실제 Claude 자격이 없으면 둘째 관문으로 간다.
        mvc.perform(get("/projects").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/claude/connect"));
    }

    /**
     * `planner-account` 칸 3 은 <b>순서</b>를 요구한다 — 「비밀번호 바꾸기 → Claude 계정 연결」.
     * 그런데 계획서의 `열린_경로` 는 두 관문을 <b>한 집합으로</b> 열어서, 임시 비밀번호를 쥔 사람이
     * 비밀번호를 안 바꾸고도 자기 계정에 <b>오래 가는 Claude 자격을 심을 수 있었다.</b>
     * (2026-08-09 코덱스 적대검증이 지목 · 코드로 확인함)
     */
    @Test
    void 비밀번호를_안_바꿨으면_클로드_연결로_못_간다() throws Exception {
        mvc.perform(get("/claude/connect").with(user(loadAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password"));
    }

    @Test
    void 두_칸이_다르면_안_바뀐다() throws Exception {
        mvc.perform(post("/password").with(user(loadAdmin())).with(csrf())
                        .param("newPassword", "비밀번호하나1234")
                        .param("confirm", "비밀번호둘1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("confirmError", "입력한 비밀번호가 서로 다릅니다."));
    }

    @Test
    void 새_비밀번호가_짧으면_해당_입력칸에_오류를_보인다() throws Exception {
        mvc.perform(post("/password").with(user(loadAdmin())).with(csrf())
                        .param("newPassword", "짧음123")
                        .param("confirm", "짧음123"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("passwordError", "비밀번호는 8자 이상 입력해 주세요."));
    }

    @Test
    void 임시_비밀번호를_새_비밀번호로_다시_사용할_수_없다() throws Exception {
        Account admin = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updateToTemporaryPassword(admin.getId(), encoder.encode("firstpass"));

        mvc.perform(post("/password").with(user(loadAdmin())).with(csrf())
                        .param("newPassword", "firstpass")
                        .param("confirm", "firstpass"))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "passwordError", "임시 비밀번호와 다른 비밀번호를 입력해 주세요."));

        Account account = accounts.selectByLoginId("admin").orElseThrow();
        assertThat(account.isMustChangePassword()).isTrue();
        assertThat(encoder.matches("firstpass", account.getPasswordHash())).isTrue();
    }

    private BuilderUser loadAdmin() {
        return BuilderUser.of(accounts.selectByLoginId("admin").orElseThrow(), false);
    }
}
