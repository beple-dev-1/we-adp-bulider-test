package com.bizplay.builder.web;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 전역 오류 화면({@code templates/error.html}) — {@link com.bizplay.builder.web} 에 컨트롤러가
 * 없다. 스프링 부트 {@code BasicErrorController} 가 뷰 이름 {@code error} 를 내고 이 파일이 그린다.
 *
 * <p>⭐ <b>실측(2026-09-04)</b> — {@code MockMvc} 는 서블릿 컨테이너의 오류 디스패치(ERROR
 * dispatch)를 타지 않는다. {@code mvc.perform(get("/없는주소"))} 는 상태만 404 이고 본문이 빈다.
 * 반면 {@code /error} 를 오류 속성({@code jakarta.servlet.error.*})과 함께 <b>직접</b> 부르면
 * {@code BasicErrorController} 가 그 속성을 읽어 실제로 뷰를 그린다 — 이 파일은 전부 그 길을 쓴다.
 * 다만 {@code Accept} 헤더 없이 부르면 컨트롤러가 JSON 으로 응답한다({@code errorHtml} 이 아니라
 * {@code error} 메서드로 빠짐) — 그래서 {@code .accept(MediaType.TEXT_HTML)} 을 반드시 붙인다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class ErrorScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;

    @Test
    void 없는_주소를_열면_404_안내_화면이_뜬다() throws Exception {
        String html = errorBody(404, Map.of());

        assertThat(html).contains("화면을 찾지 못했습니다",
                "요청하신 주소에 해당하는 화면이 없습니다. 주소가 바뀌었거나 삭제된 화면일 수 있습니다.",
                "프로젝트 목록에서 다시 찾아 주세요.");
        // ⚠ 응답에 그 낱말이 아예 없어야 한다. 한때 error.html 의 자기 설명 주석에 "Whitelabel" 이
        //   적혀 있어 응답에 실려 나갔다 — html 주석은 Thymeleaf 가 안 지운다. 그래서 주석 쪽 글을
        //   바꿨고, 여기서는 낱말 자체를 막는다. 사람이 `grep Whitelabel` 로 세는 확인과도 맞는다.
        assertThat(html).doesNotContain("Whitelabel", "Whitelabel Error Page");
    }

    @Test
    void _404_가_아닌_오류는_다른_글을_보여_준다() throws Exception {
        String html = errorBody(500, Map.of());

        assertThat(html).contains("화면을 여는 중 문제가 생겼습니다",
                "서버가 요청을 끝내지 못했습니다.",
                "잠시 후 다시 시도해 주세요. 계속되면 관리자에게 알려 주세요.");
        assertThat(html).doesNotContain("화면을 찾지 못했습니다");
    }

    /**
     * ⚠ 안전한 쪽(500 계열 문구)이 기본이어야 한다 — 상태 속성이 없을 때 404 글이 뜨면 실패다.
     *
     * <p>⚠ <b>모델의 {@code status} 가 널이 되는 길은 없다</b> — {@code DefaultErrorAttributes} 가
     * 속성이 없으면 <b>999</b> 를 넣는다. 그래서 템플릿의 {@code status != null} 은 지금 도달할 수
     * 없는 방어이고, 이 시험이 실제로 지나는 길은 아래 999 시험과 같다. 이름을 「상태 속성이
     * 없으면」으로 적는 까닭이다.
     */
    @Test
    void 상태_속성이_없으면_안전한_쪽_글이_뜬다() throws Exception {
        String html = errorBody(null, Map.of());

        assertThat(html).contains("화면을 여는 중 문제가 생겼습니다");
        assertThat(html).doesNotContain("화면을 찾지 못했습니다");
    }

    /**
     * ⚠ {@code /error} 를 사람이 직접 열면 {@code BasicErrorController} 가 상태 자리에 <b>999</b> 를
     *넣는다. 그것은 HTTP 상태가 아니라 「모른다」는 뜻이라 화면에 뜨면 읽는 사람이 헛짚는다 —
     * 2026-09-04 트랙2 에서 실제로 「상태 코드 999」가 떠서 막았다.
     */
    @Test
    void 상태값이_HTTP_상태가_아니면_상태_코드_줄을_안_그린다() throws Exception {
        // ⚠ CSRF 토큰을 걷어내고 본다 — "999" 도 난수에 섞일 수 있다(까닭은 withoutCsrfTokens).
        assertThat(withoutCsrfTokens(errorBody(999, Map.of()))).doesNotContain("상태 코드", "999");
        // ⭐ 999 는 응답 상태로도 안 나간다 — 스프링이 500 으로 눌러 담는다. 모델의 status(999)와
        //    응답 상태(500)가 갈리는 자리라, 화면이 모델 값을 그대로 찍으면 사람이 헛짚는다.
        assertThat(mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr("jakarta.servlet.error.status_code", 999)
                        .with(user(superUser())))
                .andReturn().getResponse().getStatus()).isEqualTo(500);
        assertThat(errorBody(null, Map.of())).doesNotContain("상태 코드");

        // 진짜 상태일 때는 그대로 보여 준다 — 문의할 때 쓰는 값이라 지우면 안 된다.
        assertThat(errorBody(404, Map.of())).contains("상태 코드 404");
        assertThat(errorBody(500, Map.of())).contains("상태 코드 500");
    }

    @Test
    void 오류_화면은_경로와_예외_메시지를_안_흘린다() throws Exception {
        String html = errorBody(500, Map.of(
                "jakarta.servlet.error.request_uri", "/projects/비밀경로/x",
                "jakarta.servlet.error.message", "java.lang.RuntimeException: boom",
                "jakarta.servlet.error.exception", new RuntimeException("민감정보 유출 시험")));

        assertThat(html).doesNotContain("비밀경로", "RuntimeException", "java.lang.",
                "민감정보 유출 시험", "\tat ");
    }

    @Test
    void 오류_화면은_프로젝트_목록으로_가는_길_하나만_준다() throws Exception {
        String html = errorBody(404, Map.of());

        assertThat(html).contains("href=\"/projects\"");
        assertThat(html).doesNotContain("뒤로", "이전으로");
    }

    /** 껍데기 계약 — '카드' 모양은 왼쪽 메뉴({@code fragments/parts :: nav}) 를 아예 안 그린다. */
    @Test
    void 오류_화면은_왼쪽_메뉴_없이_카드로_선다() throws Exception {
        String html = errorBody(404, Map.of());

        assertThat(html).contains("app-shell--card");
        assertThat(html).doesNotContain("builder-navigation");
    }

    private String errorBody(Integer statusCode, Map<String, Object> extraAttrs) throws Exception {
        MockHttpServletRequestBuilder request = get("/error")
                .accept(MediaType.TEXT_HTML)
                .with(user(superUser()));
        if (statusCode != null) {
            request = request.requestAttr("jakarta.servlet.error.status_code", statusCode);
        }
        for (var entry : extraAttrs.entrySet()) {
            request = request.requestAttr(entry.getKey(), entry.getValue());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        // ⛔ 「404 를 200 으로 바꾸지 않는다」를 여기서 잠근다 — 오류 화면은 보여 주는 쪽만 고친다.
        //    BasicErrorController 가 jakarta.servlet.error.status_code 를 읽어 응답 상태를 세운다.
        // ⚠ 진짜 HTTP 상태일 때만 잰다 — 999 처럼 상태가 아닌 값은 스프링이 500 으로 눌러 담는다
        //    (2026-09-04 실측). 그 갈래는 아래 999 시험이 따로 단정한다.
        if (statusCode != null && statusCode >= 400 && statusCode < 600) {
            assertThat(response.getStatus()).isEqualTo(statusCode);
        }
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
