package com.bizplay.builder.ai;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * {@code claude} 를 한 번 돌린다. <b>테스트는 여기에 대역을 낀다.</b>
 *
 * <p>구현은 {@link CliClaudeRunner} 하나다.
 */
public interface ClaudeRunner {

    /**
     * @param credentialDir 이 실행에만 쓰는 {@code CLAUDE_CONFIG_DIR}.
     *                      ⛔ 사람마다도 아니고 <b>실행마다</b> 따로다 — 같은 사람이 두 일을 동시에 돌릴 때
     *                      한쪽의 {@code finally} 가 다른 쪽 자격 파일을 지운다
     * @param onStarted     프로세스가 <b>뜨는 순간</b> 부른다. 일꾼이 그것을 지도에 넣어 두고
     *                      {@code finally} 에서 지운다 — <b>그만두기가 닿는 유일한 손잡이다.</b>
     *                      ⚠ 대역은 프로세스를 안 띄우므로 이것을 부르지 않는다
     */
    ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                     String instruction, Consumer<Process> onStarted);

    /**
     * 명령줄 조각을 더 붙여서 돌린다 — 도구 제한({@code --allowed-tools})과
     * 읽어도 되는 자리 추가({@code --add-dir})가 그 자리다.
     *
     * <p>⚠ <b>기본 구현은 조각을 버리고 위의 {@link #run} 을 그대로 부른다.</b> 테스트 대역이
     * 이것을 다시 구현하지 않아도 되게 하려는 것이다 — 대역은 프로세스를 안 띄우므로
     * 조각에 걸릴 것이 없다. <b>실물 {@link CliClaudeRunner} 만 이것을 덮어쓴다.</b>
     *
     * <p>⚠ <b>플래그 둘을 2026-08-16 에 실측했다</b> — {@code --allowed-tools <tools...>} 와
     * {@code --add-dir <directories...>} 는 실물에 있고, <b>둘 다 값을 여러 개 받는 꼴</b>이다.
     * ⛔ 그래서 조각은 <b>언제나 명령줄 맨 뒤</b>에 붙고 지시문은 앞에 선다 —
     * 순서를 뒤집으면 지시문이 그 목록으로 빨려 들어가 <b>프롬프트가 통째로 사라진다.</b>
     * 자세한 것은 {@link CliClaudeRunner#command} 에 적어 뒀다.
     *
     * <p>⛔ <b>조각을 코드에 박지 마라</b> — 부르는 쪽이 설치 설정에서 받아 넘긴다.
     */
    default ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                             java.util.List<String> extraArgs,
                             String instruction, Consumer<Process> onStarted) {
        return run(credentialDir, workDir, timeout, instruction, onStarted);
    }

    /**
     * 진행을 <b>돌는 중에</b> 받으면서 돌린다.
     *
     * <p>⭐ <b>왜 필요한가.</b> 한 판이 4~5분이라 끝난 뒤에만 찍으면 사람이 「도는 중인가 멈춘
     * 것인가」를 가릴 수 없다. {@code onProgress} 가 있으면 {@code claude} 를
     * {@code --output-format stream-json} 으로 돌려 <b>도구 호출과 AI 의 말마다</b> 한 걸음씩 알린다.
     *
     * <p>⚠ <b>{@code null} 이면 종전 그대로다</b> — 한 덩어리 JSON. 진행이 필요 없는 자리는
     * 계약을 바꾸지 않는다.
     *
     * <p>⚠ <b>기본 구현은 진행만 버리고 조각 있는 판으로 내린다.</b> 테스트 대역은 프로세스를
     * 안 띄우므로 알릴 것이 없다. ⛔ <b>{@code run(5개)} 로 곧장 내리지 마라</b> —
     * 그러면 대역이 오버라이드한 <b>조각 있는 판을 건너뛰어</b> {@code extraArgs} 가 조용히 사라진다
     * (2026-08-18 에 그 꼴로 시험 셋이 깨졌다). 내림 순서는 <b>7 → 6 → 5</b> 다.
     *
     * @param onProgress 진행 한 걸음. ⛔ {@link Progress.Kind#SAY} 는 로그로 보내지 마라.
     *                   ⛔ 여기서 오래 붙잡지 마라 — <b>출력을 빨아내는 스레드에서 부른다.</b>
     */
    default ClaudeResult run(Path credentialDir, Path workDir, Duration timeout,
                             java.util.List<String> extraArgs, String instruction,
                             Consumer<Process> onStarted, Consumer<Progress> onProgress) {
        return run(credentialDir, workDir, timeout, extraArgs, instruction, onStarted);
    }

    /**
     * 도는 중에 흘러오는 진행 한 걸음.
     *
     * <p>⭐ <b>말과 도구를 갈라 둔다.</b> 사람이 기다리며 보고 싶은 것은 「무슨 파일을 열었나」보다
     * <b>「무엇을 알아냈나」</b>이고 그것이 {@link Kind#SAY} 다.
     *
     * <p>⛔ <b>{@link Kind#SAY} 를 서버 로그에 붓지 마라.</b> 그 말에는 요구사항 내용이 섞여 나온다 —
     * 「사업 내용을 서버 로그에 매 실행마다 붓지 않는다」가 그 자리다. <b>SAY 는 화면 몫이고
     * 로그는 TOOL 만 받는다.</b>
     */
    record Progress(Kind kind, String text) {

        public enum Kind {
            /** 도구를 불렀다 — {@code "Read core/webview/pages/…md"} 꼴. 로그에도 찍어도 된다. */
            TOOL,
            /** AI 가 한 말. ⛔ 사업 내용이 섞인다 — 화면에만 낸다. */
            SAY
        }

        /** 화면이 갈래마다 다른 표시를 붙일 수 있게. */
        public boolean isSay() {
            return kind == Kind.SAY;
        }
    }

    /**
     * 한 번 돌린 결과. <b>구조화된 필드만</b> 담는다.
     *
     * <p>⛔ <b>사람이 읽는 문구를 파싱해 갈래를 만들지 마라</b> — 버전마다 바뀐다.
     *
     * @param terminalReason 왜 끝났나. {@code claude} 가 준 값이거나 {@link #TIMED_OUT_REASON} 이다
     * @param body           {@code result} 문자열. ⛔ 화면에 그대로 내지 않는다 — 개발자 로그로만 간다
     */
    record ClaudeResult(int exitCode, boolean isError, String terminalReason, Integer apiStatus,
                        String body, String sessionId, Metrics metrics) {

        /**
         * 계기를 안 쓰는 자리(시험 대역이 대부분이다)를 위한 짧은 판.
         * ⚠ <b>계기가 없다는 뜻이지 0 이라는 뜻이 아니다</b> — 그래서 널이다.
         */
        public ClaudeResult(int exitCode, boolean isError, String terminalReason, Integer apiStatus, String body) {
            this(exitCode, isError, terminalReason, apiStatus, body, null, null);
        }

        /**
         * 한 판이 무엇을 얼마나 썼나 — <b>{@code claude} 가 result 줄에 실어 준 숫자 그대로</b>다.
         *
         * <p>⭐ <b>이것이 있어야 「350초가 어디로 갔나」를 잰다 (2026-08-19).</b> 종전에는 이 숫자들이
         * 오는 줄 알면서도 버려서, 탐색이 오래 걸리는 것인지 출력이 오래 걸리는 것인지를
         * <b>로그만 보고는 가릴 수 없었다.</b>
         *
         * <p>⛔ <b>없는 값을 0 으로 채우지 마라</b> — {@link ClaudeResult#metrics()} 가 통째로 널이면
         * 「안 왔다」는 뜻이다.
         *
         * @param apiDurationMs 저쪽에 물어보느라 쓴 시간. {@code durationMs} 와의 차이가 <b>우리 쪽 몫</b>이다
         * @param numTurns      도구를 부르고 다시 생각한 횟수. 탐색이 얼마나 길었나가 여기 있다
         */
        record Metrics(long durationMs, long apiDurationMs, int numTurns, long inputTokens,
                       long outputTokens, long cacheReadTokens, long cacheCreationTokens, Double costUsd) {

            /**
             * 사람이 로그에서 읽는 한 줄. ⚠ <b>초로 바꿔서</b> 낸다 — 밀리초를 그대로 뱉으면
             * 읽는 사람이 매번 1000 으로 나누고 앉아 있게 된다.
             * ⚠ 자격이 섞일 자리가 없다 — 전부 숫자다.
             */
            @Override
            public String toString() {
                return "%d초(API %d초) 도구턴=%d 입력=%d 출력=%d 캐시읽기=%d 캐시쓰기=%d"
                        .formatted(durationMs / 1000, apiDurationMs / 1000, numTurns,
                                inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
            }
        }

        /**
         * 상한을 넘겨 우리가 죽였다는 표시.
         *
         * <p>⚠ <b>{@code claude} 가 낼 수 없는 글자여야 한다</b> — 콜론 앞의 {@code builder} 가 그 몫이다.
         * 겹치면 저쪽이 낸 값을 우리 시간초과로 잘못 읽는다.
         */
        public static final String TIMED_OUT_REASON = "builder:timed_out";

        /** 상한을 넘겨 죽인 결과. 종료코드는 실제로 없으므로 {@code -1} 로 둔다. */
        public static ClaudeResult timedOut() {
            return new ClaudeResult(-1, true, TIMED_OUT_REASON, null, "");
        }

        public boolean isTimedOut() {
            return TIMED_OUT_REASON.equals(terminalReason);
        }

        /**
         * <b>저쪽이 붐벼서</b> 끝났나 — 요청이 틀린 것이 아니라 <b>기다렸다 다시 걸면 되는</b> 실패다.
         *
         * <p>⭐ <b>2026-09-01 실측.</b> FRD 0000069 가 204초를 <b>다 쓰고 마지막에</b>
         * {@code api_error apiStatus=529 (Overloaded)} 로 버려졌다. 사람이 「다시 분석하기」를 눌러
         * 그 204초를 처음부터 다시 내야 했다 — <b>일시적인 혼잡을 사람 손으로 갚게 하지 않는다.</b>
         *
         * <p>⛔ <b>4xx 를 여기 더하지 마라</b>(429 만 예외다) — 요청이 틀린 것이라 백 번 걸어도 같다.
         * ⚠ {@code apiStatus} 가 없는 실패도 여기가 아니다: 자격끊김({@link #credentialLost()})과
         * 우리 쪽 시간초과가 그 모양이라, 상태 없는 것까지 붐빔으로 읽으면 <b>그 둘을 덮어쓴다.</b>
         *
         * <p>⚠ {@code GeminiDocumentUnderstanding} 의 {@code isBusy} 와 <b>같은 뜻으로 같은 줄</b>이다 —
         * 그쪽도 503 하나 때문에 사람에게 「문서 오류」를 내밀던 자리였다.
         */
        public boolean busy() {
            return "api_error".equals(terminalReason) && apiStatus != null
                    && (apiStatus == 429 || apiStatus / 100 == 5);
        }

        /**
         * Claude 계정의 사용 한도 또는 호출 빈도 제한에 걸렸나.
         *
         * <p>{@code 429}만으로는 구독 총량과 짧은 시간의 요청 제한을 더 세밀하게 가를 수 없다.
         * 따라서 화면에서도 둘 중 하나로 안내하고 초기화 시각을 지어내지 않는다.
         */
        public boolean rateLimited() {
            return "api_error".equals(terminalReason) && Integer.valueOf(429).equals(apiStatus);
        }

        /**
         * 자격끊김 — 실측된 모양 <b>하나에만</b> 맞춘다. 그 밖은 전부 「실패」다.
         *
         * <p>⚠ {@code terminal_reason=api_error && apiStatus=null} 만으로 자격끊김이라 하면 안 된다 —
         * 네트워크 장애나 상태 없는 다른 API 오류도 같은 모양일 수 있다. <b>본문의 {@code Not logged in}
         * 까지 맞을 때만</b> 자격끊김이다. 이것은 「문구 파싱」이 아니라 <b>좁히는 조건</b>이다 — 문구가 바뀌면
         * 자격끊김이 실패로 떨어질 뿐(fail-safe) 엉뚱한 것을 자격끊김이라 하지 않는다.
         *
         * <p>⚠ 2026-08-14 실측으로 이 네 조건이 전부 확인됐다. 2026-08-27 에 {@code AiRunWorker} 에서 여기로
         * 옮겼다 — {@code ClaudeCredentialRunner} 의 자동 재시도도 같은 판정을 쓴다.
         */
        public boolean credentialLost() {
            return exitCode == 1
                    && "api_error".equals(terminalReason)
                    && apiStatus == null
                    && body != null && body.contains("Not logged in");
        }
    }
}
