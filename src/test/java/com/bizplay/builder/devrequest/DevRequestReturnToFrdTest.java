package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdWorkspace;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「FRD 로 되돌리기」 — 전송 전 개발요청서를 지우고 FRD 작업을 다시 연다 (병주 지시 2026-08-25).
 *
 * <p>⭐ 왜 있나: 개발요청서가 생기면 FRD 는 {@code REVIEW} 로 가고 거기서 나가는 길이 {@code DONE} 만이라,
 * 인터뷰가 남긴 「확인 필요」를 정리하거나 화면을 더 고칠 길이 없었다. 반대쪽 「전송 철회」는 있는데
 * 전송 전 폐기가 없는 것도 비대칭이다.
 *
 * <p>⚠ FRD 가 어디로 돌아가나는 <b>워크트리 존재</b>로 가른다 — {@code DRAFTING} 은 워크트리 생성 뒤에만
 * 찍히고(생애 설계), 워크트리는 {@code reset}·{@code rollback} 안에서만 지워진다.
 */
class DevRequestReturnToFrdTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired DevelopmentRequestService service;
    @Autowired ProjectPaths paths;
    @Autowired FrdWorkspace workspaces;
    @Autowired GitCommand git;
    @Autowired IdSequence ids;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 작업대_없이_만든_것은_개발_범위_확인으로_돌아간다() {
        Project project = readyProject("되돌리기-간단");
        String frdId = frd(project, Frd.State.SCOPE_REVIEW);
        String requestId = service.createFromConfirmedScope(project.getId(), frdId).id();
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.REVIEW);

        service.returnToFrd(project.getId(), requestId);

        assertThat(requests.selectById(requestId)).isNull();
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.SCOPE_REVIEW);
    }

    @Test
    void 작업대에서_완료한_것은_수정_중으로_돌아간다() throws IOException {
        Project project = readyProject("되돌리기-작업대");
        String frdId = frd(project, Frd.State.DRAFTING);
        Path worktree = paths.frdWorktree(project.getId(), frdId);
        Files.createDirectories(worktree);
        try {
            // 작업대 완료가 남기는 모양 그대로 — 화면 파일 하나가 「작업 완료」 커밋으로 묶여 있다.
            git(worktree, "init", "-q");
            git(worktree, "config", "user.email", "t@example.com");
            git(worktree, "config", "user.name", "시험");
            Files.writeString(worktree.resolve("README.md"), "# 기획 저장소\n");
            git(worktree, "add", ".");
            git(worktree, "commit", "-q", "-m", "첫 커밋");
            Files.writeString(worktree.resolve("wv-usage-detail.html"), "<main>고친 화면</main>");
            git(worktree, "add", ".");
            git(worktree, "commit", "-q", "-m",
                    FrdWorkspace.completionMessage(frds.selectById(frdId).label()));
            String requestId = service.createFromCompletedFrd(project.getId(), frdId).id();

            service.returnToFrd(project.getId(), requestId);

            assertThat(requests.selectById(requestId)).isNull();
            assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.DRAFTING);
            // ⭐ 완료 커밋을 풀어 고친 파일이 다시 「수정 중」이 된다 — 그래야 「작업 완료」 버튼이 켜진다.
            //    (2026-08-25 병주 실측: 되돌아가도 버튼이 꺼져 있었다)
            assertThat(worktree.resolve("wv-usage-detail.html")).hasContent("<main>고친 화면</main>");
            assertThat(workspaces.hasChanges(project.getId(), frdId)).isTrue();
        } finally {
            // ⚠ 임시 자리를 여러 시험이 같이 써서 남의 파일이 들어 있을 수 있다 — 통째로 지운다.
            try (var walk = Files.walk(worktree)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 임시 폴더 정리 실패는 시험 결과가 아니다.
                    }
                });
            }
        }
    }

    /**
     * ⛔ <b>003 이전에 쓰인 전송 시도 줄이 남아 있어도 되돌아가야 한다.</b>
     *
     * <p>003(2026-09-06)에서 전송을 없앴지만 표 {@code adk_builder_dev_request_delivery} 는
     * 죽은 채 남겼고, 그 FK 에 {@code on delete cascade} 가 <b>없다</b>({@code V48}).
     * 옛 줄을 먼저 안 치우면 {@code deleteNotSent} 가 FK 위반으로 죽어 화면이 500 이 된다 —
     * 그러면 기획자가 빠져나갈 길이 없다. 새로 쓰는 코드는 없지만 <b>옛 자료가 있는 DB</b> 가 있다.
     */
    @Test
    void 옛_전송_시도_줄이_남아_있어도_FRD로_되돌아간다() {
        Project project = readyProject("되돌리기-옛시도");
        String frdId = frd(project, Frd.State.SCOPE_REVIEW);
        String requestId = service.createFromConfirmedScope(project.getId(), frdId).id();
        jdbc.update("""
                insert into builder.adk_builder_dev_request_delivery
                       (dev_request_id, delivery_key, body_fingerprint, outcome, failure)
                values (?, ?, ?, 'NOT_SENT', '창구가 응답하지 않았다')
                """, requestId, "DRK-옛시도", "a".repeat(64));

        service.returnToFrd(project.getId(), requestId);

        assertThat(requests.selectById(requestId)).isNull();
        Integer left = jdbc.queryForObject(
                "select count(*) from builder.adk_builder_dev_request_delivery where dev_request_id = ?",
                Integer.class, requestId);
        assertThat(left).isZero();
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private void git(Path directory, String... args) {
        var result = git.run(directory, Duration.ofSeconds(30), args);
        assertThat(result.succeeded()).as(result.stderr()).isTrue();
    }

    private String frd(Project project, Frd.State state) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "웹뷰 이용내역 상세조회 기간 안내 문구 수정", "기간 안내 문구를 고친다.", planner().getId()));
        frds.updateAfterPick(id, "웹뷰 이용내역 상세조회 기간 안내 문구 수정", "webview", null,
                Frd.State.PICKED, null);
        frds.updateState(id, state);
        return id;
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/" + name + ".git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account planner() {
        return accounts.selectByLoginId("drreturn").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "drreturn", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
