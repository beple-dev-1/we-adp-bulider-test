package com.bizplay.builder.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>{@code claude} CLI 를 어떻게 띄울지 정하는 한 자리다.</b>
 *
 * <p>⛔ <b>여기 말고 다른 곳에서 {@code new ProcessBuilder("claude"…)} 를 쓰지 마라.</b>
 * 004-1 에서 한 자리만 고쳤더니 나머지 둘이 남아 004-2 로 다시 왔다. 자리가 흩어져 있으면
 * 하나를 고쳐도 다음이 남는다. {@code ClaudeCliLaunchSitesTest} 가 그것을 막는다.
 *
 * <p><b>왜 자리마다 답이 다른가 — 2026-09-06 실측이다.</b> 윈도우에는 PATH 에 {@code claude.exe} 가
 * 없고 npm 이 깐 {@code claude.cmd}(배치)만 있다. 자바 {@code ProcessBuilder} 는 {@code .exe} 만
 * 찾으므로({@code PATHEXT} 를 안 본다) 무엇이든 한 겹을 끼워야 하는데, <b>그 한 겹이 인자를 상하게 한다.</b>
 *
 * <pre>
 *   보낸 값: 한 줄짜리 (따옴표·백슬래시·&amp; | &gt; % ^ 포함)
 *     .cmd 를 절대경로로 직접   → 그대로 도착
 *     cmd /c 로 감싸서          → 그대로 도착
 *     진짜 .exe 로 직접          → 그대로 도착
 *
 *   보낸 값: <b>여러 줄</b> (지시문이 이 꼴이다)
 *     .cmd 를 절대경로로 직접   → ⛔ <b>첫 줄만 도착</b> (65자 → 53자)
 *     cmd /c 로 감싸서          → ⛔ <b>첫 줄만 도착</b>
 *     진짜 .exe 로 직접          → 그대로 도착
 * </pre>
 *
 * <p>⛔ <b>배치를 지나면 줄바꿈 뒤가 조용히 잘린다.</b> 오류가 안 나고 지시문만 짧아지므로
 * <b>모델이 엉뚱한 답을 내는 것으로만 드러난다</b> — 2026-08-18 에 따옴표가 사라져 프롬프트를
 * 세 판 고쳐 봤던 것과 같은 부류의 사고다.
 *
 * <p>그래서 <b>고정 낱말만 넘기는 자리</b>({@link #fixedCommand})는 {@code cmd /c} 로 가고,
 * <b>자유형 인자가 섞이는 자리</b>({@link #launcher})는 <b>진짜 실행파일을 찾아 그대로 부른다.</b>
 */
public final class ClaudeCli {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCli.class);

    /** PATH 에 있는 이름. 리눅스·맥은 이것 하나로 끝난다. */
    public static final String NAME = "claude";

    /**
     * npm 배치가 진짜 실행파일을 부르는 줄에서 그 경로를 꺼내는 자리.
     *
     * <p>⛔ <b>아무 {@code .exe} 나 집지 마라.</b> npm 은 JS 진입 패키지에 다른 꼴의 배치를 깔고,
     * 거기에는 {@code IF EXIST "%dp0%
ode.exe"} 가 <b>먼저</b> 나온다. 그 줄을 집으면
     * {@code node.exe -p "<지시문>"} 이 돌아 node 가 {@code -p} 를 <b>JS 평가</b>로 읽는다.
     * 그래서 아래에서 <b>파일 이름이 우리 것인 후보만</b> 고른다.
     */
    private static final Pattern QUOTED_EXE = Pattern.compile("\"([^\"]*\\.exe)\"");

    private static volatile String cachedLauncher;

    private ClaudeCli() {
    }

    /**
     * <b>고정 낱말만 인자로 가는 자리</b>에 쓸 명령줄. 윈도우면 {@code cmd /c} 를 앞에 붙인다 —
     * 확장자 탐색({@code PATHEXT})은 원래 {@code cmd} 의 일이고, 우리가 흉내 낼 까닭이 없다.
     *
     * <p>⛔ <b>여기에 사람이 준 값을 인자로 넘기지 마라.</b> 셸이 한 번 더 파싱하므로
     * 「인자가 전부 고정 낱말이라 넣을 구멍이 없다」는 안전 근거가 그 순간 무너진다.
     * ⛔ <b>줄바꿈이 든 값도 넘기지 마라</b> — 배치가 첫 줄에서 자른다(위 실측).
     * 그런 값이 필요하면 {@link #launcher} 쪽이다.
     *
     * <p>⭐ <b>자식 환경은 {@code cmd} 를 지나 손자까지 그대로 내려간다</b>(2026-09-06 실측 ·
     * 공백이 든 경로 포함). {@code CLAUDE_CONFIG_DIR} 설정과 열쇠 제거가 살아 있다는 뜻이라,
     * 여기에 셸을 끼우는 선택의 안전 근거 가운데 하나다.
     */
    public static List<String> fixedCommand(String osName, List<String> args) {
        List<String> command = new ArrayList<>();
        if (isWindows(osName)) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(NAME);
        command.addAll(args);
        return List.copyOf(command);
    }

    /**
     * <b>자유형 인자가 섞이는 자리</b>가 부를 실행파일. 리눅스·맥은 {@code claude} 그대로다.
     *
     * <p>윈도우에서는 <b>진짜 실행파일을 찾아 그 절대경로를 낸다.</b> 셸이 안 끼므로 인자가
     * {@code ProcessBuilder} 규칙 그대로 가고, {@code CliClaudeRunner.forArgv} 가 2026-08-18 에
     * 재 둔 전제도 그대로 선다.
     *
     * <p>찾는 순서 — ① PATH 의 {@code claude.exe}(네이티브 설치) ② PATH 의 {@code claude.cmd} 가
     * <b>스스로 가리키는</b> 실행파일. ②는 경로를 코드에 박는 것이 아니라 <b>깔린 배치에게 물어보는</b> 것이다.
     *
     * <p>⛔ <b>못 찾았다고 {@code cmd /c} 로 되돌아가지 마라.</b> 그러면 여러 줄 지시문이
     * <b>말없이 잘린 채</b> 돌아 아무도 못 알아챈다. 차라리 {@code claude} 그대로 두어
     * {@code CreateProcess error=2} 로 <b>크게 터지게</b> 둔다 — 004-1 이전과 같은 자리다.
     */
    public static String launcher(String osName) {
        if (!isWindows(osName)) {
            return NAME;
        }
        String found = cachedLauncher;
        if (found == null) {
            found = resolveWindowsExecutable().map(Path::toString).orElseGet(() -> {
                log.warn("윈도우에서 claude 실행파일을 못 찾았다 — {} 로 그대로 띄운다."
                        + " 지시문이 잘리는 것보다 크게 터지는 쪽이 낫다.", NAME);
                return NAME;
            });
            cachedLauncher = found;
        }
        return found;
    }

    /** ⚠ 시험이 자리를 다시 재게 하는 자리. 운영 코드에서 부르지 않는다. */
    static void forgetCache() {
        cachedLauncher = null;
    }

    static Optional<Path> resolveWindowsExecutable() {
        Optional<Path> direct = onPath(NAME + ".exe");
        if (direct.isPresent()) {
            return direct;
        }
        return onPath(NAME + ".cmd").flatMap(ClaudeCli::executableDeclaredBy);
    }

    /** PATH 를 앞에서부터 훑어 그 이름의 파일을 찾는다. */
    static Optional<Path> onPath(String fileName) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry.trim(), fileName);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            } catch (RuntimeException notAPath) {
                // PATH 에는 경로로 못 읽는 조각이 섞여 있을 수 있다. 그 조각만 건너뛴다.
                log.debug("PATH 조각을 경로로 못 읽어 건너뛴다 entry={}", entry, notAPath);
            }
        }
        return Optional.empty();
    }

    /**
     * 배치가 스스로 적어 둔 실행파일을 꺼낸다. npm 이 까는 꼴이 {@code "%dp0%\…\claude.exe"   %*} 다.
     *
     * <p>⚠ <b>경로를 우리가 짓지 않는다</b> — 배치에 적힌 것을 읽어 그 배치가 있는 자리 기준으로 편다.
     * 설치 방식이 바뀌어 그 줄이 없으면 <b>빈 값</b>을 낸다. 지어내지 않는다.
     */
    static Optional<Path> executableDeclaredBy(Path shim) {
        String body;
        try {
            body = Files.readString(shim, StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            log.debug("배치를 읽지 못했다 shim={}", shim, unreadable);
            return Optional.empty();
        }
        Path home = shim.getParent();
        if (home == null) {
            return Optional.empty();
        }
        Matcher found = QUOTED_EXE.matcher(body);
        while (found.find()) {
            String declared = found.group(1)
                    .replace("%~dp0", home + File.separator)
                    .replace("%dp0%", home + File.separator);
            try {
                Path candidate = Path.of(declared).toAbsolutePath().normalize();
                // ⛔ 이름이 우리 것이어야 한다 — 배치의 첫 .exe 가 node.exe 인 꼴이 실재한다.
                if (!candidate.getFileName().toString().equalsIgnoreCase(NAME + ".exe")) {
                    continue;
                }
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException notAPath) {
                // 배치에 적힌 것이 경로가 아닐 수 있다. 다음 후보로 간다.
                log.debug("배치가 가리킨 값을 경로로 못 읽어 건너뛴다 declared={}", declared, notAPath);
            }
        }
        return Optional.empty();
    }

    public static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
