package com.bizplay.builder.artifact;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.shell.ShellContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 산출물 목록 화면 하나가 <b>열쇠 열한 개 전부</b>에서 뜨는지 본다.
 *
 * <p>여기가 초록이면 사슬이 끝까지 돈 것이다 — 고른 프로젝트가 주소를 타고 넘어와
 * 껍데기 계약을 통과하고 메뉴가 그 프로젝트를 가리킨다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class ArtifactListTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다. */
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    /**
     * ⛔ 메뉴가 열쇠 열한 개의 이름과 주소를 <b>모든 화면에서 전부</b> 그린다 — 그래서
     * {@code contains(이름)} · {@code contains(그 열쇠의 주소)} 는 그 열쇠로 안 들어가도
     * 항상 참이다. 컨트롤러가 제목을 상수로 고정해도 이 둘은 안 빨개진다
     * (2026-08-10 전체 브랜치 검토가 짚었다 — Task 4 가 재려던 것을 못 쟀다).
     *
     * <p>화면이 <b>제 열쇠로 렌더됐다</b>를 증명하려면 메뉴에 안 반복되는 자리를 봐야 한다 —
     * {@code <title>}(그 화면 하나에만 붙는다)과 메뉴의 지금 표시(그 열쇠의 링크에만 붙는다).
     * 후자는 {@code ShellContractTest} 의 도구를 그대로 쓴다 — 이 저장소가 정규식 경계로
     * 데인 것이 세 번째라 새 정규식을 또 안 만든다.
     */
    @Test
    void 산출물_화면은_모두_열리고_표시된_메뉴만_현재_위치를_단다() throws Exception {
        Project p = readyProject("탐나는전");

        for (String key : ShellContract.ARTIFACT_KEYS) {
            String html = mvc.perform(
                            get("/projects/" + p.getId() + "/artifacts/" + key).with(user(superUser())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .as("열쇠 '%s' 화면의 <title> 이 제 이름을 단다 — 상수 제목이면 여기가 빨개진다",
                            key)
                    .contains("<title>" + ShellContract.ARTIFACT_NAMES.get(key) + " · 빌더</title>");
            assertThat(com.bizplay.builder.shell.ShellContractTest.currentMarkCount(html))
                    .as("열쇠 '%s' 화면은 표시된 메뉴일 때만 현재 위치를 단다", key)
                    .isEqualTo(ShellContract.ARTIFACT_MENU_KEYS.contains(key) ? 1 : 0);
            if (ShellContract.ARTIFACT_MENU_KEYS.contains(key)) {
                assertThat(com.bizplay.builder.shell.ShellContractTest.markedLinkHref(html))
                        .as("열쇠 '%s' 의 지금 표시가 제 열쇠의 주소에 붙는다", key)
                        .isEqualTo("/projects/" + p.getId() + "/artifacts/" + key);
            }
            assertThat(html)
                    .as("열쇠 '%s' 화면 머리에 프로젝트 이름이 뜬다", key)
                    .contains("탐나는전");
        }
    }

    /**
     * design(「프로젝트를 고르는 길」)의 요구다 — 「하나를 누르면 그 프로젝트의 같은 메뉴로
     * 간다」. 처음 구현은 이 값을 받지 않고 매번 BRD 로 하드코딩했는데, 설계의 예시 그림이
     * 마침 BRD 를 예로 들어서 눈에 안 띄었다(2026-08-10 전체 브랜치 검토가 짚었다).
     * 요구사항처럼 BRD 가 아닌 열쇠에서 재야 그 실수가 잡힌다(추적 매트릭스로 재던 것을 2026-08-27 삭제에 맞춰 바꿨다).
     */
    @Test
    void 프로젝트를_바꾸면_지금_보던_메뉴로_착지한다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("전자세금계산서");

        String html = mvc.perform(
                        get("/projects/" + mine.getId() + "/artifacts/requirements").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("요구사항을 보다가 프로젝트를 바꾸면 그쪽도 요구사항으로 가야 한다")
                .contains("/projects/" + other.getId() + "/artifacts/requirements")
                .doesNotContain("/projects/" + other.getId() + "/artifacts/brd");
    }

    @Test
    void 프로젝트가_둘이면_공통_메뉴에서_다른_프로젝트로_건너뛸_수_있다() throws Exception {
        Project mine = readyProject("탐나는전");
        Project other = readyProject("전자세금계산서");

        String html = mvc.perform(
                        get("/projects/" + mine.getId() + "/artifacts/brd").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("공통 메뉴에 다른 프로젝트로 가는 선택지가 있다")
                .contains("data-project-switch")
                .contains("data-project-switch-option")
                .contains("data-project-url=\"/projects/" + other.getId() + "/artifacts/brd\"");
    }

    /**
     * ⛔ 프로젝트 선택 표시가 메뉴의 현재 위치 표시와 <b>같은 것으로 세어지면 안 된다.</b>
     * {@code ShellContractTest} 는 {@code aria-current="page"} 를 페이지 전체에서 세어
     * 「딱 하나」를 요구한다 — 머리에 하나 더 붙으면 그 검사가 통째로 무너진다.
     * 그래서 프로젝트 select의 selected 상태를 쓴다.
     */
    @Test
    void 머리의_지금_표시가_메뉴의_현재_위치_표시를_흐리지_않는다() throws Exception {
        readyProject("전자세금계산서");
        Project mine = readyProject("탐나는전");

        String html = mvc.perform(
                        get("/projects/" + mine.getId() + "/artifacts/menu-tree").with(user(superUser())))
                .andReturn().getResponse().getContentAsString();

        assertThat(com.bizplay.builder.shell.ShellContractTest.currentMarkCount(html))
                .as("aria-current=\"page\" 는 메뉴에 딱 하나뿐이다")
                .isEqualTo(1);
        assertThat(html)
                .as("프로젝트 선택 콤보박스가 현재 프로젝트를 선택 상태로 표시한다")
                .contains("data-project-switch")
                .contains("role=\"combobox\"")
                .contains("data-project-url=\"/projects/" + mine.getId() + "/artifacts/menu-tree\"")
                .contains("aria-selected=\"true\"");
    }

    @Test
    void 프로젝트가_하나뿐이면_프로젝트_선택_콤보박스를_표시한다() throws Exception {
        Project only = readyProject("탐나는전");

        String html = mvc.perform(
                        get("/projects/" + only.getId() + "/artifacts/brd").with(user(superUser())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("프로젝트가 하나여도 현재 프로젝트를 표시한다")
                .contains("data-project-switch")
                .contains("role=\"combobox\"")
                .contains("aria-selected=\"true\"")
                .contains("탐나는전");
    }

    @Test
    void 모르는_열쇠는_404_다() throws Exception {
        Project p = readyProject("탐나는전");

        mvc.perform(get("/projects/" + p.getId() + "/artifacts/brdd").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 없는_프로젝트의_산출물_주소는_고르기로_돌려보낸다() throws Exception {
        mvc.perform(get("/projects/999999/artifacts/brd").with(user(superUser())))
                .andExpect(status().is3xxRedirection());
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    /**
     * ⚠ 2026-08-15 부터 <b>앉히기가 두 걸음</b>이다 — {@code insert} 는 늘 {@code RECEIVING} 으로 넣고
     * ({@code Project.create} 가 처음 상태의 정본이다), 준비됨은 {@code updateState} 로 따로 만든다.
     * ⛔ 「엔티티를 고치고 저장」으로 되돌리지 마라 — MyBatis 엔 더티 체킹이 없어 조용히 잃는다.
     */
    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(com.bizplay.builder.id.IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git", "main",
                "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
