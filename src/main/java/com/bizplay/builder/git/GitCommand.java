package com.bizplay.builder.git;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class GitCommand {

    private static final Pattern CREDENTIALED_URL = Pattern.compile("://[^/@\\s]+@");

    /** {@link #killTree(Process)} 가 다 죽은 뒤에 주는 고정 유예. 실측 근거는 그 메서드의 주석에 있다. */
    private static final Duration GRACE_AFTER_KILL = Duration.ofMillis(50);

    /** 로그·예외 어디에도 토큰이 남지 않게 가린다. */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        return CREDENTIALED_URL.matcher(text).replaceAll("://***@");
    }

    public String authenticatedUrl(String repoUrl, String token) {
        int at = repoUrl.indexOf("://");
        if (at < 0) {
            throw new GitException("http(s) 주소가 아니다: " + repoUrl);
        }
        String scheme = repoUrl.substring(0, at + 3);
        String rest = repoUrl.substring(at + 3);
        return scheme + "oauth2:" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "@" + rest;
    }

    /**
     * ⭐ <b>자격 도우미를 끈 채로 부른다 (2026-08-27 실측).</b> 우리는 토큰을 주소에 실어 보내므로
     * 도우미가 할 일이 없는데, 윈도우 git 은 {@code credential.helper=manager} 가 기본이라
     * <b>인증에 성공한 다음</b> 토큰을 저장하려고 {@code git credential-manager store} 를 부른다.
     * 그것이 죽으면서 <b>이미 성공한 클론까지 종료코드 128 로 끌고 내려갔다.</b>
     * {@code GIT_TRACE} 가 잡아 준 마지막 줄이 정확히 그 자리였다:
     * <pre>trace: run_command: 'git credential-manager store'  ← 여기서 끝, exit 128</pre>
     *
     * <p>⛔ <b>되돌리지 마라 — 끄는 것이 맞는 두 번째 이유가 있다.</b> 켜 두면 기획 레포의 토큰이
     * 서버(또는 개발자 PC)의 <b>자격 저장소에 조용히 남는다.</b> 아무도 그걸 부탁하지 않았고,
     * 봉인해서 DB 에 넣어 둔 뜻과도 어긋난다.
     *
     * <p>⚠ 빈 값 {@code -c credential.helper=} 는 <b>앞서 쌓인 도우미 설정을 전부 지우는</b> git 의 약속이다.
     * ⛔ 이름을 지어 넣지 마라({@code -c credential.helper=none} 따위) — 그건 「none 이라는 도우미를
     * 찾아라」가 되어 도리어 실패한다.
     *
     * <p>⚠ {@code GIT_TERMINAL_PROMPT=0} 도 함께 준다. 서버에는 사람이 없다 —
     * 물어보려 드는 순간 상한(30분)까지 매달린다. 물어보는 대신 바로 실패해야 한다.
     */
    static List<String> command(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("credential.helper=");
        command.addAll(List.of(args));
        return command;
    }

    public GitResult run(Path workingDir, Duration timeout, String... args) {
        List<String> command = command(args);

        Process process = null;
        try {
            Files.createDirectories(workingDir);
            ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();

            // ⚠ 두 스트림을 **동시에** 빨아낸다. 한 줄씩 순서대로 다 읽으면 두 가지가 깨진다 —
            //   ① 읽기가 프로세스 끝까지 막혀서 아래 waitFor 가 언제나 「이미 끝났다」를 보고
            //      **시간 상한이 아무 일도 안 하게 된다**(계획서 코드가 실제로 그랬다).
            //   ② stdout 을 끝까지 읽는 동안 stderr 파이프가 차면 서로 기다리다 **교착**한다.
            //      `git clone` 은 진행 표시를 stderr 로 쏟으므로 실제로 걸리는 자리다.
            StreamPump stdout = StreamPump.start(process.getInputStream(), "git-stdout");
            StreamPump stderr = StreamPump.start(process.getErrorStream(), "git-stderr");

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killTree(process);
                // ⛔ 죽인 뒤에는 끝난 것을 확인한다 — ai-run 이 정한 그대로다.
                process.waitFor(5, TimeUnit.SECONDS);
                throw new GitException("git 이 시간 상한을 넘었다: " + mask(String.join(" ", command)));
            }
            return new GitResult(process.exitValue(), stdout.text(), stderr.text());
        } catch (IOException e) {
            throw new GitException("git 을 돌리지 못했다: " + mask(String.join(" ", command)), e);
        } catch (InterruptedException e) {
            if (process != null) {
                killTree(process);
            }
            Thread.currentThread().interrupt();
            throw new GitException("git 을 기다리다 끊겼다", e);
        }
    }

    /**
     * ⛔ <b>{@code destroyForcibly()} 하나로 끝내지 마라 — 직속 자식만 죽는다.</b>
     * {@code git clone}/{@code fetch} 는 자격을 물으러 {@code git-remote-https} 같은 헬퍼를
     * 손자로 띄운다(실측(2026-08-15)으로는 윈도우가 콘솔 앱을 띄울 때 붙이는 {@code conhost.exe}
     * 였다 — 둘 다 <b>같은 함정</b>이다: 손자가 작업 디렉터리를 물고 있으면). 부모(git)만 죽으면
     * 손자는 살아남아 작업 디렉터리의 파일 핸들을 계속 쥐고, 그 핸들 때문에 JUnit {@code @TempDir}
     * 정리가 임시 폴더를 못 지운다(윈도우에서만 막힌다 — 열려 있는 파일은 지우기 자체가 거부된다).
     * {@code AiRunService}·{@code CliClaudeRunner} 가 이미 잡아 둔 자리이고, 여기가 마지막 남은
     * 자리였다.
     *
     * <p>⚠ <b>자손 목록은 부모를 죽이기 전에 거둬야 한다.</b> {@link Process#descendants()} 는
     * 그 순간의 프로세스 트리를 따라간다 — 부모가 먼저 죽으면 OS 가 트리를 거둬 가서 훑을 것이
     * 남지 않는다. 그래서 <b>목록을 리스트로 먼저 굳히고, 자손부터 죽이고, 부모는 맨 나중</b>이다.
     *
     * <p>⚠ <b>죽었다는 확인(onExit)만으로는 부족하다</b> — 실측(2026-08-15, 반복 재현)으로
     * {@code onExit()} 가 끝난 <i>바로 다음 순간</i>에 지워도 절반 가까이 「다른 프로세스가 쓰는
     * 중」으로 막혔다. OS 가 프로세스 종료를 알리는 시점과 그 프로세스가 쥐고 있던 디렉터리
     * 핸들을 실제로 놓는 시점 사이에 짧은 틈(실측 10~수십 ms)이 있다는 뜻이다. 그래서 확인 뒤에도
     * <b>고정된 짧은 유예</b>를 준다 — 무한정 기다리는 게 아니라 한 번, 한도(50ms) 안에서다.
     */
    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        // 죽였다고 그 자리에서 바로 핸들이 풀리는 게 아니다 — OS 가 거둘 잠깐을 준다.
        // ⛔ 여기서 매달리지 않는다: 5초 안에 안 끝나도 그냥 넘어간다(최선만 한다).
        try {
            CompletableFuture.allOf(descendants.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException e) {
            // 최선만 한다 — 위 주석대로.
        }
        try {
            Thread.sleep(GRACE_AFTER_KILL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 자식의 출력 한 갈래를 <b>따로 도는 데몬 스레드</b>에서 통째로 빨아낸다.
     *
     * <p>데몬이라 상한에 걸려 죽인 자식의 스레드가 서버를 붙잡지 않는다.
     * 스트림은 자식이 끝나거나 강제로 죽는 순간 닫히므로 스레드도 그때 끝난다.
     */
    private static final class StreamPump {

        private final Thread thread;
        private volatile String captured = "";

        private StreamPump(InputStream stream, String name) {
            this.thread = new Thread(() -> {
                try {
                    captured = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // 죽인 자식의 파이프가 끊긴 것이다 — 여기서 할 일이 없다.
                    // ⛔ 내용은 로그에 붓지 않는다: 자격이 섞여 나올 수 있다.
                    captured = "";
                }
            }, name);
            this.thread.setDaemon(true);
        }

        static StreamPump start(InputStream stream, String name) {
            StreamPump it = new StreamPump(stream, name);
            it.thread.start();
            return it;
        }

        /** 자식이 끝난 뒤에 부른다. 혹시 스레드가 안 끝나도 <b>여기서 매달리지 않는다.</b> */
        String text() {
            try {
                thread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return captured;
        }
    }
}
