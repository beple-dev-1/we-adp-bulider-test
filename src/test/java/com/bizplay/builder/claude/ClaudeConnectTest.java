package com.bizplay.builder.claude;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class ClaudeConnectTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired ClaudeCredentialMapper credentials;
    @Autowired ClaudeCredentialService service;
    @Autowired ClaudeAuthGateway gateway;   // @Primary 로 끼운 대역이 온다
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @BeforeEach
    void clearAll() {
        ((FakeClaudeAuthGateway) gateway).clear();
        // 자격을 비운다. 「연결 전엔 연결 화면으로 간다」가 앞 테스트의 자격에 흔들리지 않게.
        // (2026-08-08 정정: 「zonky 는 DB 를 안 되돌린다」고 적혀 있었는데, 이제 `AbstractDbTest` 에
        //  `@Transactional` 이 있어 테스트마다 되돌아간다. 이 줄은 그래도 남긴다 — 같은 테스트 안에서
        //  자격을 앉히고 다시 재는 경우가 있어서다.)
        credentials.deleteAll();
    }

    @Test
    void 기획자는_연결_전엔_어디를_가도_연결_화면으로_간다() throws Exception {
        mvc.perform(get("/projects").with(user(planner())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/claude/connect"));
    }

    @Test
    void 연결_화면을_열기만_하면_인증을_시작하지_않는다() throws Exception {
        mvc.perform(get("/claude/connect").with(user(planner())))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("authorizeUrl"));

        assertThat(((FakeClaudeAuthGateway) gateway).beginCount()).isZero();
    }

    @Test
    void 승인_화면_열기를_누르면_인증을_시작한다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();

        start(session, person)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/claude/connect"));

        mvc.perform(get("/claude/connect").session(session).with(user(person)))
                .andExpect(model().attribute("authorizeUrl",
                        "https://claude.com/cai/oauth/authorize?fake=1"));
        assertThat(((FakeClaudeAuthGateway) gateway).beginCount()).isEqualTo(1);
    }

    @Test
    void 화면이_연_창에_보낼_승인_주소를_JSON_으로_준다() throws Exception {
        var session = new MockHttpSession();

        mvc.perform(post("/claude/connect/start").session(session).with(user(planner())).with(csrf())
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl")
                        .value("https://claude.com/cai/oauth/authorize?fake=1"));

        // 같은 세션에 앉아야 뒤이어 넣는 코드가 이 로그인에 붙는다.
        mvc.perform(get("/claude/connect").session(session).with(user(planner())))
                .andExpect(model().attribute("authorizeUrl",
                        "https://claude.com/cai/oauth/authorize?fake=1"));
    }

    @Test
    void 연결_화면은_한_번_눌러_승인_창이_열리게_한다() throws Exception {
        String html = mvc.perform(get("/claude/connect").with(user(planner())))
                .andReturn().getResponse().getContentAsString();

        // ⛔ 창을 여는 자리가 submit 처리 안이어야 한다 — 응답을 기다렸다 열면 팝업으로 막힌다.
        assertThat(html)
                .contains("window.open('', 'claude-authorize')")
                .contains("'X-Requested-With': 'fetch'");
    }

    @Test
    void 연결_화면은_목업의_단계와_행동을_보여준다() throws Exception {
        String html = mvc.perform(get("/claude/connect").with(user(planner())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("class=\"setup-screen setup-screen--claude\"")
                .contains("최초 로그인 · 선택 설정")
                .contains("Claude Code 계정 연결")
                .contains("승인 화면 열기")
                .contains("data-submit-loading=\"승인 화면 여는 중\"")
                .contains("나중에 연결")
                .contains("data-submit-loading=\"나중에 연결 처리 중\"")
                .contains("계정 연결")
                .contains("data-submit-loading=\"Claude 계정 연결 중\"");
    }

    @Test
    void 코드를_붙여넣으면_claudeAiOauth_한_칸이_봉인돼_앉는다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();   // ⚠ 주소를 받은 세션에서 코드를 넣어야 한다
        start(session, person);
        mvc.perform(post("/claude/connect").session(session).with(user(person)).with(csrf())
                        .param("code", "맞는코드"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects"));

        assertThat(credentials.selectByAccountId(person.accountId())).isPresent();
        // 그대로 .credentials.json 으로 쓸 수 있는 한 칸짜리 문서다.
        assertThat(service.tokenOf(person.accountId()))
                .contains("{\"claudeAiOauth\":{\"accessToken\":\"가짜토큰\",\"refreshToken\":\"가짜갱신\"}}");
        // 끝난 로그인은 붙잡아 두지 않는다.
        assertThat(((FakeClaudeAuthGateway) gateway).discarded).hasSize(1);
    }

    @Test
    void 봉인된_것은_평문으로_안_들어_있다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();
        start(session, person);
        mvc.perform(post("/claude/connect").session(session).with(user(person)).with(csrf())
                .param("code", "맞는코드"));

        var stored = credentials.selectByAccountId(person.accountId()).orElseThrow();
        assertThat(new String(stored.getSealedToken())).doesNotContain("가짜토큰");
    }

    @Test
    void 코드가_틀리면_이유가_뜨고_그_로그인은_버려진다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();
        start(session, person);
        mvc.perform(post("/claude/connect").session(session).with(user(person)).with(csrf())
                        .param("code", "틀린코드"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));

        // 틀린 코드로 죽은 자식을 붙잡아 두지 않는다 — 화면은 새 주소를 다시 띄운다.
        assertThat(((FakeClaudeAuthGateway) gateway).discarded).hasSize(1);
    }

    /**
     * ⚠ <b>2026-08-14 실측</b> — 요즘 {@code claude auth login --claudeai} 는 브라우저 콜백으로
     * 스스로 끝내고 자식이 종료한다. 그때는 <b>넣을 코드가 아예 없다.</b>
     */
    @Test
    void 승인_창이_콜백으로_끝내면_코드_없이_연결된다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();
        start(session, person);

        ((FakeClaudeAuthGateway) gateway).finishByCallback();   // 새 창에서 승인을 마쳤다

        mvc.perform(post("/claude/connect").session(session).with(user(person)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects"));

        assertThat(credentials.selectByAccountId(person.accountId())).isPresent();
    }

    /**
     * ⛔ <b>일찍 누른 한 번이 진행 중인 승인을 죽이면 안 된다.</b>
     *
     * <p>옛 판은 실패를 {@code finally} 로 뭉뚱그려 로그인을 무조건 버렸다. 버리면 그 자리를 지우는데,
     * 사람이 승인을 마치는 중이면 <b>곧 앉을 자격 파일이 갈 곳을 잃는다</b> —
     * 실제로 2026-08-14 에 <b>이미 받아 둔 자격이 그렇게 지워질 뻔했다.</b>
     */
    @Test
    void 아직_승인_전에_눌러도_진행중인_로그인을_안_버린다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();
        start(session, person);

        mvc.perform(post("/claude/connect").session(session).with(user(person)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                // 주소가 그대로 있어야 새 창을 다시 안 연다 — 새 주소를 뽑으면 앞의 승인이 무효가 된다.
                .andExpect(model().attribute("authorizeUrl",
                        "https://claude.com/cai/oauth/authorize?fake=1"));

        assertThat(((FakeClaudeAuthGateway) gateway).discarded).isEmpty();

        // 그리고 승인을 마치고 다시 누르면 그대로 이어진다.
        ((FakeClaudeAuthGateway) gateway).finishByCallback();
        mvc.perform(post("/claude/connect").session(session).with(user(person)).with(csrf()))
                .andExpect(redirectedUrl("/projects"));
        assertThat(credentials.selectByAccountId(person.accountId())).isPresent();
    }

    @Test
    void 승인_화면_열기를_다시_누르면_앞의_로그인을_버린다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();
        start(session, person);
        start(session, person);

        // 승인을 안 끝내고 다시 시작한 사람 — 앞의 자식 프로세스가 남으면 안 된다.
        assertThat(((FakeClaudeAuthGateway) gateway).discarded).hasSize(1);
    }

    @Test
    void 나중에_연결을_고르면_현재_세션에서는_연결_관문을_건너뛴다() throws Exception {
        var person = planner();
        var session = new MockHttpSession();

        mvc.perform(post("/claude/connect/skip").session(session).with(user(person)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects"));

        mvc.perform(get("/projects").session(session).with(user(person)))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isNotEqualTo("/claude/connect"));
    }

    @Test
    void 슈퍼관리자도_자격이_없으면_연결_화면으로_간다() throws Exception {
        var superUser = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(superUser.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        var changed = accounts.selectById(superUser.getId()).orElseThrow();

        mvc.perform(get("/projects").with(user(BuilderUser.of(changed, false))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/claude/connect"));
    }

    /**
     * ⚠ 2026-08-15 에 <b>saveAndFlush 가 여기서 사라졌다.</b> 그것은 계정이 JPA 라 커밋 직전까지
     * INSERT 를 미뤄서(write-behind), 뒤따르는 MyBatis 의 자격 INSERT 가
     * {@code adk_builder_claude_credential.account_id} FK 를 못 채우던 것을 막던 장치였다.
     * <b>계정도 MyBatis 가 되어 곧장 들어가므로 그 까닭이 사라졌다.</b>
     * ⛔ 되살리지 마라 — 매퍼에는 flush 라는 것 자체가 없다.
     */
    @Test
    void 같은_Claude_계정은_다른_사용자에게_연결할_수_없다() throws Exception {
        BuilderUser first = planner();
        BuilderUser second = otherPlanner();

        MockHttpSession firstSession = new MockHttpSession();
        start(firstSession, first);
        mvc.perform(post("/claude/connect").session(firstSession).with(user(first)).with(csrf())
                        .param("code", "맞는코드"))
                .andExpect(status().is3xxRedirection());
        assertThat(credentials.selectByAccountId(first.accountId()))
                .get().extracting(ClaudeCredential::getClaudeEmail)
                .isEqualTo("planner@claude.example");

        MockHttpSession secondSession = new MockHttpSession();
        start(secondSession, second);
        mvc.perform(post("/claude/connect").session(secondSession).with(user(second)).with(csrf())
                        .param("code", "맞는코드"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error",
                        "이미 다른 사용자에게 연결된 Claude 계정입니다. 본인의 계정으로 다시 승인해 주세요."));

        assertThat(credentials.selectByAccountId(first.accountId())).isPresent();
        assertThat(credentials.selectByAccountId(second.accountId())).isEmpty();
    }

    private BuilderUser otherPlanner() {
        var account = com.bizplay.builder.account.Account.create(
                ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT), "planner2", "다른 기획자",
                "other@bizplay.co.kr", encoder.encode("바뀐비번1234"), false);
        accounts.insert(account);
        accounts.updatePassword(account.getId(), encoder.encode("바뀐비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), false);
    }

    private BuilderUser planner() {
        var account = accounts.selectByLoginId("planner").orElseGet(() -> {
            var fresh = com.bizplay.builder.account.Account.create(
                    ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT), "planner", "이영희", "lee@bizplay.co.kr",
                    encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        var changed = accounts.selectById(account.getId()).orElseThrow();
        return BuilderUser.of(changed, credentials.selectByAccountId(changed.getId()).isPresent());
    }

    private org.springframework.test.web.servlet.ResultActions start(
            MockHttpSession session, BuilderUser person) throws Exception {
        return mvc.perform(post("/claude/connect/start")
                .session(session).with(user(person)).with(csrf()));
    }
}
