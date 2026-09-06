package com.bizplay.builder.design;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 디자인 시스템의 네이티브 랜딩과 산출물 경계 계약을 확인한다. */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class DesignGuideScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;

    @Test
    void 최신_디자인시스템은_iframe_없이_Builder_화면에서_렌더한다() throws Exception {
        Project project = readyProject();
        seedGuide(project.getId(), "export/guide");

        String page = mvc.perform(get("/projects/{id}/artifacts/design-guide", project.getId())
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-design-guide", "dg-render-scope", "dg-native-context-row",
                "dg-native-facet--single", "조회 필터", "버튼",
                "기본 스타일", "UI 컴포넌트", "화면 레이아웃", "화면 템플릿", "백오피스 공통 골격", "조회 목록",
                "원본 확인 모달", "처리 사유 입력");
        assertThat(page).doesNotContain("data-guide-tab=\"compositions\"", "현재 시스템", "원본 CSS 연결");
        assertThat(page).doesNotContain("dg-artifact-frame", "untrusted()", "onclick=", "화면 유형");
        String componentPanel = page.substring(page.indexOf("data-guide-panel=\"components\""),
                page.indexOf("data-guide-panel=\"layouts\""));
        assertThat(componentPanel).contains("dg-live-card__meta")
                .doesNotContain("개 역할", "개 화면에서 사용", ">지원 상태</summary>");
        String layoutPanel = page.substring(page.indexOf("data-guide-panel=\"layouts\""),
                page.indexOf("data-guide-panel=\"templates\""));
        assertThat(layoutPanel).doesNotContain("개 화면에서 사용");
        String templatePanel = page.substring(page.indexOf("data-guide-panel=\"templates\""));
        assertThat(templatePanel).doesNotContain("개 화면에서 사용");
        Matcher ticket = Pattern.compile("/files/([^/]+)/export/guide/styles/backoffice\\.css").matcher(page);
        assertThat(ticket.find()).isTrue();

        mvc.perform(get("/projects/{id}/artifacts/design-guide/files/{ticket}/export/guide/styles/backoffice.css",
                        project.getId(), ticket.group(1)))
                .andExpect(status().isOk())
                // ⛔ 이 경로만 X-Frame-Options 를 아예 안 보낸다 — SecurityConfig 의 frameAllowed 가 그 자리다.
                //    SAMEORIGIN 이 붙으면 opaque sandbox 안쪽이 자기 부모에 막혀 미리보기 칸이 말없이 빈다.
                .andExpect(header().doesNotExist("X-Frame-Options"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("dg-render-scope")));
    }

    /**
     * ⚠ 위 시험의 씨앗은 다섯 갈래가 모두 찬 계약이라 <b>우리가 실제로 받는 모양이 아니다.</b>
     * 004(2026-09-06) 시점의 EXW 계약은 foundations 만 있고 나머지 넷이 <b>빈 배열</b>이다.
     * 그 모양에서 화면이 뜨는지, 빈 갈래가 각자의 안내를 그리는지 아무도 안 지키고 있었다.
     */
    @Test
    void foundations만_있고_나머지가_빈_계약도_네_갈래를_다_그린다() throws Exception {
        Project project = readyProject();
        seedFoundationsOnlyGuide(project.getId());

        String page = mvc.perform(get("/projects/{id}/artifacts/design-guide", project.getId())
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("최신 디자인 시스템 산출물이 없습니다.", "dg-artifact-frame");
        assertThat(page).contains("data-guide-tab=\"foundations\"", "data-guide-tab=\"components\"",
                "data-guide-tab=\"layouts\"", "data-guide-tab=\"templates\"");
        // foundations 는 실물이 있다 — 색 역할·글꼴이 그려져야 한다.
        assertThat(page).contains("주색 토큰", "Pretendard", "#1F1F1F");
        // 나머지 셋은 「없다」가 아니라 각자의 안내를 그린다.
        assertThat(page).contains("확인된 컴포넌트가 없습니다.",
                "공통 레이아웃이 등록되지 않았습니다.", "등록된 화면 템플릿이 없습니다.");
        // ⛔ 안내 위에 빈 색인 막대를 남기지 마라 — 같은 말을 못 하면서 자리만 차지한다.
        assertThat(page).doesNotContain("dg-component-index");
    }

    /**
     * 004(2026-09-06)에서 검수용 프레임 갈래를 걷었다 — 부르는 화면·js 가 0곳이었다.
     *
     * <p>⛔ <b>이 자리를 되살리지 마라.</b> 되살아나면 화면이 「소스를 보여준다」면서 소스에 없는
     * 모양을 내는 길이 다시 열린다. 클래스를 지운 것만으로는 아무도 못 지키므로 경로로 못 박는다.
     */
    @Test
    void 없앤_검수용_프레임_경로는_열리지_않는다() throws Exception {
        Project project = readyProject();

        mvc.perform(get("/projects/{id}/artifacts/design-guide/frame", project.getId())
                        .with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_산출물이나_저장소_바깥_파일은_디자인시스템_주소로_읽을수없다() throws Exception {
        Project project = readyProject();
        seedGuide(project.getId(), "design-guide");
        String page = mvc.perform(get("/projects/{id}/artifacts/design-guide", project.getId())
                        .with(user(superUser())))
                .andReturn().getResponse().getContentAsString();
        Matcher ticket = Pattern.compile("/files/([^/]+)/design-guide/styles/backoffice\\.css").matcher(page);
        assertThat(ticket.find()).isTrue();

        mvc.perform(get("/projects/{id}/artifacts/design-guide/files/not-issued/design-guide/styles/backoffice.css",
                        project.getId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/projects/{id}/artifacts/design-guide/files/{ticket}/manifest.json",
                        project.getId(), ticket.group(1)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/projects/{id}/artifacts/design-guide/files/{ticket}/core/backoffice/pages/sample.html",
                        project.getId(), ticket.group(1)))
                .andExpect(status().isOk());
    }

    @Test
    void 최신_계약이_없으면_검수용_iframe으로_되돌아가지_않는다() throws Exception {
        Project project = readyProject();

        mvc.perform(get("/projects/{id}/artifacts/design-guide", project.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("최신 디자인 시스템 산출물이 없습니다.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("dg-artifact-frame"))));
    }

    @Test
    void Builder에서_컴포넌트_이름과_variant를_정리해_확정본에_적용한다() throws Exception {
        Project project = readyProject();
        seedGuide(project.getId(), "design-guide");

        mvc.perform(get("/projects/{id}/artifacts/design-guide", project.getId())
                        .param("edit", "true").with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Builder 편집 모드")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Variant 정리")));

        mvc.perform(post("/projects/{id}/artifacts/design-guide/curation/backoffice/components/button",
                        project.getId())
                        .with(user(superUser())).with(csrf())
                        .param("version", "0")
                        .param("label", "행동 버튼")
                        .param("category", "button")
                        .param("displayOrder", "3")
                        .param("variants[0].id", "primary")
                        .param("variants[0].label", "주요 행동")
                        .param("variants[0].hidden", "false"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/projects/{id}/artifacts/design-guide", project.getId())
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("행동 버튼")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("주요 행동")));
    }

    private Project readyProject() {
        String id = ids.next(IdSequence.Kind.PROJECT);
        wipeClone(id);
        var sealed = sealer.seal("glpat-design-guide-test");
        projects.insert(Project.create(id, "디자인 시스템 검증", "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private void seedGuide(String projectId, String guidePath) {
        Path clone = paths.cloneDir(projectId);
        write(clone.resolve("manifest.json"), """
                {"schema":"we-adk-planning-repo/1","design-guide":"%s","systems":[{"id":"backoffice"}]}
                """.formatted(guidePath));
        write(clone.resolve(guidePath).resolve("design-guide.json"), """
                {
                  "schema":"we-adk-design-guide/7",
                  "systems":{"backoffice":{
                    "styles":[{"id":"backoffice","css":"styles/backoffice.css"}],
                    "foundations":{"colorRoles":{"roles":[{"label":"기본 강조","entries":[{"value":"#1a32d8"}]}]},"typography":{"fonts":[{"family":"SUIT","sampleWeights":["500","700"]}]}},
                    "components":[
                      {"id":"button","label":"버튼","category":"button","description":"행동을 실행하는 버튼","variants":[{"id":"primary","label":"주요 버튼","class":"source-button","states":[{"state":"default","label":"기본","renderable":true}]}],"usage":{"count":3},"evidence":{"sourceScreen":"core/backoffice/pages/sample.html","range":{"file":"core/backoffice/pages/sample.html","from":1,"to":2}},"specimen":{"status":"visible","html":"fragments/backoffice--c-button.html","systemStyleId":"backoffice","width":640,"height":96}},
                      {"id":"modal","label":"모달","category":"modal","variants":[{"id":"pop_wrap","label":"기본 모달","class":"pop_wrap","states":[{"state":"default","label":"기본","renderable":true}]}],"usage":{"count":4},"specimen":{"status":"visible","html":"fragments/backoffice--c-modal.html","systemStyleId":"backoffice","width":640,"height":96}}
                    ],
                    "compositions":[{"id":"search-filter","label":"조회 필터","description":"조건을 입력해 목록을 조회한다","usage":{"count":3},"evidence":{"sourceScreen":"core/backoffice/pages/sample.html","range":{"file":"core/backoffice/pages/sample.html","from":1,"to":2}},"specimen":{"status":"visible","html":"fragments/backoffice--x-search-filter.html","systemStyleId":"backoffice","width":640,"height":96}},{"id":"modal-confirm","label":"모달 · 확인","description":"완결된 확인 모달","usage":{"count":4},"evidence":{"sourceScreen":"core/backoffice/pages/sample.html","range":{"file":"core/backoffice/pages/sample.html","from":3,"to":8}},"specimen":{"status":"visible","html":"fragments/backoffice--x-modal-confirm.html","systemStyleId":"backoffice","width":520,"height":310}}],
                    "layouts":[{"id":"backoffice-shell","label":"백오피스 공통 골격","kind":"shell","description":"사이드바 + 헤더 + 본문","regions":[{"id":"header","label":"헤더","required":true,"behavior":{"position":"relative","measured":{"visible":true,"width":640,"height":42}}}],"variants":[{"id":"backoffice","label":"backoffice","specimen":{"status":"visible","html":"fragments/backoffice--l-backoffice-shell.html","systemStyleId":"backoffice","width":640,"height":240}}]}],
                    "templates":[{"id":"search-list","label":"조회 목록","purpose":"조건을 넣어 목록을 조회한다","layoutId":"backoffice-shell","componentIds":["search-filter"],"regions":[{"id":"content.search","label":"검색 영역","required":true,"componentId":"search-filter"}],"representativeScreens":[{"id":"sample","path":"core/backoffice/pages/sample.html","purpose":"목록을 조회한다"}],"usage":{"count":3},"specimen":{"status":"visible","html":"fragments/backoffice--t-search-list.html","systemStyleId":"backoffice","width":640,"height":280}}]
                  }}
                }
                """);
        write(clone.resolve(guidePath).resolve("styles/backoffice.css"),
                ".dg-render-scope[data-system=\"backoffice\"] .source-button { color: #1a32d8; }");
        write(clone.resolve(guidePath).resolve("fragments/backoffice--c-button.html"), """
                <div class="dg-body"><style>.dg-sp-grid{display:flex}</style><div class="dg-sp-grid">
                  <div class="dg-sp-cell" data-dg-variant="primary"><button class="source-button" onclick="untrusted()">조회</button><span class="dg-sp-tag">primary</span></div>
                </div><script>untrusted()</script></div>
                """);
        write(clone.resolve(guidePath).resolve("fragments/backoffice--x-search-filter.html"),
                "<div class=\"dg-body\"><label>검색 <input></label><button class=\"source-button\">조회</button></div>");
        write(clone.resolve(guidePath).resolve("fragments/backoffice--c-modal.html"),
                "<div class=\"dg-body\"><div class=\"pop_wrap\"><img><button>확인</button></div></div>");
        write(clone.resolve(guidePath).resolve("fragments/backoffice--x-modal-confirm.html"),
                "<div class=\"dg-body\"><div class=\"pop_wrap\"><div class=\"pop_header\"><h1>원본 확인 모달</h1></div><div class=\"pop_container\"><textarea placeholder=\"처리 사유 입력\"></textarea></div><div class=\"pop_foot\"><button>확인</button><button>취소</button></div></div></div>");
        write(clone.resolve(guidePath).resolve("fragments/backoffice--l-backoffice-shell.html"),
                "<div class=\"dg-body\"><div data-dg-region=\"header\">헤더</div><div>본문</div></div>");
        write(clone.resolve(guidePath).resolve("fragments/backoffice--t-search-list.html"),
                "<div class=\"dg-body\"><div data-dg-component=\"search-filter\">조회 필터</div><table><tr><td>목록</td></tr></table></div>");
        write(clone.resolve("core/backoffice/pages/sample.html"), "<html><body>source</body></html>");
    }

    /**
     * 추출기가 2026-09-06 에 실제로 보낸 EXW 계약과 같은 모양 — foundations 만 차 있고
     * {@code components}·{@code compositions}·{@code layouts}·{@code templates} 가 빈 배열이다.
     */
    private void seedFoundationsOnlyGuide(String projectId) {
        Path clone = paths.cloneDir(projectId);
        write(clone.resolve("manifest.json"), """
                {"schema":"we-adk-planning-repo/1","systems":[{"id":"EXW"}]}
                """);
        write(clone.resolve("design-guide/design-guide.json"), """
                {
                  "schema":"we-adk-design-guide/7",
                  "systems":{"EXW":{
                    "styles":[{"id":"EXW","css":"styles/EXW.css"}],
                    "foundations":{
                      "colorRoles":{"roles":[{"label":"주색 토큰","entries":[{"value":"#1F1F1F"}]}]},
                      "typography":{"fonts":[{"family":"Pretendard","sampleWeights":["400","700"]}]},
                      "spacing":[{"value":"1.2rem"}],
                      "radius":[{"value":"0.8rem"}]
                    },
                    "components":[],
                    "compositions":[],
                    "layouts":[],
                    "templates":[]
                  }}
                }
                """);
        write(clone.resolve("design-guide/styles/EXW.css"),
                ".dg-render-scope[data-system=\"EXW\"] .btn { color: #1F1F1F; }");
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("변경비번234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    private void wipeClone(String projectId) {
        Path dir = paths.cloneDir(projectId);
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
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

    private static void write(Path path, String body) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, body, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
