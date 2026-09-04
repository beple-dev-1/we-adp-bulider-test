package com.bizplay.builder.solution;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 솔루션 목업 화면 둘 — 목록({@code 08})과 상세({@code 08a}).
 *
 * <p>여기가 초록이면 <b>빌더가 기획 저장소를 읽는 길이 섰다</b>는 뜻이다. 지금까지 화면은 전부
 * 빌더 DB 만 봤다 — 메뉴구조도·BRD 의 대상 화면·작업 목업이 뒤에 같은 길을 쓴다.
 *
 * <p>⭐ <b>이 시험의 심장은 「자료가 셋에서 온다」이다</b> — 색인(화면ID·시스템·종류) ·
 * 화면 md(화면명·메뉴 경로) · {@code git log}(수정 이력). 셋 중 하나만 빠져도 화면이 반쯤 빈다.
 *
 * <p>정본: 목업 {@code docs/mockups/08-solution-mockups.html}·{@code 08a-solution-mockup-detail.html}.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class SolutionMockupScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;
    @Autowired GitCommand git;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired ScreenStandardIdMapper standardIds;
    @Autowired ProjectSystemService projectSystems;
    @Autowired SolutionMockupService solutions;

    // ── 클론이 없을 때 ────────────────────────────────────────────────────

    /**
     * ⛔ <b>클론이 없다고 500 을 내면 안 된다.</b> {@code ArtifactListTest} 가 열쇠 열넷을 도는데
     * 그 프로젝트에는 클론이 없다 — 여기가 빨개지면 그쪽도 같이 빨개진다. 그리고 사람에게는
     * 「메뉴를 눌렀을 뿐인데 화면이 깨진」 것으로 보이고 할 수 있는 일이 없다.
     */
    @Test
    void 클론이_없어도_목록이_빈_상태로_뜬다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = list(p.getId());

        assertThat(html).contains("<title>솔루션 템플릿 · 빌더</title>");
        assertThat(com.bizplay.builder.shell.ShellContractTest.markedLinkHref(html))
                .as("껍데기가 메뉴를 본문보다 먼저 그리므로 첫 표시는 메뉴의 것이다")
                .isEqualTo("/projects/" + p.getId() + "/artifacts/solution-mockups");
        assertThat(html).contains("조회된 내용이 없습니다.");
    }

    // ── 목록 ──────────────────────────────────────────────────────────────

    @Test
    void 목록이_화면관리번호와_화면_ID와_화면_정보를_낸다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        seedStandardId(p.getId());

        String html = list(p.getId());

        assertThat(html)
                .as("화면 ID는 색인에서, 화면명은 화면 md 에서 온다")
                .contains("bo-sample-list")
                .contains("선불카드 관리 (목록)")
                .contains("백오피스")
                .contains("웹뷰");
        assertThat(html)
                .as("화면명을 맨 앞에 두고 화면관리번호와 화면 ID를 이어 표시한다")
                .containsSubsequence(">화면명</th>", ">화면관리번호</th>", ">화면 ID</th>")
                .contains("PS-BO-MRC-010-L01-S");
        assertThat(countOf(html, ">bo-sample-pop</td>"))
                .as("화면관리번호가 없는 화면은 화면관리번호와 화면 ID 칸에 화면 ID가 각각 표시된다")
                .isEqualTo(2);
        assertThat(html)
                .as("긴 화면명은 말줄임표로 표시하고 전체 이름은 제목 속성으로 확인한다")
                .contains("artifact-list-link solution-screen-name", "title=\"선불카드 관리 (목록)\"");
        assertThat(html)
                .as("적용 구분이 있으면 기본 검색 배치를 유지한다")
                .doesNotContain("filter-bar--solution-compact");
        assertThat(html)
                .as("검색 영역은 시스템, 적용 구분, 화면 검색, 조회 순서다")
                .containsSubsequence("id=\"solution-system\"",
                        "id=\"solution-search\"", ">조회</button>")
                .doesNotContain("solution-menu", "상위 메뉴", "화면 조회");
        assertThat(html)
                .as("조회와 쪽 이동과 목록 크기 변경은 결과 영역 로딩 상태를 쓴다")
                .contains("data-list-loading-region", "data-list-loading-overlay",
                        "aria-label=\"조회 결과 처리 중\"", "data-list-loading-trigger",
                        "data-list-loading-links", "data-list-loading-page-size");
        assertThat(html)
                .as("목록에는 메뉴구조도 연결 정보를 표시하지 않는다")
                .doesNotContain("메뉴구조도 연결", "IA 연결", "DB 직접 연결 없음",
                        "연결 편집 중", "메뉴구조도 연결 없음", "실물과 다름 0");
        assertThat(html)
                .as("종류는 색인의 ia.종류 다 — 팝업도 목록에 뜬다")
                .contains("팝업");
        assertThat(html)
                .as("⛔ SOL-* 꼴 자기 이름을 짓지 않는다 (artifacts.md · 2026-08-13 확정)")
                .doesNotContain("SOL-");
        assertThat(html)
                .as("솔루션 목업 버전은 메뉴구조도와 별개인 열로 표시한다")
                .contains(">버전</th>", "solution-summary-strip")
                .doesNotContain("현재 버전");
        assertThat(html)
                .as("목록 표는 페이지 스크롤을 쓰고 내부 세로 스크롤을 만들지 않는다")
                .contains("table-wrap solution-mockup-table-wrap")
                .doesNotContain("table-wrap table-wrap--tall");
    }

    @Test
    void 검색과_거르개가_실제로_거른다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        seedStandardId(p.getId());

        assertThat(listWith(p.getId(), "query=wv-sample"))
                .contains("메인 홈")
                .doesNotContain("선불카드 관리 (목록)");

        assertThat(listWith(p.getId(), "query=PS-BO-MRC-010-L01-S"))
                .as("사람이 표준 ID를 그대로 입력해 화면을 찾을 수 있다")
                .contains("선불카드 관리 (목록)")
                .doesNotContain("메인 홈");

        assertThat(listWith(p.getId(), "system=웹뷰"))
                .contains("메인 홈")
                .doesNotContain("카드상태 확인 팝업");

        assertThat(listWith(p.getId(), "query=그런화면없다"))
                .as("아무것도 안 걸리면 목록 공통 빈 결과를 낸다")
                .contains("조회된 내용이 없습니다.");
    }

    /**
     * ⚠ <b>한 쪽에 안 들어가는 수를 깔아야 잰다.</b> 씨앗 셋으로는 늘 한 쪽이라
     * 「쪽 이동이 있다」만 보고 「나눈다」를 못 본다.
     */
    @Test
    void 쪽_이동이_실제로_나눈다() throws Exception {
        Project p = readyProject("탐나는전");
        seedManyScreens(p.getId(), 25);

        String first = listWith(p.getId(), "page=1");
        assertThat(first)
                .as("적용 구분이 없으면 빈 필터 칸을 남기지 않는 배치를 쓴다")
                .contains("bo-many-001")
                .doesNotContain("bo-many-025", "filter-bar--solution-compact");

        String third = listWith(p.getId(), "page=3");
        assertThat(third).contains("bo-many-025").doesNotContain("bo-many-001");

        // ⚠ 10·20·50·100 만 받는다. 그 밖의 값은 첫 값으로 되돌아간다 — 주소로 아무 크기나 못 만든다.
        assertThat(listWith(p.getId(), "pageSize=7&page=1"))
                .doesNotContain("bo-many-025");
        // ⚠ 범위 밖 쪽은 마지막 쪽으로 붙인다 — 빈 표를 내지 않는다.
        assertThat(listWith(p.getId(), "page=99"))
                .contains("bo-many-025");
    }

    // ── 상세 ──────────────────────────────────────────────────────────────

    @Test
    void 상세가_화면_정보와_미리보기_자리를_낸다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        seedStandardId(p.getId());

        String html = detail(p.getId(), "bo-sample-list");

        assertThat(html).contains("<title>선불카드 관리 (목록) · 빌더</title>");
        assertThat(html).contains("bo-sample-list · 백오피스");
        assertThat(html)
                .contains(">화면관리번호</dt>", "PS-BO-MRC-010-L01-S",
                        ">화면 ID</dt>", ">최초 작성일</dt>", ">최종 수정</dt>")
                .doesNotContain(">종류</dt>", ">적용 구분</dt>", ">IA 연결</dt>");
        assertThat(html)
                .as("미리보기는 레포 배치와 같은 주소를 가리킨다 — 그래야 html 속 상대 경로가 맞는다")
                .contains("/artifacts/solution-mockups/files/backoffice/pages/bo-sample-list.html");
        assertThat(html)
                .as("⛔ sandbox 를 빼지 마라 — 추출 html 에 남의 스크립트가 남아 있다")
                .contains("sandbox");
        assertThat(html)
                .as("캔버스에서 같은 화면의 md 기능정의서를 바로 확인한다")
                .contains("id=\"open-solution-document\"", "aria-label=\"기능정의서 보기\"",
                        "id=\"solution-document-dialog\"", "시험용 씨앗이다.");
        assertThat(html)
                .as("화면 md 좌표를 운영 화면 위 기능 위치 표시에 쓴다")
                .contains("id=\"toggle-solution-features\"", "aria-label=\"기능 위치 표시\"",
                        "data-locator=\"id=sample-action\"", "data-anchor=\"bo-sample-list-e01\"",
                        "data-label=\"조회\"", "[data-element-id]",
                        "id=\"solution-feature-panel\"", "기능 설명", "현재 숨김",
                        "selectFeature", "scrollIntoView", "is-selected")
                .doesNotContain("solution-feature-status", "builder-feature-card");
        assertThat(html)
                .as("새 창 열기는 글자 버튼이 아니라 보조 아이콘 기능이다")
                .contains("class=\"icon-button preview-open-window\"", "aria-label=\"새 창으로 열기\"")
                .doesNotContain(">새 창으로 열기</a>");
    }

    /**
     * ⭐ <b>울타리 한 글자에 화면이 걸려 있다.</b> {@code allow-same-origin} 이 없으면 안의
     * 문서가 출처 없는 문서가 되어 곁딸린 요청에 {@code JSESSIONID} 가 안 붙고, 곁의 css 가
     * {@code 302 → /login} 으로 되튕겨 <b>뼈대만 뜬 화면</b>이 된다(2026-08-17 실측).
     *
     * <p>⛔ 그렇다고 {@code allow-scripts} 를 더하면 안의 문서가 울타리를 스스로 걷어낸다 —
     * 둘이 <b>같이</b> 있는 것이 위험한 것이고, 지금 이 자리는 그 하나가 없어서 안전하다.
     */
    @Test
    void 미리보기_울타리가_css_는_들이고_스크립트는_막는다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String policy = mvc.perform(get(base(p.getId()) + "/files/backoffice/pages/bo-sample-list.html")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(policy)
                .as("새 창으로 열 때는 이 헤더 하나가 울타리다 — iframe 이 그때 없다")
                .isEqualTo("sandbox allow-same-origin");

        String html = detail(p.getId(), "bo-sample-list");
        assertThat(html)
                .as("화면의 울타리와 헤더의 울타리가 같은 글자여야 한다")
                .contains("sandbox=\"allow-same-origin\"");

        // ⛔ 이 둘이 같이 있으면 남의 스크립트가 우리 자격으로 돈다. 헤더에도 화면에도 없어야 한다.
        assertThat(policy).doesNotContain("allow-scripts");
        assertThat(html).doesNotContain("allow-scripts");
    }

    @Test
    void 상세에_보정과_실물과_다름을_표현하지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        assertThat(detail(p.getId(), "bo-sample-list"))
                .doesNotContain("보정하기", "실물과 다름");
        assertThat(countOf(detail(p.getId(), "bo-sample-list"),
                "artifacts/solution-mockups/bo-sample-list/"))
                .as("상세는 이 화면에 대한 변경 요청을 보내지 않는다")
                .isZero();
    }

    @Test
    void 없는_화면ID_는_404_다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        mvc.perform(get(base(p.getId()) + "/그런화면없다").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    /**
     * ⚠ 위 시험(「그런화면없다」)과 다르다 — 저건 <b>한 번도 없던 ID</b>고, 이건
     * <b>색인에 있다가 IA 개정으로 사라진 키</b>다. 화면 md·html 파일은 클론에 그대로 남는다
     * (2026-08-27 개정 문서의 「표준화면ID 옛 발번은 죽은 채 남는다」와 같은 모양) — 색인
     * ({@code index.json} 의 {@code screens}) 에서만 키가 빠진다.
     *
     * <p>⚠ <b>MockMvc 는 오류 디스패치를 안 탄다</b>(2026-09-04 실측, {@code ErrorScreenTest}) —
     * 이 경로(컨트롤러가 {@code ResponseStatusException} 을 던지는 실제 요청)의 응답 본문은
     * 비어 있다. 그래서 여기서는 <b>상태 404 만</b> 단정한다. 오류 화면 본문에
     * {@code Whitelabel} 이 없는지는 {@code ErrorScreenTest} 가 맡는다 — 빈 본문을 그 증거로
     * 쓰지 않는다.
     */
    @Test
    void 색인에서_사라진_화면키를_열면_404_이고_흰_오류판이_아니다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        assertThat(Files.exists(clone(p.getId()).resolve("core/backoffice/pages/bo-sample-list.md")))
                .as("색인에서만 빠진다 — 파일은 죽은 채 남는다")
                .isTrue();

        dropFromIndex(p.getId(), "bo-sample-list");

        mvc.perform(get(base(p.getId()) + "/bo-sample-list").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    /** ⚠ 클론이 프로젝트마다 따로라 남의 프로젝트 화면은 주소를 알아도 안 열린다. */
    @Test
    void 남의_프로젝트_화면은_주소를_알아도_안_열린다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("전자세금계산서");
        seedClone(other.getId());

        mvc.perform(get(base(mine.getId()) + "/bo-sample-list").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    // ── 갈래 화면 ─────────────────────────────────────────────────────────

    /**
     * ⭐ <b>갈래 화면에는 기저 {@code pages/<ID>.html} 이 없다</b> — 있으면 기획 저장소의
     * 검사기가 {@code A-1} red 를 낸다. 그래서 기저를 열려고 하면 <b>항상</b> 실패한다.
     */
    @Test
    void 갈래_화면은_기관_둘을_내고_기저_html_없이도_안_깨진다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(p.getId(), "jeju", "제주"));
        seedClone(p.getId());

        String html = detail(p.getId(), "wv-sample-home");

        assertThat(html).contains("익산").contains("제주").doesNotContain("연결 필요");
        assertThat(html)
                .as("기저가 아니라 기관 하나를 골라 연다")
                .contains("/files/webview/variants-iksan/wv-sample-home.html")
                .doesNotContain("/files/webview/pages/wv-sample-home.html");

        String jeju = mvc.perform(get(base(p.getId()) + "/wv-sample-home?variant=jeju")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(jeju).contains("/files/webview/variants-jeju/wv-sample-home.html");
    }

    @Test
    void 기관_전용_화면은_적용_구분_이름으로_표시하고_거른다() throws Exception {
        Project p = readyProject("탐나는전 기관 구분");
        projectFacets.insert(ProjectFacet.create(p.getId(), "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(p.getId(), "jeju", "제주"));
        seedClone(p.getId());

        String all = list(p.getId());
        assertThat(all)
                .contains("적용 구분", "익산, 제주", ">전체</span>")
                .doesNotContain("전체 적용", "기관 갈래", "기관별 화면");

        String jeju = listWithFacet(p.getId(), "제주");
        assertThat(jeju).contains("카드상태 확인 팝업").contains("선불카드 관리 (목록)");

        String iksan = listWithFacet(p.getId(), "익산");
        assertThat(iksan).doesNotContain("카드상태 확인 팝업").contains("선불카드 관리 (목록)");
    }

    // ── 미리보기 ──────────────────────────────────────────────────────────

    @Test
    void 미리보기가_추출된_html_과_곁의_css_를_그대로_준다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        var page = mvc.perform(get(base(p.getId()) + "/files/backoffice/pages/bo-sample-list.html")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(page.getContentAsString(StandardCharsets.UTF_8))
                .contains("선불카드 목록 운영화면");
        assertThat(page.getContentType()).startsWith("text/html");
        assertThat(page.getHeader("Content-Security-Policy"))
                .as("⛔ 새 창으로 열어도 남의 스크립트가 안 돌아야 한다 — iframe sandbox 는 그때 없다")
                .isEqualTo("sandbox allow-same-origin");

        assertThat(mvc.perform(get(base(p.getId()) + "/files/backoffice/assets/css/style.css")
                        .with(user(superUser())))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("html 이 ../assets/css/style.css 를 상대 경로로 부른다 — 그 자리가 열려야 화면이 산다")
                .contains("운영화면-스타일");
    }

    /**
     * ⭐ <b>미리보기는 iframe 안에서만 쓸모가 있다.</b> 스프링 시큐리티가 기본으로
     * {@code X-Frame-Options: DENY} 를 모든 응답에 붙이는데, 그러면 상세의 미리보기 칸이
     * <b>통째로 빈칸</b>이 된다 — 서버는 200 을 내는데 브라우저가 안 그린다.
     * 200 만 재는 시험은 이것을 못 잡는다.
     */
    @Test
    void 미리보기는_같은_출처_iframe_에_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        var preview = mvc.perform(get(base(p.getId()) + "/files/backoffice/pages/bo-sample-list.html")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(preview.getHeader("X-Frame-Options"))
                .as("⛔ DENY 가 붙으면 미리보기 칸이 빈다 — 우리 화면에 우리 파일을 끼우는 자리다")
                .isEqualTo("SAMEORIGIN");

        var detail = mvc.perform(get(base(p.getId()) + "/bo-sample-list").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(detail.getHeader("X-Frame-Options"))
                .as("⛔ 미리보기 말고는 그대로 막는다 — 느슨해진 자리를 미리보기 하나로 좁혀 둔다")
                .isEqualTo("DENY");
    }

    /** ⛔ 화면 명세(md)는 미리보기가 아니다. 저장소 통째로가 정적 서버가 되면 안 된다. */
    @Test
    void 흰_목록에_없는_확장자는_안_준다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        mvc.perform(get(base(p.getId()) + "/files/backoffice/pages/bo-sample-list.md")
                        .with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 클론_밖을_가리키는_미리보기는_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        // ⚠ 앞단(보안 방화벽)이 먼저 막을 수도 있고 우리 울타리가 막을 수도 있다 —
        //   어느 쪽이든 "안 준다"가 지켜야 할 것이다. 우리 울타리를 지우면 앞단만 남는데,
        //   앞단은 스프링 설정 하나로 느슨해질 수 있어 그것 하나에 기대지 않는다.
        mvc.perform(get(base(p.getId()) + "/files/backoffice/../../index.json")
                        .with(user(superUser())))
                .andExpect(status().is4xxClientError());
        mvc.perform(get(base(p.getId()) + "/files/backoffice/%2e%2e/%2e%2e/index.json")
                        .with(user(superUser())))
                .andExpect(status().is4xxClientError());
        // ⭐ 클론 밖의 파일은 흰 목록에 든 확장자여도 안 준다.
        write(clone(p.getId()).resolve("바깥.css"), "/* 클론 뿌리에 있다 */");
        mvc.perform(get(base(p.getId()) + "/files/backoffice/../../바깥.css")
                        .with(user(superUser())))
                .andExpect(status().is4xxClientError());
    }

    // ── 실물과 다름 ───────────────────────────────────────────────────────

    /**
     * ⭐ 문 셋 중 <b>이것만 지금 실제로 돈다</b> — 설계(2026-08-14)가 이 문에만
     * 보정 권한도 Claude 자격도 요구하지 않기로 정했다.
     */
    @Test
    void 실물과_다름이_남고_목록에만_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        mvc.perform(post(base(p.getId()) + "/bo-sample-list/mismatch")
                        .param("reason", "카드상태 버튼 5개가 동시에 뜬다. 실제론 하나씩")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(detail(p.getId(), "bo-sample-list"))
                .doesNotContain("카드상태 버튼 5개가 동시에 뜬다. 실제론 하나씩", "실물과 다름");
        assertThat(list(p.getId()))
                .as("목록에서 바로 읽혀야 한다 — 상세로 들어가야 알 수 있으면 아무도 안 본다")
                .contains("⚑ 실물과 다름 1");
    }

    @Test
    void 사유가_비면_아무것도_안_남긴다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        mvc.perform(post(base(p.getId()) + "/bo-sample-list/mismatch")
                        .param("reason", "   ")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(list(p.getId()))
                .doesNotContain("⚑ 실물과 다름");
    }

    // ── 수정 이력 ─────────────────────────────────────────────────────────

    /** 화면별 Git 변경 순서가 솔루션 목업 버전과 수정 이력의 근거다. */
    @Test
    void 수정_이력이_클론의_git_log_에서_나온다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        commitClone(p.getId(), "백오피스 화면을 처음 올린다");
        Files.writeString(clone(p.getId()).resolve("core/backoffice/pages/bo-sample-list.md"),
                markdown("bo-sample-list", "backoffice", "선불카드 관리 > 목록", "선불카드 관리 (목록)")
                        + "\n추가 설명 한 줄.\n", StandardCharsets.UTF_8);
        commitClone(p.getId(), "목록 화면 명세를 손본다");

        String html = detail(p.getId(), "bo-sample-list");
        String listHtml = list(p.getId());

        assertThat(html)
                .contains("목록 화면 명세를 손본다")
                .contains("백오피스 화면을 처음 올린다")
                .contains("검사원");
        assertThat(html)
                .as("상세 화면은 버전 열과 별개로 Git 수정 이력을 보여 준다")
                .contains("수정 이력")
                .doesNotContain("버전 이력");
        assertThat(listHtml)
                .as("같은 화면을 건드린 Git 커밋 두 건을 v2로 표시한다")
                .contains(">버전</th>", ">v2</td>");
    }

    /**
     * 미리보기 실물이 없는 화면 — <b>스프링 오류 페이지 대신 빈 상태</b>를 그린다 (과업 002).
     *
     * <p>⭐ <b>실물이 없는 것은 결함이 아니라 정상 상태다.</b> 화면을 소스에서 뽑아 기획 저장소에
     * 넣는 것은 기획 세션 몫이고, 빌더는 들어온 것을 띄우기만 한다. 2026-09-04 실물에서
     * 색인 449장 중 실물이 있는 것이 14장이었다 — <b>대부분이 이 갈래를 지난다.</b>
     *
     * <p>⛔ 그때 {@code <iframe>} 을 그대로 그리면 그 안이 404 를 받아 <b>Whitelabel Error Page</b>
     * 가 미리보기 칸을 채운다. 기획자에게 영어 스택 화면이 보인다.
     */
    @Test
    void 미리보기_실물이_없으면_오류_페이지_대신_빈_상태를_그린다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = detail(p.getId(), "bo-sample-gone");

        assertThat(html)
                .as("⛔ 영어 스택 화면을 기획자에게 보이지 않는다")
                .doesNotContain("Whitelabel Error Page");
        assertThat(html)
                .as("빈 상태는 옆 선례(기능정의서 없음)와 같은 뼈대·말투다")
                .contains("empty-state solution-preview-empty",
                        "현재 운영 화면이 아직 없습니다",
                        "이 화면의 운영 화면 파일(html)을 기획 저장소에서 찾지 못했습니다."
                                + " 기획 저장소 갱신 상태를 확인해 주세요.");
        assertThat(html)
                .as("끼울 것이 없으면 iframe 을 아예 안 그린다 — 그려야 404 가 안 들어온다")
                .doesNotContain("class=\"preview-frame\"");
        assertThat(html)
                .as("눌러도 404 로 가는 단추를 남기지 않는다 — 새 창 열기와 확대·축소 둘 다")
                .doesNotContain("preview-open-window", "id=\"zoom-in\"");
    }

    /**
     * 실물이 있는 화면은 <b>손대기 전과 똑같이</b> 뜬다 — 과업 002 의 회귀 경계.
     *
     * <p>⚠ 이 시험이 깨지면 002 가 「없을 때」를 고치다 「있을 때」를 함께 바꾼 것이다.
     */
    @Test
    void 미리보기_실물이_있으면_iframe_과_보조_단추가_그대로다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = detail(p.getId(), "bo-sample-list");

        assertThat(html).contains("class=\"preview-frame\"", "sandbox=\"allow-same-origin\"",
                        "preview-open-window", "id=\"zoom-in\"", "id=\"zoom-out\"", "id=\"zoom-reset\"")
                .doesNotContain("solution-preview-empty", "현재 운영 화면이 아직 없습니다");
    }

    /**
     * 목록은 <b>있는 쪽</b>에 표시를 단다.
     *
     * <p>⭐ 실물이 449 중 14장뿐이라(2026-09-04 실측) 「없음」을 달면 435줄이 표시로 덮인다.
     * 사람이 찾는 것은 <b>열리는 화면</b>이다.
     */
    @Test
    void 목록은_미리보기가_있는_줄에만_표시를_단다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = list(p.getId());

        assertThat(html).contains("미리보기 있음");
        assertThat(countOf(html, "미리보기 있음"))
                .as("씨앗 넷 중 실물이 있는 셋에만 붙는다 — 갈래 화면은 기관 파일이 실물이다")
                .isEqualTo(3);
    }

    /**
     * 존재 확인의 울타리 — <b>클론 밖은 있어도 없는 것</b>이다.
     *
     * <p>⛔ {@code Files.exists()} 를 단독으로 부르면 여기가 뚫린다. {@code system}·{@code screenId}
     * 는 <b>남의 저장소</b>인 기획 레포 색인에서 온 검증 안 된 글자다 — {@code ../} 가 섞이면
     * 클론 밖을 가리키고, 그러면 「그 경로에 파일이 있나」를 밖으로 흘리는 문이 된다.
     */
    @Test
    void 존재_확인은_클론_밖을_가리키는_경로를_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        Path outside = clone(p.getId()).getParent().resolve("클론밖.html");
        write(outside, "<html>밖에 있는 파일</html>");
        SolutionScreen escaping = screenOf("../../클론밖", "backoffice");

        assertThat(Files.isRegularFile(outside))
                .as("먼저 그 파일이 진짜로 있는 것을 확인한다 — 없으면 이 시험이 아무것도 안 잰다")
                .isTrue();
        assertThat(solutions.previewExists(p.getId(), escaping, null))
                .as("깊은 문(상세)이 거절한다")
                .isFalse();
        assertThat(solutions.hasPreview(p.getId(), escaping))
                .as("얕은 문(목록)도 같이 거절한다 — 여기만 뚫리면 「있음」이 오라클이 된다")
                .isFalse();
    }

    /**
     * ⛔ <b>정규화만으로는 심볼릭 링크를 못 막는다.</b> 클론 안의 링크가 밖을 가리키면
     * 글자로는 울타리 안인데 <b>가리키는 것은 밖</b>이다. 클론은 우리가 만든 것이 아니라 남의
     * 저장소라 이 길이 실재한다.
     *
     * <p>⚠ 상세(깊은 문)만 {@code toRealPath} 로 이것을 잰다. 목록(얕은 문)이 안 재는 것은
     * <b>정한 것</b>이다 — 최대 100장마다 실제 경로를 펴면 파일 계통 호출이 곱절로 나고,
     * 목록이 내는 것은 「있음」 표시 하나라 밖의 내용이 안 샌다.
     */
    @Test
    void 깊은_문은_클론_밖을_가리키는_심볼릭_링크를_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        Path outside = clone(p.getId()).getParent().resolve("링크대상.html");
        write(outside, "<html>밖에 있는 파일</html>");
        Path link = clone(p.getId()).resolve("core/backoffice/pages/bo-link.html");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException notAllowed) {
            // ⚠ 윈도우는 개발자 모드나 관리자 자격이 없으면 링크를 못 만든다. 그때는 잴 것이 없다.
            org.junit.jupiter.api.Assumptions.abort("이 기계에서 심볼릭 링크를 못 만든다: " + notAllowed);
            return;
        }

        assertThat(Files.isRegularFile(link))
                .as("글자로는 클론 안이고 실제로 읽히기까지 한다 — 그래서 normalize 만으로는 안 걸린다")
                .isTrue();
        assertThat(solutions.previewExists(p.getId(), screenOf("bo-link", "backoffice"), null))
                .as("실경로로 다시 재서 거절한다")
                .isFalse();
    }

    /** 존재 확인만 재는 데 필요한 최소 화면 하나. 색인을 거치지 않고 곧장 문에 넣는다. */
    private SolutionScreen screenOf(String screenId, String system) {
        return new SolutionScreen(screenId, screenId, system, "화면", "목록", "ID", "", "",
                null, null, java.util.List.of(), java.util.List.of(), null, java.util.List.of(),
                false, ScreenHistory.EMPTY);
    }

    // ── 씨앗 ──────────────────────────────────────────────────────────────

    /**
     * 진짜 클론을 흉내 낸 것 — 색인 하나 · 화면 넷(백오피스 셋 · 웹뷰 갈래 하나) · 곁의 css 하나.
     *
     * <p>⚠ 갈래 화면에는 {@code pages/<ID>.html} 을 <b>일부러 안 만든다.</b> 실물이 그렇다.
     *
     * <p>⚠ {@code bo-sample-gone} 도 <b>일부러 html 이 없다</b> — 화면 md 만 있고 운영 화면은
     * 아직 안 뽑힌 것이다. 실물 클론에서 이쪽이 다수다(2026-09-04 실측 · 449 중 435).
     */
    private void seedClone(String projectId) throws IOException {
        Path core = clone(projectId).resolve("core");

        write(clone(projectId).resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    "bo-sample-list":  {"system": "backoffice", "ia": {"경로": "sample/list", "종류": "화면"}},
                    "bo-sample-pop":   {"system": "backoffice", "ia": {"경로": "sample/list", "종류": "팝업"}},
                    "wv-sample-home":  {"system": "webview",    "ia": {"경로": "home", "종류": "화면"}},
                    "bo-sample-gone":  {"system": "backoffice", "ia": {"경로": "sample/gone", "종류": "화면"}}
                  },
                  "facetIndex": {"jeju": ["bo-sample-pop"]},
                  "variantIndex": {"iksan": ["wv-sample-home"], "jeju": ["wv-sample-home"]},
                  "counts": {"screens": 4}
                }
                """);

        write(core.resolve("backoffice/pages/bo-sample-list.md"),
                markdown("bo-sample-list", "backoffice", "선불카드 관리 > 목록", "선불카드 관리 (목록)"));
        write(core.resolve("backoffice/pages/bo-sample-pop.md"),
                markdown("bo-sample-pop", "backoffice", "선불카드 관리 > 상세 > 확인팝업", "카드상태 확인 팝업"));
        write(core.resolve("webview/pages/wv-sample-home.md"),
                markdown("wv-sample-home", "webview", "홈 > 메인", "메인 홈"));
        // ⛔ 짝이 되는 html 을 만들지 마라 — 「운영 화면이 아직 없는 화면」이 이 씨앗의 몫이다.
        write(core.resolve("backoffice/pages/bo-sample-gone.md"),
                markdown("bo-sample-gone", "backoffice", "선불카드 관리 > 아직 없는 화면", "아직 안 뽑힌 화면"));

        write(core.resolve("backoffice/pages/bo-sample-list.html"),
                page("선불카드 목록 운영화면"));
        write(core.resolve("backoffice/pages/bo-sample-pop.html"), page("확인 팝업 운영화면"));
        write(core.resolve("webview/variants-iksan/wv-sample-home.html"), page("익산 메인 홈"));
        write(core.resolve("webview/variants-jeju/wv-sample-home.html"), page("제주 메인 홈"));
        write(core.resolve("backoffice/assets/css/style.css"), "/* 운영화면-스타일 */ body{margin:0}");

        // ⚠ 시스템 한글 이름은 프로젝트 등록 자료에서 온다(2026-08-21) — 코드에 박힌 표가 아니다.
        //   실물에서는 클론이 manifest.json 을 읽어 앉히고 관리자가 이름을 넣는다. 여기서도 같은 길을 탄다.
        write(clone(projectId).resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":["
                        + "{\"id\":\"backoffice\",\"prefix\":\"bo\"},"
                        + "{\"id\":\"webview\",\"prefix\":\"wv\"}]}");
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId,
                new java.util.LinkedHashMap<>(java.util.Map.of("backoffice", "백오피스", "webview", "웹뷰")));
    }

    /**
     * IA 개정 흉내 — {@code seedClone} 이 심은 넷 중 {@code screenId} 하나만 색인에서 뺀다.
     * 화면 파일(md·html)은 건드리지 않는다. 지금은 {@code bo-sample-list} 만 지원한다.
     */
    private void dropFromIndex(String projectId, String screenId) throws IOException {
        if (!"bo-sample-list".equals(screenId)) {
            throw new IllegalArgumentException("이 도우미는 bo-sample-list 만 뺄 수 있다: " + screenId);
        }
        // ⛔ 아래는 색인을 통째로 덮어쓴다. seedClone 에 화면이 늘면 이 사본이 그것까지 조용히
        //    되돌리고 시험은 그대로 초록이 된다 — 그래서 덮기 전에 씨앗과 대조해 시끄럽게 깬다.
        String seeded = Files.readString(clone(projectId).resolve("index.json"), StandardCharsets.UTF_8);
        assertThat(seeded)
                .as("씨앗 색인이 바뀌었다 — dropFromIndex 의 사본을 함께 고쳐라")
                .contains("\"bo-sample-list\"", "\"bo-sample-pop\"", "\"wv-sample-home\"", "\"bo-sample-gone\"");
        assertThat(seeded.split("\"system\"", -1).length - 1)
                .as("씨앗 색인에 화면이 늘었다 — dropFromIndex 의 사본을 함께 고쳐라")
                .isEqualTo(4);

        write(clone(projectId).resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    "bo-sample-pop":   {"system": "backoffice", "ia": {"경로": "sample/list", "종류": "팝업"}},
                    "wv-sample-home":  {"system": "webview",    "ia": {"경로": "home", "종류": "화면"}},
                    "bo-sample-gone":  {"system": "backoffice", "ia": {"경로": "sample/gone", "종류": "화면"}}
                  },
                  "facetIndex": {"jeju": ["bo-sample-pop"]},
                  "variantIndex": {"iksan": ["wv-sample-home"], "jeju": ["wv-sample-home"]},
                  "counts": {"screens": 3}
                }
                """);
    }

    /** 한 쪽(20줄)에 안 들어가는 수를 깐다. 쪽 이동을 재려면 이것이 있어야 한다. */
    private void seedManyScreens(String projectId, int howMany) throws IOException {
        Path core = clone(projectId).resolve("core");
        StringBuilder screens = new StringBuilder();
        for (int number = 1; number <= howMany; number++) {
            String id = "bo-many-%03d".formatted(number);
            if (number > 1) {
                screens.append(",\n    ");
            }
            screens.append("\"%s\": {\"system\": \"backoffice\", \"ia\": {\"종류\": \"화면\"}}".formatted(id));
            write(core.resolve("backoffice/pages/" + id + ".md"),
                    markdown(id, "backoffice", "많은 메뉴 > 목록", "많은 화면 " + number));
            write(core.resolve("backoffice/pages/" + id + ".html"), page("많은 화면 " + number));
        }
        write(clone(projectId).resolve("index.json"),
                "{\n  \"schema\": \"we-adk-index/3\",\n  \"screens\": {\n    %s\n  }\n}\n"
                        .formatted(screens));
    }

    private void seedStandardId(String projectId) {
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                projectId, "bo-sample-list", "PS-BO-MRC-010-L01", ScreenStandardId.Origin.S, 1));
    }

    /** 겹치지 않는 조각이 몇 번 나오나. 「자리가 하나뿐이다」를 재는 데 쓴다. */
    private int countOf(String html, String needle) {
        int found = 0;
        for (int at = html.indexOf(needle); at >= 0; at = html.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    /** 실물 화면 md 의 머리 두 블록. 우리가 읽는 값은 여기 다 있다. */
    private String markdown(String id, String system, String menuPath, String screenName) {
        return """
                --- 꼬리표 ---
                id: %s / system: %s / 기능: %s / 과업: []

                --- 화면명세 ---
                화면명: %s
                목적: 시험용 씨앗이다.

                --- 정의 ---
                - 구분: 기능 / 좌표: id=sample-action / 라벨: 조회 / 앵커: %s-e01 / 해설: 조회 조건에 맞는 결과를 표시한다.
                """.formatted(id, system, menuPath, screenName, id);
    }

    private String page(String marker) {
        return """
                <!DOCTYPE html>
                <html lang="ko"><head><meta charset="utf-8">
                <link rel="stylesheet" href="../assets/css/style.css">
                </head><body><h1>%s</h1><button id="sample-action" type="button">조회</button><script>console.log('남의 스크립트')</script></body></html>
                """.formatted(marker);
    }

    /**
     * 씨앗 클론을 진짜 git 저장소로 만들고 한 판 커밋한다.
     *
     * <p>⚠ 사람 이름을 {@code -c} 로 준다 — 기계의 전역 git 설정에 기대면 그것이 없는 기계에서
     * 커밋이 통째로 실패한다.
     */
    private void commitClone(String projectId, String subject) {
        Path dir = clone(projectId);
        Duration limit = Duration.ofSeconds(30);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            git.run(dir, limit, "init");
        }
        git.run(dir, limit, "add", "-A");
        git.run(dir, limit,
                "-c", "user.email=검사원@example.com", "-c", "user.name=검사원",
                "commit", "-m", subject);
    }

    private Path clone(String projectId) {
        return paths.cloneDir(projectId);
    }

    /** 앞 테스트가 같은 번호로 깔아 둔 클론을 지운다. 없으면 아무것도 안 한다. */
    private void wipeClone(String projectId) {
        Path dir = clone(projectId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                // ⚠ git 은 objects/ 를 읽기 전용으로 깐다 — 윈도우에서는 그대로면 못 지운다.
                path.toFile().setWritable(true, false);
                try {
                    Files.delete(path);
                } catch (IOException stuck) {
                    throw new UncheckedIOException(stuck);
                }
            });
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private void write(Path target, String body) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body, StandardCharsets.UTF_8);
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String base(String projectId) {
        return "/projects/" + projectId + "/artifacts/solution-mockups";
    }

    private String list(String projectId) throws Exception {
        return mvc.perform(get(base(projectId)).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String listWith(String projectId, String queryString) throws Exception {
        return mvc.perform(get(base(projectId) + "?" + queryString).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String listWithFacet(String projectId, String facet) throws Exception {
        return mvc.perform(get(base(projectId)).param("facet", facet).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String detail(String projectId, String screenId) throws Exception {
        return mvc.perform(get(base(projectId) + "/" + screenId).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * ⚠ <b>클론은 DB 가 아니라 디스크에 앉는다 — {@code @Transactional} 이 안 되돌린다.</b>
     * 그런데 프로젝트 번호는 롤백에 되돌아가서 <b>다음 테스트가 같은 번호를 받는다</b>.
     * 씻지 않으면 「클론이 없어야 하는」 테스트가 앞 테스트의 클론을 주워 읽는다 —
     * 순서에 따라 통과와 실패가 갈리는 자리다.
     */
    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        wipeClone(id);
        projects.insert(Project.create(id, name,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
