package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트의 시스템 등록 — 코드는 레포에서, 이름은 사람에게서.
 *
 * <p>⭐ <b>이 시험이 지키는 것</b> — 2026-08-21 까지 시스템 한글 이름은 자바 상수 세 줄이었다
 * ({@code SolutionScreen.SYSTEM_LABELS}). 실물 레포의 시스템이 여섯이 되자 나머지 셋
 * ({@code saleoffice}·{@code lspnoffice}·{@code portal})이 화면에 영문으로 떴다.
 * 채번이 2026-08-20 에 같은 실수를 {@code yml} 에서 겪었다 — 그래서 <b>코드 목록의 정본은
 * 레포({@code manifest.json})</b>이고 이름만 프로젝트에 등록한다.
 */
@AutoConfigureMockMvc
class ProjectSystemTest extends AbstractDbTest {

    private Path cloneToClean;

    @Autowired MockMvc mvc;
    @Autowired ProjectMapper projects;
    @Autowired ProjectPaths paths;
    @Autowired SecretSealer sealer;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectSystemService systems;
    @Autowired ProjectSystemMapper systemRows;

    @AfterEach
    void cleanClone() {
        if (cloneToClean != null) FileSystemUtils.deleteRecursively(cloneToClean.toFile());
    }

    @Test
    void manifest_의_시스템_전부가_이름_없이_앉는다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice", "webview", "online-pg", "saleoffice", "lspnoffice", "portal");

        systems.syncFromRepo(projectId);

        // ⭐ 실물 레포(planning-g2c)의 여섯이다. 셋만 아는 표를 코드에 두면 나머지 셋이 영문으로 뜬다.
        assertThat(systems.all(projectId)).extracting(ProjectSystem::systemCode)
                .containsExactly("backoffice", "lspnoffice", "online-pg", "portal", "saleoffice", "webview");
        assertThat(systems.all(projectId)).allMatch(system -> !system.named());
    }

    /** ⚠ 이름이 없는 것이 정상이다 — 빈칸을 내면 「시스템이 없는 화면」으로 보인다. */
    @Test
    void 이름이_없으면_코드를_그대로_낸다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice", "saleoffice");
        systems.syncFromRepo(projectId);

        systems.replaceNames(projectId, Map.of("backoffice", "백오피스"));

        assertThat(systems.labels(projectId).label("backoffice")).isEqualTo("백오피스");
        assertThat(systems.labels(projectId).label("saleoffice")).isEqualTo("saleoffice");
        // 등록조차 안 된 코드도 그대로 낸다 — 동기화가 아직 안 돈 프로젝트가 실제로 있다.
        assertThat(systems.labels(projectId).label("webview")).isEqualTo("webview");
    }

    /**
     * ⛔ 여기가 이 기능의 급소다. {@code manifest.json} 을 한 번 못 읽었다고 0행으로 밀면
     * 사람이 적어 둔 한글 이름이 통째로 날아간다.
     */
    @Test
    void manifest_를_못_읽으면_이미_넣은_이름을_그대로_둔다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice");
        systems.syncFromRepo(projectId);
        systems.replaceNames(projectId, Map.of("backoffice", "백오피스"));

        Files.delete(paths.cloneDir(projectId).resolve("manifest.json"));
        systems.syncFromRepo(projectId);

        assertThat(systems.all(projectId)).extracting(ProjectSystem::displayName).containsExactly("백오피스");
    }

    @Test
    void 레포에서_사라진_시스템은_목록에서_빠진다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice", "webview");
        systems.syncFromRepo(projectId);

        writeManifest(projectId, "backoffice");
        systems.syncFromRepo(projectId);

        assertThat(systems.all(projectId)).extracting(ProjectSystem::systemCode).containsExactly("backoffice");
    }

    /** ⚠ 이름은 사람의 것이라 동기화가 다시 돌아도 안 지워진다. */
    @Test
    void 다시_동기화해도_넣은_이름은_살아남는다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice", "webview");
        systems.syncFromRepo(projectId);
        systems.replaceNames(projectId, Map.of("backoffice", "백오피스", "webview", "웹뷰"));

        systems.syncFromRepo(projectId);

        assertThat(systems.all(projectId)).extracting(ProjectSystem::label)
                .containsExactly("백오피스", "웹뷰");
    }

    @Test
    void 관리_상세가_시스템을_코드와_함께_내고_이름을_저장한다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice", "saleoffice");
        systems.syncFromRepo(projectId);

        String before = detail(projectId);
        assertThat(before)
                .as("코드 칸은 읽기 전용이다 — 목록의 정본이 레포라서다")
                .contains("시스템 관리", "name=\"systemCodes\"", "readonly", "saleoffice")
                .contains("system-setting-table__head", "system-setting-list--table")
                .contains("data-submit-loading=\"시스템 이름 저장 중\"")
                .doesNotContain("시스템 추가");

        mvc.perform(post("/admin/projects/" + projectId + "/systems")
                        .param("systemCodes", "backoffice", "saleoffice")
                        .param("systemNames", "백오피스", "판매점오피스")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(detail(projectId)).contains("백오피스", "판매점오피스");
    }

    /**
     * ⛔ 코드의 정본은 레포다. 손으로 넣은 코드는 어느 화면의 자료와도 만나지 못하므로
     * 관리 화면에만 앉은 줄이 되어 「등록했는데 아무 데도 안 나온다」가 된다.
     */
    @Test
    void 레포에_없는_코드를_보내도_새_줄이_생기지_않는다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice");
        systems.syncFromRepo(projectId);

        mvc.perform(post("/admin/projects/" + projectId + "/systems")
                        .param("systemCodes", "backoffice", "지어낸시스템")
                        .param("systemNames", "백오피스", "없는 것")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(systemRows.selectByProjectId(projectId)).extracting(ProjectSystem::systemCode)
                .containsExactly("backoffice");
    }

    /** ⚠ 솔루션 목업의 거르개가 <b>이름</b>으로 거른다 — 이름이 겹치면 숫자를 못 믿는다. */
    @Test
    void 같은_이름을_두_시스템에_주면_거절된다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice", "webview");
        systems.syncFromRepo(projectId);

        assertThatThrownBy(() -> systems.replaceNames(projectId,
                new java.util.LinkedHashMap<>(Map.of("backoffice", "같은이름", "webview", "같은이름"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 표시 이름");

        mvc.perform(post("/admin/projects/" + projectId + "/systems")
                        .param("systemCodes", "backoffice", "webview")
                        .param("systemNames", "같은이름", "같은이름")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isOk())   // ⛔ 목록이 아니라 폼이 있는 상세로 되돌아온다
                .andReturn();

        assertThat(systemRows.selectByProjectId(projectId)).allMatch(system -> !system.named());
    }

    /** 이름을 지우면 코드로 되돌아간다 — 빈 칸과 「처음부터 없음」을 같은 것으로 다룬다. */
    @Test
    void 이름을_비우면_코드로_되돌아간다() throws Exception {
        String projectId = readyProject();
        writeManifest(projectId, "backoffice");
        systems.syncFromRepo(projectId);
        systems.replaceNames(projectId, Map.of("backoffice", "백오피스"));

        mvc.perform(post("/admin/projects/" + projectId + "/systems")
                        .param("systemCodes", "backoffice")
                        .param("systemNames", "  ")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(systems.labels(projectId).label("backoffice")).isEqualTo("backoffice");
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String detail(String projectId) throws Exception {
        return mvc.perform(get("/admin/projects/" + projectId).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** {@code manifest.json} 만 있는 최소 클론. 동기화가 읽는 것은 이 파일 하나다. */
    private void writeManifest(String projectId, String... systemIds) throws Exception {
        StringBuilder body = new StringBuilder();
        for (String id : systemIds) {
            if (body.length() > 0) body.append(',');
            // ⚠ prefix 는 채번의 것이다. 시스템 등록은 id 만 본다 — 없어도 시스템은 있는 것이다.
            body.append("{\"id\":\"").append(id).append("\",\"prefix\":\"xx\"}");
        }
        Path clone = paths.cloneDir(projectId);
        Files.createDirectories(clone);
        cloneToClean = clone;
        Files.writeString(clone.resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":[" + body + "]}",
                StandardCharsets.UTF_8);
    }

    private String readyProject() {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "시스템 등록 시험 " + id,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return id;
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
