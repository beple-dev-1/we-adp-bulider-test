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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 꾸러미를 <b>전달 전용 브랜치</b>로 올리나.
 *
 * <p>⭐ <b>기본 브랜치에서 갈라 만든다.</b> 그 브랜치의 {@code core/<시스템>/pages/} 는
 * as-is 그대로여서 「기본 브랜치 계열의 {@code pages/} 는 사실이다」가 지켜진다 —
 * to-be 는 꾸러미 안에만 있다.
 *
 * <p>⛔ <b>FRD 브랜치는 올리지 않는다</b>(2026-09-22 확정). 올리면 to-be 가 담긴
 * {@code pages/} 가 원격에 생겨 「그것을 as-is 로 읽는다」와 「PR 로 기본 브랜치에 병합된다」가
 * 열린다.
 */
class DevRequestDeliveryWorkspaceTest {

    private static final String PROJECT = "0000001";
    private static final String REQUEST = "0000009";

    @TempDir Path dataRoot;

    private GitCommand git;
    private ProjectPaths paths;
    private DevRequestDeliveryWorkspace deliveries;
    private Path clone;
    private Path remote;

    @BeforeEach
    void setUp() throws IOException {
        BuilderProperties properties = new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                dataRoot, Duration.ofMinutes(10), 4, 50, Duration.ofMinutes(2));
        git = new GitCommand();
        paths = new ProjectPaths(properties);
        deliveries = new DevRequestDeliveryWorkspace(paths, git, Duration.ofSeconds(30));

        clone = paths.cloneDir(PROJECT);
        Files.createDirectories(clone.resolve("core/EXW/pages"));
        Files.writeString(clone.resolve("core/EXW/pages/EXW-UWV-70-30-10-C.html"),
                "<main>현재 회원가입</main>", StandardCharsets.UTF_8);
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
    void 꾸러미를_전달_전용_브랜치로_올리고_기본_브랜치는_그대로_둔다() throws IOException {
        String remoteMainBefore = run(remote, "rev-parse", "refs/heads/main").stdout().strip();

        var published = deliveries.publish(PROJECT, REQUEST, "EXW", "DR-009", "main",
                remote.toUri().toString(), this::writePackage);

        assertThat(published.branch()).isEqualTo("dr/EXW/DR-009");
        assertThat(run(remote, "rev-parse", "refs/heads/dr/EXW/DR-009").stdout().strip())
                .isEqualTo(published.commit());
        assertThat(run(remote, "rev-parse", "refs/heads/main").stdout().strip())
                .isEqualTo(remoteMainBefore);
    }

    /** ⭐ 꾸러미는 <b>저장소 뿌리</b>에 앉는다 (사용자 확정 2026-09-22). */
    @Test
    void 꾸러미는_저장소_뿌리에_앉고_as_is_는_원자리_그대로다() throws IOException {
        var published = deliveries.publish(PROJECT, REQUEST, "EXW", "DR-009", "main",
                remote.toUri().toString(), this::writePackage);

        String tree = run(remote, "ls-tree", "-r", "--name-only", published.commit()).stdout();
        assertThat(tree).contains("DR-009/dev-request.md")
                .contains("DR-009/screens/EXW/EXW-UWV-70-30-10-C/to-be.html")
                // ⭐ 기본 브랜치의 as-is 가 그 브랜치에도 그대로 있다 — 갈라 나온 자리라서다.
                .contains("core/EXW/pages/EXW-UWV-70-30-10-C.html");
        assertThat(run(remote, "show", published.commit() + ":core/EXW/pages/EXW-UWV-70-30-10-C.html")
                .stdout()).contains("현재 회원가입");
    }

    /** ⚠ <b>다시 구우면 통째로 갈아 낀다.</b> 판이 남으면 어느 것을 보냈는지 알 수 없다. */
    @Test
    void 다시_구우면_앞판이_남지_않는다() throws IOException {
        deliveries.publish(PROJECT, REQUEST, "EXW", "DR-009", "main", remote.toUri().toString(),
                (worktree, base) -> {
                    write(worktree, "DR-009/dev-request.md", "첫 판");
                    write(worktree, "DR-009/버린다.md", "두 번째 판에는 없다");
                });

        var again = deliveries.publish(PROJECT, REQUEST, "EXW", "DR-009", "main",
                remote.toUri().toString(), this::writePackage);

        String tree = run(remote, "ls-tree", "-r", "--name-only", again.commit()).stdout();
        assertThat(tree).doesNotContain("DR-009/버린다.md");
        assertThat(run(remote, "show", again.commit() + ":DR-009/dev-request.md").stdout())
                .contains("에이블리 회원가입 프리필");
    }

    /** ⛔ 전달 워크트리는 전송이 끝나면 지운다. FRD 워크트리는 남긴다 — 그쪽은 재전송의 재료다. */
    @Test
    void 전달_워크트리는_전송이_끝나면_치운다() throws IOException {
        deliveries.publish(PROJECT, REQUEST, "EXW", "DR-009", "main",
                remote.toUri().toString(), this::writePackage);

        assertThat(paths.devRequestDeliveryWorktree(PROJECT, REQUEST)).doesNotExist();
        assertThat(run(clone, "worktree", "list").stdout()).doesNotContain("dr-" + REQUEST);
    }

    /** ⛔ 실패 메시지가 토큰을 실어 나르지 않는다 — git 은 실패하면 원격 주소를 그대로 되뱉는다. */
    @Test
    void 올리지_못하면_자격을_가리고_알린다() {
        assertThatThrownBy(() -> deliveries.publish(PROJECT, REQUEST, "EXW", "DR-009", "main",
                "https://oauth2:비밀토큰1234@localhost:1/planning.git", this::writePackage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("비밀토큰1234");
    }

    /**
     * ⭐ <b>목록은 전용 브랜치에 올리고 기본 브랜치는 안 움직인다</b> (2026-09-23 사용자 확정).
     * 기본 브랜치에 올리던 판은 넘기기가 스스로 {@code main} 을 한 판 앞으로 밀어, 꾸러미가 알린
     * 기준 커밋이 곧바로 낡았다 — 문서대로 한 개발이 늘 「기준이 다르다」로 거절됐다.
     */
    @Test
    void 목록을_전용_브랜치에_올리고_기본_브랜치는_안_움직인다() {
        String mainBefore = run(remote, "rev-parse", "refs/heads/main").stdout().strip();

        deliveries.updateIndexBranch(PROJECT, REQUEST, remote.toUri().toString(),
                existing -> "{\"deliveries\": [{\"dr\": \"DR-009\"}]}\n", "docs: DR-009 전달 목록");

        assertThat(run(remote, "rev-parse", "refs/heads/main").stdout().strip()).isEqualTo(mainBefore);
        String ref = "refs/heads/" + DeliveryIndex.BRANCH;
        assertThat(run(remote, "show", ref + ":" + DeliveryIndex.PATH).stdout()).contains("DR-009");
        // ⭐ 뿌리가 따로인 브랜치다 — main 의 파일도 이력도 안 섞인다. 목록과 그 입구 README 뿐이다.
        assertThat(run(remote, "ls-tree", "-r", "--name-only", ref).stdout().strip().lines().sorted().toList())
                .containsExactly(DeliveryIndex.README, DeliveryIndex.PATH);
        assertThat(run(remote, "show", ref + ":" + DeliveryIndex.README).stdout())
                .contains(DeliveryIndex.deliveryBranch("<시스템>", "<dr>"));
        assertThat(run(remote, "rev-list", "--count", ref).stdout().strip()).isEqualTo("1");
    }

    /** ⭐ 앞서 적힌 줄을 <b>읽어서</b> 합친다 — 덮어쓰지 않는다. 두 번째부터는 앞 커밋 위에 쌓는다. */
    @Test
    void 앞서_올린_목록을_읽어_합친다() {
        deliveries.updateIndexBranch(PROJECT, REQUEST, remote.toUri().toString(),
                existing -> DeliveryIndex.merge(existing, indexEntry("DR-009")), "docs: DR-009");

        deliveries.updateIndexBranch(PROJECT, "0000010", remote.toUri().toString(),
                existing -> DeliveryIndex.merge(existing, indexEntry("DR-010")), "docs: DR-010");

        String ref = "refs/heads/" + DeliveryIndex.BRANCH;
        assertThat(run(remote, "show", ref + ":" + DeliveryIndex.PATH).stdout())
                .contains("DR-009").contains("DR-010");
        assertThat(run(remote, "rev-list", "--count", ref).stdout().strip()).isEqualTo("2");
    }

    /** ⚠ 바뀐 것이 없으면 빈 커밋을 만들지 않는다. */
    @Test
    void 바뀐_것이_없으면_커밋하지_않는다() {
        deliveries.updateIndexBranch(PROJECT, REQUEST, remote.toUri().toString(),
                existing -> "같은 내용\n", "docs: 처음");
        String ref = "refs/heads/" + DeliveryIndex.BRANCH;
        String before = run(remote, "rev-parse", ref).stdout().strip();

        String second = deliveries.updateIndexBranch(PROJECT, REQUEST, remote.toUri().toString(),
                existing -> "같은 내용\n", "docs: 두 번째");

        assertThat(second).isNull();
        assertThat(run(remote, "rev-parse", ref).stdout().strip()).isEqualTo(before);
    }

    private DeliveryIndex.Entry indexEntry(String dr) {
        return new DeliveryIndex.Entry(dr, DeliveryIndex.deliveryBranch("EXW", dr),
                "commit-" + dr, "base", "EXW",
                java.util.List.of("EXW-UWV-70-30-10-C"), java.time.Instant.parse("2026-09-22T08:24:58Z"),
                "key");
    }

    private void writePackage(Path worktree, String base) {
        write(worktree, "DR-009/dev-request.md", "# DR-009 · 에이블리 회원가입 프리필\n");
        write(worktree, "DR-009/manifest.json", "{\"specVersion\": 2}\n");
        write(worktree, "DR-009/screens/EXW/EXW-UWV-70-30-10-C/to-be.html", "<main>프리필된 회원가입</main>");
    }

    private void write(Path root, String relative, String content) {
        try {
            Path target = root.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private com.bizplay.builder.git.GitResult run(Path directory, String... args) {
        com.bizplay.builder.git.GitResult result = git.run(directory, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
        return result;
    }
}
