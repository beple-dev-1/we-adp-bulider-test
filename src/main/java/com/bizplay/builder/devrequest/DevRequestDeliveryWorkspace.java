package com.bizplay.builder.devrequest;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 꾸러미를 <b>전달 전용 브랜치</b>({@code dr/DR-nnn})로 올린다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-handoff-to-dev-design.md} 「줄기 — 칸 넷」의
 * 칸 3·4. 그 문서는 개발 API 로 보내는 그림이었고, <b>통로가 git 뿐이라 브랜치로 옮겼다</b>
 * (사용자 확정 2026-09-22). 상태 이름(전송중·전송완료·대기)과 「먼저 남기고 보낸다」 규율은 그대로다.
 * ⭐ <b>git 에서는 성공 판정이 하나다</b> — 원격에 ref 가 생겼거나 아니거나. 설계가 답을
 * 4xx·5xx·무응답으로 잘게 가른 것은 HTTP 답이 애매해서였다.
 *
 * <p>⭐ <b>기본 브랜치에서 갈라 만든다.</b> 그래서 이 브랜치의 {@code core/<시스템>/pages/} 는
 * <b>as-is 그대로</b>이고, to-be 는 꾸러미 안에만 있다 — 「기본 브랜치 계열의 {@code pages/} 는
 * 사실이다」가 지켜진다.
 * ⛔ <b>FRD 브랜치를 올리지 마라</b>(2026-09-22 확정 · 008 을 010 에서 되돌렸다). 올리면 to-be 가
 * 담긴 {@code pages/} 가 원격에 생겨 「그것을 as-is 로 읽는다」와 「PR 로 기본 브랜치에 병합된다」가
 * 열린다 — lifecycle 설계가 ⛔ 로 막아 둔 그것이다.
 *
 * <p>⛔ <b>FRD 워크트리를 지우지 마라.</b> 여기서 지우는 것은 <b>전달 워크트리</b>뿐이다.
 * FRD 워크트리는 재전송과 역류 대조의 재료다(사용자 확정 2026-09-22).
 */
public class DevRequestDeliveryWorkspace {

    private static final Logger log = LoggerFactory.getLogger(DevRequestDeliveryWorkspace.class);

    /**
     * ⭐ 받기 커밋의 신원 — 기획이 만든 커밋과 <b>이력에서 갈린다</b>(설계 「커밋 신원 — 빌더 수신」).
     * ⛔ 기획 쪽 신원과 같게 두지 마라 — 무엇이 개발에서 온 것인지 나중에 못 가린다.
     */
    private static final String RECEIVER_NAME = "WE-ADP Builder 수신";
    private static final String RECEIVER_EMAIL = "builder+received@we-adp.local";

    /** ⚠ 올리기는 검사보다 오래 걸린다 — 자산이 함께 나가므로 넉넉히 둔다. */
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(10);

    private final ProjectPaths paths;
    private final GitCommand git;
    private final Duration timeout;

    public DevRequestDeliveryWorkspace(ProjectPaths paths, GitCommand git, Duration timeout) {
        this.paths = paths;
        this.git = git;
        this.timeout = timeout;
    }

    /**
     * 올린 자리 — 어느 브랜치의 어느 커밋인가. 이 둘이 「보냈다」의 증거다.
     *
     * @param base 이 꾸러미가 갈라 나온 <b>기본 브랜치의 그때 판</b>. 역류가 이 값 위에서 갈라 와야
     *             한다 — 개발에게 알려 줄 기준이고, 목록과 {@code expected-back.md} 가 같은 값을 쓴다
     */
    public record Published(String branch, String commit, String base) {
    }

    /** 꾸러미를 쓰는 일. 기준 커밋을 함께 받는다 — 「돌려받을 것」이 그 값을 담기 때문이다. */
    @FunctionalInterface
    public interface PackageWriter {
        void write(Path worktree, String base);
    }

    /**
     * 기본 브랜치에서 갈라 꾸러미를 커밋하고 원격에 올린다.
     *
     * <p>⚠ <b>다시 구우면 통째로 갈아 낀다</b> — 개발요청서 하나에 꾸러미 하나다. 앞판이 남으면
     * 어느 것을 보냈는지 알 수 없다. 그래서 브랜치를 {@code -B} 로 다시 세우고 워크트리도 새로 판다.
     *
     * @param label       {@code DR-009} 꼴. 브랜치 이름이 {@code dr/<label>} 이 된다
     * @param writePackage 전달 워크트리 <b>뿌리</b>를 받아 꾸러미를 쓰는 일. 이 클래스는 git 만 한다
     */
    public synchronized Published publish(String projectId, String requestId, String label,
                                          String defaultBranch, String authenticatedUrl,
                                          PackageWriter writePackage) {
        Path clone = paths.cloneDir(projectId);
        Path worktree = paths.devRequestDeliveryWorktree(projectId, requestId);
        String branch = "dr/" + label;
        try {
            discard(clone, worktree);
            /*
             * ⭐ 로컬 클론이 아니라 **원격의 지금 판**에서 가른다. 로컬은 낡아 있을 수 있고
             *   (사용자가 일부러 안 맞춰 두기도 한다), 낡은 자리에서 가르면 개발에게
             *   **낡은 기준**을 알려 주게 된다 — 그 기준으로 갈라 온 역류는 병합이 막힌다.
             */
            require(clone, "기획 저장소의 기본 브랜치를 받지 못했습니다.",
                    "fetch", authenticatedUrl, defaultBranch);
            String base = require(clone, "기준 커밋을 확인하지 못했습니다.",
                    "rev-parse", "FETCH_HEAD").stdout().strip();
            require(clone, "전달 작업 자리를 만들지 못했습니다.",
                    "worktree", "add", "-B", branch, worktree.toString(), base);

            writePackage.write(worktree, base);

            require(worktree, "꾸러미를 커밋 대상으로 올리지 못했습니다.", "add", "-A");
            GitResult changed = git.run(worktree, timeout, "diff", "--cached", "--quiet");
            if (changed.exitCode() == 0) {
                throw new IllegalStateException("보낼 꾸러미가 비어 있습니다.");
            }
            require(worktree, "꾸러미 커밋을 만들지 못했습니다.", "commit", "-m",
                    "docs: " + label + " 전송 꾸러미");
            String commit = require(worktree, "꾸러미 커밋을 확인하지 못했습니다.",
                    "rev-parse", "HEAD").stdout().strip();

            /*
             * ⚠ 강제 갱신이다(`+`). 다시 구운 판은 기본 브랜치에서 새로 갈라 나오므로 앞판의
             *   자손이 아니고, 그냥 밀면 원격이 거절한다. 「다시 구우면 통째로 갈아 낀다」가
             *   설계이므로 갈아 끼우는 것이 맞다 — 판이 남으면 어느 것을 보냈는지 알 수 없다.
             * ⛔ 그래서 이 브랜치에 남이 커밋을 얹으면 날아간다. 개발이 돌려보내는 것은
             *   이 브랜치가 아니라 <b>따로 갈라 온다</b> — 그 규율을 어기면 여기서 잃는다.
             */
            GitResult pushed = git.run(worktree, PUSH_TIMEOUT, "push", authenticatedUrl,
                    "+HEAD:refs/heads/" + branch);
            if (!pushed.succeeded()) {
                /*
                 * ⛔ git 출력을 그대로 붙이지 않는다 — 실패 메시지에 원격 주소를 되뱉고 그 주소에
                 *   토큰이 박혀 있다. ⚠ 그렇다고 버리지도 않는다(2026-09-22 실측: 버려 놓으니
                 *   정작 실패했을 때 로그에 「로그를 보라」만 남아 원인을 못 봤다). 가려서 남긴다.
                 */
                String reason = redactCredentials(detail(pushed));
                log.warn("꾸러미를 기획 저장소에 올리지 못했다 projectId={} 개발요청서={} 브랜치={} 사유={}",
                        projectId, requestId, branch, reason);
                throw new IllegalStateException(authenticationRejected(reason)
                        ? "기획 저장소가 자격을 거절했습니다. 프로젝트 설정의 저장소 토큰을 확인해 주십시오."
                        : "꾸러미를 기획 저장소에 올리지 못했습니다. 브랜치=" + branch + " " + reason);
            }
            log.info("꾸러미를 올렸다 projectId={} 개발요청서={} 브랜치={} 커밋={} 기준={}",
                    projectId, requestId, branch, commit, base);
            return new Published(branch, commit, base);
        } finally {
            // ⭐ 전달 워크트리는 전송이 끝나면 지운다 — 성공이든 실패든. 브랜치는 남는다.
            discard(clone, worktree);
        }
    }

    /**
     * 기본 브랜치의 파일 하나를 고쳐 올린다 — 전달 <b>목록</b>이 사는 자리다.
     *
     * <p>⭐ <b>꾸러미와 달리 강제 갱신이 아니다.</b> 기본 브랜치는 남들도 쓰는 자리라 밀어 덮으면
     * 남의 커밋이 사라진다. 그래서 <b>원격을 받아 그 위에서</b> 고치고 보통 push 로 올린다.
     *
     * <p>⚠ <b>클론의 작업폴더를 쓰지 않는다.</b> 클론은 낡아 있을 수 있고(사용자가 일부러
     * 안 맞춰 두기도 한다), 거기서 고치면 사람이 보던 자리가 흔들린다. 받아 온 판 위에
     * <b>임시 워크트리</b>를 띄워 거기서만 고친다.
     *
     * @param update 기존 내용(없으면 {@code null})을 받아 새 내용을 내는 일
     * @return 올린 커밋. 바뀐 것이 없으면 {@code null}
     */
    public synchronized String updateOnDefaultBranch(String projectId, String requestId,
                                                     String defaultBranch, String authenticatedUrl,
                                                     String relativePath,
                                                     java.util.function.UnaryOperator<String> update,
                                                     String message) {
        Path clone = paths.cloneDir(projectId);
        Path worktree = paths.devRequestDeliveryWorktree(projectId, requestId);
        try {
            discard(clone, worktree);
            // ⭐ 원격의 지금 판을 받아 그 위에서 고친다 — 로컬 클론이 낡아도 상관없다.
            require(clone, "기획 저장소의 기본 브랜치를 받지 못했습니다.",
                    "fetch", authenticatedUrl, defaultBranch);
            require(clone, "목록을 고칠 자리를 만들지 못했습니다.",
                    "worktree", "add", "--detach", worktree.toString(), "FETCH_HEAD");

            Path target = worktree.resolve(relativePath);
            String existing = Files.exists(target) ? readString(target) : null;
            writeString(target, update.apply(existing));

            require(worktree, "목록을 커밋 대상으로 올리지 못했습니다.", "add", "--", relativePath);
            GitResult changed = git.run(worktree, timeout, "diff", "--cached", "--quiet");
            if (changed.exitCode() == 0) {
                // ⚠ 바뀐 것이 없으면 빈 커밋을 만들지 않는다 — 이력이 뜻 없이 길어진다.
                return null;
            }
            require(worktree, "목록 커밋을 만들지 못했습니다.", "commit", "-m", message);
            String commit = require(worktree, "목록 커밋을 확인하지 못했습니다.",
                    "rev-parse", "HEAD").stdout().strip();

            GitResult pushed = git.run(worktree, PUSH_TIMEOUT, "push", authenticatedUrl,
                    "HEAD:refs/heads/" + defaultBranch);
            if (!pushed.succeeded()) {
                /*
                 * ⛔ 여기서 강제로 밀지 마라. 거절은 대개 그 사이 남이 올렸다는 뜻이고,
                 *   밀어 덮으면 그 커밋이 사라진다. 다시 부르면 새로 받아 그 위에서 고친다.
                 */
                String reason = redactCredentials(detail(pushed));
                log.warn("전달 목록을 올리지 못했다 projectId={} 개발요청서={} 사유={}",
                        projectId, requestId, reason);
                throw new IllegalStateException(authenticationRejected(reason)
                        ? "기획 저장소가 자격을 거절했습니다. 프로젝트 설정의 저장소 토큰을 확인해 주십시오."
                        : "전달 목록을 기획 저장소에 올리지 못했습니다. 다시 눌러 주십시오. " + reason);
            }
            log.info("전달 목록을 올렸다 projectId={} 개발요청서={} 커밋={}", projectId, requestId, commit);
            return commit;
        } finally {
            discard(clone, worktree);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException("전달 목록을 읽지 못했습니다.", failed);
        }
    }

    private static void writeString(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException("전달 목록을 쓰지 못했습니다.", failed);
        }
    }

    /** 받은 결과 — 놓았으면 커밋이, 거절이면 사유가 담긴다. */
    public record Received(boolean accepted, List<String> rejections, String commit) {
    }

    /** 회신서와 지금 기준을 받아 판정하는 일. ⛔ 규율은 이 클래스가 아니라 판정기가 안다. */
    @FunctionalInterface
    public interface Judge {
        ReturnBatch.Verdict judge(String returnJson, String currentBase);
    }

    /**
     * 개발이 돌려보낸 배치를 <b>골라 담아</b> 기본 브랜치에 놓는다.
     *
     * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md}
     * 「받으면 바로 넣는다 — 사람이 끼어들지 않는다」.
     *
     * <p>⭐ <b>통째 병합이 아니다.</b> 받지 않기로 한 것이 있으므로 {@code merge} 는 못 쓴다 —
     * {@code checkout <회신판> -- <받을 파일>} 로 꺼내 <b>커밋 하나</b>를 만든다.
     * 그래서 「배치가 다 차고 전부 규격을 지나야 커밋한다 · 커밋은 배치당 하나」가 성립한다.
     *
     * <p>⭐ <b>하나라도 떨어지면 아무것도 안 놓는다.</b> 임시 워크트리에서만 만들다 버리므로
     * 반쪽 상태가 원격에 남을 길이 없다.
     *
     * <p>⭐ <b>받기 커밋은 신원을 갈라 찍는다</b> — 설계에 「커밋 신원 — 『빌더 수신』」 절이 있다.
     * 기획이 만든 커밋과 개발 결과를 받아 놓은 커밋이 이력에서 구분되어야 한다.
     */
    public synchronized Received receive(String projectId, String requestId, String defaultBranch,
                                         String authenticatedUrl, String feedbackBranch,
                                         String returnFilePath, Judge judge, String message) {
        Path clone = paths.cloneDir(projectId);
        Path worktree = paths.devRequestDeliveryWorktree(projectId, requestId);
        try {
            discard(clone, worktree);
            require(clone, "기획 저장소의 기본 브랜치를 받지 못했습니다.",
                    "fetch", authenticatedUrl, defaultBranch);
            String currentBase = require(clone, "기준 커밋을 확인하지 못했습니다.",
                    "rev-parse", "FETCH_HEAD").stdout().strip();

            GitResult fetched = git.run(clone, timeout, "fetch", authenticatedUrl, feedbackBranch);
            if (!fetched.succeeded()) {
                return new Received(false,
                        List.of("돌려보낸 브랜치를 받지 못했습니다: " + feedbackBranch), null);
            }
            String returnedHead = require(clone, "회신 커밋을 확인하지 못했습니다.",
                    "rev-parse", "FETCH_HEAD").stdout().strip();

            GitResult shown = git.run(clone, timeout, "show", returnedHead + ":" + returnFilePath);
            if (!shown.succeeded()) {
                // ⚠ 회신서가 없으면 배치가 무엇인지 알 길이 없다 — 거절이지 「그냥 다 받기」가 아니다.
                return new Received(false,
                        List.of("회신서가 없습니다: " + returnFilePath), null);
            }
            ReturnBatch.Verdict verdict = judge.judge(shown.stdout(), currentBase);
            if (!verdict.accepted()) {
                return new Received(false, verdict.rejections(), null);
            }
            if (verdict.filesToTake().isEmpty()) {
                return new Received(true, List.of(), null);
            }

            require(clone, "받을 자리를 만들지 못했습니다.",
                    "worktree", "add", "--detach", worktree.toString(), currentBase);
            List<String> checkout = new ArrayList<>(List.of("checkout", returnedHead, "--"));
            checkout.addAll(new LinkedHashSet<>(verdict.filesToTake()));
            require(worktree, "돌려받은 파일을 꺼내지 못했습니다.", checkout.toArray(String[]::new));

            require(worktree, "받은 것을 커밋 대상으로 올리지 못했습니다.", "add", "-A");
            GitResult changed = git.run(worktree, timeout, "diff", "--cached", "--quiet");
            if (changed.exitCode() == 0) {
                // ⚠ 내용이 같으면 빈 커밋을 만들지 않는다 — 「changed 라 했는데 같았다」는 흔하다.
                return new Received(true, List.of(), null);
            }
            require(worktree, "받기 커밋을 만들지 못했습니다.",
                    "-c", "user.name=" + RECEIVER_NAME, "-c", "user.email=" + RECEIVER_EMAIL,
                    "commit", "-m", message);
            String commit = require(worktree, "받기 커밋을 확인하지 못했습니다.",
                    "rev-parse", "HEAD").stdout().strip();

            GitResult pushed = git.run(worktree, PUSH_TIMEOUT, "push", authenticatedUrl,
                    "HEAD:refs/heads/" + defaultBranch);
            if (!pushed.succeeded()) {
                // ⛔ 강제로 밀지 마라 — 거절은 대개 그 사이 남이 올렸다는 뜻이다. 다시 받으면 된다.
                String reason = redactCredentials(detail(pushed));
                log.warn("받은 것을 올리지 못했다 projectId={} 개발요청서={} 사유={}",
                        projectId, requestId, reason);
                return new Received(false,
                        List.of("받은 것을 기획 저장소에 올리지 못했습니다. 다시 받아 주십시오. " + reason),
                        null);
            }
            log.info("개발 결과를 받았다 projectId={} 개발요청서={} 커밋={} 파일={}",
                    projectId, requestId, commit, verdict.filesToTake().size());
            return new Received(true, List.of(), commit);
        } finally {
            discard(clone, worktree);
        }
    }

    /**
     * 전달 워크트리를 치운다.
     *
     * <p>⚠ <b>브랜치는 지우지 않는다</b> — 무엇을 보냈나의 근거다. 지우는 것은 작업 자리뿐이다.
     */
    private void discard(Path clone, Path worktree) {
        if (Files.exists(worktree)) {
            git.run(clone, timeout, "worktree", "remove", "--force", worktree.toString());
        }
        deleteTree(worktree);
        // ⚠ 폴더를 손으로 지우면 git 의 워크트리 장부에 유령이 남는다 — 그 장부도 함께 턴다.
        git.run(clone, timeout, "worktree", "prune");
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 남은 파일은 다음 번 worktree add 가 거절로 알려 준다 — 여기서 삼키지 않는다
                }
            });
        } catch (IOException failed) {
            throw new UncheckedIOException("전달 작업 자리를 치우지 못했습니다.", failed);
        }
    }

    /** {@code https://사용자:토큰@호스트} 의 자격 부분을 지운다. ⛔ 자격이 밖으로 나가는 유일한 길이다. */
    static String redactCredentials(String text) {
        return text == null ? null : text.replaceAll("(?i)(https?://)[^/@\\s]*@", "$1<자격 가림>@");
    }

    private static boolean authenticationRejected(String reason) {
        String lower = reason == null ? "" : reason.toLowerCase();
        return lower.contains("authentication failed") || lower.contains("invalid username or token")
                || lower.contains("403") || lower.contains("permission");
    }

    private GitResult require(Path directory, String message, String... args) {
        GitResult result = git.run(directory, timeout, args);
        if (!result.succeeded()) {
            throw new IllegalStateException(message + " " + redactCredentials(detail(result)));
        }
        return result;
    }

    private static String detail(GitResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr().strip();
        return stderr.isBlank() ? "(exit " + result.exitCode() + ")" : stderr;
    }
}
