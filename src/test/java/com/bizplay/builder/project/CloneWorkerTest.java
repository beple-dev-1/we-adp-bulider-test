package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.git.RepoProbe;
import com.bizplay.builder.screenid.ScreenStandardIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloneWorkerTest extends AbstractDbTest {

    @Autowired ProjectService projects;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 재는 것은 그대로 「DB 에 뭐가 남았나」다. */
    @Autowired ProjectMapper repository;
    @Autowired ProjectPaths paths;
    @MockitoBean RepoProbe probe;
    @MockitoBean GitCommand git;
    @MockitoBean ScreenStandardIdWorker screenIds;
    @MockitoBean ProjectSystemService projectSystems;

    /**
     * ⚠ `CloneWorker` 를 `@Autowired` 로 받지 마라. 주입받는 것은 <b>프록시</b>라
     * `@Async("cloneExecutor")` 가 그대로 발동해 다른 스레드로 넘어간다.
     * 그러면 바로 아래 줄의 상태 검사가 <b>경합</b>이 되어 통과와 실패가 번갈아 난다.
     * (프록시를 우회하는 것은 「자기 자신을 부를 때」뿐이고, 주입받은 빈 호출은 우회하지 않는다.)
     * 여기서는 뒤에서 도는 것이 아니라 <b>하는 일</b>을 재는 것이라, 손으로 만들어 동기로 부른다.
     * 「진짜로 뒤에서 도나」는 Task 10 끝의 사람 눈 확인이 맡는다.
     */
    private CloneWorker worker() {
        return new CloneWorker(projects, paths, git, screenIds, projectSystems);
    }

    @Test
    void 클론이_되면_준비됨이_된다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        when(git.authenticatedUrl(any(), any())).thenReturn("https://oauth2:t@host/x.git");
        when(git.run(any(), any(), any(String[].class)))
                .thenReturn(new GitResult(0, "", ""));

        Project p = projects.register("클론성공", "https://host/x.git", "main", "t");
        worker().clone(p.getId(), "acc-1");

        assertThat(repository.selectById(p.getId()).orElseThrow().getState())
                .isEqualTo(ProjectState.READY);
        // ⚠ any() 는 null 도 통과시킨다 — accountId 가 채번 워커까지 실제로 닿는지는
        //   값을 못박아야만 잰다.
        verify(screenIds).assignQuietly(eq(p.getId()), eq("acc-1"));
    }

    @Test
    void 클론이_실패하면_실패와_이유가_남는다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        when(git.authenticatedUrl(any(), any())).thenReturn("https://oauth2:t@host/x.git");
        when(git.run(any(), any(), any(String[].class)))
                .thenReturn(new GitResult(128, "", "fatal: 못 받았다"));

        Project p = projects.register("클론실패", "https://host/x.git", "main", "t");
        worker().clone(p.getId(), "acc-1");

        Project reloaded = repository.selectById(p.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(ProjectState.FAILED);
        assertThat(reloaded.getFailureReason()).contains("못 받았다");
        // ⛔ 종료코드를 뺀 채로 되돌리지 마라 — 이것이 없어서 실제 실패를 못 읽었다.
        assertThat(reloaded.getFailureReason()).contains("종료코드 128");
    }

    /**
     * 실제로 난 실패가 이 모양이었다 — stderr 가 {@code Cloning into …} 한 줄뿐이라
     * 사유를 되짚을 수 없었다. 적어도 종료코드는 남아야 한다.
     */
    @Test
    void git_이_아무_말_없이_죽어도_종료코드는_남는다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        when(git.authenticatedUrl(any(), any())).thenReturn("https://oauth2:t@host/x.git");
        when(git.run(any(), any(), any(String[].class)))
                .thenReturn(new GitResult(143, "", ""));

        Project p = projects.register("조용한실패", "https://host/x.git", "main", "t");
        worker().clone(p.getId(), "acc-1");

        Project reloaded = repository.selectById(p.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(ProjectState.FAILED);
        assertThat(reloaded.getFailureReason()).contains("종료코드 143");
    }

    /** ⚠ git 은 오류를 맨 끝에 찍는다 — 앞에서 자르면 잘라 낸 쪽이 정작 사유다. */
    @Test
    void 사유가_길면_앞이_아니라_뒤를_남긴다() {
        String noise = "Updating files: 1%".repeat(300);
        String reason = CloneWorker.describeFailure(
                new GitResult(128, "", noise + "\nfatal: 진짜 사유는 맨 끝에 있다"));

        assertThat(reason).contains("fatal: 진짜 사유는 맨 끝에 있다");
        assertThat(reason).contains("종료코드 128");
        assertThat(reason.length()).isLessThan(2100);
    }

    @Test
    void 다시_시도하면_받는_중으로_돌아간다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        when(git.authenticatedUrl(any(), any())).thenReturn("https://oauth2:t@host/x.git");
        when(git.run(any(), any(), any(String[].class)))
                .thenReturn(new GitResult(128, "", "fatal: 못 받았다"))
                .thenReturn(new GitResult(0, "", ""));

        Project p = projects.register("다시시도", "https://host/x.git", "main", "t");
        worker().clone(p.getId(), "acc-1");
        assertThat(repository.selectById(p.getId()).orElseThrow().getState())
                .isEqualTo(ProjectState.FAILED);

        projects.retry(p.getId());
        worker().clone(p.getId(), "acc-1");

        assertThat(repository.selectById(p.getId()).orElseThrow().getState())
                .isEqualTo(ProjectState.READY);
    }

    /**
     * ★ 픽스라운드 2(2026-08-20) — {@code @Async} 제출 자체가 거절되면 그 예외는
     * {@code assignQuietly} 본문이 시작되기도 전에 <b>부르는 쪽 스레드에서 동기로</b> 던져진다.
     * {@code CloneWorker} 가 그것을 감싸지 않으면 이미 성공한 클론이 실패로 뒤집힌다 —
     * 이 시험이 바로 그 뒤집힘이 안 일어나는지를 잰다.
     */
    @Test
    void 채번_제출이_거절돼도_이미_성공한_클론은_실패로_안_바뀐다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        when(git.authenticatedUrl(any(), any())).thenReturn("https://oauth2:t@host/x.git");
        when(git.run(any(), any(), any(String[].class)))
                .thenReturn(new GitResult(0, "", ""));
        doThrow(new TaskRejectedException("대기열이 찼다")).when(screenIds).assignQuietly(any(), any());

        Project p = projects.register("채번거절", "https://host/x.git", "main", "t");
        worker().clone(p.getId(), "acc-1");

        assertThat(repository.selectById(p.getId()).orElseThrow().getState())
                .isEqualTo(ProjectState.READY);
    }

    @Test
    void 클론은_프로젝트마다_다른_자리에_앉는다() {
        assertThat(paths.cloneDir("0000001")).isNotEqualTo(paths.cloneDir("0000002"));
        assertThat(paths.cloneDir("0000001").toString()).contains("0000001");
    }

    /**
     * ⛔ 번호가 글자가 되면서 난 새 위험이다. {@code Long} 이던 때는 타입이 막아 줬다 —
     * 이제 {@code ".."} 가 그냥 통과하면 클론 폴더가 <b>data-root 밖</b>에 앉는다.
     */
    @Test
    void 번호_꼴이_아니면_경로를_안_만든다() {
        assertThatThrownBy(() -> paths.cloneDir(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paths.worktreeRoot("1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ★ 계획서가 「이 계획서가 남긴 가장 큰 검증 구멍」이라고 지목한 자리다(Task 10, 2026-08-08 코덱스 2회차).
     *
     * <p>위의 실패 시험 둘은 <b>git 이 실패한 경우</b>만 본다. 진짜 위험한 경로는 다른 쪽이다 —
     * <b>토큰이 안 풀리는 경우</b>. {@code CloneWorker.clone} 에 트랜잭션이 걸려 있으면
     * {@code tokenOf} 가 던지는 순간 공유 트랜잭션이 rollback-only 로 찍히고,
     * 예외를 잡아 {@code cloneFailed} 를 불러도 <b>커밋 때 통째로 되돌아가</b> 프로젝트가 「받는 중」에 굳는다.
     *
     * <p>그래서 이 시험만 <b>상속 트랜잭션을 끊는다</b>({@code NOT_SUPPORTED}).
     * 안 끊으면 {@code cloneFailed} 가 테스트 트랜잭션에 얹혀 들어가 <b>커밋됐는지가 안 보인다</b> —
     * 상태가 바뀐 것처럼 보이지만 아무것도 증명하지 못한다.
     *
     * <p>⚠ 트랜잭션을 끊었으니 이 시험이 만든 줄은 <b>되돌아가지 않는다.</b> 끝에서 손으로 지운다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 토큰이_안_풀려도_실패가_커밋된다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));

        Project p = projects.register("봉인깨진것", "https://host/x.git", "main", "t");
        String id = p.getId();
        try {
            // 봉인을 깬다 — 다른 열쇠로 봉인한 것처럼 보이는 쓰레기를 심는다.
            // ⚠ 2026-08-15 까지는 「찾아와서 replaceToken 하고 save」였다. 엔티티에서 상태 변경
            //    메서드를 걷어냈으니 이제 고치는 길은 매퍼의 update 하나다.
            repository.updateToken(id, new byte[] {1, 2, 3, 4}, new byte[12]);

            worker().clone(id, "acc-1");

            // 새로 읽는다. 상속 트랜잭션이 없으니 이건 **커밋된 것**을 보는 것이다.
            Project reloaded = repository.selectById(id).orElseThrow();
            assertThat(reloaded.getState()).isEqualTo(ProjectState.FAILED);
            assertThat(reloaded.getFailureReason()).contains("토큰을 다시 넣어");
        } finally {
            repository.deleteById(id);   // ⚠ 트랜잭션을 끊었으니 되돌아가지 않는다 — 손으로 치운다
        }
    }

    /**
     * ⛔ git 은 윈도우에서 pack 을 읽기 전용으로 만든다. 그걸 못 지우면 다시 받기가 영원히 막힌다.
     * 실제로 {@code AccessDeniedException: …\.git\objects\pack\pack-….idx} 로 막혔다 (2026-08-27).
     */
    @Test
    void 읽기_전용_파일이_있어도_클론_자리를_지운다(@TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path pack = tmp.resolve("clone/.git/objects/pack");
        java.nio.file.Files.createDirectories(pack);
        java.nio.file.Path idx = pack.resolve("pack-abc.idx");
        java.nio.file.Files.writeString(idx, "x");
        assertThat(idx.toFile().setReadOnly()).isTrue();

        CloneWorker.deleteClone(tmp.resolve("clone"));

        assertThat(java.nio.file.Files.exists(tmp.resolve("clone"))).isFalse();
    }

    /** 없는 자리를 지우라고 해도 조용해야 한다 — 첫 클론에는 지울 것이 없다. */
    @Test
    void 지울_것이_없으면_아무_일도_없다(@TempDir java.nio.file.Path tmp) throws Exception {
        CloneWorker.deleteClone(tmp.resolve("없는자리"));
    }
}
