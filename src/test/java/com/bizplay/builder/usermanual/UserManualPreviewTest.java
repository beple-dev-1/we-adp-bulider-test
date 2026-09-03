package com.bizplay.builder.usermanual;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 매뉴얼 보기 — <b>관문 셋 회귀 시험</b> (그린존 A2 · 2026-08-27).
 *
 * <p>⛔ <b>html 을 {@code <iframe>} 으로 내주는 길은 관문 셋을 다 지나야 한다</b> —
 * ① {@code X-Frame-Options} 예외에 이 주소가 있어야 하고 ② {@code Content-Security-Policy: sandbox}
 * ③ {@code X-Content-Type-Options: nosniff} 를 붙여야 한다.
 * <b>셋 중 하나만 빠져도 로그에 안 남는 고장이 된다</b> — 서버는 200 인데 브라우저가 안 그린다.
 *
 * <p>⚠ <b>계획 8 의 FRD 미리보기가 ①②를 빠뜨려 끝 조건이 무효인 채로 Task 리뷰 여섯을 통과했다.</b>
 * 솔루션 목업 쪽에는 이 회귀 시험이 있었고 FRD 쪽에는 없던 것이 갈린 까닭이다 —
 * 그래서 <b>새 미리보기 길을 낼 때마다 이 시험을 같이 베낀다.</b> 본은
 * {@code SolutionMockupScreenTest.미리보기는_같은_출처_iframe_에_뜬다} 다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class UserManualPreviewTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired UserManualMapper manuals;
    @Autowired UserManualCaptureStore captureStore;
    @Autowired BuilderProperties properties;
    @Autowired ProjectPaths paths;
    @Autowired ProjectSystemService projectSystems;

    @Test
    void 보기가_관문_셋을_다_지나_같은_출처_iframe_에_뜬다() throws Exception {
        Project project = readyProject("매뉴얼-보기");
        manuals.upsert(UserManual.of(project.getId(), "backoffice", "bo-delivery-detail",
                "<h1>선불카드 배송 상세 사용법</h1>"));

        var preview = mvc.perform(get(preview(project.getId(), "backoffice", "bo-delivery-detail"))
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(preview.getContentAsString(StandardCharsets.UTF_8))
                .contains("선불카드 배송 상세 사용법");
        assertThat(preview.getHeader("X-Frame-Options"))
                .as("⛔ DENY 가 붙으면 보기 칸이 빈다 — 우리 화면에 우리 글을 끼우는 자리다")
                .isEqualTo("SAMEORIGIN");
        assertThat(preview.getHeader("Content-Security-Policy"))
                .as("⛔ 새 창으로 열어도 스크립트가 안 돌아야 한다 — iframe sandbox 는 그때 없다")
                .contains("default-src 'none'")
                .contains("style-src 'unsafe-inline'")
                .contains("img-src data:")
                .contains("sandbox allow-same-origin");
        assertThat(preview.getHeader("X-Content-Type-Options"))
                .as("⛔ 브라우저가 종류를 다시 짐작하지 못하게 막는다")
                .isEqualTo("nosniff");
    }

    @Test
    void 마지막_정상본의_실제_화면을_문서에_넣는다() throws Exception {
        Project project = readyProject("매뉴얼-실제화면");
        seedClone(project.getId());
        String generationId = UUID.randomUUID().toString();
        assertThat(manuals.beginGeneration(project.getId(), "backoffice", "bo-delivery-detail",
                generationId, Instant.now())).isOne();
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var prepared = properties.dataRoot().resolve("preview-capture-" + generationId);
        Files.createDirectories(prepared);
        Files.write(prepared.resolve("manual-preview.png"), png);
        String bundle = captureStore.publish(prepared, project.getId(), generationId);
        UserManualCapture capture = new UserManualCapture(bundle, "manual-preview.png", "기본 화면",
                1440, 1000, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
        assertThat(manuals.saveDone(project.getId(), "backoffice", "bo-delivery-detail", generationId,
                "<h1>선불카드 배송 상세</h1><h2>개요</h2><p>배송 정보를 확인합니다.</p>",
                "a".repeat(64), UserManual.CURRENT_GENERATOR_VERSION, capture)).isOne();

        String body = mvc.perform(get(preview(project.getId(), "backoffice", "bo-delivery-detail"))
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("data:image/png;base64,")
                .contains("실제 화면 · 기본 화면")
                .contains("선불카드 배송 상세 · 기본 화면 실제 화면");
    }

    /** ⛔ <b>느슨해진 자리를 보기 하나로 좁혀 둔다</b> — 목록은 그대로 막힌다. */
    @Test
    void 목록은_여전히_프레임에_안_뜬다() throws Exception {
        Project project = readyProject("매뉴얼-목록막힘");

        var list = mvc.perform(get("/projects/" + project.getId() + "/artifacts/user-manual")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(list.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    /** ⚠ 안 만든 매뉴얼은 404 다 — 빈 200 을 내면 iframe 이 흰 칸으로 뜨고 까닭을 못 짚는다. */
    @Test
    void 안_만든_매뉴얼은_없다고_답한다() throws Exception {
        Project project = readyProject("매뉴얼-보기없음");

        mvc.perform(get(preview(project.getId(), "backoffice", "bo-nothing")).with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    private String preview(String projectId, String systemCode, String screenId) {
        return "/projects/" + projectId + "/artifacts/user-manual/preview/" + systemCode + "/" + screenId;
    }

    private void seedClone(String projectId) throws Exception {
        var root = paths.cloneDir(projectId);
        var core = root.resolve("core");
        Files.createDirectories(core.resolve("backoffice/pages"));
        Files.writeString(root.resolve("index.json"), """
                {"schema":"we-adk-index/3","screens":{"bo-delivery-detail":
                {"system":"backoffice","ia":{"종류":"화면"}}},"counts":{"screens":1}}
                """, StandardCharsets.UTF_8);
        Files.writeString(core.resolve("backoffice/pages/bo-delivery-detail.md"),
                "화면명: 선불카드 배송 상세", StandardCharsets.UTF_8);
        Files.writeString(core.resolve("backoffice/pages/bo-delivery-detail.html"),
                "<html><body><h1>선불카드 배송 상세</h1></body></html>", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":[{\"id\":\"backoffice\",\"prefix\":\"bo\"}]}",
                StandardCharsets.UTF_8);
        projectSystems.syncFromRepo(projectId);
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
