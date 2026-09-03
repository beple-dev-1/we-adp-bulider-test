package com.bizplay.builder.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 실물 러너 — {@code claude -p --output-format json} 을 <b>남의 자격으로</b> 한 번 돌린다.
 *
 * <p>계약은 2026-08-14 스파이크가 실측으로 굳혔다
 * 계약을 잰 값은 {@code CliClaudeRunnerTest} 가 계속 지킨다.
 *
 * <p>⛔ <b>못 잰 것을 「된다」로 읽지 마라</b> — 스파이크는 <b>같은 기계 안의 다른 폴더</b>로만 옮겼다.
 * 기획자의 자격이 <b>리눅스 서버</b>에서 도는 것은 여전히 미측이다.
 */
@Component
public class CliClaudeRunner implements ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(CliClaudeRunner.class);

    /** 죽인 뒤 <b>끝난 것을 확인하는</b> 짧은 상한. */
    private static final Duration REAP_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                            String instruction, Consumer<Process> onStarted) {
        return run(credentialDir, workDir, timeout, List.of(), instruction, onStarted);
    }

    /**
     * 명령줄을 조립한다.
     *
     * <p>⛔ <b>지시문을 맨 뒤에 두지 마라 (2026-08-16 실측).</b> {@code claude} 의
     * {@code --add-dir <directories...>} 와 {@code --allowed-tools <tools...>} 는
     * <b>값을 여러 개 받는 꼴</b>이라, 뒤에 오는 지시문을 <b>그 목록의 한 칸으로 삼킨다.</b>
     * 그러면 프롬프트가 통째로 사라지고
     * {@code "Input must be provided either through stdin or as a prompt argument"} 로 죽는다.
     *
     * <p>⭐ <b>그래서 지시문이 {@code -p} 바로 뒤다.</b> {@code -p} 는 값을 안 받는 스위치라
     * 지시문이 곧장 위치 인자로 앉고, 뒤에 붙는 플래그가 삼킬 것이 없어진다.
     * <b>덧붙이는 조각은 언제나 맨 뒤에 둔다</b> — 여러 값을 받는 플래그가 와도 그 뒤가 비어 있다.
     *
     * <p>⚠ 2026-08-16 에 두 꼴을 다 돌려 봤다. 뒤에 두면 위 오류로 죽고, 앞에 두면
     * {@code is_error:false} 로 돌아온다.
     */
    static List<String> command(List<String> extraArgs, String instruction) {
        return command(extraArgs, instruction, File.separatorChar != '/', false);
    }

    static List<String> command(List<String> extraArgs, String instruction, boolean onWindows) {
        return command(extraArgs, instruction, onWindows, false);
    }

    /**
     * ⭐ <b>윈도우는 큰따옴표를 먹는다 (2026-08-18 실측).</b> {@link ProcessBuilder} 가 공백 든 인자를
     * {@code "…"} 로 감싸는데, 그 안의 {@code "} 가 <b>자식의 인자 파서에서 인용 기호로 소비된다.</b>
     * {@code {"a":"b"}} 를 보내면 자식은 {@code {a:b}} 를 받고, 값에 공백이 있으면
     * <b>인자가 거기서 쪼개지기까지 한다.</b> {@code \"} 로 미리 바꿔 두면 자식이 원본을 되찾는다.
     *
     * <p>⛔ <b>이것이 오래 숨어 있었다.</b> 지시문의 JSON 예시에서 따옴표만 조용히 사라져
     * 모델이 <b>따옴표 없는 예시</b>를 보고 매번 다른 모양을 냈다 — 프롬프트를 세 판 고쳐 보고서야
     * 여기가 원인이었음이 드러났다({@code --json-schema} 가 아예 못 서면서 표가 났다).
     *
     * <p>⛔ <b>리눅스에서는 손대지 마라.</b> 그쪽은 argv 를 그대로 넘기므로 이스케이프하면
     * <b>리터럴 백슬래시가 값에 박힌다.</b> 운영이 리눅스라 <b>운영에서는 나지 않던 문제</b>다.
     *
     * <p>⚠ <b>백슬래시가 따옴표 바로 앞에 오는 조합은 안 잰 자리다.</b> 우리가 넘기는 값
     * (지시문·스키마·경로)에 그 조합이 없어서 단순 치환으로 둔다 — 경로에는 백슬래시가 있지만
     * 따옴표가 없고, 스키마·지시문에는 백슬래시가 없다. <b>그 전제가 깨지면 여기를 다시 재라.</b>
     *
     * @param onWindows 플랫폼을 시험이 강제할 수 있게 갈라 둔 것
     */
    static List<String> command(List<String> extraArgs, String instruction, boolean onWindows,
                                boolean streaming) {
        List<String> command = new java.util.ArrayList<>(List.of(
                "claude", "-p", forArgv(instruction == null ? "" : instruction, onWindows),
                "--output-format", streaming ? "stream-json" : "json"));
        if (streaming) {
            // ⚠ 없으면 claude 가 stream-json 을 거절한다 (2026-08-18 실측).
            command.add("--verbose");
        }
        if (extraArgs != null) {
            for (int index = 0; index < extraArgs.size(); index++) {
                String arg = extraArgs.get(index);
                // 출력 형식은 진행 콜백 계약이 정한다. 호출자가 같은 옵션을 다시 넣어
                // stream-json을 json으로 덮어쓰면 실행은 성공해도 결과를 읽지 못한다.
                if ("--output-format".equals(arg)) {
                    if (index + 1 < extraArgs.size()) index++;
                    continue;
                }
                if (arg != null && arg.startsWith("--output-format=")) continue;
                command.add(forArgv(arg, onWindows));
            }
        }
        return command;
    }

    private static String forArgv(String value, boolean onWindows) {
        return onWindows && value.indexOf('"') >= 0 ? value.replace("\"", "\\\"") : value;
    }

    /** ⚠ 실물은 <b>진행 있는 판이 알맹이</b>다 — 이쪽은 진행 없이 그것을 부른다. */
    @Override
    public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                            List<String> extraArgs, String instruction, Consumer<Process> onStarted) {
        return run(credentialDir, workDir, timeout, extraArgs, instruction, onStarted, null);
    }

    @Override
    public ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                            List<String> extraArgs, String instruction,
                            Consumer<Process> onStarted, Consumer<Progress> onProgress) {
        boolean streaming = onProgress != null;
        List<String> command = command(extraArgs, instruction, File.separatorChar != '/', streaming);

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            // ★ 사람마다 여기로 갈린다.
            pb.environment().put("CLAUDE_CONFIG_DIR", credentialDir.toString());
            // ⛔ planner-account 의 금지: 서버 전체에 Claude 자격이 걸려 있으면 개인 자격이 통째로
            //    무시되는데 **오류가 안 난다** — 아무도 모르는 채 남의 계정으로 돌게 된다.
            //    자식에게 물려주는 환경에서만 걷어낸다. 서버 환경은 안 건드린다.
            //    ⚠ 있는 코드를 베끼는 것이지 새로 정하는 것이 아니다 — CliClaudeAuthGateway 가 이미 그렇게 한다.
            pb.environment().remove("ANTHROPIC_API_KEY");
            pb.environment().remove("ANTHROPIC_AUTH_TOKEN");
            pb.environment().remove("CLAUDE_CODE_OAUTH_TOKEN");
            // ⚠ stdin 을 반드시 닫아라 (2026-08-14 실측). 안 닫으면 claude 가
            //    "no stdin data received in 3s" 를 내고 **실행마다 3초를 버린다.**
            pb.redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())));

            process = pb.start();
            // ★ 뜨는 순간 알린다 — 그만두기가 닿는 유일한 손잡이다.
            onStarted.accept(process);

            // ⚠ 두 스트림을 **동시에** 빨아낸다. 한 줄씩 다 읽으면 시간 상한이 아무 일도 안 하게 되고,
            //   파이프가 차면 교착한다. GitCommand 가 같은 함정에서 실측으로 잡은 자리다.
            /*
             * ⚠ 진행 한 줄에서 작업 디렉터리 접두사를 잘라낸다 — 실물은 절대경로 통째로 온다.
             * ⚠ 거부된 도구 시도도 찍힌다(실측에서 Bash 가 그랬다) — 「시도했다」이지
             *   「됐다」가 아니다. 읽기 전용 보장은 CLAUDE_CONFIG_DIR 격리가 지킨다.
             */
            Consumer<Progress> shortened = onProgress == null ? null : step ->
                    onProgress.accept(step.kind() == Progress.Kind.TOOL
                            ? new Progress(step.kind(), shorten(step.text(), workDir)) : step);
            StreamPump stdout = StreamPump.start(process.getInputStream(), "claude-stdout", shortened);
            StreamPump stderr = StreamPump.start(process.getErrorStream(), "claude-stderr", null);

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killTree(process);
                // ⛔ 죽인 뒤에는 끝난 것을 확인한다 — 확인 없이 되돌리면
                //    **죽는 중인 프로세스가 되돌린 뒤에 파일을 한 번 더 쓴다.**
                process.waitFor(REAP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return ClaudeResult.timedOut();
            }
            return streaming
                    ? parseStream(process.exitValue(), stdout.text(), stderr.text())
                    : parse(process.exitValue(), stdout.text(), stderr.text());
        } catch (IOException e) {
            log.warn("claude 를 돌리지 못했다", e);
            return new ClaudeResult(-1, true, "builder:not_started", null, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            if (process != null) {
                killTree(process);
            }
            Thread.currentThread().interrupt();
            return new ClaudeResult(-1, true, "builder:interrupted", null, "");
        }
    }

    /**
     * ⚠ <b>{@code claude} 의 JSON 은 stdout 한 줄 덩어리로 온다</b>(2026-08-14 실측). JSONL 이 아니다 —
     * 줄마다 파싱하는 코드를 쓰지 마라. <b>한 덩어리를 받아 한 번에 파싱한다.</b>
     *
     * <p>⛔ <b>{@code subtype} 을 읽지 않는다</b> — 실패한 실행도 {@code "success"} 라 함정이다.
     * 성패는 종료코드와 {@code is_error} 가 가른다.
     */
    ClaudeResult parse(int exitCode, String stdout, String stderr) {
        try {
            JsonNode json = mapper.readTree(stdout);
            if (json == null || !json.isObject()) {
                throw new IOException("JSON 객체가 아니다");
            }
            return new ClaudeResult(
                    exitCode,
                    json.path("is_error").asBoolean(exitCode != 0),
                    json.path("terminal_reason").isTextual() ? json.get("terminal_reason").asText() : null,
                    json.path("api_error_status").isInt() ? json.get("api_error_status").asInt() : null,
                    bodyOf(json),
                    json.path("session_id").isTextual() ? json.get("session_id").asText() : null,
                    metricsOf(json));
        } catch (IOException e) {
            // 출력이 JSON 이 아니다 — 프로세스가 뜨자마자 죽은 경우가 여기다. **모르면 실패**로 둔다.
            // ⛔ stdout 을 로그에 붓지 않는다: 자격이 섞여 나올 수 있다.
            log.warn("claude 출력을 JSON 으로 못 읽었다 exitCode={}", exitCode);
            return new ClaudeResult(exitCode, exitCode != 0, "builder:unparsable", null, stderr);
        }
    }

    /**
     * {@code stream-json} 출력에서 <b>마지막 {@code result} 줄</b>을 결과로 읽는다.
     *
     * <p>⛔ <b>앞선 줄을 결과로 읽지 마라.</b> {@code assistant}·{@code system} 줄에는 성패가 없다.
     * ⛔ <b>{@code result} 줄이 없으면 모르는 것</b>이다 — 갈래를 지어내지 않는다.
     *
     * <p>⭐ <b>{@code --json-schema} 와 같이 선다</b>(2026-08-18 실측) — 그 줄에
     * {@code structured_output} 이 그대로 온다. <b>실시간과 스키마를 맞바꾸지 않는다.</b>
     */
    ClaudeResult parseStream(int exitCode, String stdout, String stderr) {
        String resultLine = null;
        for (String line : stdout.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("{") && trimmed.contains("\"type\":\"result\"")) {
                resultLine = trimmed;   // 마지막 것이 남는다
            }
        }
        if (resultLine == null) {
            log.warn("claude 스트림에 result 줄이 없다 exitCode={}", exitCode);
            return new ClaudeResult(exitCode, true, "builder:unparsable", null, stderr);
        }
        return parse(exitCode, resultLine, stderr);
    }

    /**
     * 진행 한 줄 — 사람이 「지금 무엇을 하는 중인지」 읽는 글자.
     *
     * <p>⚠ <b>도구 호출만 낸다.</b> {@code thinking_tokens}·{@code hook_*} 같은 줄까지 찍으면
     * 로그가 잡말로 덮여 정작 볼 것이 묻힌다. 도구가 아닌 줄에는 {@code null} 을 낸다.
     *
     * <p>⛔ <b>도구 인자를 통째로 찍지 마라</b> — 요구사항 원문이나 파일 내용이 섞여 나올 수 있다.
     * 파일 경로·검색어처럼 <b>짧고 뜻이 분명한 칸 하나</b>만 곁들인다.
     */
    static Progress progressOf(String line) {
        try {
            JsonNode json = SHARED.readTree(line);
            if ("user".equals(json.path("type").asText())) {
                return failedToolOf(json);
            }
            if (!"assistant".equals(json.path("type").asText())) {
                return null;
            }
            for (JsonNode block : json.path("message").path("content")) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    String said = oneLine(block.path("text").asText(""));
                    if (!said.isEmpty()) {
                        return new Progress(Progress.Kind.SAY, said);
                    }
                } else if ("tool_use".equals(type)) {
                    return new Progress(Progress.Kind.TOOL, toolOf(block));
                }
            }
            return null;
        } catch (IOException notJson) {
            return null;
        }
    }

    /**
     * <b>실패한 도구 결과</b>만 한 줄로 낸다.
     *
     * <p>⭐ <b>왜 다시 했는지가 여기 있다 (2026-08-19 실측).</b> {@code StructuredOutput} 이 두 번
     * 찍히고 그 사이 34초가 사라졌는데, 종전에는 {@code assistant} 줄만 봐서 <b>까닭이 어디에도
     * 안 남았다.</b> 도구가 거절당한 사실은 {@code user} 줄의 {@code tool_result} 에 온다.
     *
     * <p>⛔ <b>성공한 결과는 절대 내지 마라.</b> 그 자리에는 <b>읽은 파일이 통째로</b> 담겨 온다 —
     * 화면과 로그에 사업 내용을 붓는 문이 된다. 오류 글자만 짧게 낸다.
     */
    private static Progress failedToolOf(JsonNode json) {
        for (JsonNode block : json.path("message").path("content")) {
            if (!"tool_result".equals(block.path("type").asText())
                    || !block.path("is_error").asBoolean(false)) {
                continue;
            }
            JsonNode content = block.path("content");
            String said = oneLine(content.isTextual() ? content.asText() : content.toString());
            return new Progress(Progress.Kind.TOOL, "도구 실패 — " + said);
        }
        return null;
    }

    /**
     * ⛔ <b>도구 인자를 통째로 찍지 마라</b> — 요구사항 원문이나 파일 내용이 섞여 나올 수 있다.
     * 파일 경로·검색어처럼 <b>짧고 뜻이 분명한 칸 하나</b>만 곁들인다.
     */
    private static String toolOf(JsonNode block) {
        String name = block.path("name").asText("도구");
        JsonNode input = block.path("input");
        for (String field : List.of("file_path", "pattern", "path", "url")) {
            if (input.path(field).isTextual()) {
                return name + " " + input.get(field).asText();
            }
        }
        return name;
    }

    /** ⚠ 진행 표시의 한 칸이다 — 줄바꿈이 목록을 무너뜨리고 문단이 표시를 덮는다. */
    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= SAY_LIMIT ? flat : flat.substring(0, SAY_LIMIT) + "…";
    }

    /** AI 가 하는 말의 한 칸 상한. ⚠ 화면 한 줄에 앉을 만큼이다. */
    private static final int SAY_LIMIT = 200;

    /**
     * 진행 한 줄에서 <b>작업 디렉터리 접두사를 잘라낸다.</b>
     *
     * <p>⚠ 실물은 절대경로 통째로 온다(2026-08-18 실측) — 로그에서 읽을 것보다 자리 차지가 크다.
     * 남기는 것은 <b>클론 안에서의 자리</b>이고, 클론 밖의 자리는 그대로 둔다.
     */
    static String shorten(String progress, Path workDir) {
        String root = workDir.toString();
        String slashed = progress.replace(root + File.separator, "").replace(root, "");
        return slashed.equals(progress) ? progress : slashed.replace('\\', '/');
    }

    /** ⚠ {@link #progressOf} 가 static 이라 따로 둔다. ObjectMapper 는 스레드에 안전하다. */
    private static final ObjectMapper SHARED = new ObjectMapper();

    /**
     * 한 판이 쓴 시간과 토큰을 <b>있는 그대로</b> 옮긴다.
     *
     * <p>⛔ <b>없으면 널을 낸다.</b> 0 으로 채우면 「안 왔다」가 「0 이었다」로 둔갑해,
     * 계기를 보고 판단하는 사람이 <b>없는 숫자를 근거로 삼는다.</b>
     *
     * <p>⚠ {@code duration_ms} 를 있고 없음의 기준으로 삼는다 — 성공한 result 줄에는 언제나 있다.
     */
    private static ClaudeResult.Metrics metricsOf(JsonNode json) {
        if (!json.path("duration_ms").isNumber()) {
            return null;
        }
        JsonNode usage = json.path("usage");
        return new ClaudeResult.Metrics(
                json.path("duration_ms").asLong(),
                json.path("duration_api_ms").asLong(),
                json.path("num_turns").asInt(),
                usage.path("input_tokens").asLong(),
                usage.path("output_tokens").asLong(),
                usage.path("cache_read_input_tokens").asLong(),
                usage.path("cache_creation_input_tokens").asLong(),
                json.path("total_cost_usd").isNumber() ? json.get("total_cost_usd").asDouble() : null);
    }

    /**
     * 본문으로 삼을 글자.
     *
     * <p>⭐ <b>{@code --json-schema} 를 준 실행은 {@code structured_output} 이 정본이다
     * (2026-08-18 실측).</b> {@code claude} 가 스키마에 맞춰 <b>파싱된 객체</b>를 그 자리에 담아 준다 —
     * 그것을 쓰면 울타리도 잡말도 <b>모양 흔들림도 원인부터 없어진다.</b>
     *
     * <p>⛔ <b>{@code result} 를 먼저 보지 마라.</b> 둘 다 있을 때 스키마 검사를 지난 것은
     * {@code structured_output} 쪽이다. {@code result} 에는 같은 뜻이 울타리에 싸여 오거나
     * 아예 다른 말이 올 수 있다.
     *
     * <p>⚠ 스키마를 안 준 실행에는 그 자리가 없다 — 그때는 {@code result} 가 본문이다.
     */
    private String bodyOf(JsonNode json) {
        JsonNode structured = json.path("structured_output");
        return structured.isObject() || structured.isArray()
                ? structured.toString() : json.path("result").asText("");
    }

    /**
     * ⛔ <b>{@code destroyForcibly()} 하나로 끝내지 마라.</b> 직속 자식만 죽어서
     * {@code claude} 가 띄운 손자가 살아남고 <b>과금되는 일이 계속 돈다.</b>
     * {@code GitCommand} 가 아직 이 함정에 걸려 있다 — <b>여기서는 처음부터 훑는다.</b>
     */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** 개발은 윈도우 · 운영은 리눅스다. 빈 입력의 이름이 서로 다르다. */
    private static String nullDevice() {
        return File.separatorChar == '/' ? "/dev/null" : "NUL";
    }

    /**
     * 자식의 출력 한 갈래를 <b>따로 도는 데몬 스레드</b>에서 통째로 빨아낸다.
     *
     * <p>{@code GitCommand.StreamPump} 를 그대로 베낀 것이다 — 새로 정한 것이 아니다.
     * 데몬이라 상한에 걸려 죽인 자식의 스레드가 서버를 붙잡지 않는다.
     */
    private static final class StreamPump {

        private final Thread thread;
        private volatile String captured = "";

        /**
         * @param onProgress 널이 아니면 <b>줄마다</b> 부른다 — 그래야 진행이 실시간이 된다.
         *                   ⛔ {@code readAllBytes()} 로 통째로 빨아내면 끝날 때까지 아무것도 못 알린다.
         *                   널이면 종전처럼 통째로 빨아낸다(줄 나누기 비용을 안 낸다).
         */
        private StreamPump(InputStream stream, String name, Consumer<Progress> onProgress) {
            this.thread = new Thread(() -> {
                try {
                    captured = onProgress == null ? readAll(stream) : readLines(stream, onProgress);
                } catch (IOException e) {
                    // 죽인 자식의 파이프가 끊긴 것이다 — 여기서 할 일이 없다.
                    // ⛔ 내용은 로그에 붓지 않는다: 자격이 섞여 나올 수 있다.
                    captured = "";
                }
            }, name);
            this.thread.setDaemon(true);
        }

        private static String readAll(InputStream stream) throws IOException {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        /**
         * 줄마다 알리면서 통째로도 모은다 — 마지막 {@code result} 줄이 결과라 <b>다 모아야 한다.</b>
         *
         * <p>⛔ <b>알림에서 던진 것이 빨아내기를 멈추게 하지 마라.</b> 파이프가 막히면 자식이
         * 교착한다 — 이 스레드의 유일한 임무는 계속 빨아내는 것이다.
         */
        private static String readLines(InputStream stream, Consumer<Progress> onProgress)
                throws IOException {
            StringBuilder all = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    all.append(line).append('\n');
                    Progress progress = progressOf(line);
                    if (progress == null) {
                        continue;
                    }
                    try {
                        onProgress.accept(progress);
                    } catch (RuntimeException ignored) {
                        // 알리다 실패해도 빨아내기는 계속한다.
                    }
                }
            }
            return all.toString();
        }

        static StreamPump start(InputStream stream, String name, Consumer<Progress> onProgress) {
            StreamPump pump = new StreamPump(stream, name, onProgress);
            pump.thread.start();
            return pump;
        }

        /** 자식이 끝난 뒤에 부른다. 혹시 스레드가 안 끝나도 <b>여기서 매달리지 않는다.</b> */
        String text() {
            try {
                thread.join(REAP_TIMEOUT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return captured;
        }
    }
}
