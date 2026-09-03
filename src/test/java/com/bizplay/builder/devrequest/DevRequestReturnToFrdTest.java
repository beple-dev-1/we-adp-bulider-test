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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired DevRequestDeliveryMapper attempts;
    @Autowired DevelopmentRequestService service;
    @Autowired ProjectPaths paths;
    @Autowired FrdWorkspace workspaces;
    @Autowired GitCommand git;
    @Autowired IdSequence ids;

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

    @Test
    void 이미_보낸_것은_되돌리지_못한다() {
        Project project = readyProject("되돌리기-보냄");
        String frdId = frd(project, Frd.State.SCOPE_REVIEW);
        String requestId = service.createFromConfirmedScope(project.getId(), frdId).id();
        requests.requestDelivery(requestId, null, null, null, null, null, null, null, null);
        assertThat(attempts.moveFromSending(requestId, DeliveryOutcome.SENT)).isEqualTo(1);

        assertThatThrownBy(() -> service.returnToFrd(project.getId(), requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전송 전");
        assertThat(requests.selectById(requestId)).isNotNull();
        assertThat(frds.selectById(frdId).state()).isEqualTo(Frd.State.REVIEW);
    }

    @Test
    void 다른_개발요청서가_앞_것으로_가리키면_되돌리지_못한다() {
        Project project = readyProject("되돌리기-참조");
        String earlier = service.createFromConfirmedScope(project.getId(),
                frd(project, Frd.State.SCOPE_REVIEW)).id();
        String later = service.createFromConfirmedScope(project.getId(),
                frd(project, Frd.State.SCOPE_REVIEW)).id();
        requests.requestDelivery(later, null, null, null, null, null, null, earlier, null);

        assertThatThrownBy(() -> service.returnToFrd(project.getId(), earlier))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("앞 개발요청서");
        assertThat(requests.selectById(earlier)).isNotNull();
    }

    @Test
    void 실패한_전송_시도_이력이_있어도_지워진다() {
        Project project = readyProject("되돌리기-시도");
        String frdId = frd(project, Frd.State.SCOPE_REVIEW);
        String requestId = service.createFromConfirmedScope(project.getId(), frdId).id();
        String attemptId = ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY);
        attempts.insert(new DevRequestDeliveryAttempt(attemptId, requestId, "DRK-시험", null,
                DeliveryOutcome.SENDING, null, null, null, null, null, null));
        attempts.finish(attemptId, DeliveryOutcome.NOT_SENT, 502, null, "창구가 응답하지 않았다");

        service.returnToFrd(project.getId(), requestId);

        assertThat(requests.selectById(requestId)).isNull();
        assertThat(attempts.selectByRequestId(requestId)).isEmpty();
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
