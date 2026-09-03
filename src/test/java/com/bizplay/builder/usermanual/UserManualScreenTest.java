package com.bizplay.builder.usermanual;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 매뉴얼 — 목록 (그린존 A2 · 2026-08-27).
 *
 * <p><b>단위는 화면 하나당 한 건이다.</b> 현재 운영 화면·화면 명세·IA를 같은 화면 열쇠로 묶는다.
 *
 * <p>어느 시스템이 사용자용인지 코드에서 추측하지 않고 <b>시스템 열쇠와 표시 이름을 그대로 쓴다.</b>
 *
 * <p>⭐ <b>경로의 축은 {@code menuPath} 하나다</b> — A1 기능명세서 목록이 쓰는 것과 같은 칸이다.
 * 갈라 쓰면 같은 화면이 두 목록에서 다른 자리에 서게 된다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class UserManualScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;
    @Autowired ProjectSystemService projectSystems;
    @Autowired UserManualMapper manuals;
    @Autowired ScreenStandardIdMapper standardIds;

    /**
     * ⭐ <b>아직 아무것도 안 만들었으면 기능명세서와 같이 「미생성」이다.</b>
     */
    @Test
    void 목록이_화면당_한_줄로_현재_화면과_매뉴얼_상태를_낸다() throws Exception {
        Project project = readyProject("매뉴얼-목록");
        seedClone(project.getId());

        String html = list(project.getId());

        assertThat(html)
                .contains("사용자 매뉴얼")
                .containsSubsequence(
                        "<th scope=\"col\">화면명</th>",
                        "<th scope=\"col\">화면관리번호</th>",
                        "<th scope=\"col\">화면 ID</th>",
                        "<th scope=\"col\">IA 메뉴 경로</th>",
                        "<th scope=\"col\">시스템</th>",
                        "<th scope=\"col\">생성여부</th>",
                        "<th scope=\"col\">운영 화면 수정일</th>",
                        "<th scope=\"col\">문서 작성일</th>")
                .contains("list-loading-region user-manual-list-page")
                .contains("filter-bar--user-manual")
                .contains("artifact-list-table-wrap user-manual-table-wrap")
                .contains("data-list-loading-links")
                .contains("aria-current=\"page\"")
                .contains("class=\"status-badge status-badge--waiting\"")
                .contains("PS-BO-MRC-010-D01-S")
                .contains("선불카드 관리 &gt; 선불카드 배송 관리 &gt; 상세")
                .contains(">미생성<")
                .doesNotContain("summary-strip")
                .doesNotContain("마지막 정상 매뉴얼 내려받기", "이 시스템 정상 매뉴얼 내려받기")
                .doesNotContain("운영 화면 수정</th>", "매뉴얼 생성</th>")
                .doesNotContain("매뉴얼 상태")
                .doesNotContain("조건 지우기")
                .doesNotContain("매뉴얼 확인")
                .doesNotContain("매뉴얼 작업");
    }

    /**
     * ⭐ <b>목록의 「상태」와 「작성일」은 저장된 것에서 온다.</b> 최신 정상본의 날짜가 없으면
     * 어느 판인지 가릴 수 없다.
     *
     * <p>⚠ 한 프로젝트 안에 만든 것과 안 만든 것이 섞이는 것을 함께 잰다 — 실물이 그 모양이다.
     */
    @Test
    void 만든_화면은_완료와_작성일이_뜨고_안_만든_화면은_미생성이다() throws Exception {
        Project project = readyProject("매뉴얼-작성됨");
        seedClone(project.getId());
        manuals.upsert(UserManual.of(project.getId(), "backoffice", "bo-delivery-detail",
                "<h1>선불카드 배송 상세</h1>"));

        String html = list(project.getId());

        assertThat(html)
                .contains("class=\"status-badge status-badge--complete\"", ">완료<")
                .contains(LocalDate.now(ZoneId.of("Asia/Seoul")).toString())
                .contains(">미생성<");
    }

    /** ⭐ 만들기 요청 뒤에는 목록 위 같은 매뉴얼 레이어로 돌아간다. */
    @Test
    void 만들기를_누르면_목록_위_레이어로_돌아온다() throws Exception {
        Project project = readyProject("매뉴얼-누름");
        seedClone(project.getId());

        String redirect = mvc.perform(post(base(project.getId()) + "/backoffice/bo-delivery-detail")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect)
                .startsWith(base(project.getId()) + "?")
                .contains("selectedSystem=backoffice", "selectedScreen=bo-delivery-detail");
    }

    /** ⚠ 실패한 것은 생성 실패로 보여야 사람이 다시 누른다 — 매뉴얼 없음과 같아 보이면 까닭을 못 짚는다. */
    @Test
    void 실패한_매뉴얼은_실패로_뜬다() throws Exception {
        Project project = readyProject("매뉴얼-실패표시");
        seedClone(project.getId());
        String generationId = UUID.randomUUID().toString();
        manuals.beginGeneration(project.getId(), "backoffice", "bo-delivery-detail", generationId,
                Instant.now().minusSeconds(900));
        manuals.markFailed(project.getId(), "backoffice", "bo-delivery-detail", generationId,
                "NO_CREDENTIAL");

        assertThat(list(project.getId()))
                .as("⚠ 배지의 글자 노드를 지목한다 — 셸에 「실패」가 이미 있어 낱말만으로는 헛통과했다")
                .contains(">생성 실패<");
    }

    /**
     * ⛔ <b>클론이 없다고 500 을 내면 안 된다.</b> {@code ArtifactListTest} 가 열쇠 전부를 도는데
     * 그 프로젝트에는 클론이 없다 — 여기가 빨개지면 그쪽도 같이 빨개진다(A1 이 밟은 자리다).
     */
    @Test
    void 클론이_없어도_목록이_빈_상태로_뜬다() throws Exception {
        Project project = readyProject("매뉴얼-클론없음");
        wipeClone(project.getId());

        String html = list(project.getId());

        assertThat(html).contains("사용자 매뉴얼");
    }

    // ── 거르개와 쪽매김 ───────────────────────────────────────────────────

    /** ⚠ 거르개의 보기는 실제 자료에서 만든다 — 코드에 박으면 그 사업에 없는 값이 뜬다. */
    @Test
    void 시스템으로_거르면_그_시스템만_남는다() throws Exception {
        Project project = readyProject("매뉴얼-시스템거르개");
        seedClone(project.getId());

        String html = listWith(project.getId(), "system=backoffice");

        assertThat(html)
                .contains("선불카드 관리 &gt; 선불카드 배송 관리 &gt; 상세")
                .doesNotContain("홈 &gt; 메인");
    }

    @Test
    void 매뉴얼상태로_거르면_그_상태만_남는다() throws Exception {
        Project project = readyProject("매뉴얼-상태거르개");
        seedClone(project.getId());
        manuals.upsert(UserManual.of(project.getId(), "backoffice", "bo-delivery-detail", "<h1>있다</h1>"));

        String written = listWith(project.getId(), "manual=완료");
        assertThat(written)
                .contains("선불카드 관리 &gt; 선불카드 배송 관리 &gt; 상세")
                .doesNotContain("홈 &gt; 메인");

        String missing = listWith(project.getId(), "manual=미생성");
        assertThat(missing)
                .contains("홈 &gt; 메인")
                .doesNotContain("선불카드 관리 &gt; 선불카드 배송 관리 &gt; 상세");
    }

    /** ⚠ 화면ID 로도 찾는다 — 사람은 알고 있는 ID 를 그대로 쳐 넣는다. */
    @Test
    void 검색어로_화면을_찾는다() throws Exception {
        Project project = readyProject("매뉴얼-검색");
        seedClone(project.getId());

        assertThat(listWith(project.getId(), "query=wv-sample"))
                .contains("홈 &gt; 메인")
                .doesNotContain("선불카드 관리 &gt; 선불카드 배송 관리 &gt; 상세");
    }

    @Test
    void 목록_레이어를_열면_매뉴얼을_자동으로_만들고_생성_버튼은_보이지_않는다() throws Exception {
        Project project = readyProject("매뉴얼-레이어");
        seedClone(project.getId());

        String html = mvc.perform(get(base(project.getId())
                        + "?selectedSystem=backoffice&selectedScreen=bo-delivery-detail")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("id=\"user-manual-dialog\"")
                .contains("aria-modal=\"true\"")
                .contains("id=\"user-manual-dialog-title\" tabindex=\"-1\"")
                .contains("선불카드 배송 상세")
                .contains("백오피스 · PS-BO-MRC-010-D01-S")
                .contains("/js/user-manual.js")
                .doesNotContain("rq-meta", "백오피스 · bo-delivery-detail");
        assertThat(Files.readString(Path.of("src/main/resources/templates/artifacts/user-manual.html"),
                        StandardCharsets.UTF_8))
                .contains("artifact-generation", "사용자 매뉴얼을 작성하고 있습니다",
                        "artifact-generation-paper");
        assertThat(manuals.selectOne(project.getId(), "backoffice", "bo-delivery-detail")).isPresent();
        assertThat(Jsoup.parse(html).select("#user-manual-dialog form")).isEmpty();
        assertThat(Jsoup.parse(html).select("#user-manual-dialog button").eachText())
                .doesNotContain("매뉴얼 만들기", "매뉴얼 다시 만들기");
        assertThat(Jsoup.parse(html).select("#user-manual-dialog .dialog__actions").text())
                .isEqualTo("닫기");
    }

    @Test
    void 이전_상세_주소는_목록_레이어_주소로_보낸다() throws Exception {
        Project project = readyProject("매뉴얼-이전주소");
        seedClone(project.getId());

        String redirect = mvc.perform(get(base(project.getId()) + "/backoffice/bo-delivery-detail")
                        .with(user(superUser())))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect)
                .startsWith(base(project.getId()) + "?")
                .contains("selectedSystem=backoffice", "selectedScreen=bo-delivery-detail");
    }

    @Test
    void 다른_시스템의_화면ID로_상세를_열면_없다고_답한다() throws Exception {
        Project project = readyProject("매뉴얼-상세-시스템불일치");
        seedClone(project.getId());

        mvc.perform(get(base(project.getId()) + "/webview/bo-delivery-detail")
                        .with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    /**
     * <b>화면이 많아도 한 쪽에 모두 싣지 않는다.</b>
     * ⚠ 모르는 {@code pageSize} 는 기본값으로 되돌린다 — 주소를 손으로 고쳐 전부를 뽑지 못하게.
     */
    @Test
    void 한_쪽에_안_들어가면_쪽이_갈린다() throws Exception {
        Project project = readyProject("매뉴얼-쪽매김");
        seedManyScreens(project.getId(), 25);

        String first = list(project.getId());
        assertThat(first)
                .contains("2 페이지", "value=\"10\" selected=\"selected\"")
                .contains("bo-many-00")
                .doesNotContain("bo-many-10", "bo-many-24");

        String third = listWith(project.getId(), "page=3");
        assertThat(third).contains("bo-many-24");

        String unsupportedSize = listWith(project.getId(), "pageSize=999");
        assertThat(unsupportedSize).contains("bo-many-00").doesNotContain("bo-many-10");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private String base(String projectId) {
        return "/projects/" + projectId + "/artifacts/user-manual";
    }

    private String list(String projectId) throws Exception {
        return mvc.perform(get(base(projectId)).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 한 쪽에 안 들어가는 수를 깐다. 쪽 이동을 재려면 이것이 있어야 한다. */
    private void seedManyScreens(String projectId, int howMany) throws IOException {
        wipeClone(projectId);
        Path root = clone(projectId);
        Path core = root.resolve("core");
        StringBuilder screens = new StringBuilder();
        for (int i = 0; i < howMany; i++) {
            String id = "bo-many-%02d".formatted(i);
            if (i > 0) {
                screens.append(", ");
            }
            screens.append("\"%s\": {\"system\": \"backoffice\", \"ia\": {\"종류\": \"화면\"}}".formatted(id));
            write(core.resolve("backoffice/pages/" + id + ".md"), """
                    --- 꼬리표 ---
                    id: %s / system: backoffice / 기능: 묶음 > 화면 %02d / 과업: []

                    --- 화면명세 ---
                    화면명: 화면 %02d
                    목적: 쪽매김을 재려고 깐 화면이다

                    --- 원본 글 ---
                    > 역추출 소스: 시험용
                    """.formatted(id, i, i));
        }
        write(root.resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    %s
                  },
                  "counts": {"screens": %d}
                }
                """.formatted(screens, howMany));
        write(root.resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":["
                        + "{\"id\":\"backoffice\",\"prefix\":\"bo\"}]}");
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId, new LinkedHashMap<>(Map.of("backoffice", "백오피스")));
    }

    private String listWith(String projectId, String queryString) throws Exception {
        return mvc.perform(get(base(projectId) + "?" + queryString).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void seedClone(String projectId) throws IOException {
        wipeClone(projectId);
        Path core = clone(projectId).resolve("core");

        write(clone(projectId).resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    "bo-delivery-detail": {"system": "backoffice", "ia": {"종류": "화면"}},
                    "wv-sample-home":     {"system": "webview",    "ia": {"종류": "화면"}}
                  },
                  "counts": {"screens": 2}
                }
                """);

        write(core.resolve("backoffice/pages/bo-delivery-detail.md"), """
                --- 꼬리표 ---
                id: bo-delivery-detail / system: backoffice / 기능: 선불카드 관리 > 선불카드 배송 관리 > 상세 / 과업: []

                --- 화면명세 ---
                화면명: 선불카드 배송 상세
                목적: 선택한 배송 건을 조회하고 반송을 처리한다

                --- IA ---
                - 종류: 화면

                --- 정의 ---
                - 구분: 기능 / 좌표: id=btnZipSearch / 앵커: bo-delivery-detail-e02 / 해설: 우편번호 검색

                --- 원본 글 ---
                > 역추출 소스: 배송 상세 화면
                """);

        write(core.resolve("webview/pages/wv-sample-home.md"), """
                --- 꼬리표 ---
                id: wv-sample-home / system: webview / 기능: 홈 > 메인 / 과업: []

                --- 화면명세 ---
                화면명: 메인 홈
                목적: 웹뷰 첫 화면이다

                --- IA ---
                - 종류: 화면

                --- 정의 ---
                - 구분: 기능 / 좌표: id=btnGo / 앵커: wv-sample-home-e01 / 해설: 카드 목록으로 이동

                --- 원본 글 ---
                > 역추출 소스: 웹뷰 홈
                """);

        write(clone(projectId).resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":["
                        + "{\"id\":\"backoffice\",\"prefix\":\"bo\"},"
                        + "{\"id\":\"webview\",\"prefix\":\"wv\"}]}");
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId,
                new LinkedHashMap<>(Map.of("backoffice", "백오피스", "webview", "웹뷰")));
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID), projectId,
                "bo-delivery-detail", "PS-BO-MRC-010-D01", ScreenStandardId.Origin.S, 1));
    }

    private Path clone(String projectId) {
        return paths.cloneDir(projectId);
    }

    private void wipeClone(String projectId) {
        Path dir = clone(projectId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
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

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
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
