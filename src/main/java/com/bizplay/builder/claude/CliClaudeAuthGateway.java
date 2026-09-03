package com.bizplay.builder.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 스파이크(2026-08-08)가 실물에 대고 잰 계약 그대로다.
 *
 * <pre>
 *   주소 내기 : CLAUDE_CONFIG_DIR=&lt;자리&gt; claude auth login --claudeai
 *              → stdout 에 "If the browser didn't open, visit: &lt;URL&gt;" 한 줄. stderr 는 비어 있다
 *   코드 넣기 : ★ 같은 프로세스의 stdin. 프롬프트는 "Paste code here if prompted &gt; "
 *   앉는 파일 : &lt;자리&gt;/.credentials.json
 * </pre>
 *
 * <p>⛔ `ant` CLI 는 <b>없다.</b> `ANTHROPIC_CONFIG_DIR` 은 <b>아무 효과가 없다.</b> 둘 다 쓰지 마라.
 *
 * <p>⚠ 주소를 내는 프로세스와 코드를 넣는 프로세스가 <b>같아야 한다</b> — PKCE 의 `code_challenge` 와
 * `state` 를 그 프로세스가 메모리에 쥔다. 그래서 그 사이 자식이 살아 있어야 하고,
 * 살려 두는 일은 {@link ClaudeLoginSessions} 가 맡는다.
 */
@Component
public class CliClaudeAuthGateway implements ClaudeAuthGateway {

    private static final Logger log = LoggerFactory.getLogger(CliClaudeAuthGateway.class);

    /** 스파이크가 본 그 줄. 이 표지 뒤에 주소가 온다. */
    private static final String URL_MARKER = "If the browser didn't open, visit:";
    /** 주소가 나오기를 기다리는 짧은 상한. 요청 스레드가 여기 묶이므로 짧아야 한다. */
    private static final Duration URL_TIMEOUT = Duration.ofSeconds(30);
    /** 코드를 넣고 자식이 끝나기를 기다리는 상한. */
    private static final Duration CODE_TIMEOUT = Duration.ofSeconds(60);
    /** 「연결 확인」을 눌렀을 때 자격 파일이 앉기를 기다려 주는 짧은 여유. 놓쳐도 다시 누르면 된다. */
    private static final Duration CALLBACK_GRACE = Duration.ofSeconds(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    /**
     * 있지도 않은 자리. CLI 의 브라우저 자동 열기를 여기로 흘려보내 서버 화면에 아무것도 안 뜨게 한다.
     * ⚠ 실행할 것이 없으면 CLI 는 기본 브라우저로 되돌아가지 않는다 — 그래서 이것으로 충분하다.
     */
    private static final String NO_BROWSER = "we-adp-builder-no-browser";

    private final ClaudeLoginSessions sessions;
    private final ClaudeCredentialFile credentialFile;
    private final ClaudeAccountIdentityReader identityReader;

    public CliClaudeAuthGateway(ClaudeLoginSessions sessions, ClaudeCredentialFile credentialFile,
                                ClaudeAccountIdentityReader identityReader) {
        this.sessions = sessions;
        this.credentialFile = credentialFile;
        this.identityReader = identityReader;
    }

    @Override
    public Authorization begin() {
        String handle = UUID.randomUUID().toString();
        try {
            Path dir = sessions.makeDir(handle);

            ProcessBuilder pb = new ProcessBuilder("claude", "auth", "login", "--claudeai");
            // ★ 사람마다 여기로 갈린다. ANTHROPIC_CONFIG_DIR 이 아니다.
            pb.environment().put("CLAUDE_CONFIG_DIR", dir.toString());
            // ⛔ planner-account 의 금지: 서버 전체에 Claude 자격이 걸려 있으면 개인 자격이 통째로
            //    무시되는데 **오류가 안 난다** — 아무도 모르는 채 남의 계정으로 돌게 된다.
            //    자식에게 물려주는 환경에서 그 열쇠들을 걷어낸다. 서버 환경은 안 건드린다.
            pb.environment().remove("ANTHROPIC_API_KEY");
            pb.environment().remove("ANTHROPIC_AUTH_TOKEN");
            pb.environment().remove("CLAUDE_CODE_OAUTH_TOKEN");
            // ⛔ 서버에서 브라우저가 뜨면 안 된다 — 여기는 서버고 사람은 다른 PC 에 있다.
            //    CLI 는 `BROWSER` 에 적힌 것을 주소와 함께 실행하고, 그것이 없으면
            //    아무것도 안 열고 조용히 넘어간다(2026-08-27 실측). 기본 브라우저로 되돌아가지 않는다.
            pb.environment().put("BROWSER", NO_BROWSER);
            // stderr 는 비어 있지만 섞어 둬야 아무도 안 읽는 파이프가 안 막힌다.
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader out = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

            // 맡기는 순간부터 상한 시계가 돈다. 주소가 안 나오면 30초에 죽고,
            // 그러면 아래 readLine 이 EOF 를 받아 요청 스레드가 풀린다.
            sessions.keep(handle, dir, p, out, URL_TIMEOUT);
            String url = readUrl(out);

            // ⚠ 주소를 찾았다고 읽기를 멈추면 안 된다. 멈추면 자식이 그 뒤에 파이프 용량보다 많이 쏟을 때
            //    write 에서 막히고, complete() 의 waitFor 가 상한까지 기다린 뒤
            //    **정상 승인을 「코드가 맞지 않는다」로 처리한다.** 끝까지 계속 비워 준다.
            //    ⛔ 비운 내용은 어디에도 남기지 않는다 — 뒤에 자격이 섞여 나올 수 있다.
            drainForever(out);

            // 주소가 나왔으니 이제부터는 사람이 승인할 시간이다 — 상한을 늘린다.
            sessions.resetTimeout(handle, sessions.approvalTimeout());
            return new Authorization(handle, url);
        } catch (IOException e) {
            sessions.discard(handle);
            throw new IllegalStateException("Claude 인증 주소를 얻지 못했다", e);
        } catch (RuntimeException e) {
            sessions.discard(handle);
            throw e;
        }
    }

    /**
     * ⚠ <b>2026-08-27 개정 — 코드 붙여넣기가 다시 본길이다.</b> CLI 는 주소를 <b>둘</b> 만든다:
     * 스스로 여는 쪽은 {@code redirect_uri=http://localhost:&lt;임의포트&gt;/callback} 이고,
     * stdout 에 <b>찍는</b> 쪽은 {@code redirect_uri=https://platform.claude.com/oauth/code/callback}
     * 이다 — 같은 {@code code_challenge}·{@code state} 라 어느 쪽으로 승인해도 이 프로세스가 받는다.
     * 빌더가 사람에게 주는 것은 <b>찍힌 쪽</b>이고, 그 길은 승인 뒤 <b>화면에 코드를 보여 준다.</b>
     * 서버의 localhost 로 안 돌아오므로 <b>사람이 어느 PC 에서 승인하든 된다</b> — 서버가 아니어도 된다.
     *
     * <p>2026-08-14 에 「보통은 브라우저 콜백으로 끝나고 자식이 스스로 죽는다」고 적었던 것은
     * <b>서버 PC 에서 CLI 가 연 localhost 쪽</b>으로 승인했을 때다. 그 자동 열기는 이제 껐다
     * ({@code BROWSER}). 그래도 그 길이 남아 있을 수 있으니 <b>자식이 죽었으면 stdin 에 쓰지 않는다</b>
     * — <b>죽은 프로세스의 stdin 에 쓰면 터진다.</b>
     *
     * <p>그래서 <b>판정을 프로세스가 아니라 자격 파일로 옮겼다</b> — 어느 길로 끝났든 결과는 그 파일 하나다.
     *
     * <p>⛔ <b>「아직 안 끝났다」에서 세션을 버리지 마라.</b> 버리면 그 자리를 지우는데,
     * 사람이 승인을 마치는 중이면 <b>곧 앉을 자격 파일이 갈 곳을 잃는다.</b>
     * 옛 판은 {@code finally} 로 무조건 버려서, 일찍 누른 한 번이 <b>이미 받아 둔 자격까지 지웠다.</b>
     */
    @Override
    public Optional<AuthenticatedCredential> complete(Authorization authorization, String code) {
        var live = sessions.find(authorization.handle())
                .orElseThrow(() -> new IllegalStateException("로그인이 살아 있지 않다."));
        Path credentialsPath = live.dir().resolve(".credentials.json");
        try {
            // ① 본길 — 사람이 붙여넣은 코드. 이미 자격이 앉았거나 자식이 죽었으면 건너뛴다.
            if (!Files.exists(credentialsPath) && code != null && !code.isBlank()
                    && live.process().isAlive()) {
                // ★ 주소를 낸 바로 그 프로세스의 stdin 이다. 프롬프트: "Paste code here if prompted > "
                OutputStream stdin = live.process().getOutputStream();
                stdin.write((code + "\n").getBytes(StandardCharsets.UTF_8));   // 윈도우에서도 "\n" 이면 된다
                stdin.flush();
                live.process().waitFor(CODE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }

            // ② 판정은 여기 하나다 — 콜백으로 끝났든 코드로 끝났든 자격 파일이 앉았나만 본다.
            Optional<String> oauthOnly = readWhenReady(credentialsPath);
            if (oauthOnly.isEmpty()) {
                return Optional.empty();            // ⛔ 아직이다. 세션을 살려 둔다
            }
            ClaudeAccountIdentity identity = identityReader.read(live.dir());
            // 끝났으니 치운다 — 그 자리의 .credentials.json 을 디스크에 남기지 않는다.
            sessions.discard(authorization.handle());
            return Optional.of(new AuthenticatedCredential(oauthOnly.get(), identity));
        } catch (IOException e) {
            sessions.discard(authorization.handle());
            throw new IllegalArgumentException("코드를 자격으로 바꾸지 못했다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sessions.discard(authorization.handle());
            throw new IllegalArgumentException("코드를 자격으로 바꾸지 못했다.", e);
        }
    }

    /**
     * 자격 파일이 앉기를 <b>짧게</b> 기다린다 — 사람이 승인을 마치는 순간과 버튼을 누르는 순간이
     * 어긋날 수 있다. 못 기다리면 사람이 한 번 더 누르면 되므로 <b>여기서 오래 매달리지 않는다.</b>
     *
     * <p>⚠ <b>파일이 보이는 것과 다 쓰인 것은 다르다.</b> 반쯤 쓰인 JSON 을 읽으면 파싱이 터지므로,
     * 읽어서 <b>실제로 뜯어지는지</b>까지가 「됐다」의 조건이다.
     */
    private Optional<String> readWhenReady(Path credentialsPath) throws IOException {
        long deadline = System.nanoTime() + CALLBACK_GRACE.toNanos();
        while (true) {
            if (Files.exists(credentialsPath) && Files.size(credentialsPath) > 0) {
                try {
                    // ★ 통째로 옮기지 않는다 — 같은 파일에 그 사람의 MCP 서버 OAuth 토큰이 함께 산다.
                    //    빌더가 쥘 것은 claudeAiOauth 한 칸뿐이다.
                    return Optional.of(credentialFile.extractOAuthBlock(
                            Files.readString(credentialsPath, StandardCharsets.UTF_8)));
                } catch (RuntimeException e) {
                    // 아직 다 안 쓰였다. 남은 시간 안에서 다시 본다.
                    // ⛔ 내용은 로그에 안 남긴다 — 자격이 섞여 나온다.
                }
            }
            if (System.nanoTime() > deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    @Override
    public void discard(String handle) {
        sessions.discard(handle);
    }

    /**
     * 주소를 찾은 뒤부터 자식이 끝날 때까지 출력을 계속 버린다.
     *
     * <p>데몬 스레드라 상한에 걸려 죽인 자식의 스레드가 서버를 붙잡지 않는다.
     * 스트림은 자식이 끝나거나 죽는 순간 닫히므로 스레드도 그때 끝난다.
     */
    private void drainForever(BufferedReader out) {
        Thread t = new Thread(() -> {
            try {
                while (out.readLine() != null) {
                    // ⛔ 버린다. 로그에도 안 남긴다 — 자격이 섞여 나올 수 있다.
                }
            } catch (IOException e) {
                // 죽인 자식의 파이프가 끊긴 것이다. 여기서 할 일이 없다.
            }
        }, "claude-login-drain");
        t.setDaemon(true);
        t.start();
    }

    /** stdout 에서 주소 줄을 찾는다. 프로세스가 상한에 걸려 죽으면 EOF 로 풀린다. */
    private String readUrl(BufferedReader out) throws IOException {
        String line;
        while ((line = out.readLine()) != null) {
            if (line.contains(URL_MARKER) && line.contains("http")) {
                return line.substring(line.indexOf("http")).strip();
            }
        }
        // ⛔ 못 찾은 줄들을 로그에 붓지 않는다 — 뒤에 자격이 섞여 나올 수 있다.
        log.warn("Claude 인증 주소가 출력에 없다");
        throw new IllegalStateException("Claude 인증 주소를 얻지 못했다");
    }
}
