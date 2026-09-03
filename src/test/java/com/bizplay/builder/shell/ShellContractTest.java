package com.bizplay.builder.shell;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
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
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 껍데기 조각의 <b>인자 계약</b>을 밟는다.
 *
 * <p>왜 따로 있나 — {@link ShellTest} 는 <b>실제로 있는 화면</b>으로만 껍데기를 잰다.
 * 그래서 모양 넷 중 {@code '카드'} 와 {@code '관리'} 둘만 밟혔고 {@code '산출물'} · {@code '꽉'} 과
 * 산출물 메뉴 열쇠 열한 개, {@code null} 아닌 {@code projectName} 은 <b>한 번도 안 재였다.</b>
 *
 * <p>⛔ 그게 왜 위험했나: 인자를 잘못 넘겨도 <b>실패하지 않았다.</b> {@code shape} 가 틀리면 전부
 * {@code '꽉'} 으로 흘렀고 {@code current} 가 틀리면 메뉴는 그리되 현재 위치 표시만 사라졌다 —
 * 즉 다음 화면에서 문자열 오타가 나면 500 이 아니라 <b>「메뉴와 프로젝트 이름이 사라진 정상 화면」</b>
 * 처럼 보였다. 사람이 눈치채기 가장 어려운 모양이다.
 *
 * <p>근거: {@code docs/superpowers/captain/handoff-screen-shell-impl.md} 미결 1-나
 * (2026-08-09 코덱스 관문 3회차).
 */
@AutoConfigureMockMvc
@Import({FakeClaudeAuthGateway.Wiring.class, ShellContractTest.Probe.class})
public class ShellContractTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;

    // ── 네 모양 ────────────────────────────────────────────────────────────

    private static final String PROJECT = "탐나는전";
    private static final String PROJECT_ID = "0000007";

    @Test
    void 산출물과_관리는_메뉴_열을_달고_카드와_꽉은_안_단다() throws Exception {
        assertThat(shell("산출물", "brd", PROJECT))
                .contains("app-nav")
                .contains("data-nav-toggle", "aria-controls=\"builder-navigation\"")
                .doesNotContain("app-shell--card")
                .doesNotContain("app-shell--wide");

        assertThat(shell("관리", "projects", null))
                .contains("app-nav")
                .contains("data-nav-toggle", "aria-controls=\"builder-navigation\"")
                .doesNotContain("app-shell--card")
                .doesNotContain("app-shell--wide");

        assertThat(shell("카드", null, null))
                .contains("app-shell--card")
                .contains("app-card")
                .doesNotContain("app-nav", "data-nav-toggle");

        // ⚠ '꽉' 은 작업대(계획 3)가 쓸 자리다. 지금은 쓰는 화면이 없어서 여기서만 밟힌다.
        assertThat(shell("꽉", null, PROJECT))
                .contains("app-shell--wide")
                .doesNotContain("app-nav", "data-nav-toggle")
                .doesNotContain("app-card");
    }

    @Test
    void 공통_머리는_팝업_닫기_동작과_표준_로그아웃_문구를_제공한다() throws Exception {
        assertThat(shell("산출물", "brd", PROJECT))
                .contains("/js/shell.js")
                .contains("data-page-loading-overlay", "aria-label=\"화면 이동 처리 중\"")
                .contains("로그아웃")
                .doesNotContain(">나간다<");
    }

    // ── current 열쇠 ──────────────────────────────────────────────────────

    /**
     * 열쇠 하나하나가 실제로 메뉴 항목에 가 닿는지 본다.
     *
     * <p>이 테스트는 <b>정본이 둘로 갈라지는 것</b>도 같이 막는다 — 허용 목록은
     * {@link ShellContract} 가 들고 있고 실제 링크는 {@code fragments/parts.html} 이 들고 있다.
     * 한쪽에서 열쇠 이름을 고치면 여기가 빨개진다.
     */
    @Test
    void 산출물_열쇠_열한_개가_전부_제_링크에서_지금_표시를_받는다() throws Exception {
        for (String key : ShellContract.ARTIFACT_MENU_KEYS) {
            String html = shell("산출물", key, PROJECT);
            assertThat(currentMarkCount(html))
                    .as("열쇠 '%s' 가 산출물 메뉴에서 지금 표시를 딱 한 번 받는다", key)
                    .isEqualTo(1);
            // ⚠ 표시가 **그 열쇠의 링크에** 붙었는지까지 봐야 한다. 개수만 세면
            //    href 만 /projects/7/artifacts/brdd 로 오타 나도 통과한다(2026-08-09 코덱스가 짚었다).
            assertThat(markedLinkHref(html))
                    .as("열쇠 '%s' 의 표시가 그 열쇠의 주소에 붙었다", key)
                    .isEqualTo("/projects/" + PROJECT_ID + "/artifacts/" + key);
        }
    }

    @Test
    void 관리_열쇠_둘도_전부_제_링크에서_지금_표시를_받는다() throws Exception {
        for (String key : ShellContract.ADMIN_KEYS) {
            String html = shell("관리", key, null);
            assertThat(currentMarkCount(html))
                    .as("열쇠 '%s' 가 관리 메뉴에서 지금 표시를 딱 한 번 받는다", key)
                    .isEqualTo(1);
            assertThat(markedLinkHref(html))
                    .as("열쇠 '%s' 의 표시가 그 열쇠의 주소에 붙었다", key)
                    .isEqualTo("/admin/" + key);
        }
    }

    /**
     * 반대 방향도 막는다 — {@code parts.html} 에 링크를 <b>더하고</b> 허용 목록에 안 넣으면
     * 위 테스트는 통과한다(있는 열쇠는 다 닿으니까). 주소 집합을 통째로 대조해야 그게 잡힌다.
     */
    @Test
    void 산출물_메뉴의_주소_집합이_허용_열쇠_집합과_정확히_같다() throws Exception {
        String html = shell("산출물", "brd", PROJECT);

        var rendered = new java.util.TreeSet<String>();
        var m = java.util.regex.Pattern
                .compile("(?<![-\\w])href=\"(/projects/\\d+/artifacts/[^\"]*)\"").matcher(html);
        while (m.find()) {
            rendered.add(m.group(1));
        }

        var expected = new java.util.TreeSet<String>();
        ShellContract.ARTIFACT_MENU_KEYS
                .forEach(key -> expected.add("/projects/" + PROJECT_ID + "/artifacts/" + key));

        assertThat(rendered)
                .as("메뉴에 그려진 주소 = 허용 열쇠에서 나온 주소 (한 쪽만 고치면 여기가 빨개진다)")
                .isEqualTo(expected);
    }

    // ── 프로젝트 이름 ──────────────────────────────────────────────────────

    @Test
    void 상단에는_프로젝트_이름을_중복해서_표시하지_않는다() throws Exception {
        assertThat(shell("산출물", "brd", PROJECT))
                .doesNotContain("app-header__project");

        // 관리·카드에서도 상단 프로젝트 자리가 없는 것이 정상이다
        assertThat(shell("관리", "projects", null))
                .doesNotContain("app-header__project");
    }

    /**
     * ⚠ 2026-08-09 코덱스 적대검증이 짚은 자리다. {@code projectName} 은 조각 인자가 아니라
     * <b>모델에서 따로 읽는 값</b>이라 처음 만든 계약이 이것만 안 보고 있었다 —
     * 그래서 산출물 화면이 <b>프로젝트 이름 없이 조용히 뜨는 길</b>이 그대로 남아 있었다.
     * 계약이 막겠다고 한 실패 방식 바로 그것이다.
     */
    @Test
    void 프로젝트_안_화면인데_프로젝트_이름이_없으면_실패한다() {
        assertThatThrownBy(() -> shell("산출물", "brd", null))
                .hasStackTraceContaining("projectName");

        assertThatThrownBy(() -> shell("산출물", "brd", "   "))
                .as("빈 칸만 든 이름도 없는 것이다")
                .hasStackTraceContaining("projectName");

        assertThatThrownBy(() -> shell("꽉", null, null))
                .as("작업대도 프로젝트 안이다")
                .hasStackTraceContaining("projectName");
    }

    /**
     * ⚠ 프로젝트 이름과 같은 실패 방식이다 — 번호가 없으면 메뉴 링크가
     * {@code /projects//artifacts/brd} 로 조용히 나가서 <b>누르면 고르기로 튕기는 메뉴</b>가 된다.
     * 이름과 마찬가지로 렌더를 실패시킨다.
     */
    @Test
    void 프로젝트_안_화면인데_프로젝트_번호가_없으면_실패한다() {
        assertThatThrownBy(() -> shell("산출물", "brd", PROJECT, null))
                .hasStackTraceContaining("projectId");

        assertThatThrownBy(() -> shell("꽉", null, PROJECT, null))
                .as("작업대도 프로젝트 안이다")
                .hasStackTraceContaining("projectId");
    }

    @Test
    void 산출물_이름_열한_개가_열쇠와_정확히_짝이_맞고_메뉴에_그대로_그려진다() throws Exception {
        assertThat(ShellContract.ARTIFACT_NAMES.keySet())
                .as("이름표의 열쇠 = 허용 열쇠 (한 쪽만 고치면 여기가 빨개진다)")
                .containsExactlyElementsOf(ShellContract.ARTIFACT_KEYS);

        String html = shell("산출물", "brd", PROJECT, PROJECT_ID);
        ShellContract.ARTIFACT_MENU_KEYS.stream()
                .map(ShellContract.ARTIFACT_NAMES::get)
                .forEach(name -> assertThat(html)
                        .as("메뉴에 '%s' 가 그려진다", name)
                        .contains(">" + name + "</a>"));
        assertThat(ShellContract.ARTIFACT_MENU_KEYS)
                .containsSubsequence("frds", "srts", "dev-requests", "menu-tree");
        assertThat(html).containsSubsequence(">FRD 작업</a>", ">SRT</a>", ">개발요청서</a>", ">IA</a>");
    }

    // ── 계약을 어기면 조용히 넘어가지 않는다 ────────────────────────────────

    @Test
    void 모르는_모양은_렌더가_실패한다() {
        assertThatThrownBy(() -> shell("산출믈", "brd", PROJECT))
                .as("오타 난 모양이 '꽉' 으로 조용히 흐르면 안 된다")
                .hasStackTraceContaining("shape");
    }

    @Test
    void 모르는_지금은_렌더가_실패한다() {
        assertThatThrownBy(() -> shell("산출물", "brdd", PROJECT))
                .as("오타 난 열쇠가 '표시만 사라진 정상 화면' 으로 보이면 안 된다")
                .hasStackTraceContaining("current");
    }

    @Test
    void 메뉴가_없는_모양에_지금을_주면_실패한다() {
        assertThatThrownBy(() -> shell("카드", "brd", null))
                .as("메뉴가 없는데 메뉴 위치를 준 것은 부르는 쪽의 착각이다")
                .hasStackTraceContaining("current");
    }

    @Test
    void 산출물인데_관리_열쇠를_주면_실패한다() {
        assertThatThrownBy(() -> shell("산출물", "accounts", PROJECT))
                .hasStackTraceContaining("current");
    }

    /**
     * 위 두 테스트가 쓰는 검사 자체를 밟는다. 속성 이름에 경계를 안 붙이면
     * {@code data-href} · {@code data-aria-current} 가 그대로 걸려서,
     * <b>누를 수도 없고 현재 위치도 아닌 것</b>을 「맞다」고 읽는다 — 그러면 이 테스트들이
     * 지키는 것이 아무것도 없게 된다. (2026-08-09 코덱스 2회차)
     */
    @Test
    void 검사가_data_속성에_속지_않는다() {
        String fake = "<a data-href=\"/projects/7/artifacts/brd\" data-aria-current=\"page\">BRD</a>";
        assertThat(currentMarkCount(fake)).as("data-aria-current 는 현재 위치 표시가 아니다").isZero();
        assertThat(markedLinkHref(fake)).isEqualTo("표시받은 링크가 없다");

        String real = "<a href=\"/projects/7/artifacts/brd\" aria-current=\"page\">BRD</a>";
        assertThat(currentMarkCount(real)).isEqualTo(1);
        assertThat(markedLinkHref(real)).isEqualTo("/projects/7/artifacts/brd");

        String hrefless = "<a data-href=\"/projects/7/artifacts/brd\" aria-current=\"page\">BRD</a>";
        assertThat(markedLinkHref(hrefless))
                .as("표시는 진짜인데 주소가 data- 뿐이면 주소가 없는 것이다")
                .isEqualTo("그 링크에 href 가 없다");
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String shell(String shape, String current, String projectName) throws Exception {
        // 프로젝트 밖 모양(관리·카드)은 번호도 없다
        return shell(shape, current, projectName, projectName == null ? null : PROJECT_ID);
    }

    private String shell(String shape, String current, String projectName, String projectId)
            throws Exception {
        var request = get("/__shell-probe").param("shape", shape).with(user(superUser()));
        if (current != null) {
            request = request.param("current", current);
        }
        if (projectName != null) {
            request = request.param("projectName", projectName);
        }
        if (projectId != null) {
            request = request.param("projectId", String.valueOf(projectId));
        }
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /**
     * {@code aria-current="page"} 가 붙은 링크의 {@code href} 를 꺼낸다.
     * 표시가 <b>어느 링크에</b> 붙었는지를 봐야 열쇠와 주소가 갈라지는 것을 잡는다.
     */
    /*
     * ⚠ 속성 이름 앞에 경계를 붙인다. 안 붙이면 `data-href` · `data-aria-current` 가
     *    그대로 걸려서, 누를 수도 없고 현재 위치도 아닌 것을 「맞다」고 읽는다.
     *    (2026-08-09 코덱스 2회차. 이 저장소가 정규식 경계로 데인 것이 이번이 세 번째다.)
     */
    private static final java.util.regex.Pattern CURRENT_MARK =
            java.util.regex.Pattern.compile("(?<![-\\w])aria-current=\"page\"");
    private static final java.util.regex.Pattern MARKED_ANCHOR =
            java.util.regex.Pattern.compile("<a[^>]*(?<![-\\w])aria-current=\"page\"[^>]*>",
                    java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern HREF =
            java.util.regex.Pattern.compile("(?<![-\\w])href=\"([^\"]*)\"");

    public static String markedLinkHref(String html) {
        var m = MARKED_ANCHOR.matcher(html);
        if (!m.find()) {
            return "표시받은 링크가 없다";
        }
        var h = HREF.matcher(m.group());
        return h.find() ? h.group(1) : "그 링크에 href 가 없다";
    }

    public static int currentMarkCount(String html) {
        int count = 0;
        var m = CURRENT_MARK.matcher(html);
        while (m.find()) {
            count++;
        }
        return count;
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    /**
     * 시험에서만 뜨는 화면 하나. 껍데기 조각을 <b>임의의 인자로</b> 부르게 해 준다.
     * 운영 코드에는 이 경로가 없다.
     */
    @TestConfiguration
    @Controller
    static class Probe {

        @GetMapping("/__shell-probe")
        String render(@RequestParam String shape,
                      @RequestParam(required = false) String current,
                      @RequestParam(required = false) String projectName,
                      @RequestParam(required = false) String projectId,
                      Model model) {
            model.addAttribute("title", "프로브");
            model.addAttribute("shape", shape);
            model.addAttribute("current", current);
            model.addAttribute("projectName", projectName);
            model.addAttribute("projectId", projectId);
            return "shellprobe";
        }
    }
}
