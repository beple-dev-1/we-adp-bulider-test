package com.bizplay.builder.solution;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdFacet;
import com.bizplay.builder.frd.FrdFacetMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
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
import java.time.Instant;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>제주 사업인데 익산으로 보이는 것</b>을 막는 시험.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-preview-skin-design.md}.
 * 계약은 추출기 회신 #5 — <b>마크업이 갈리면 추출기(갈래 목업), 스타일만 갈리면 빌더(렌더 시점 치환).</b>
 *
 * <p>2026-08-22 실측이 계기다: FRD 의 적용 대상이 <b>제주</b>인데 웹뷰 초안이 익산 스타일
 * ({@code assets/css/iks})로 그려지고 있었고, 갈래 화면은 언제나 <b>첫 기관</b>(가나다순이라 익산)을 열었다.
 *
 * <p>⚠ 씨앗의 {@code iks}·{@code tnj} 는 이 시험이 지어낸 글자다 — <b>코드가 아는 글자가 아니다.</b>
 * 실물에서는 {@code manifest.json} 의 {@code systems[].skins} 하나가 그 매핑의 출처다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class PreviewSkinScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdFacetMapper frdFacets;

    // ── 미리보기 문 ───────────────────────────────────────────────────────

    @Test
    void 기관을_고르면_그_기관의_스킨으로_그린다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = preview(p.getId(), "webview/pages/wv-skin-home.html", "jeju");

        assertThat(html)
                .as("스타일만 갈리는 화면은 목업이 한 장뿐이다 — 렌더 시점에 갈지 않으면 제주로 볼 길이 없다")
                .contains("href=\"../assets/css/tnj/ui.base.css\"")
                .doesNotContain("css/iks/");
    }

    /** ⚠ 실물 목업이 {@code ?ver=3.4} 를 달고 부른다. 떼면 브라우저 캐시가 갈린 판을 안 받는다. */
    @Test
    void 질의문자열을_단_링크도_기관만_갈린다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = preview(p.getId(), "webview/pages/wv-skin-home.html", "jeju");

        assertThat(html).contains("../assets/css/tnj/ui.theme.css?ver=3.4");
    }

    /**
     * ⛔ <b>기본 기관을 지어내지 않는다</b>(추출기 회신 #5 의 물음 ②).
     * 이 프로젝트는 적용 구분이 둘이라 「고른 기관」이 없다 — 그때는 색인이 그린 그대로다.
     */
    @Test
    void 기관을_안_고르면_파일_그대로_낸다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = preview(p.getId(), "webview/pages/wv-skin-home.html", null);

        assertThat(html).contains("href=\"../assets/css/iks/ui.base.css\"");
    }

    @Test
    void 스킨을_선언하지_않은_시스템은_기관을_골라도_안_갈린다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = preview(p.getId(), "backoffice/pages/bo-plain-list.html", "jeju");

        assertThat(html).contains("href=\"../assets/css/style.css\"");
    }

    /**
     * ⭐ <b>스킨 폴더 밖의 기관 자산은 못 고친다</b> — 그쪽 {@code SKIN-3} review 가 이름을 부르는 자리다.
     * css 를 갈아도 그 그림은 안 갈린다. <b>이름으로 짐작해 고치면 지어내는 것</b>이 된다.
     */
    @Test
    void 스킨_폴더_밖의_기관_그림은_그대로_남는다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = preview(p.getId(), "webview/pages/wv-skin-home.html", "jeju");

        assertThat(html).contains("src=\"../assets/images/card/tnj-card-1.png\"");
    }

    /** 자산은 이미 갈린 주소로 들어온다 — 그대로 낸다. 여기서 또 만지면 두 번 갈린다. */
    @Test
    void 자산_요청은_기관을_붙여도_파일_그대로다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String css = mvc.perform(get(files(p.getId()) + "webview/assets/css/tnj/ui.base.css")
                        .param("skin", "iksan").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(css).contains("제주 스킨");
    }

    // ── 상세 화면의 기관 탭 ───────────────────────────────────────────────

    @Test
    void 스킨_화면에도_기관_탭이_뜨고_미리보기가_그_기관으로_열린다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());

        String html = mvc.perform(get("/projects/{p}/artifacts/solution-mockups/{s}",
                        p.getId(), "wv-skin-home").param("variant", "jeju").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .as("갈래가 없는 화면에도 기관 축이 있다 — 스타일이 갈리기 때문이다")
                .contains(">익산</a>").contains(">제주</a>")
                .contains("skin=jeju");
    }

    // ── FRD 작업대 ────────────────────────────────────────────────────────

    /**
     * ⭐ <b>2026-08-22 실측이 잡은 그 고장이다.</b> FRD-027 은 적용 대상이 제주인데 초안이
     * 익산 스타일로 그려지고 있었다.
     */
    @Test
    void 제주_FRD_의_초안_미리보기는_제주_스킨으로_나온다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        String frdId = seedFrd(p, "제주");
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId,
                "<html><head><link rel=\"stylesheet\" href=\"../assets/css/iks/ui.base.css\">"
                        + "</head><body>초안</body></html>", null, Instant.now());

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("href=\"../assets/css/tnj/ui.base.css\"")
                .as("자산 기준 경로는 그대로 살아 있어야 한다 — 치환이 그것을 망가뜨리면 안 붙는다")
                .contains("<base href=\"/projects/" + p.getId()
                        + "/artifacts/solution-mockups/files/webview/pages/\">");
    }

    /** ⛔ 적용 대상이 둘이면 못 정한 것이다 — 아무거나 고르면 그것이 바로 이 고장의 씨다. */
    @Test
    void 적용_대상이_둘이면_초안을_안_갈아낀다() throws Exception {
        Project p = readyProject("탐나는전");
        seedClone(p.getId());
        String frdId = seedFrd(p, "제주", "익산");
        String screenRowId = screens.selectByFrdId(frdId).get(0).id();
        screens.updateGenerated(screenRowId,
                "<html><head><link rel=\"stylesheet\" href=\"../assets/css/iks/ui.base.css\">"
                        + "</head><body>초안</body></html>", null, Instant.now());

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/screens/{s}/preview",
                        p.getId(), frdId, screenRowId).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("href=\"../assets/css/iks/ui.base.css\"");
    }

    // ── 씨앗 ──────────────────────────────────────────────────────────────

    private String preview(String projectId, String path, String skin) throws Exception {
        var request = get(files(projectId) + path).with(user(superUser()));
        if (skin != null) {
            request = request.param("skin", skin);
        }
        return mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String files(String projectId) {
        return "/projects/" + projectId + "/artifacts/solution-mockups/files/";
    }

    /**
     * g2c 와 같은 모양의 씨앗 — <b>방향이 시스템마다 다른 것</b>까지 옮겼다.
     * 웹뷰는 익산으로 그려져 있고 백오피스에는 기관 폴더가 아예 없다.
     */
    private void seedClone(String projectId) {
        Path clone = paths.cloneDir(projectId);
        Path core = clone.resolve("core");
        write(clone.resolve("manifest.json"), """
                {"schema":"we-adk-planning-repo/1","systems":[
                  {"id":"backoffice","prefix":"bo","skins":{}},
                  {"id":"webview","prefix":"wv","skins":{
                     "iksan":"core/webview/assets/css/iks",
                     "jeju":"core/webview/assets/css/tnj"}}]}
                """);
        write(clone.resolve("index.json"), """
                {
                  "schema": "we-adk-index/6",
                  "screens": {
                    "bo-plain-list": {"system": "backoffice", "ia": {"종류": "화면"}},
                    "wv-skin-home":  {"system": "webview", "ia": {"종류": "화면"}, "skin": "iksan"}
                  }
                }
                """);
        write(core.resolve("webview/pages/wv-skin-home.md"), """
                --- 꼬리표 ---
                id: wv-skin-home / system: webview / 기능: 홈 > 메인 / 과업: []

                --- 화면명세 ---
                화면명: 메인 홈
                """);
        write(core.resolve("webview/pages/wv-skin-home.html"), """
                <!DOCTYPE html>
                <html lang="ko"><head><meta charset="utf-8">
                <link rel="stylesheet" href="../assets/css/iks/ui.base.css">
                <link rel="stylesheet" href="../assets/css/iks/ui.theme.css?ver=3.4">
                </head><body><img src="../assets/images/card/tnj-card-1.png"></body></html>
                """);
        write(core.resolve("backoffice/pages/bo-plain-list.html"), """
                <!DOCTYPE html>
                <html lang="ko"><head><link rel="stylesheet" href="../assets/css/style.css"></head>
                <body>백오피스</body></html>
                """);
        write(core.resolve("webview/assets/css/iks/ui.base.css"), "/* 익산 스킨 */");
        write(core.resolve("webview/assets/css/tnj/ui.base.css"), "/* 제주 스킨 */");

        projectFacets.insert(ProjectFacet.create(projectId, "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(projectId, "jeju", "제주"));
    }

    /** 화면 한 장을 가진 작업중 FRD. 적용 대상은 부르는 쪽이 정한다. */
    private String seedFrd(Project project, String... facetNames) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "이용내역 안내 문구 수정", "안내 문구를 고쳐야 한다.", null));
        for (String name : facetNames) {
            frdFacets.insert(FrdFacet.create(id, project.getId(), name));
        }
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), id,
                "wv-skin-home", "메인 홈", "wv-skin-home", null, "문구를 고친다", "webview"));
        frds.updateAfterPick(id, "이용내역 안내 문구 수정", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        return id;
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        wipeClone(id);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    /**
     * ⚠ 클론은 디스크에 앉아 {@code @Transactional} 이 안 되돌리는데 프로젝트 번호는 되돌아간다 —
     * 씻지 않으면 다음 시험이 앞 시험의 클론을 주워 읽는다.
     */
    private void wipeClone(String projectId) {
        Path dir = paths.cloneDir(projectId);
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

    private void write(Path target, String body) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, body, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    @Autowired IdSequence ids;
}
