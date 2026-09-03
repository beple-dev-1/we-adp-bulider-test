package com.bizplay.builder.account;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.id.IdSequence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AdminAccountTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;

    @Test
    void 슈퍼계정이_기획자_계정을_만든다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "younghee")
                        .param("name", "이영희")
                        .param("email", "lee@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/accounts"))
                .andExpect(flash().attribute("success",
                        "사용자가 등록되었습니다. 로그인 아이디와 임시 비밀번호를 사용자에게 별도로 전달해 주세요."));

        Account created = accounts.selectByLoginId("younghee").orElseThrow();
        assertThat(created.isSuperAccount()).isFalse();
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(created.getName()).isEqualTo("이영희");
        assertThat(encoder.matches("임시비번1234", created.getPasswordHash())).isTrue();
    }

    @Test
    void 슈퍼관리자_권한을_선택해_계정을_만든다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "new-super")
                        .param("name", "새 관리자")
                        .param("email", "new-super@bizplay.co.kr")
                        .param("role", "SUPER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().is3xxRedirection());

        assertThat(accounts.selectByLoginId("new-super").orElseThrow().isSuperAccount()).isTrue();
    }

    @Test
    void 허용되지_않은_권한으로는_계정을_만들지_못한다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "invalid-role")
                        .param("name", "잘못된 권한")
                        .param("email", "invalid-role@bizplay.co.kr")
                        .param("role", "ADMIN")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorField", "role"))
                .andExpect(content().string(containsString("등록할 권한을 선택해 주세요")));

        assertThat(accounts.selectByLoginId("invalid-role")).isEmpty();

        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "missing-role")
                        .param("name", "권한 누락")
                        .param("email", "missing-role@bizplay.co.kr")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorField", "role"));

        assertThat(accounts.selectByLoginId("missing-role")).isEmpty();
    }

    @Test
    void 같은_아이디는_두_번_안_만들어진다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                .param("loginId", "dup").param("name", "김철수")
                .param("email", "kim@bizplay.co.kr").param("role", "PLANNER")
                .param("temporaryPassword", "임시비번1234"));

        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "dup").param("name", "다른사람")
                        .param("email", "other@bizplay.co.kr").param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }

    /**
     * ⛔ FIX 5 재현 — {@code ProjectService.register} 는 이름을 다듬고 빈 것을 거절하는데
     * 계정 쪽 등록에는 그 검사가 없었다. 공백뿐인 로그인 아이디가 그대로 저장되면
     * DB 유니크 제약도 못 막고, 로그인 폼이 값을 다듬지 않는 한 그 계정은 로그인할 길이 없다.
     */
    @Test
    void 로그인_아이디가_공백뿐이면_거절된다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "   ")
                        .param("name", "이영희")
                        .param("email", "lee5@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("로그인 아이디를 입력해 주세요")));

        assertThat(accounts.selectAll()).noneMatch(a -> "이영희".equals(a.getName()));
    }

    /**
     * ⛔ 앞뒤 공백만 다른 아이디 둘이 나란히 앉으면 겉보기엔 같은데 하나는 영원히 로그인이
     * 안 된다 — 다듬은 값으로 저장하고 다듬은 값으로 겹치는지 봐야 한다.
     */
    @Test
    void 앞_공백이_있는_아이디와_없는_아이디가_같은_아이디로_겹친다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", " younghee5")
                        .param("name", "이영희")
                        .param("email", "lee5b@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().is3xxRedirection());

        assertThat(accounts.selectByLoginId("younghee5")).isPresent();

        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "younghee5")
                        .param("name", "다른사람")
                        .param("email", "other5@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("같은 로그인 아이디가 이미 등록되어 있습니다")));

        assertThat(accounts.selectAll().stream().filter(a -> "younghee5".equals(a.getLoginId())).count())
                .isEqualTo(1);
    }

    @Test
    void 사용자_등록_화면이_뜨고_입력칸과_권한_선택이_있다() throws Exception {
        String html = mvc.perform(get("/admin/accounts/new").with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("새 사용자 등록")))
                .andExpect(content().string(containsString("name=\"loginId\"")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"temporaryPassword\"")))
                .andExpect(content().string(containsString("name=\"role\"")))
                .andExpect(content().string(containsString("value=\"PLANNER\"")))
                .andExpect(content().string(containsString("value=\"SUPER\"")))
                .andExpect(content().string(containsString("maxlength=\"64\"")))
                .andExpect(content().string(containsString("maxlength=\"255\"")))
                .andExpect(content().string(containsString("document-register-head__actions")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("data-password-toggle"))))
                .andExpect(content().string(containsString("form=\"user-register-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("기획 저장소의 변경 기록"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .containsOnlyOnce("data-submit-loading=\"사용자 등록 중\"")
                .containsOnlyOnce(">취소</a>");
    }

    @Test
    void 사용자_등록_뒤_목록에서_완료와_다음_행동을_안내한다() throws Exception {
        mvc.perform(get("/admin/accounts")
                        .flashAttr("success",
                                "사용자가 등록되었습니다. 로그인 아이디와 임시 비밀번호를 사용자에게 별도로 전달해 주세요.")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("사용자 등록을 완료했습니다.")))
                .andExpect(content().string(containsString("임시 비밀번호를 사용자에게 별도로 전달해 주세요.")));
    }

    @Test
    void 사용자_등록값이_DB_길이보다_길면_입력칸에서_오류를_안내한다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "a".repeat(65))
                        .param("name", "이영희")
                        .param("email", "lee@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorField", "loginId"))
                .andExpect(content().string(containsString("64자 이하")))
                .andExpect(content().string(containsString("id=\"login-id\"")))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")))
                .andExpect(content().string(containsString("aria-describedby=\"login-id-error\"")));

        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "long-name")
                        .param("name", "가".repeat(65))
                        .param("email", "lee@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorField", "name"))
                .andExpect(content().string(containsString("64자 이하")));

        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "long-email")
                        .param("name", "이영희")
                        .param("email", "a".repeat(244) + "@example.com")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorField", "email"))
                .andExpect(content().string(containsString("255자 이하")));
    }

    @Test
    void 이메일_형식이_아니면_이메일_입력칸에서_오류를_안내한다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "younghee-email")
                        .param("name", "이영희")
                        .param("email", "올바르지-않은-이메일")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorField", "email"))
                .andExpect(content().string(containsString("이메일 형식이 올바르지 않습니다")))
                .andExpect(content().string(containsString("id=\"user-email-error\"")))
                .andExpect(content().string(containsString(
                        "aria-describedby=\"user-email-help user-email-error\"")));

        assertThat(accounts.selectByLoginId("younghee-email")).isEmpty();
    }

    @Test
    void 같은_아이디를_또_넣으면_등록_화면으로_되돌아온다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                .param("loginId", "younghee2").param("name", "이영희")
                .param("email", "lee@bizplay.co.kr").param("role", "PLANNER")
                .param("temporaryPassword", "임시비번1234"));

        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "younghee2").param("name", "이영희2")
                        .param("email", "lee2@bizplay.co.kr").param("role", "PLANNER")
                        .param("temporaryPassword", "임시비번1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("같은 로그인 아이디가 이미 등록되어 있습니다")))
                .andExpect(content().string(containsString("새 사용자 등록")));
    }

    @Test
    void 등록에_실패하면_비밀번호를_제외한_입력값을_유지한다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "remember-me")
                        .param("name", "입력 유지")
                        .param("email", "remember@bizplay.co.kr")
                        .param("role", "PLANNER")
                        .param("temporaryPassword", "짧다"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"remember-me\"")))
                .andExpect(content().string(containsString("value=\"입력 유지\"")))
                .andExpect(content().string(containsString("value=\"remember@bizplay.co.kr\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("value=\"짧다\""))));
    }

    @Test
    void 임시_비밀번호가_짧으면_등록_화면으로_되돌아온다() throws Exception {
        mvc.perform(post("/admin/accounts").with(user(superUser())).with(csrf())
                        .param("loginId", "younghee3").param("name", "이영희")
                        .param("email", "lee3@bizplay.co.kr").param("role", "PLANNER")
                        .param("temporaryPassword", "짧다"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("8자 이상")))
                .andExpect(content().string(containsString("새 사용자 등록")));
    }

    @Test
    void 기획자는_계정_화면에_못_들어간다() throws Exception {
        var planner = Account.create(ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT), "planner0", "이영희", "lee@bizplay.co.kr",
                encoder.encode("임시1234"), false);
        accounts.insert(planner);
        accounts.updatePassword(planner.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        var changed = accounts.selectById(planner.getId()).orElseThrow();

        mvc.perform(get("/admin/accounts").with(user(BuilderUser.of(changed, true))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 상세_계정_정보에_이름과_이메일과_권한과_클로드_상태가_뜬다() throws Exception {
        String id = createPlanner("jimin", "박지민", "park@bizplay.co.kr");

        mvc.perform(get("/admin/accounts/" + id).with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<dt>이름</dt><dd>박지민</dd>")))
                .andExpect(content().string(containsString("park@bizplay.co.kr")))
                .andExpect(content().string(containsString("기획자")))
                .andExpect(content().string(containsString("미연결")));
    }

    @Test
    void 임시_비밀번호를_재발급하면_비밀번호_설정_필요로_되돌아간다() throws Exception {
        String id = createPlanner("minsoo", "최민수", "choi@bizplay.co.kr");
        // ⚠ 「이미 비밀번호를 바꾼 사람」이 이 시험의 전제다 — 아래 단언(재발급하면 다시 참이 된다)은
        //    출발이 거짓일 때만 뜻이 있다. 2026-08-15 까지는 findById(...).changePassword(...) 였는데,
        //    그것은 JPA 더티 체킹으로만 저장되던 모양이다. MyBatis 엔 그것이 없어 update 로 옮겼다.
        // ⛔ 이 줄을 지우지 마라 — 지워도 테스트는 초록으로 남는데 재는 것이 없어진다.
        accounts.updatePassword(id, "이미바꾼해시");

        mvc.perform(post("/admin/accounts/" + id + "/temporary-password")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(accounts.selectById(id).orElseThrow().isMustChangePassword()).isTrue();
    }

    /**
     * ⛔ flash 속성은 <b>딱 한 번</b>만 다음 요청에 실린다. 브라우저라면 POST 뒤의 리다이렉트를
     * 자동으로 따라가 그 GET 에서 새 비밀번호를 한 번 보여 준다 — 그다음 새로고침(같은 세션의
     * 또 다른 GET)에서는 이미 소비돼 없다. 이 흐름 전부를 <b>같은 세션</b>으로 태운다 — 세션을
     * 새로 갈면, 비밀번호를 {@code session.setAttribute} 로 몰래 심어 두는 가상의 버그가 있어도
     * 새 세션에는 애초에 없으니 이 테스트가 그것을 못 잡는다.
     */
    @Test
    void 재발급하면_리다이렉트_직후_한_번만_보여주고_그다음부터는_안_보인다() throws Exception {
        String id = createPlanner("dohyun", "김도현", "kim@bizplay.co.kr");
        MockHttpSession session = new MockHttpSession();

        var result = mvc.perform(post("/admin/accounts/" + id + "/temporary-password")
                        .session(session)
                        .with(user(superUser())).with(csrf()))
                .andExpect(flash().attributeExists("issuedPassword"))
                .andReturn();

        String issued = (String) result.getFlashMap().get("issuedPassword");
        assertThat(issued).hasSize(12);

        // 리다이렉트를 따라간 첫 GET(같은 세션) — 여기서는 한 번 보여 준다
        String firstView = mvc.perform(get("/admin/accounts/" + id).session(session).with(user(superUser())))
                .andReturn().getResponse().getContentAsString();
        assertThat(firstView).contains(issued);

        // 같은 세션으로 다시 열면 없다 — Builder 는 이것을 어디에도 다시 보여 주지 않는다
        String secondView = mvc.perform(get("/admin/accounts/" + id).session(session).with(user(superUser())))
                .andReturn().getResponse().getContentAsString();
        assertThat(secondView).doesNotContain(issued);
    }

    /** 슈퍼계정도 Claude 계정을 등록할 수 있으므로 자격이 없으면 미연결로 표시한다. */
    @Test
    void 슈퍼계정도_클로드_자격이_없으면_미연결이다() throws Exception {
        String superId = accounts.selectByLoginId("admin").orElseThrow().getId();

        mvc.perform(get("/admin/accounts/" + superId).with(user(superUser())))
                .andExpect(content().string(containsString("미연결")))
                .andExpect(model().attribute("setupState", "Claude 연결 필요"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("해당 없음"))));
    }

    /**
     * ⛔ 본인 계정을 겨눈 재발급은 거절해야 한다 — 새 비밀번호를 한 번 보여 준 뒤
     * 못 적어 두면(세션 끊김 등) 되돌릴 길이 DB 손질뿐이다. 유일한 슈퍼계정이면 관리 화면 전체가 잠긴다.
     */
    @Test
    void 본인_계정은_재발급을_거절한다() throws Exception {
        BuilderUser admin = superUser();
        String beforeHash = accounts.selectById(admin.accountId()).orElseThrow().getPasswordHash();

        mvc.perform(post("/admin/accounts/" + admin.accountId() + "/temporary-password")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("본인 계정의 임시 비밀번호는 이 화면에서 재발급할 수 없습니다")));

        assertThat(accounts.selectById(admin.accountId()).orElseThrow().getPasswordHash())
                .isEqualTo(beforeHash);
    }

    @Test
    void 본인_상세_화면에는_재발급_버튼_대신_안내문이_뜬다() throws Exception {
        BuilderUser admin = superUser();

        String html = mvc.perform(get("/admin/accounts/" + admin.accountId()).with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("임시 비밀번호 재발급");
        assertThat(html).contains("비밀번호 변경");
    }

    /**
     * ⛔ 지금은 컨트롤러 클래스 전체에 걸린 {@code @PreAuthorize("hasRole('SUPER')")} 가 막아 준다.
     * 나중에 상세 화면을 넓히려고 메서드 단위 어노테이션을 따로 달면, 이 검사가 없으면
     * 기획자가 남의 비밀번호를 재발급해도 아무것도 빨갛게 뜨지 않는다.
     */
    @Test
    void 기획자는_다른_사람의_비밀번호를_재발급하지_못한다() throws Exception {
        var planner = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "planner9", "이영희", "lee@bizplay.co.kr",
                encoder.encode("임시1234"), false);
        accounts.insert(planner);
        accounts.updatePassword(planner.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        var changed = accounts.selectById(planner.getId()).orElseThrow();

        String targetId = createPlanner("target9", "김대상", "target@bizplay.co.kr");

        mvc.perform(post("/admin/accounts/" + targetId + "/temporary-password")
                        .with(user(BuilderUser.of(changed, true))).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 목록에_필요한_열_다섯이_뜨고_이름이_상세로_이어진다() throws Exception {
        String id = createPlanner("younghee2", "이영희", "lee2@bizplay.co.kr");

        String html = mvc.perform(get("/admin/accounts").with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("/admin/accounts/" + id)
                .contains("/admin/accounts/new")
                .contains("로그인 아이디").contains("권한").contains("Claude 계정")
                .doesNotContain("name=\"role\"").doesNotContain("name=\"setup\"")
                .doesNotContain("<th scope=\"col\">최초 설정</th>");
    }

    @Test
    void 이름으로_거르면_그_사람만_남는다() throws Exception {
        createPlanner("aaa", "김하나", "a@bizplay.co.kr");
        createPlanner("bbb", "박두울", "b@bizplay.co.kr");

        String html = mvc.perform(get("/admin/accounts").param("q", "하나")
                        .with(user(superUser())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("김하나").doesNotContain("박두울");
    }

    /**
     * ⛔ 계획 4 작업 6 검토가 남긴 자리 — 재발급 버튼이 뜨는지는 논리로만 확인했지,
     * 슈퍼관리자가 남의 계정을 볼 때 실제로 렌더되는지는 아무 테스트도 안 쟀다.
     */
    @Test
    void 슈퍼관리자가_다른_사람의_상세를_보면_재발급_버튼이_있다() throws Exception {
        String targetId = createPlanner("target10", "김대상", "target10@bizplay.co.kr");

        String html = mvc.perform(get("/admin/accounts/" + targetId).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("임시 비밀번호 재발급")
                .contains("data-submit-loading=\"임시 비밀번호 재발급 중\"");
    }

    private String createPlanner(String loginId, String name, String email) {
        var account = Account.create(ids.next(IdSequence.Kind.ACCOUNT),
                loginId, name, email, "해시", false);
        accounts.insert(account);
        return account.getId();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
