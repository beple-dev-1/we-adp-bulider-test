package com.bizplay.builder.ai;

import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code claude} 의 <b>출력 계약</b>을 잰다 — 2026-08-14 스파이크가 실물에 대고 잰 그대로다
 *
 * <p>여기서 실물 프로세스를 띄우지 않는다. 띄우면 <b>병주 계정으로 진짜 과금이 나가고</b>
 * 빌드 기계마다 {@code claude} 유무로 초록불이 갈린다. 프로세스 조립(환경변수 걷어내기 · stdin 닫기 ·
 * 두 스트림 흡입 · 손자까지 죽이기)은 <b>사람이 눈으로</b> 확인할 자리로 남긴다.
 */
class CliClaudeRunnerTest {

    private final CliClaudeRunner runner = new CliClaudeRunner();

    /** 스파이크 3번 — 성공했을 때. {@code total_cost_usd} 같은 나머지 열몇 개는 안 쓴다. */
    @Test
    void 성공한_출력을_그대로_읽는다() {
        ClaudeResult result = runner.parse(0, """
                {"type":"result","subtype":"success","is_error":false,"terminal_reason":"completed",
                 "stop_reason":"end_turn","api_error_status":null,"result":"2","total_cost_usd":0.102039}""", "");

        assertThat(result.exitCode()).isZero();
        assertThat(result.isError()).isFalse();
        assertThat(result.terminalReason()).isEqualTo("completed");
        assertThat(result.apiStatus()).isNull();
        assertThat(result.body()).isEqualTo("2");
        assertThat(result.isTimedOut()).isFalse();
    }

    /**
     * 스파이크 4번 — 자격이 없을 때. ⛔ <b>{@code subtype} 이 성공과 글자까지 같다.</b>
     * 그래서 이 record 에 {@code subtype} 자리를 안 만들었다 — <b>없는 것이 옳다.</b>
     */
    @Test
    void 자격이_없는_출력에서_네_조건이_다_나온다() {
        ClaudeResult result = runner.parse(1, """
                {"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error",
                 "api_error_status":null,"result":"Not logged in · Please run /login"}""", "");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.isError()).isTrue();
        assertThat(result.terminalReason()).isEqualTo("api_error");
        assertThat(result.apiStatus()).isNull();
        assertThat(result.body()).contains("Not logged in");
    }

    /**
     * ⭐ <b>{@code --json-schema} 를 준 실행은 {@code structured_output} 이 정본이다
     * (2026-08-18 실측).</b> {@code claude} 가 스키마에 맞춰 <b>파싱된 객체</b>를 그 자리에 담아 준다 —
     * 그러면 울타리도 잡말도 모양 흔들림도 원인부터 없어진다.
     *
     * <p>⛔ <b>{@code result} 를 먼저 보지 마라.</b> 둘 다 있을 때 정본은 {@code structured_output} 이고,
     * 그쪽이 스키마 검사를 지난 것이다.
     */
    @Test
    void 스키마를_준_실행은_구조화_출력을_본문으로_삼는다() {
        ClaudeResult result = runner.parse(0, """
                {"is_error":false,"terminal_reason":"completed",
                 "result":"이 글자는 볼 것이 아니다",
                 "structured_output":{"title":"실측 시험",
                                      "items":[{"requirement":"전체 메뉴 히든","verdict":"SCREEN"}]}}""", "");

        assertThat(result.body())
                .doesNotContain("볼 것이 아니다")
                .contains("\"title\":\"실측 시험\"")
                .contains("\"verdict\":\"SCREEN\"");
    }

    /** ⚠ 스키마를 안 준 실행은 그대로다 — {@code structured_output} 이 없으면 {@code result} 가 본문이다. */
    @Test
    void 구조화_출력이_없으면_결과_글자가_그대로_본문이다() {
        ClaudeResult result = runner.parse(0, """
                {"is_error":false,"result":"그냥 글자"}""", "");

        assertThat(result.body()).isEqualTo("그냥 글자");
    }

    /** 상태가 실제로 실린 API 오류. ⚠ 이건 자격끊김이 아니다 — 네 조건 중 {@code apiStatus} 가 어긋난다. */
    @Test
    void 상태가_실린_api_오류는_상태를_들고_온다() {
        ClaudeResult result = runner.parse(1, """
                {"is_error":true,"terminal_reason":"api_error","api_error_status":500,"result":"서버 오류"}""", "");

        assertThat(result.apiStatus()).isEqualTo(500);
    }

    /**
     * ⛔ <b>출력이 JSON 이 아닐 때 갈래를 지어내지 않는다 — 모르면 실패다.</b>
     * 프로세스가 뜨자마자 죽으면 stdout 이 비어 있다.
     */
    @Test
    void JSON_이_아니면_모르는_것으로_두고_실패로_읽는다() {
        ClaudeResult result = runner.parse(127, "", "claude: command not found");

        assertThat(result.isError()).isTrue();
        assertThat(result.terminalReason()).isEqualTo("builder:unparsable");
        assertThat(result.body()).contains("command not found");
        // 성공 판정(종료코드 0 && !is_error)에 걸리지 않는다.
        assertThat(result.exitCode()).isNotZero();
    }

    /**
     * ⛔ <b>2026-08-16 실측으로 잡은 자리다.</b> 지시문을 명령줄 <b>맨 뒤</b>에 두면
     * {@code --add-dir <directories...>} 가 그것을 <b>제 목록의 한 칸으로 삼킨다</b> —
     * 프롬프트가 통째로 사라지고 {@code claude} 가
     * {@code "Input must be provided either through stdin or as a prompt argument"} 로 죽는다.
     * 실물에 대고 두 꼴을 다 돌려서 확인했다.
     *
     * <p>⚠ 여기서 프로세스를 안 띄운다 — <b>조립한 글자만</b> 잰다. 그래서 과금도 없고
     * 빌드 기계에 {@code claude} 가 없어도 초록이다.
     */
    @Test
    void 지시문은_여러_값을_받는_플래그보다_앞에_선다() {
        var command = CliClaudeRunner.command(
                java.util.List.of("--allowed-tools", "Read,Glob,Grep", "--add-dir", "/tmp/일감"),
                "요구사항을 뽑아라");

        assertThat(command).containsExactly("claude", "-p", "요구사항을 뽑아라",
                "--output-format", "json",
                "--allowed-tools", "Read,Glob,Grep", "--add-dir", "/tmp/일감");
        assertThat(command.indexOf("요구사항을 뽑아라"))
                .as("⛔ 지시문이 --add-dir 뒤로 가면 그 목록에 삼켜진다")
                .isLessThan(command.indexOf("--add-dir"));
    }

    /**
     * ⭐ <b>윈도우는 큰따옴표를 먹는다 (2026-08-18 실측).</b> {@code ProcessBuilder} 가 공백 든 인자를
     * {@code "…"} 로 감싸는데, 그 안의 {@code "} 가 자식의 인자 파서에서 <b>인용 기호로 소비된다.</b>
     * 그래서 {@code {"a":"b"}} 를 보내면 자식은 {@code {a:b}} 를 받는다 — 게다가 값에 공백이 있으면
     * <b>인자가 거기서 쪼개지기까지 한다.</b>
     *
     * <p>⛔ <b>이것이 오래 숨어 있었다.</b> 지시문의 JSON 예시에서 따옴표만 조용히 사라졌고,
     * 모델은 <b>따옴표 없는 예시</b>를 보고 매번 다른 모양을 냈다 — 세 판을 프롬프트로 고쳐 보고서야
     * 여기가 원인이었음이 드러났다({@code --json-schema} 가 아예 못 서면서 표가 났다).
     *
     * <p>⚠ <b>리눅스에는 이 문제가 없다</b> — argv 를 그대로 넘긴다. 운영이 리눅스라
     * <b>운영에서는 나지 않던 것</b>이고, 그래서 더 늦게 드러났다.
     */
    @Test
    void 윈도우에서는_큰따옴표를_이스케이프해_넘긴다() {
        var command = CliClaudeRunner.command(
                java.util.List.of("--json-schema", "{\"type\":\"object\"}"),
                "결과는 {\"title\":\"업무명\"} 꼴로 내라", true);

        assertThat(command).containsExactly("claude", "-p",
                "결과는 {\\\"title\\\":\\\"업무명\\\"} 꼴로 내라",
                "--output-format", "json",
                "--json-schema", "{\\\"type\\\":\\\"object\\\"}");
    }

    /** ⛔ 리눅스에서 이스케이프하면 <b>리터럴 백슬래시가 값에 박힌다</b> — 그쪽은 손대지 않는다. */
    @Test
    void 리눅스에서는_손대지_않는다() {
        var command = CliClaudeRunner.command(
                java.util.List.of("--json-schema", "{\"type\":\"object\"}"),
                "결과는 {\"title\":\"업무명\"} 꼴로 내라", false);

        assertThat(command).containsExactly("claude", "-p",
                "결과는 {\"title\":\"업무명\"} 꼴로 내라",
                "--output-format", "json",
                "--json-schema", "{\"type\":\"object\"}");
    }

    /** ⚠ 따옴표가 없는 값은 어느 쪽에서도 그대로다 — 경로의 백슬래시를 건드리지 않는다. */
    @Test
    void 따옴표가_없으면_윈도우에서도_그대로다() {
        var command = CliClaudeRunner.command(
                java.util.List.of("--add-dir", "C:\\WorkSpace\\일감"), "짚어라", true);

        assertThat(command).containsExactly("claude", "-p", "짚어라",
                "--output-format", "json", "--add-dir", "C:\\WorkSpace\\일감");
    }

    /**
     * ⭐ <b>실행 중에 무엇을 하는지 알려면 {@code stream-json} 이다 (2026-08-18 실측).</b>
     * 한 판이 4~5분이라 끝난 뒤에만 찍으면 「도는 중인가 멈춘 것인가」를 가릴 수 없었다.
     *
     * <p>⚠ <b>{@code --verbose} 가 같이 가야 한다</b> — 없으면 {@code claude} 가 거절한다.
     * ⭐ <b>{@code --json-schema} 와 같이 선다</b>(실측) — 마지막 {@code result} 줄에
     * {@code structured_output} 이 그대로 온다. 실시간과 스키마를 <b>맞바꾸지 않는다.</b>
     */
    @Test
    void 진행을_받겠다고_하면_스트림_꼴로_돌린다() {
        var command = CliClaudeRunner.command(java.util.List.of("--model", "sonnet"),
                "짚어라", false, true);

        assertThat(command).containsExactly("claude", "-p", "짚어라",
                "--output-format", "stream-json", "--verbose", "--model", "sonnet");
    }

    @Test
    void 호출자가_출력_형식을_다시_넣어도_진행용_스트림_형식을_덮지_못한다() {
        var command = CliClaudeRunner.command(
                java.util.List.of("--model", "sonnet", "--output-format", "json"),
                "짚어라", false, true);

        assertThat(command).containsExactly("claude", "-p", "짚어라",
                "--output-format", "stream-json", "--verbose", "--model", "sonnet");
    }

    /** ⚠ 안 받겠다면 종전 그대로다 — 한 덩어리 JSON. */
    @Test
    void 진행을_안_받으면_종전_꼴이다() {
        assertThat(CliClaudeRunner.command(java.util.List.of(), "짚어라", false, false))
                .containsExactly("claude", "-p", "짚어라", "--output-format", "json");
    }

    /**
     * ⭐ <b>{@code stream-json} 은 줄마다 온다 — 마지막 {@code result} 줄이 결과다.</b>
     * ⛔ 앞선 줄들을 결과로 읽지 마라: {@code assistant}·{@code system} 줄에는 성패가 없다.
     */
    @Test
    void 스트림_꼴에서는_마지막_result_줄을_결과로_읽는다() {
        String stream = """
                {"type":"system","subtype":"init","session_id":"ㄱ"}
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read",\
                "input":{"file_path":"index.json"}}]}}
                {"type":"result","subtype":"success","is_error":false,"terminal_reason":"completed",\
                "result":"이 글자는 볼 것이 아니다",\
                "structured_output":{"title":"스트림 시험","items":[]}}
                """;

        ClaudeResult result = runner.parseStream(0, stream, "");

        assertThat(result.isError()).isFalse();
        assertThat(result.terminalReason()).isEqualTo("completed");
        assertThat(result.body())
                .doesNotContain("볼 것이 아니다")
                .contains("\"title\":\"스트림 시험\"");
    }

    /** ⛔ {@code result} 줄이 아예 없으면 <b>모르는 것</b>이다 — 갈래를 지어내지 않는다. */
    @Test
    void 스트림에_result_줄이_없으면_실패로_읽는다() {
        ClaudeResult result = runner.parseStream(0, """
                {"type":"system","subtype":"init"}
                """, "죽었다");

        assertThat(result.isError()).isTrue();
        assertThat(result.terminalReason()).isEqualTo("builder:unparsable");
    }

    /** ⚠ 도구 호출을 사람이 읽는 한 줄로 바꾼다. */
    @Test
    void 도구_호출을_사람이_읽는_한_줄로_바꾼다() {
        var read = CliClaudeRunner.progressOf("""
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read",\
                "input":{"file_path":"core/webview/pages/wv-modal-all-menu.md"}}]}}""");

        assertThat(read.kind()).isEqualTo(ClaudeRunner.Progress.Kind.TOOL);
        assertThat(read.text()).isEqualTo("Read core/webview/pages/wv-modal-all-menu.md");
        assertThat(CliClaudeRunner.progressOf("""
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Grep",\
                "input":{"pattern":"고유가","path":"core"}}]}}""").text())
                .isEqualTo("Grep 고유가");
    }

    /**
     * ⭐ <b>AI 가 실제로 하는 말이 알맹이다 (2026-08-18 병주 요청).</b> 도구 이름만 흘리면
     * 「무슨 파일을 열었나」만 알고 <b>무엇을 알아냈나</b>는 모른다 — 사람이 기다리며 보고 싶은 것은
     * 그쪽이다.
     *
     * <p>⛔ <b>{@code SAY} 를 서버 로그에 붓지 마라.</b> 그 말에는 요구사항 내용이 섞여 나온다 —
     * 「사업 내용을 서버 로그에 매 실행마다 붓지 않는다」는 규율이 그 자리다. <b>화면 몫이다.</b>
     */
    @Test
    void AI_가_하는_말도_진행으로_낸다() {
        var said = CliClaudeRunner.progressOf("""
                {"type":"assistant","message":{"content":[{"type":"text",\
                "text":"index.json 을 훑어 웹뷰 후보를 넷으로 좁혔다."}]}}""");

        assertThat(said.kind()).isEqualTo(ClaudeRunner.Progress.Kind.SAY);
        assertThat(said.text()).isEqualTo("index.json 을 훑어 웹뷰 후보를 넷으로 좁혔다.");
    }

    /** ⚠ 긴 말은 잘라 낸다 — 화면 한 줄이 문단으로 늘어지면 진행 표시가 아니게 된다. */
    @Test
    void 긴_말은_잘라_낸다() {
        var said = CliClaudeRunner.progressOf("""
                {"type":"assistant","message":{"content":[{"type":"text","text":"%s"}]}}"""
                .formatted("가".repeat(400)));

        assertThat(said.text()).hasSizeLessThanOrEqualTo(201).endsWith("…");
    }

    /** ⚠ 여러 줄로 온 말은 첫 문단만 — 줄바꿈이 화면 목록을 무너뜨린다. */
    @Test
    void 여러_줄로_온_말은_한_줄로_만든다() {
        var said = CliClaudeRunner.progressOf("""
                {"type":"assistant","message":{"content":[{"type":"text",\
                "text":"첫 줄이다.\\n\\n둘째 줄이다."}]}}""");

        assertThat(said.text()).isEqualTo("첫 줄이다. 둘째 줄이다.");
    }

    /**
     * ⚠ <b>작업 디렉터리 접두사를 잘라낸다.</b> 실물이 절대경로 통째로 온다(2026-08-18 실측) —
     * {@code C:\…\projects\0000001\clone\core\webview\pages\wv-card-list.md} 는 로그에서
     * 읽을 것보다 자리 차지가 크다. 남기는 것은 <b>클론 안에서의 자리</b>다.
     */
    @Test
    void 작업_디렉터리_접두사를_잘라_낸다() {
        assertThat(CliClaudeRunner.shorten(
                "Read C:\\일감\\clone\\core\\webview\\pages\\wv-card-list.md",
                java.nio.file.Path.of("C:\\일감\\clone")))
                .isEqualTo("Read core/webview/pages/wv-card-list.md");
    }

    /** ⚠ 클론 밖의 자리는 그대로 둔다 — 요구사항 원문이 앉는 실행 폴더가 그렇다. */
    @Test
    void 작업_디렉터리_밖의_자리는_그대로_둔다() {
        assertThat(CliClaudeRunner.shorten("Read C:\\다른곳\\요구사항.md",
                java.nio.file.Path.of("C:\\일감\\clone")))
                .isEqualTo("Read C:\\다른곳\\요구사항.md");
    }

    /** ⛔ 도구도 말도 아닌 줄에는 아무것도 내지 않는다 — 잡말이 진행 표시를 덮는다. */
    @Test
    void 도구도_말도_아닌_줄은_내지_않는다() {
        assertThat(CliClaudeRunner.progressOf("""
                {"type":"system","subtype":"thinking_tokens"}""")).isNull();
        assertThat(CliClaudeRunner.progressOf("JSON 이 아닌 줄")).isNull();
        assertThat(CliClaudeRunner.progressOf("""
                {"type":"assistant","message":{"content":[{"type":"text","text":"   "}]}}""")).isNull();
    }

    /** 조각이 없어도 자리는 같다 — 꼴이 둘로 갈리면 한쪽만 재게 된다. */
    @Test
    void 덧붙이는_조각이_없어도_지시문_자리는_같다() {
        assertThat(CliClaudeRunner.command(java.util.List.of(), "정리해라"))
                .containsExactly("claude", "-p", "정리해라", "--output-format", "json");
        assertThat(CliClaudeRunner.command(null, null))
                .containsExactly("claude", "-p", "", "--output-format", "json");
    }

    /**
     * ⚠ 시간초과 표시는 <b>{@code claude} 가 낼 수 없는 글자</b>여야 한다 —
     * 겹치면 저쪽이 낸 값을 우리 시간초과로 잘못 읽는다.
     */
    @Test
    void 시간초과_표시는_claude_가_낼_수_없는_글자다() {
        assertThat(ClaudeResult.timedOut().isTimedOut()).isTrue();
        assertThat(ClaudeResult.TIMED_OUT_REASON).startsWith("builder:");

        // 저쪽이 내는 값들은 그 표시로 안 읽힌다.
        assertThat(runner.parse(0, """
                {"is_error":false,"terminal_reason":"completed","result":"됐다"}""", "").isTimedOut()).isFalse();
    }

    /**
     * ⭐ <b>{@code claude} 가 준 숫자를 버리지 마라 (2026-08-19).</b> 한 판이 350초인데 그 350초가
     * 탐색인지 출력인지 사고인지를 <b>가릴 길이 없었다</b> — result 줄에 다 실려 오는데 안 읽었다.
     *
     * <p>⚠ {@code session_id} 는 계기가 아니라 <b>손잡이</b>다 — 다음 판을 이어 붙이는 열쇠다.
     */
    @Test
    void 걸린_시간과_토큰과_세션ID_를_같이_읽는다() {
        ClaudeResult result = runner.parse(0, """
                {"type":"result","subtype":"success","is_error":false,"terminal_reason":"completed",
                 "session_id":"79a07238-1ceb-4b01-bfdd-241183d0686b",
                 "duration_ms":350123,"duration_api_ms":301000,"num_turns":31,"total_cost_usd":1.23,
                 "usage":{"input_tokens":12,"output_tokens":4200,
                          "cache_read_input_tokens":29960,"cache_creation_input_tokens":14985},
                 "result":"{}"}""", "");

        assertThat(result.sessionId()).isEqualTo("79a07238-1ceb-4b01-bfdd-241183d0686b");
        assertThat(result.metrics().durationMs()).isEqualTo(350123);
        assertThat(result.metrics().apiDurationMs()).isEqualTo(301000);
        assertThat(result.metrics().numTurns()).isEqualTo(31);
        assertThat(result.metrics().outputTokens()).isEqualTo(4200);
        assertThat(result.metrics().cacheReadTokens()).isEqualTo(29960);
        assertThat(result.metrics().cacheCreationTokens()).isEqualTo(14985);
    }

    /** ⛔ <b>없는 숫자를 0 으로 지어내지 마라</b> — 「안 왔다」와 「0 이었다」는 다른 말이다. */
    @Test
    void 숫자가_없는_출력에는_계기가_안_붙는다() {
        ClaudeResult result = runner.parse(0, """
                {"type":"result","is_error":false,"terminal_reason":"completed","result":"2"}""", "");

        assertThat(result.metrics()).isNull();
        assertThat(result.sessionId()).isNull();
    }

    /**
     * 계기는 <b>사람이 로그에서 읽을 수 있어야</b> 계기다.
     * ⚠ 밀리초를 그대로 뱉으면 350123 을 사람이 초로 나누고 앉아 있게 된다.
     */
    @Test
    void 계기는_사람이_읽는_한_줄로_찍힌다() {
        ClaudeResult.Metrics metrics =
                new ClaudeResult.Metrics(350123, 301000, 31, 12, 4200, 29960, 14985, 1.23);

        assertThat(metrics.toString())
                .contains("350초")
                .contains("API 301초")
                .contains("도구턴=31")
                .contains("출력=4200")
                .contains("캐시읽기=29960");
    }

    /**
     * ⭐ <b>도구가 실패한 것을 사람에게 알린다 (2026-08-19).</b> 실측 로그에서
     * {@code StructuredOutput} 이 <b>두 번</b> 찍히고 그 사이에 34초가 사라졌는데,
     * 종전 진행 표시는 <b>assistant 줄만</b> 봐서 <b>왜 다시 냈는지가 어디에도 안 남았다.</b>
     * 도구 결과의 오류는 {@code user} 줄에 온다.
     */
    @Test
    void 실패한_도구_결과를_사람이_읽는_한_줄로_바꾼다() {
        var failed = CliClaudeRunner.progressOf("""
                {"type":"user","message":{"content":[{"type":"tool_result","is_error":true,                "content":"Input does not match schema: required property 'noScreenReason' is missing"}]}}""");

        assertThat(failed.kind()).isEqualTo(ClaudeRunner.Progress.Kind.TOOL);
        assertThat(failed.text()).startsWith("도구 실패");
        assertThat(failed.text()).contains("noScreenReason");
    }

    /** ⛔ <b>성공한 도구 결과는 내지 않는다</b> — 파일 내용이 통째로 담겨 오는 자리다. */
    @Test
    void 성공한_도구_결과는_내지_않는다() {
        assertThat(CliClaudeRunner.progressOf("""
                {"type":"user","message":{"content":[{"type":"tool_result",                "content":"화면 md 의 본문이 여기 통째로 온다"}]}}""")).isNull();
    }
}
