package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 프로젝트 고르기 세 갈래와 로그인 뒤 <b>FRD 작업 목록만 기본으로 여는 것</b>.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-10-project-context-design.md}
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class ProjectPickTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 앉히는 것도 재는 것도 그대로다. */
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    @Test
    void 준비된_것이_하나면_고르기를_건너뛰고_그_프로젝트_FRD_작업으로_보낸다() throws Exception {
        Project only = seatProject("탐나는전", ProjectState.READY);

        mvc.perform(get("/projects").with(user(superUser())))
                .andExpect(redirectedUrl("/projects/" + only.getId() + "/artifacts/frds"));
    }

    @Test
    void 준비된_것이_둘이어도_ID가_가장_작은_프로젝트를_연다() throws Exception {
        Project first = seatProject("탐나는전", ProjectState.READY);
        Project second = seatProject("전자세금계산서", ProjectState.READY);

        mvc.perform(get("/projects").with(user(superUser())))
                .andExpect(redirectedUrl("/projects/" + first.getId() + "/artifacts/frds"));
    }

    @Test
    void 준비된_것이_없으면_기획자에게_프로젝트_없음_화면을_보여준다() throws Exception {
        seatProject("받는중", ProjectState.RECEIVING);

        String html = mvc.perform(get("/projects").with(user(plannerUser())))
                .andExpect(status().isOk())
                .andExpect(view().name("project-empty"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("설정된 프로젝트가 없습니다")
                .contains("관리자에게 프로젝트 설정을 요청해 주세요")
                .contains("로그인 화면으로 돌아가기", "action=\"/logout\"")
                // 기획자에겐 등록하는 자리가 없다 — 링크를 보여 주면 눌러도 403 이다.
                .doesNotContain("/admin/projects");
    }

    /** 관리자는 안내 카드를 거치지 않고 등록하는 자리로 바로 간다. */
    @Test
    void 준비된_것이_없으면_관리자는_프로젝트_관리로_간다() throws Exception {
        seatProject("받는중", ProjectState.RECEIVING);

        mvc.perform(get("/projects").with(user(superUser())))
                .andExpect(redirectedUrl("/admin/projects"));
    }

    @Test
    void 관리에서_Builder로_이동할_때_준비된_프로젝트가_없으면_이유를_알린다() throws Exception {
        seatProject("받는중", ProjectState.RECEIVING);

        mvc.perform(get("/projects").param("from", "admin").with(user(superUser())))
                .andExpect(redirectedUrl("/admin/projects?builderUnavailable"));

        mvc.perform(get("/admin/projects").param("builderUnavailable", "").with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("준비된 프로젝트가 없어 Builder로 이동할 수 없습니다")));
    }

    /**
     * ⚠ 하나뿐이어도 <b>「없다」를 달고 온 요청은 건너뛰지 않는다.</b>
     * 안 그러면 낡은 북마크로 들어온 사람이 아무 말도 못 듣고 다른 프로젝트에 앉는다.
     */
    @Test
    void 없다를_달고_오면_하나뿐이어도_목록과_까닭을_보여준다() throws Exception {
        seatProject("탐나는전", ProjectState.READY);

        String html = mvc.perform(get("/projects").param("gone", "").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("그 프로젝트가 없다");
    }

    /** ⚠ 「없다」를 달고 온 관리자는 까닭을 들어야 하므로 관리로 보내지 않는다. */
    @Test
    void 없다를_달고_온_관리자는_준비된_것이_없어도_까닭을_본다() throws Exception {
        String html = mvc.perform(get("/projects").param("gone", "").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("그 프로젝트가 없다");
    }

    @Test
    void 중간_주소로_들어와도_로그인_뒤_FRD_작업_목록으로_간다() throws Exception {
        Project p = seatProject("탐나는전", ProjectState.READY);
        String intended = "/projects/" + p.getId() + "/artifacts/mockups";

        // 임시 비밀번호 관문이 로그인 성공 주소보다 먼저 개입하지 않도록 이미 바꾼 사용자로 시험한다.
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));

        MockHttpSession session = new MockHttpSession();

        mvc.perform(get(intended).session(session))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "admin")
                        .param("password", "바꾼비번1234"))
                .andExpect(redirectedUrl("/projects"));

        mvc.perform(get("/projects").session(session))
                .andExpect(redirectedUrl("/projects/" + p.getId() + "/artifacts/frds"));
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    /**
     * ⚠ 2026-08-15 부터 <b>앉히기가 두 걸음</b>이다 — {@code insert} 는 늘 {@code RECEIVING} 으로 넣는다
     * ({@code Project.create} 가 처음 상태의 정본이다). 다른 상태로 만들려면 {@code updateState} 를
     * 따로 부른다. ⛔ 그 두 걸음을 「엔티티를 고치고 저장」으로 되돌리지 마라 — 상태 변경 메서드를
     * 없앤 까닭이 그것이다(MyBatis 엔 더티 체킹이 없어 조용히 잃는다).
     */
    private Project seatProject(String name, ProjectState state) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(com.bizplay.builder.id.IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git", "main",
                "PS", sealed.cipher(), sealed.nonce()));
        if (state != ProjectState.RECEIVING) {
            projects.updateState(id, state, null);
        }
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser plannerUser() {
        var u = superUser();
        return new BuilderUser(u.accountId(), u.loginId(), u.name(), u.email(), u.passwordHash(),
                false, u.mustChangePassword(), u.claudeConnected());
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
