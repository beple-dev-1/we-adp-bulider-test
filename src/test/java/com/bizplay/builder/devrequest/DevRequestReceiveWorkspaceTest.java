package com.bizplay.builder.devrequest;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.project.ProjectPaths;
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
 * 개발이 돌려보낸 배치를 <b>골라 담아</b> 기본 브랜치에 놓나.
 *
 * <p>⭐ <b>통째 병합이 아니다.</b> 받지 않기로 한 것이 있으므로 {@code merge} 는 못 쓴다 —
 * 받을 파일만 꺼내 <b>커밋 하나</b>를 만든다.
 * ⭐ <b>하나라도 떨어지면 아무것도 안 놓는다.</b> 반쪽 상태를 다음 FRD 가 as-is 로 읽는 것이
 * 가장 나쁘다.
 */
class DevRequestReceiveWorkspaceTest {

    private static final String PROJECT = "0000001";
    private static final String REQUEST = "0000009";

    @TempDir Path dataRoot;

    private GitCommand git;
    private ProjectPaths paths;
    private DevRequestDeliveryWorkspace workspaces;
    private Path clone;
    private Path remote;

    @BeforeEach
    void setUp() throws IOException {
        BuilderProperties properties = new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                dataRoot, Duration.ofMinutes(10), 4, 50, Duration.ofMinutes(2));
        git = new GitCommand();
        paths = new ProjectPaths(properties);
        workspaces = new DevRequestDeliveryWorkspace(paths, git, Duration.ofSeconds(30));

        clone = paths.cloneDir(PROJECT);
        Files.createDirectories(clone.resolve("core/EXW/pages"));
        write(clone, "core/EXW/pages/EXW-1.html", "<main>기획이 그린 것</main>");
        write(clone, "core/EXW/pages/EXW-1.md", "기획이 쓴 정의서");
        write(clone, "index.json", "{\"screens\": {}}");
        run(clone, "init", "-q", "-b", "main");
        run(clone, "config", "user.email", "t@example.com");
        run(clone, "config", "user.name", "시험");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "as-is");

        remote = dataRoot.resolve("remote.git");
        run(dataRoot, "init", "--bare", "-q", remote.toString());
        run(clone, "push", "-q", remote.toUri().toString(), "HEAD:refs/heads/main");
    }

    @Test
    void 통과하면_바뀐_것만_골라_담아_기본_브랜치에_놓는다() throws IOException {
        pushFeedback("""
                {"dr": "DR-009", "base": "%s", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "changed"}
                ]}
                """.formatted(remoteMain()));

        var received = workspaces.receive(PROJECT, REQUEST, "main", remote.toUri().toString(),
                "feedback/DR-009", "DR-009/" + ReturnBatch.FILE,
                (returnJson, currentBase, returnedFile) -> ReturnBatch.judge(expected(), returnJson, currentBase),
                "chore: DR-009 개발 결과 반영");

        assertThat(received.accepted()).isTrue();
        String head = run(remote, "rev-parse", "refs/heads/main").stdout().strip();
        assertThat(run(remote, "show", head + ":core/EXW/pages/EXW-1.html").stdout())
                .contains("개발이 만든 것");
        // ⭐ unchanged 인 화면 md 는 기획이 쓴 것이 그대로 남는다 — 파일이 아니기 때문이다.
        assertThat(run(remote, "show", head + ":core/EXW/pages/EXW-1.md").stdout())
                .contains("기획이 쓴 정의서");
        assertThat(run(remote, "show", head + ":index.json").stdout()).contains("개발이 고친 색인");
    }

    /** ⭐ 설계가 「커밋 신원 — 『빌더 수신』」 절을 따로 둔 자리다 — 기획이 만든 커밋과 갈려야 한다. */
    @Test
    void 받기_커밋은_수신_신원으로_찍는다() throws IOException {
        pushFeedback("""
                {"dr": "DR-009", "base": "%s", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "changed"}
                ]}
                """.formatted(remoteMain()));

        workspaces.receive(PROJECT, REQUEST, "main", remote.toUri().toString(),
                "feedback/DR-009", "DR-009/" + ReturnBatch.FILE,
                (returnJson, currentBase, returnedFile) -> ReturnBatch.judge(expected(), returnJson, currentBase),
                "chore: DR-009 개발 결과 반영");

        String head = run(remote, "rev-parse", "refs/heads/main").stdout().strip();
        assertThat(run(remote, "log", "-1", "--format=%an", head).stdout()).contains("수신");
    }

    /** ⛔ 거절이면 기본 브랜치가 한 글자도 안 움직인다. */
    @Test
    void 거절이면_아무것도_안_놓는다() throws IOException {
        pushFeedback("""
                {"dr": "DR-009", "base": "낡은-기준", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "changed", "index": "changed"}
                ]}
                """);
        String before = remoteMain();

        var received = workspaces.receive(PROJECT, REQUEST, "main", remote.toUri().toString(),
                "feedback/DR-009", "DR-009/" + ReturnBatch.FILE,
                (returnJson, currentBase, returnedFile) -> ReturnBatch.judge(expected(), returnJson, currentBase),
                "chore: DR-009 개발 결과 반영");

        assertThat(received.accepted()).isFalse();
        assertThat(received.rejections()).isNotEmpty();
        assertThat(remoteMain()).isEqualTo(before);
    }

    /** ⚠ 회신서가 아예 없으면 거절이다 — 배치가 뭔지 알 길이 없다. */
    @Test
    void 회신서가_없으면_거절한다() throws IOException {
        Path work = dataRoot.resolve("dev");
        run(dataRoot, "clone", "-q", remote.toUri().toString(), work.toString());
        run(work, "config", "user.email", "d@example.com");
        run(work, "config", "user.name", "개발");
        run(work, "checkout", "-q", "-b", "feedback/DR-009");
        write(work, "core/EXW/pages/EXW-1.html", "<main>개발이 만든 것</main>");
        run(work, "add", ".");
        run(work, "commit", "-q", "-m", "개발 결과");
        run(work, "push", "-q", "origin", "feedback/DR-009");

        var received = workspaces.receive(PROJECT, REQUEST, "main", remote.toUri().toString(),
                "feedback/DR-009", "DR-009/" + ReturnBatch.FILE,
                (returnJson, currentBase, returnedFile) -> ReturnBatch.judge(expected(), returnJson, currentBase),
                "chore: DR-009 개발 결과 반영");

        assertThat(received.accepted()).isFalse();
        assertThat(received.rejections()).anyMatch(reason -> reason.contains("회신서"));
    }

    private void pushFeedback(String returnJson) throws IOException {
        Path work = dataRoot.resolve("dev-" + System.nanoTime());
        run(dataRoot, "clone", "-q", remote.toUri().toString(), work.toString());
        run(work, "config", "user.email", "d@example.com");
        run(work, "config", "user.name", "개발");
        run(work, "checkout", "-q", "-b", "feedback/DR-009");
        write(work, "core/EXW/pages/EXW-1.html", "<main>개발이 만든 것</main>");
        write(work, "index.json", "{\"screens\": {}, \"메모\": \"개발이 고친 색인\"}");
        write(work, "DR-009/" + ReturnBatch.FILE, returnJson);
        run(work, "add", ".");
        run(work, "commit", "-q", "-m", "개발 결과");
        run(work, "push", "-q", "origin", "feedback/DR-009");
    }

    private String remoteMain() {
        return run(remote, "rev-parse", "refs/heads/main").stdout().strip();
    }

    private ExpectedBack expected() {
        return new ExpectedBack("feedback/DR-009", remoteMain(),
                List.of(new ExpectedBack.Screen("EXW-1", "EXW",
                        List.of(ExpectedBack.PAGES, ExpectedBack.SCREEN_MD, ExpectedBack.INDEX))),
                List.of(), List.of(), List.of());
    }

    private void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private com.bizplay.builder.git.GitResult run(Path directory, String... args) {
        com.bizplay.builder.git.GitResult result = git.run(directory, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
        return result;
    }
}
