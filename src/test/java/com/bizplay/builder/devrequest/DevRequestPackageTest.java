package com.bizplay.builder.devrequest;

import com.bizplay.builder.git.GitCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전송 꾸러미의 <b>화면 층과 {@code manifest.json}</b> 을 제대로 뽑나.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⭐ <b>여기서 증명하는 것은 「무엇을 → 무엇으로」다.</b> to-be 만 나가면 개발이 diff 를 스스로
 * 짜야 하고, 뒤에 「원래 그랬다/아니다」를 가릴 근거가 없다. as-is 는 완료 커밋의 <b>앞판</b>에서
 * 뽑는다 — 워크트리가 지워지지 않으므로 같은 자리에서 둘 다 나온다.
 */
class DevRequestPackageTest {

    @TempDir Path root;

    private GitCommand git;
    private DevRequestPackage packages;
    private Path worktree;
    private String asIsSha;
    private String toBeSha;

    @BeforeEach
    void setUp() throws IOException {
        git = new GitCommand();
        packages = new DevRequestPackage(git, Duration.ofSeconds(30));
        worktree = root.resolve("worktree");
        Files.createDirectories(worktree.resolve("core/EXW/pages"));
        write("core/EXW/pages/EXW-UWV-70-30-10-C.html", "<main>현재 회원가입</main>");
        write("core/EXW/pages/EXW-UWV-70-30-10-C.md", "# 회원가입\n현재 정의서\n");
        run("init", "-q");
        run("config", "user.email", "t@example.com");
        run("config", "user.name", "시험");
        run("add", ".");
        run("commit", "-q", "-m", "as-is");
        asIsSha = run("rev-parse", "HEAD").stdout().strip();

        write("core/EXW/pages/EXW-UWV-70-30-10-C.html", "<main>프리필된 회원가입</main>");
        write("core/EXW/pages/EXW-UWV-70-30-10-C.md", "# 회원가입\n프리필 정의서\n");
        run("add", ".");
        run("commit", "-q", "-m", "to-be");
        toBeSha = run("rev-parse", "HEAD").stdout().strip();
    }

    @Test
    void 화면마다_앞판과_뒷판을_갈라_담는다() throws IOException {
        Path out = root.resolve("DR-009");

        packages.write(worktree, request(), out);

        Path screen = out.resolve("screens/EXW/EXW-UWV-70-30-10-C");
        assertThat(Files.readString(screen.resolve("as-is.html"))).contains("현재 회원가입");
        assertThat(Files.readString(screen.resolve("to-be.html"))).contains("프리필된 회원가입");
        assertThat(Files.readString(screen.resolve("as-is.md"))).contains("현재 정의서");
        assertThat(Files.readString(screen.resolve("to-be.md"))).contains("프리필 정의서");
        assertThat(Files.readString(screen.resolve("changes.md")))
                .contains("회원정보를 미리 채운다").contains("약관 동의는 그대로 둔다");
    }

    @Test
    void manifest_가_파일_목록과_두_커밋을_담는다() throws IOException {
        Path out = root.resolve("DR-009");

        packages.write(worktree, request(), out);

        JsonNode manifest = new ObjectMapper().readTree(out.resolve("manifest.json").toFile());
        assertThat(manifest.get("specVersion").asInt()).isEqualTo(2);
        assertThat(manifest.get("request").get("label").asText()).isEqualTo("DR-009");
        assertThat(manifest.get("request").get("asIsCommit").asText()).isEqualTo(asIsSha);
        assertThat(manifest.get("request").get("toBeCommit").asText()).isEqualTo(toBeSha);
        JsonNode files = manifest.get("screens").get(0).get("files");
        assertThat(files).hasSize(5);
        assertThat(files.get(0).get("path").asText())
                .isEqualTo("screens/EXW/EXW-UWV-70-30-10-C/as-is.html");
        assertThat(files.get(0).get("sha256").asText()).hasSize(64);
    }

    /**
     * ⚠ <b>신규 화면은 앞판이 없다.</b> 그때 빈 파일을 놓으면 개발이 「원래 빈 화면이었다」로 읽는다 —
     * 파일을 만들지 않고 {@code manifest} 가 「없음」을 말한다.
     */
    @Test
    void 신규_화면은_앞판_파일을_만들지_않고_없다고_적는다() throws IOException {
        write("core/EXW/pages/exw-list-42.html", "<main>새 목록</main>");
        run("add", ".");
        run("commit", "-q", "-m", "신규 화면");
        String head = run("rev-parse", "HEAD").stdout().strip();
        Path out = root.resolve("DR-010");

        packages.write(worktree, new DevRequestPackage.Request("DR-010", asIsSha, head,
                List.of(new DevRequestPackage.Screen("EXW", "exw-list-42", "새 목록", List.of("새로 만든다")))), out);

        Path screen = out.resolve("screens/EXW/exw-list-42");
        assertThat(screen.resolve("as-is.html")).doesNotExist();
        assertThat(screen.resolve("to-be.html")).exists();
        JsonNode manifest = new ObjectMapper().readTree(out.resolve("manifest.json").toFile());
        assertThat(manifest.get("screens").get(0).get("newScreen").asBoolean()).isTrue();
    }

    /** ⚠ 화면 0장이 정상이다 — 백엔드만인 FRD 는 워크트리 커밋도 없다. 꾸러미가 그래도 서야 한다. */
    @Test
    void 화면이_없어도_꾸러미가_선다() throws IOException {
        Path out = root.resolve("DR-011");

        packages.write(worktree, new DevRequestPackage.Request("DR-011", asIsSha, toBeSha, List.of()), out);

        JsonNode manifest = new ObjectMapper().readTree(out.resolve("manifest.json").toFile());
        assertThat(manifest.get("screens")).isEmpty();
        assertThat(out.resolve("screens")).doesNotExist();
    }

    private DevRequestPackage.Request request() {
        return new DevRequestPackage.Request("DR-009", asIsSha, toBeSha,
                List.of(new DevRequestPackage.Screen("EXW", "EXW-UWV-70-30-10-C", "에이블리 회원가입",
                        List.of("회원정보를 미리 채운다", "약관 동의는 그대로 둔다"))));
    }

    private void write(String relative, String content) throws IOException {
        Path target = worktree.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private com.bizplay.builder.git.GitResult run(String... args) {
        com.bizplay.builder.git.GitResult result = git.run(worktree, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
        return result;
    }
}
