package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 문맥을 채우는 <b>한 자리</b>를 밟는다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-10-project-context-design.md}
 *
 * <p>여기서 지키는 것 — 화면 코드가 머리의 네 값을 <b>하나도 모르고도</b> 껍데기 계약을 통과한다.
 * 안 그러면 산출물 화면 열 개마다 네 값을 손으로 채워야 하고, 알림은 빠뜨려도
 * <b>조용히 빈 채로</b> 뜬다 — 계약이 막으려던 바로 그 실패다.
 */
@AutoConfigureMockMvc
@Import({FakeClaudeAuthGateway.Wiring.class, ProjectContextTest.Probe.class})
class ProjectContextTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 앉히는 것도 재는 것도 그대로다. */
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    @Test
    void 준비된_프로젝트면_머리의_네_값이_모델에_실린다() throws Exception {
        Project p = seatProject("탐나는전", ProjectState.READY);

        mvc.perform(get("/projects/" + p.getId() + "/__probe").with(user(person())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("projectId", p.getId()))
                .andExpect(model().attribute("projectName", "탐나는전"))
                .andExpect(model().attributeExists("projects"))
                .andExpect(model().attribute("unreadCount", 0))
                .andExpect(model().attributeExists("notifications"));
    }

    @Test
    void 없는_번호면_고르기로_돌려보낸다() throws Exception {
        mvc.perform(get("/projects/999999/__probe").with(user(person())))
                .andExpect(redirectedUrl("/projects?gone"));
    }

    @Test
    void 숫자가_아닌_번호도_같은_길로_돌려보낸다() throws Exception {
        mvc.perform(get("/projects/일곱/__probe").with(user(person())))
                .andExpect(redirectedUrl("/projects?gone"));
    }

    /**
     * ⚠ {@code RECEIVING} 은 클론이 아직 도는 중이라 <b>기획자에게 안 열린다</b> —
     * {@code ProjectState} 가 그렇게 적어 뒀다. 열어 주면 빈 워크트리 위에서 화면이 뜬다.
     */
    @Test
    void 아직_안_준비된_프로젝트는_안_열린다() throws Exception {
        Project receiving = seatProject("아직", ProjectState.RECEIVING);

        mvc.perform(get("/projects/" + receiving.getId() + "/__probe").with(user(person())))
                .andExpect(redirectedUrl("/projects?gone"));
    }

    @Test
    void 프로젝트들에는_준비된_것만_담긴다() throws Exception {
        Project ready = seatProject("준비된것", ProjectState.READY);
        seatProject("받는중인것", ProjectState.RECEIVING);

        var mav = mvc.perform(get("/projects/" + ready.getId() + "/__probe").with(user(person())))
                .andReturn().getModelAndView();

        assertThat((java.util.List<?>) mav.getModel().get("projects")).hasSize(1);
    }

    /**
     * 관리 화면은 프로젝트 밖이다 — 이 자리를 안 거쳐야 한다.
     * 넓게 걸면 프로젝트 번호가 없는 화면이 헛돌거나 엉뚱하게 튕긴다.
     */
    @Test
    void 관리_화면은_이_자리를_안_거친다() throws Exception {
        mvc.perform(get("/admin/projects").with(user(superUser())))
                .andExpect(status().isOk());
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    /**
     * ⚠ 2026-08-15 부터 <b>앉히기가 두 걸음</b>이다 — {@code insert} 는 늘 {@code RECEIVING} 으로 넣고
     * ({@code Project.create} 가 처음 상태의 정본이다), 다른 상태는 {@code updateState} 로 따로 만든다.
     * ⛔ 「엔티티를 고치고 저장」으로 되돌리지 마라 — MyBatis 엔 더티 체킹이 없어 조용히 잃는다.
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

    private BuilderUser person() {
        return superUser();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    /**
     * 시험에서만 뜨는 화면. 운영 코드에는 이 경로가 없다.
     *
     * <p>⚠ 브리프 원문에는 {@code shape} 을 안 채웠는데, 그러면 {@code shellprobe.html} 이
     * {@code ShellContract.check} 에 {@code null} 을 넘겨 <b>렌더 자체가 500 으로 깨진다</b>
     * (실측 — RED 를 돌려 확인했다). 이 자리가 재려는 것은 문맥 인터셉터가 다섯 값을 얹는지이지
     * 껍데기 계약의 모양 검사가 아니므로, 검사를 통과하는 가장 작은 모양 {@code '꽉'} 을 골랐다 —
     * 프로젝트 이름·번호만 있으면 되고 {@code current}(메뉴 열쇠)은 필요 없다.
     */
    @TestConfiguration
    @Controller
    static class Probe {

        @GetMapping("/projects/{번호}/__probe")
        String page(Model model) {
            model.addAttribute("title", "프로브");
            model.addAttribute("shape", "꽉");
            return "shellprobe";
        }
    }
}
