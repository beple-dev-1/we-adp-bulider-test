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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 매뉴얼 내려받기 (그린존 A2 · 2026-08-27).
 *
 * <p>목록의 내려받기는 <b>현재 시스템 거르개가 걸려 있으면 그 시스템의 마지막 정상본만</b> 담는다.
 *
 * <p>⛔ <b>꾸러미 안에 한글 이름을 쓰지 않는다.</b> {@code java.util.zip} 의 UTF-8 이름은 옛 도구에서
 * 깨질 수 있다 — {@code DevRequestPackageZipper} 가 그 위험을 이름을 영문으로 바꿔 없앴고,
 * 여기도 화면ID·시스템 코드가 영문이라 기대는 자리가 없다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class UserManualDownloadTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectSystemService projectSystems;
    @Autowired ProjectPaths paths;
    @Autowired UserManualMapper manuals;

    /** ⛔ <b>다 만들어진 것만 담는다.</b> 만드는 중인 것을 빈 파일로 담으면 받은 사람이 속는다. */
    @Test
    void 다_만들어진_것만_영문_이름으로_담는다() throws Exception {
        Project project = readyProject("매뉴얼-내려받기");
        namedSystems(project.getId());
        manuals.upsert(UserManual.of(project.getId(), "backoffice", "bo-delivery-detail", "<h1>배송</h1>"));
        manuals.upsert(UserManual.of(project.getId(), "webview", "wv-sample-home", "<h1>홈</h1>"));
        manuals.upsert(UserManual.of(project.getId(), "backoffice", "bo-removed-screen", "<h1>삭제됨</h1>"));
        manuals.beginGeneration(project.getId(), "backoffice", "bo-still-running",
                UUID.randomUUID().toString(), Instant.now().minusSeconds(900));

        List<String> names = entryNames(zip(project.getId(), ""));

        assertThat(names)
                .containsExactly("backoffice/bo-delivery-detail.html", "webview/wv-sample-home.html");
        assertThat(names)
                .as("⛔ 꾸러미 이름에 한글이 섞이면 옛 도구에서 깨진다")
                .allSatisfy(name -> assertThat(name).matches("[\\x20-\\x7E]+"));
    }

    @Test
    void 시스템_거르개가_걸리면_그_시스템만_담는다() throws Exception {
        Project project = readyProject("매뉴얼-내려받기-거르개");
        namedSystems(project.getId());
        manuals.upsert(UserManual.of(project.getId(), "backoffice", "bo-delivery-detail", "<h1>배송</h1>"));
        manuals.upsert(UserManual.of(project.getId(), "webview", "wv-sample-home", "<h1>홈</h1>"));

        assertThat(entryNames(zip(project.getId(), "?system=backoffice")))
                .containsExactly("backoffice/bo-delivery-detail.html");
    }

    /** ⚠ 빈 zip 을 내려주지 않는다 — 받은 사람이 「받았는데 비었다」로 읽고 까닭을 못 짚는다. */
    @Test
    void 담을_것이_없으면_없다고_답한다() throws Exception {
        Project project = readyProject("매뉴얼-내려받기-없음");
        namedSystems(project.getId());

        mvc.perform(get(base(project.getId()) + "/download").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private String base(String projectId) {
        return "/projects/" + projectId + "/artifacts/user-manual";
    }

    private byte[] zip(String projectId, String queryString) throws Exception {
        var pending = mvc.perform(get(base(projectId) + "/download" + queryString).with(user(superUser())))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private List<String> entryNames(byte[] bytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    /**
     * ⚠ <b>시스템은 클론의 {@code manifest.json} 에서 온다.</b> {@code syncFromRepo} 없이 이름만
     * 바꾸면 바꿀 줄이 없어 조용히 아무것도 안 되고, 거르개가 코드로 뜬다(여기서 한 번 걸렸다).
     */
    private void namedSystems(String projectId) throws IOException {
        Path root = paths.cloneDir(projectId);
        Path manifest = root.resolve("manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest,
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":["
                        + "{\"id\":\"backoffice\",\"prefix\":\"bo\"},"
                        + "{\"id\":\"webview\",\"prefix\":\"wv\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("index.json"), """
                {"schema":"we-adk-index/3","screens":{
                  "bo-delivery-detail":{"system":"backoffice","ia":{"종류":"화면"}},
                  "wv-sample-home":{"system":"webview","ia":{"종류":"화면"}}
                },"counts":{"screens":2}}
                """, StandardCharsets.UTF_8);
        writeScreen(root, "backoffice", "bo-delivery-detail", "배송 상세");
        writeScreen(root, "webview", "wv-sample-home", "홈");
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId,
                new LinkedHashMap<>(Map.of("backoffice", "백오피스", "webview", "웹뷰")));
    }

    private void writeScreen(Path root, String system, String screenId, String name) throws IOException {
        Path file = root.resolve("core").resolve(system).resolve("pages").resolve(screenId + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "화면명: " + name + "\n목적: 내려받기 시험 화면", StandardCharsets.UTF_8);
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
