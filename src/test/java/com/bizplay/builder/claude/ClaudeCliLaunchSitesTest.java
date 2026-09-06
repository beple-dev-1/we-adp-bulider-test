package com.bizplay.builder.claude;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⛔ <b>{@code claude} 를 띄우는 자리를 늘리지 마라.</b>
 *
 * <p>004-1 이 <b>세 자리 중 하나만</b> 고쳤고 나머지 둘이 남아 004-2 로 다시 왔다. 자리가 흩어져
 * 있으면 한 자리를 고쳐도 다음이 남고, <b>다음 자리는 사람이 밟기 전까지 안 드러난다.</b>
 * 그래서 자리 자체를 늘리지 못하게 소스를 훑어 막는다.
 *
 * <p>새 자리가 필요하면 {@link ClaudeCli} 를 지나가게 해라 — 고정 낱말이면
 * {@link ClaudeCli#fixedCommand}, 자유형 인자가 섞이면 {@link ClaudeCli#launcher} 다.
 * ⚠ 그 둘을 <b>바꿔 쓰면 안 되는 까닭</b>은 {@code ClaudeCli} 의 주석에 실측으로 적혀 있다.
 */
class ClaudeCliLaunchSitesTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** 프로그램 이름을 직접 적어도 되는 유일한 자리. */
    private static final String OWNER = "ClaudeCli.java";

    /** 이름 상수를 <b>제대로 지나가며</b> 쓰는 유일한 자리. 되돌림은 아래 시험이 지킨다. */
    private static final String ROUTED = "CliClaudeRunner.java";

    @Test
    void claude_를_띄우는_자리는_ClaudeCli_한_곳뿐이다() {
        List<String> offenders = sources()
                .filter(file -> !file.getFileName().toString().equals(OWNER))
                .filter(file -> read(file).contains("ProcessBuilder(\"claude\""))
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("⛔ claude 를 직접 띄우지 마라 — ClaudeCli 를 지나가게 해라. "
                        + "고정 낱말이면 fixedCommand, 자유형 인자가 섞이면 launcher 다.")
                .isEmpty();
    }

    /**
     * ⛔ 프로그램 이름을 자기 파일에 다시 적으면 {@link ClaudeCli} 를 안 지나는 길이 또 생긴다.
     * 이름의 정본은 {@link ClaudeCli#NAME} 하나다.
     */
    @Test
    void 프로그램_이름을_다른_파일에_다시_적지_않는다() {
        List<String> offenders = sources()
                .filter(file -> !file.getFileName().toString().equals(OWNER))
                .filter(file -> read(file).contains("\"claude\""))
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("⛔ \"claude\" 리터럴은 ClaudeCli.NAME 하나로 둔다")
                .isEmpty();
    }

    /**
     * ⛔ <b>이름 상수로 우회하지 마라.</b> {@code new ProcessBuilder(ClaudeCli.NAME, …)} 는
     * 위 두 검사를 <b>둘 다 지난다</b> — 소스에 {@code "claude"} 글자가 안 나오기 때문이다.
     * 네 번째 자리를 막는 것이 이 시험의 목적인데 그것이 가장 자연스러운 우회로다.
     *
     * <p>⚠ <b>{@code ProcessBuilder(ClaudeCli.NAME} 만 찾으면 못 잡는다</b> — 정규화된 이름
     * ({@code new ProcessBuilder(com.bizplay.…ClaudeCli.NAME, …)})으로 그대로 새어 나간다.
     * 물림을 재 보다 실제로 그렇게 새는 것을 봤다. 그래서 <b>상수를 쓰는 것 자체</b>를 막고,
     * 제대로 지나가는 {@code CliClaudeRunner} 한 곳만 연다 — 그쪽은 아래 시험이 따로 지킨다.
     */
    @Test
    void 이름_상수로도_직접_띄우지_않는다() {
        List<String> offenders = sources()
                .filter(file -> !file.getFileName().toString().equals(OWNER))
                .filter(file -> !file.getFileName().toString().equals(ROUTED))
                .filter(file -> read(file).contains("ClaudeCli.NAME"))
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("⛔ 이름 상수를 가져다 직접 띄우지 마라 — fixedCommand 나 launcher 를 지나가게 해라")
                .isEmpty();
    }

    /**
     * ⛔ <b>AI 실행이 실물 실행파일로 가는 것을 되돌리지 마라.</b>
     *
     * <p>{@code CliClaudeRunner.command} 의 네 인자 판은 {@code ClaudeCli.NAME} 으로 위임한다 —
     * 시험이 인자 차례를 재기 편하라고 남겨 둔 자리다. 운영 경로가 <b>그쪽으로 되돌아가면</b>
     * 윈도우에서 배치를 지나게 되고 <b>여러 줄 지시문이 첫 줄에서 잘린다</b>(004-2 실측).
     * 러너 시험은 전부 {@code "claude"} 를 기대하므로 <b>되돌려도 초록이다</b> — 그래서 여기서 막는다.
     */
    @Test
    void AI실행은_실물_실행파일로_띄운다() {
        String runner = read(MAIN.resolve(Path.of("com", "bizplay", "builder", "ai",
                "CliClaudeRunner.java")));

        assertThat(runner)
                .as("⛔ 운영 경로가 ClaudeCli.launcher 를 안 지나면 지시문이 말없이 잘린다")
                .contains("ClaudeCli.launcher(");
    }

    private Stream<Path> sources() {
        try {
            return Files.walk(MAIN).filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .toList().stream();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
