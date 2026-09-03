package com.bizplay.builder.checker;

import com.bizplay.builder.config.BuilderProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검사기 <b>출력 계약</b>을 잰다 — 2026-08-14 에 실물 사업 레포(263화면)에 대고 잰 그대로다.
 *
 * <p>여기서 node 를 띄우지 않는다. 띄우면 검사기와 148MB 레포가 있는 기계에서만 초록이 된다.
 * 프로세스 조립은 사람이 눈으로 확인할 자리로 남긴다.
 */
class NodeCheckerCommandTest {

    private final NodeCheckerCommand command = new NodeCheckerCommand(properties());

    private static BuilderProperties properties() {
        return new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofMinutes(10),
                4, 50, Duration.ofMinutes(2));
    }

    /**
     * ⛔ 윈도우에는 npm.exe 가 없다 — 자바는 PATHEXT 를 안 보고 .exe 만 붙여 찾는다.
     * 「npm」 그대로 두면 검사기 첫 실행이 CreateProcess error=2 로 죽는다 (2026-08-27 실측).
     */
    @Test
    void 윈도우에서는_npm_이_아니라_npm_cmd_를_부른다() {
        assertThat(NodeCheckerCommand.npmExecutable(true)).isEqualTo("npm.cmd");
        assertThat(NodeCheckerCommand.npmExecutable(false)).isEqualTo("npm");
    }
    /** 실측한 첫 항목을 그대로 넣었다 — 필드 여섯이 다 살아 나와야 한다. */
    @Test
    void 실측한_출력을_그대로_읽는다() {
        CheckReport report = command.parse("""
                {"exitCode":1,"counts":{"red":26,"review":17},
                 "findings":[
                   {"file":"domain-index.json","line":1,"gate":"DOMAIN-COVERAGE","level":"red",
                    "what":"후보가 앵커도 사유도 없다","fix":"번호표를 적어라"},
                   {"file":"reqs/RQ-001.md","line":9,"gate":"A3-ANCHORS","level":"review",
                    "what":"가리키는 도메인을 못 읽었다","fix":"사람이 봐라"}],
                 "ratchet":{"status":"ok","baseline":17,"reviewCount":17,"message":null}}""");

        assertThat(report.verdict()).isEqualTo(CheckReport.Verdict.CHECKED);
        assertThat(report.findings()).hasSize(2);
        assertThat(report.redCount()).isEqualTo(1);
        assertThat(report.reviewCount()).isEqualTo(1);

        Finding first = report.findings().get(0);
        assertThat(first.file()).isEqualTo("domain-index.json");
        assertThat(first.line()).isEqualTo(1);
        assertThat(first.gate()).isEqualTo("DOMAIN-COVERAGE");
        assertThat(first.level()).isEqualTo(Finding.Level.RED);
        assertThat(first.what()).contains("앵커도 사유도 없다");
        assertThat(first.fix()).isNotBlank();
    }

    /** 진단이 0건인 것은 <b>초록이고 판정을 낸 것</b>이다 — 못 낸 것과 다르다. */
    @Test
    void 진단이_0건인_것은_판정을_낸_것이다() {
        CheckReport report = command.parse("""
                {"exitCode":0,"counts":{"red":0,"review":0},"findings":[],"ratchet":{"status":"ok"}}""");

        assertThat(report.verdict()).isEqualTo(CheckReport.Verdict.CHECKED);
        assertThat(report.isUnknown()).isFalse();
        assertThat(report.findings()).isEmpty();
    }

    /**
     * ⛔ <b>출력이 JSON 이 아니면 초록이 아니라 「못 냈다」다.</b> 실물에서 이 자리는
     * {@code npm install} 이 안 돼 있을 때다 — stdout 이 0바이트로 온다.
     */
    @Test
    void JSON_이_아니면_판정을_못_낸_것으로_둔다() {
        assertThat(command.parse("").isUnknown()).isTrue();
        assertThat(command.parse("검사기 의존이 없다 — 패키지를 못 찾는다").isUnknown()).isTrue();
        // findings 가 없는 JSON 도 판정으로 못 쓴다.
        assertThat(command.parse("{\"exitCode\":1}").isUnknown()).isTrue();
    }

    /**
     * ⛔ <b>{@code status:"incomplete"} 는 판정이 아니라 「못 냈다」다</b>(추출기 `37a25fb` · 2026-08-15 실측).
     *
     * <p>판이 어긋난 레포에서 검사기는 <b>게이트를 하나도 안 돌리고</b> {@code MANIFEST} red 1건만 낸다.
     * 그것을 판정으로 읽으면 {@link DraftChecker} 의 <b>전·후 견줌에서 양쪽에 똑같이 있어 상쇄되고</b>
     * 초록이 나온다 — <b>검사가 하나도 안 돈 레포에서 저장이 열린다.</b>
     */
    @Test
    void 게이트를_하나도_안_돌린_판정은_못_낸_것으로_둔다() {
        CheckReport report = command.parse("""
                {"toolchain":"we-adk-toolchain/1","status":"incomplete","exitCode":1,
                 "counts":{"red":1,"review":0},
                 "findings":[
                   {"file":"manifest.json","line":1,"gate":"MANIFEST","level":"red",
                    "what":"manifest.json 의 toolchain 이 we-adk-toolchain/9 인데 검사기는 /1 이다","fix":"갈아끼워라"}],
                 "ratchet":{"status":"ok","baseline":17,"reviewCount":17,"message":null}}""");

        assertThat(report.isUnknown()).isTrue();
    }

    /**
     * ⚠ <b>{@code status} 가 없는 것은 「못 냈다」가 아니다.</b> 그 칸이 생기기 전 검사기가
     * 이미 나간 레포에 실려 있다 — 없다고 막으면 <b>멀쩡한 레포가 전부 판정 불가가 된다.</b>
     * 막는 것은 <b>있는데 complete 가 아닐 때</b>뿐이다.
     */
    @Test
    void status_칸이_없는_옛_검사기의_판정은_그대로_받는다() {
        CheckReport report = command.parse("""
                {"exitCode":1,"counts":{"red":1,"review":0},
                 "findings":[{"file":"a.md","line":1,"gate":"A-3","level":"red","what":"뭔가","fix":"고쳐라"}]}""");

        assertThat(report.verdict()).isEqualTo(CheckReport.Verdict.CHECKED);
        assertThat(report.redCount()).isEqualTo(1);
    }

    /**
     * ⛔ <b>모르는 등급을 조용히 버리지 마라.</b> 버리면 새 등급이 생긴 날 그 진단이
     * 없는 것처럼 보여 저장이 열린다. 모르면 막는 쪽으로 센다.
     */
    @Test
    void 모르는_등급은_버리지_않고_막는_쪽으로_센다() {
        CheckReport report = command.parse("""
                {"findings":[{"file":"a.md","line":1,"gate":"G","level":"경고","what":"뭔가","fix":"고쳐라"}]}""");

        assertThat(report.findings()).hasSize(1);
        assertThat(report.redCount()).isEqualTo(1);
    }
}
