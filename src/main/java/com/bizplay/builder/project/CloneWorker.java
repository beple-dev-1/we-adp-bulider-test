package com.bizplay.builder.project;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.screenid.ScreenStandardIdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Component
public class CloneWorker {

    private static final Logger log = LoggerFactory.getLogger(CloneWorker.class);
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(30);
    /** 실패 사유로 남길 글자 수 상한. git 진행 표시가 길어 통째로 두면 화면이 못 읽는다. */
    private static final int REASON_LIMIT = 2000;

    // ⚠ ProjectMapper 를 들지 않는다. DB 는 ProjectService 의 짧은 트랜잭션으로만 만진다.
    private final ProjectService projects;
    private final ProjectPaths paths;
    private final GitCommand git;
    private final ScreenStandardIdWorker screenIds;
    private final ProjectSystemService projectSystems;

    public CloneWorker(ProjectService projects, ProjectPaths paths, GitCommand git,
                       ScreenStandardIdWorker screenIds, ProjectSystemService projectSystems) {
        this.projects = projects;
        this.paths = paths;
        this.git = git;
        this.screenIds = screenIds;
        this.projectSystems = projectSystems;
    }

    /**
     * ⛔ <b>이 메서드에 {@code @Transactional} 을 걸지 마라.</b> 두 가지가 한꺼번에 깨진다.
     *
     * <p>① <b>실패가 저장되지 않는다.</b> 여기에 트랜잭션이 걸려 있으면 안에서 부르는
     * {@code projects.tokenOf()} 가 <b>같은 트랜잭션에 참여</b>한다. 그것이 「봉인을 풀 수 없다」로
     * 던지는 순간 스프링이 그 공유 트랜잭션을 <b>rollback-only 로 표시</b>한다. 여기서 예외를 잡아
     * {@code markFailed(...)} 를 불러도 커밋 때 {@code UnexpectedRollbackException} 이 나고
     * 프로젝트는 「받는 중」에 <b>굳는다.</b> 예외를 잡는 것만으로는 못 막는다.
     *
     * <p>② <b>DB 커넥션을 30분 문다.</b> 클론 상한이 30분인데 그동안 커넥션 하나가 잡혀 있다.
     *
     * <p>그래서 <b>DB 를 만지는 구간을 짧게 세 토막</b>으로 가른다 — 읽기 / 파일일 / 결과쓰기.
     * 결과 쓰기는 {@code ProjectService} 의 자기 트랜잭션에서 <b>따로 커밋</b>된다.
     */
    @Async("cloneExecutor")
    public void clone(String projectId, String accountId) {
        ProjectService.CloneMaterials materials;
        try {
            // ① 짧은 읽기 트랜잭션. ⚠ 토큰을 **디스크를 건드리기 전에** 푼다 —
            //    순서가 반대면 토큰이 안 풀릴 때 이미 지운 클론까지 함께 잃는다.
            materials = projects.cloneMaterials(projectId);
        } catch (RuntimeException e) {
            log.warn("클론을 시작하지 못했다 projectId={}", projectId, e);
            projects.cloneFailed(projectId, "클론을 시작하지 못했다. 토큰을 다시 넣어 봐라.");
            return;
        }

        Path dir = paths.cloneDir(projectId);
        try {
            // ② 파일 일 — 트랜잭션 밖이다.
            //    ⚠ 지난 시도가 남긴 부스러기를 먼저 지운다. 상한(30분)에 걸려 강제로 죽인 git 은
            //    자기 정리를 못 하고 반쯤 만든 디렉토리를 남긴다. 안 지우면 다시 시도가
            //    「destination path already exists and is not an empty directory」로 **영원히** 실패한다.
            //    「다시 하면 되는 일」이라는 project-setup 의 약속이 여기서 깨졌었다.
            deleteClone(dir);

            GitResult result = git.run(dir.getParent(), CLONE_TIMEOUT,
                    "clone", "--branch", materials.defaultBranch(), materials.authenticatedUrl(), dir.toString());

            // ③ 짧은 쓰기 트랜잭션. 위에서 무슨 일이 났든 여기는 깨끗한 새 트랜잭션이다.
            if (result.succeeded()) {
                projects.cloneSucceeded(projectId);
                // ⚠ 시스템 목록을 먼저 앉힌다 — manifest.json 을 읽어 화면이 시스템을 사람이 쓰는
                //    이름으로 부를 수 있게 하는 자리다(이름은 관리자가 뒤에 넣는다).
                //    ⛔ 삼킨다. 여기서 터져도 클론은 이미 성공이고, 다음 「저장소 업데이트」가 재시도다.
                projectSystems.syncQuietly(projectId);
                // ⛔ 제출 자체를 따로 감싼다. @Async 의 거절(TaskRejectedException)은 프록시가
                //    부르는 쪽 스레드에서 동기로 던진다 — assignQuietly 안의 try/catch 는
                //    아직 시작도 안 했으므로 그것을 못 잡는다. 감싸지 않으면 바로 아래 catch 가
                //    cloneFailed 를 불러 이미 성공한 클론을 실패로 뒤집는다. 이 try/catch 가
                //    있어야 「채번은 클론이 성공으로 굳은 뒤에 돈다 — 여기서 터져도 클론은
                //    이미 성공이다」가 참이 된다.
                try {
                    screenIds.assignQuietly(projectId, accountId);
                } catch (RuntimeException rejected) {
                    log.warn("표준 화면ID 채번을 시작하지 못했다. 저장소 업데이트가 재시도다 projectId={}",
                            projectId, rejected);
                }
            } else {
                // ⛔ 여기서 로그를 빼지 마라 (2026-08-27). 종전에는 이 가지만 조용해서,
                //    실제로 난 실패의 사유가 DB 의 한 줄 말고는 아무 데도 안 남았다.
                //    위 성공 가지와 아래 catch 는 로그를 남기는데 여기만 없었다.
                log.warn("클론이 실패로 끝났다 projectId={} exitCode={} stderr={} stdout={}",
                        projectId, result.exitCode(),
                        GitCommand.mask(result.stderr()).strip(),
                        GitCommand.mask(result.stdout()).strip());
                projects.cloneFailed(projectId, describeFailure(result));
            }
            // ⚠ `GitException | IOException | RuntimeException` 으로 적지 마라 — 컴파일이 안 된다.
            //    GitException 이 RuntimeException 의 자식이라 multi-catch 가 자바 문법 위반이다.
            //    RuntimeException 하나가 GitException 을 이미 덮는다.
        } catch (IOException | RuntimeException e) {
            log.warn("클론 실패 projectId={}", projectId, e);
            projects.cloneFailed(projectId, GitCommand.mask(String.valueOf(e.getMessage())));
        }
    }

    /**
     * ⭐ <b>종료코드를 반드시 남긴다 (2026-08-27).</b> 종전에는 {@code stderr} 만 적었는데,
     * 실제 실패에서 그 값이 {@code "Cloning into '…'…"} <b>한 줄뿐</b>이었다 —
     * git 이 왜 죽었는지가 기록에서 통째로 사라졌고 되짚을 길이 없었다.
     * 종료코드 하나가 128(git 이 스스로 낸 오류)과 143(밖에서 죽인 것)을 가른다.
     *
     * <p>⚠ <b>뒤를 남기고 앞을 버린다.</b> git 은 첫 줄에 {@code Cloning into …} 를 찍고
     * <b>오류는 맨 끝</b>에 찍는다. 앞에서 자르면 잘라 낸 쪽이 정작 사유다.
     *
     * <p>⚠ {@code stderr} 가 비면 {@code stdout} 을 본다 — 둘 다 비어도 종료코드는 남는다.
     */
    static String describeFailure(GitResult result) {
        String stderr = GitCommand.mask(result.stderr()).strip();
        String body = stderr.isEmpty() ? GitCommand.mask(result.stdout()).strip() : stderr;
        if (body.length() > REASON_LIMIT) {
            body = "…" + body.substring(body.length() - REASON_LIMIT);
        }
        String code = "(종료코드 " + result.exitCode() + ")";
        return body.isEmpty() ? "git 이 아무 말 없이 끝났다 " + code : body + " " + code;
    }

    /**
     * ⭐ <b>{@code FileSystemUtils.deleteRecursively} 로 되돌리지 마라 (2026-08-27 실측).</b>
     * git 은 윈도우에서 pack 파일을 <b>읽기 전용으로 만든다</b>:
     * <pre>-r--r--r--  .git/objects/pack/pack-….pack</pre>
     * 그것이 남아 있으면 지우기가 이렇게 죽는다 —
     * {@code java.nio.file.AccessDeniedException: …\.git\objects\pack\pack-….idx}.
     *
     * <p>⛔ 그러면 <b>다시 받기가 영원히 안 된다.</b> 위 ② 의 「다시 하면 되는 일」이라는 약속이
     * 바로 여기서 깨졌다 — 지우기가 못 끝나니 그 다음 clone 을 시작조차 못 한다.
     * 예외를 잡는 것으로는 안 된다: 남은 폴더 때문에 clone 이
     * {@code destination path already exists} 로 또 죽는다.
     *
     * <p>⚠ <b>리눅스에서는 안 나던 것이다.</b> 유닉스는 파일을 지울 때 그 파일의 쓰기 권한이
     * 아니라 <b>담고 있는 디렉터리</b>의 권한을 본다. 윈도우만 읽기 전용 비트에 막힌다.
     *
     * <p>⚠ 깊은 것부터 지운다 — 디렉터리는 비어야 지워진다.
     */
    static void deleteClone(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> deepestFirst;
        try (var walk = Files.walk(dir)) {
            deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : deepestFirst) {
            File file = path.toFile();
            if (!file.canWrite()) {
                file.setWritable(true);
            }
            Files.deleteIfExists(path);
        }
    }
}
