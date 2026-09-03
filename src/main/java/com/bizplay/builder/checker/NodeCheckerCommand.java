package com.bizplay.builder.checker;

import com.bizplay.builder.config.BuilderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 기획 레포에 실려 온 검사기를 실제로 돌린다 — {@code node verify/run.mjs . --json}.
 *
 * <p>2026-08-14 실측으로 굳힌 계약 (263화면짜리 실물 사업 레포) · <b>2026-08-15 갱신</b>
 * (추출기 {@code 37a25fb} — 그쪽 {@code spec/manifest.md} 가 이 형식을 <b>약속으로 못 박았다</b>):
 * <ul>
 *   <li>인자는 <b>{@code [--json] [<뿌리>]} 뿐이다</b> — 한 파일만 보는 입구는 없다</li>
 *   <li>전체 검사가 <b>~0.95초</b>다 (1021·949·896ms)</li>
 *   <li>출력은 {@code {toolchain, status, exitCode, counts, findings[], ratchet}} — 항목마다
 *       {@code file}·{@code line}·{@code gate}·{@code level}·{@code what}·{@code fix}</li>
 *   <li>⚠ <b>{@code status} 를 반드시 봐라</b> — {@code incomplete} 는 게이트를 하나도 안 돌렸다는 뜻이다.
 *       {@link #complete(com.fasterxml.jackson.databind.JsonNode)} 에 왜인지 적어 뒀다</li>
 *   <li>⚠ <b>모르는 인자는 이제 사용법 오류다</b>(종료코드 1 · stdout 0바이트). ⛔ <b>다만 이미 나간
 *       레포의 옛 검사기는 여전히 조용히 무시한다</b> — 나중에 한 파일 입구가 생겨도
 *       <b>인자를 줬다는 것만으로 그게 먹었다고 믿으면 안 된다</b></li>
 * </ul>
 *
 * <p>⛔ <b>실패를 예외로 던지지 않는다.</b> 「못 돌렸다」는 예외가 아니라 <b>세 번째 판정</b>이라
 * {@link CheckReport#unknown()} 으로 낸다 — 그래야 초록·빨강과 안 섞인다.
 */
@Component
public class NodeCheckerCommand implements CheckerCommand {

    private static final Logger log = LoggerFactory.getLogger(NodeCheckerCommand.class);

    /** 검사기가 사는 자리. ⛔ 빌더가 만든 것이 아니라 <b>레포에 실려 온 것</b>이다. */
    private static final String VERIFY_DIR = "verify";
    private static final String ENTRY = "run.mjs";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;

    public NodeCheckerCommand(BuilderProperties properties) {
        this.timeout = properties.checkTimeout();
    }

    @Override
    public CheckReport run(Path checkerHome, Path repoRoot) {
        Path verify = checkerHome.resolve(VERIFY_DIR);
        Path entry = verify.resolve(ENTRY);
        if (!Files.isRegularFile(entry)) {
            // 검사기가 없는 레포다 — 옛 판으로 만들어진 것일 수 있다. ⛔ 초록으로 읽지 않는다.
            log.warn("이 레포에 검사기가 없다 — {}", entry);
            return CheckReport.unknown();
        }
        // ⭐ 클론에 깔린 검사기로 워크트리를 검사한다 — 의존을 검사마다 새로 깔지 않으려는 것이다.
        Output output = ensureDependencies(verify)
                ? execute(checkerHome,
                        List.of("node", entry.toString(), repoRoot.toString(), "--json"), timeout)
                : null;
        if (output == null || output.stdout().isBlank()) {
            // ⚠ 실측된 실패 모양이다 — stdout 0바이트 + 종료코드 1 + stderr 에 안내.
            //    ⛔ 내용은 로그에 붓지 않는다: 레포 본문이 섞여 나온다.
            log.warn("검사기가 판정을 못 냈다 root={}", repoRoot);
            return CheckReport.unknown();
        }
        return parse(output.stdout());
    }

    /**
     * ⚠ <b>클론 직후에는 검사기가 못 돈다</b>(2026-08-14 실측) — {@code node_modules} 는
     * 커밋 대상이 아니라 클론에 안 딸려온다. 그래서 <b>검사 직전에 없으면 여기서 깐다.</b>
     *
     * <p>⛔ <b>클론이 끝난 자리에서 미리 깔지 않는다.</b> 거기서 조용히 실패하면 한참 뒤
     * 저장하려는 순간에야 드러난다 — 필요한 자리에서 깔고 실패를 바로 판정으로 낸다.
     *
     * <p>⚠ <b>운영 서버(리눅스)에서 npm 이 되는지는 아직 안 쟀다.</b> 개발 기계에서는 1초에 됐고
     * 사내 TLS 가로채기에 안 걸렸다(Maven 은 걸렸다). 서버에서 막히면 여기가 늘 {@code UNKNOWN} 이 된다.
     */
    private boolean ensureDependencies(Path verify) {
        if (Files.isDirectory(verify.resolve("node_modules"))) {
            return true;
        }
        log.info("검사기 의존을 깐다 — {}", verify);
        Output output = execute(verify,
                List.of(npmExecutable(File.separatorChar != '/'), "install", "--no-audit", "--no-fund"), timeout);
        if (output == null || output.exitCode() != 0) {
            log.warn("검사기 의존을 깔지 못했다 — 판정을 못 낸 것으로 둔다 verify={}", verify);
            return false;
        }
        return true;
    }

    /**
     * ⭐ <b>윈도우에는 {@code npm.exe} 가 없다 (2026-08-27 실측).</b> 노드가 까는 것은
     * {@code npm.cmd}·{@code npm.ps1}·{@code npm}(bash) 셋뿐이고, <b>자바는 {@code PATHEXT} 를 안 본다</b> —
     * 이름 뒤에 {@code .exe} 만 붙여 찾으므로 {@code npm} 그대로면 이렇게 죽는다:
     * {@code CreateProcess error=2, 지정된 파일을 찾을 수 없습니다}.
     *
     * <p>⛔ <b>PATH 로는 못 고친다</b> — 어디에도 {@code npm.exe} 가 없다. 같은 자리에서 {@code claude} 도
     * 걸렸지만 <b>그쪽은 심 안쪽에 진짜 {@code claude.exe} 가 있어</b> PATH 로 풀렸다. 여기는 다르다.
     *
     * <p>⚠ {@code node} 는 손대지 마라 — {@code node.exe} 가 실재해서 그대로 찾힌다.
     *
     * <p>⚠ 인자가 {@code install --no-audit --no-fund} 뿐이라 {@code .cmd} 로 넘겨도 안전하다.
     * JDK 17.0.11+ 는 특수문자 든 인자를 {@code .cmd} 에 넘기는 것을 거절한다 —
     * <b>여기에 사람이 만든 값을 끼우게 되면 이 자리를 다시 재라.</b>
     *
     * @param onWindows 플랫폼을 시험이 강제할 수 있게 갈라 둔 것 — {@code CliClaudeRunner} 와 같은 꼴이다
     */
    static String npmExecutable(boolean onWindows) {
        return onWindows ? "npm.cmd" : "npm";
    }

    /**
     * ⚠ 검사기는 <b>한 덩어리 JSON</b>을 stdout 으로 낸다.
     *
     * <p>⛔ <b>모르는 {@code level} 을 조용히 버리지 마라.</b> 버리면 새 등급이 생긴 날
     * 그 진단이 <b>없는 것처럼 보여</b> 저장이 열린다. 모르면 막는 쪽으로 센다.
     */
    CheckReport parse(String stdout) {
        try {
            JsonNode json = mapper.readTree(stdout);
            if (!complete(json)) {
                return CheckReport.unknown();
            }
            JsonNode findings = json.path("findings");
            if (!findings.isArray()) {
                throw new IOException("findings 배열이 없다");
            }
            List<Finding> parsed = new ArrayList<>();
            for (JsonNode node : findings) {
                parsed.add(new Finding(
                        node.path("file").asText(""),
                        node.path("line").asInt(0),
                        node.path("gate").asText(""),
                        level(node.path("level").asText("")),
                        node.path("what").asText(""),
                        node.path("fix").asText("")));
            }
            return new CheckReport(CheckReport.Verdict.CHECKED, parsed);
        } catch (IOException | RuntimeException e) {
            // ⛔ stdout 을 로그에 붓지 않는다 — 레포 본문이 섞여 나온다.
            log.warn("검사기 출력을 JSON 으로 못 읽었다");
            return CheckReport.unknown();
        }
    }

    /**
     * <b>게이트를 하나라도 돌렸나.</b> 검사기가 {@code status} 로 말해 준다
     * (추출기 {@code 37a25fb} · 그쪽 {@code spec/manifest.md} 의 「검사기가 내는 판정」 절).
     *
     * <p>⛔ <b>{@code incomplete} 를 판정으로 읽으면 저장이 열린다.</b> 판이 어긋난 레포에서
     * 검사기는 게이트를 하나도 안 돌리고 {@code MANIFEST} red <b>1건만</b> 낸다. 그것을 판정으로
     * 받으면 {@link DraftChecker} 의 전·후 견줌에서 <b>양쪽에 똑같이 있어 상쇄되고</b> 초록이 된다 —
     * 2026-08-15 에 실물 검사기로 만들어 확인했다({@code toolchain} 을 어긋내면 그 모양이 나온다).
     *
     * <p>⚠ <b>칸이 없는 것은 「못 냈다」가 아니다.</b> 그 칸이 생기기 전 검사기가 이미 나간 레포에
     * 실려 있다 — 없다고 막으면 멀쩡한 레포가 전부 판정 불가가 된다. 막는 것은
     * <b>있는데 {@code complete} 가 아닐 때</b>뿐이다.
     *
     * <p>⚠ 왜 멈췄는지는 {@code findings[0].gate} 가 말한다 — {@code MANIFEST} 면 검사기를
     * 갈아끼울 일이고 {@code DEPS} 면 {@code npm install} 이다. <b>그 갈래를 여기서 쓰지는 않는다</b>
     * (기획자에게 무엇을 보여줄지는 아직 안 정했다 — {@code requests-to-extractor.md} 12③).
     */
    private static boolean complete(JsonNode json) {
        JsonNode status = json.path("status");
        if (status.isMissingNode() || status.isNull()) {
            return true;
        }
        if ("complete".equals(status.asText())) {
            return true;
        }
        log.warn("검사기가 게이트를 하나도 안 돌렸다 — 판정을 못 낸 것으로 둔다 status={} gate={}",
                status.asText(), json.path("findings").path(0).path("gate").asText(""));
        return false;
    }

    private static Finding.Level level(String raw) {
        if ("review".equalsIgnoreCase(raw)) {
            return Finding.Level.REVIEW;
        }
        if (!"red".equalsIgnoreCase(raw)) {
            // 모르는 등급이다. 막는 쪽으로 센다 — 조용히 버리면 저장이 열린다.
            log.warn("모르는 진단 등급이다 — 막는 쪽으로 센다: {}", raw);
        }
        return Finding.Level.RED;
    }

    /** ⚠ 두 스트림을 동시에 빨아낸다 — {@code GitCommand} 가 같은 함정에서 실측으로 잡은 자리다. */
    private static Output execute(Path workingDir, List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(workingDir.toFile()).start();
            StreamPump stdout = StreamPump.start(process.getInputStream(), "checker-stdout");
            StreamPump stderr = StreamPump.start(process.getErrorStream(), "checker-stderr");
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("검사기가 시간 상한을 넘었다 — {}", String.join(" ", command));
                return null;
            }
            return new Output(process.exitValue(), stdout.text(), stderr.text());
        } catch (IOException e) {
            log.warn("검사기를 돌리지 못했다 — {}", String.join(" ", command), e);
            return null;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }

    /** {@code GitCommand.StreamPump} 를 그대로 베낀 것이다 — 새로 정한 것이 아니다. */
    private static final class StreamPump {

        private final Thread thread;
        private volatile String captured = "";

        private StreamPump(InputStream stream, String name) {
            this.thread = new Thread(() -> {
                try {
                    captured = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    captured = "";
                }
            }, name);
            this.thread.setDaemon(true);
        }

        static StreamPump start(InputStream stream, String name) {
            StreamPump pump = new StreamPump(stream, name);
            pump.thread.start();
            return pump;
        }

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
