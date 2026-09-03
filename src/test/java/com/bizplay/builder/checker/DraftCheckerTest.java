package com.bizplay.builder.checker;

import com.bizplay.builder.checker.CheckReport.Verdict;
import com.bizplay.builder.checker.Finding.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장 전 검사 — <b>「누구 탓인가」를 가른다.</b>
 *
 * <p>⭐ <b>왜 차이를 보는가.</b> 2026-08-14 에 실물 기획 레포를 클론해 검사기를 돌려 보니
 * <b>이미 red 26 · review 17 이 깔려 있었다.</b> 그러니 「전체가 초록이어야 저장」은 <b>애초에 불가능</b>하다 —
 * 남이 만든 빨강 26개가 기획자를 영원히 막는다. <b>후보를 얹기 전과 후를 견줘 새로 생긴 것만</b>
 * 그 사람 탓으로 돌리는 것이 유일한 길이다.
 *
 * <p>⚠ 검사기 호출을 인터페이스로 끊어 둔 것은 <b>node 없이 판정 로직을 재기 위해서다.</b>
 * 실물 호출은 {@link NodeCheckerCommandTest} 가 따로 잰다.
 */
class DraftCheckerTest {

    @TempDir Path workspace;

    private FakeChecker checker;
    private DraftChecker drafts;

    @BeforeEach
    void setUp() throws IOException {
        checker = new FakeChecker();
        drafts = new DraftChecker(checker);
        Files.createDirectories(workspace.resolve("reqs"));
        Files.writeString(workspace.resolve("reqs/RQ-001.md"), "# 원래 있던 것\n");
    }

    /** 남이 만들어 둔 빨강. 후보를 얹어도 그대로 남는다 — <b>기획자 탓이 아니다.</b> */
    private static Finding othersRed() {
        return new Finding("domain-index.json", 1, "DOMAIN-COVERAGE", Level.RED,
                "후보가 앵커도 사유도 없다", "번호표를 적어라");
    }

    private static Finding minePut(String what) {
        return new Finding("reqs/RQ-001.md", 3, "A3-ANCHORS", Level.RED, what, "앵커를 고쳐라");
    }

    // ── 누구 탓인가 ──────────────────────────────────────────────────────

    @Test
    void 후보를_얹어_새로_생긴_것만_그_사람_탓으로_돌린다() {
        checker.next(Verdict.CHECKED, othersRed());                          // 얹기 전
        checker.next(Verdict.CHECKED, othersRed(), minePut("없는 앵커다"));    // 얹은 뒤

        DraftCheckResult result = drafts.check(workspace, workspace, "reqs/RQ-001.md", "# 고친 것\n");

        assertThat(result.caused()).singleElement()
                .extracting(Finding::what).isEqualTo("없는 앵커다");
        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.RED);
    }

    /**
     * ⛔ <b>이 시험이 「전체가 초록이어야 저장」으로 되돌아가는 것을 막는다.</b>
     * 그렇게 만들면 남이 깔아 둔 red 26 개 때문에 아무도 저장을 못 한다.
     */
    @Test
    void 남이_만든_빨강은_내_판정에_안_섞인다() {
        checker.next(Verdict.CHECKED, othersRed());
        checker.next(Verdict.CHECKED, othersRed());   // 그대로다 — 내가 만든 것이 없다

        DraftCheckResult result = drafts.check(workspace, workspace, "reqs/RQ-001.md", "# 고친 것\n");

        assertThat(result.caused()).isEmpty();
        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.GREEN);
        assertThat(result.canSave()).isTrue();
    }

    @Test
    void 고쳐서_사라진_것도_같이_알려준다() {
        checker.next(Verdict.CHECKED, othersRed(), minePut("없는 앵커다"));
        checker.next(Verdict.CHECKED, othersRed());   // 내가 그걸 고쳤다

        DraftCheckResult result = drafts.check(workspace, workspace, "reqs/RQ-001.md", "# 고친 것\n");

        assertThat(result.caused()).isEmpty();
        assertThat(result.fixed()).singleElement()
                .extracting(Finding::what).isEqualTo("없는 앵커다");
        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.GREEN);
    }

    /**
     * ⚠ 같은 게이트·같은 파일이라도 <b>줄이 다르면 다른 건이다.</b> 셋으로 묶어 세지 않으면
     * 두 자리가 틀렸을 때 하나만 보여주고 나머지를 조용히 삼킨다.
     */
    @Test
    void 같은_파일_같은_게이트라도_줄이_다르면_따로_센다() {
        Finding line3 = minePut("셋째 줄이 틀렸다");
        Finding line9 = new Finding("reqs/RQ-001.md", 9, "A3-ANCHORS", Level.RED,
                "아홉째 줄이 틀렸다", "앵커를 고쳐라");
        checker.next(Verdict.CHECKED);
        checker.next(Verdict.CHECKED, line3, line9);

        assertThat(drafts.check(workspace, workspace, "reqs/RQ-001.md", "x").caused()).hasSize(2);
    }

    // ── 판정을 못 냈을 때 ────────────────────────────────────────────────

    /**
     * ⛔ <b>「판정 못 냈다」를 초록으로 읽으면 안 된다.</b> 실물에서 이것이 나는 자리는
     * {@code npm install} 이 안 돼 있을 때다 — 그때 검사기는 <b>stdout 0바이트 + 종료코드 1</b> 로 끝난다.
     * 그걸 초록으로 읽으면 <b>아무 검사도 안 하고 저장이 열린다.</b>
     */
    @Test
    void 판정을_못_내면_초록도_빨강도_아니다() {
        checker.next(Verdict.UNKNOWN);
        checker.next(Verdict.CHECKED);

        DraftCheckResult result = drafts.check(workspace, workspace, "reqs/RQ-001.md", "x");

        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.UNKNOWN);
        assertThat(result.canSave()).isFalse();
    }

    @Test
    void 얹은_뒤_판정을_못_내도_모르는_것으로_둔다() {
        checker.next(Verdict.CHECKED);
        checker.next(Verdict.UNKNOWN);

        assertThat(drafts.check(workspace, workspace, "reqs/RQ-001.md", "x").verdict())
                .isEqualTo(DraftCheckResult.Verdict.UNKNOWN);
    }

    // ── review 는 빨강이 아니다 ──────────────────────────────────────────

    /**
     * ⚠ 검사기는 「없다」와 「확인 못 함」을 갈라 후자를 {@code review} 로 낸다(`spec/reqs.md`).
     * <b>그건 막을 근거가 아니라 사람이 볼 항목이다</b> — 실물 레포에도 17건이 baseline 으로 깔려 있다.
     */
    @Test
    void 새로_생긴_것이_확인할_것뿐이면_막지_않고_알린다() {
        Finding review = new Finding("reqs/RQ-001.md", 3, "A3-ANCHORS", Level.REVIEW,
                "가리키는 도메인을 못 읽었다", "사람이 봐라");
        checker.next(Verdict.CHECKED);
        checker.next(Verdict.CHECKED, review);

        DraftCheckResult result = drafts.check(workspace, workspace, "reqs/RQ-001.md", "x");

        assertThat(result.verdict()).isEqualTo(DraftCheckResult.Verdict.REVIEW_REQUIRED);
        assertThat(result.canSave()).isTrue();          // 막지 않는다
        assertThat(result.caused()).hasSize(1);         // 다만 보여준다
    }

    // ── 작업 자리를 더럽히지 않는다 ───────────────────────────────────────

    /**
     * ⛔ <b>검사가 끝나면 원래 내용으로 되돌린다.</b> 안 되돌리면 그 워크트리에 남의 초안이 남아
     * <b>다음 사람의 「얹기 전」이 내 초안을 포함한 상태</b>가 된다 — 그러면 차이 판정이 통째로 거짓이 된다.
     */
    @Test
    void 검사가_끝나면_얹은_후보를_원래대로_되돌린다() throws IOException {
        checker.next(Verdict.CHECKED);
        checker.next(Verdict.CHECKED);

        drafts.check(workspace, workspace, "reqs/RQ-001.md", "# 후보다\n");

        assertThat(Files.readString(workspace.resolve("reqs/RQ-001.md"))).isEqualTo("# 원래 있던 것\n");
    }

    /** 없던 파일을 새로 만드는 경우 — 되돌리기는 <b>지우는 것</b>이다. */
    @Test
    void 없던_파일을_얹었으면_검사_뒤에_지운다() {
        checker.next(Verdict.CHECKED);
        checker.next(Verdict.CHECKED);

        drafts.check(workspace, workspace, "reqs/RQ-002.md", "# 새로 만든 것\n");

        assertThat(workspace.resolve("reqs/RQ-002.md")).doesNotExist();
    }

    @Test
    void 검사_도중에_터져도_후보를_되돌린다() throws IOException {
        checker.next(Verdict.CHECKED);
        checker.explodeOnSecondRun();

        assertThatThrownBy(() -> drafts.check(workspace, workspace, "reqs/RQ-001.md", "# 후보다\n"))
                .isInstanceOf(RuntimeException.class);

        assertThat(Files.readString(workspace.resolve("reqs/RQ-001.md"))).isEqualTo("# 원래 있던 것\n");
    }

    // ── 자리 안전 ───────────────────────────────────────────────────────

    /**
     * ⛔ <b>레포 밖을 가리키는 자리를 거절한다.</b> 자리 글자가 주소·화면에서 오므로
     * {@code ..} 가 그냥 통과하면 <b>레포 밖 파일을 덮어쓴다.</b>
     * {@code ProjectPaths} 가 프로젝트 번호에서 이미 같은 문을 세웠다.
     */
    @Test
    void 레포_밖을_가리키는_자리는_거절한다() {
        assertThatThrownBy(() -> drafts.check(workspace, workspace, "../밖.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.check(workspace, workspace, "reqs/../../밖.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.check(workspace, workspace, "C:/절대경로.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** ⚠ 개발은 윈도우 · 운영은 리눅스다. 레포 기준 자리는 {@code /} 로 잇는다. */
    @Test
    void 역슬래시로_적은_자리도_거절한다() {
        assertThatThrownBy(() -> drafts.check(workspace, workspace, "reqs\\RQ-001.md", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    /** 넣어 둔 보고서를 순서대로 내놓는다. 「얹기 전」과 「얹은 뒤」 두 번 불린다. */
    private static final class FakeChecker implements CheckerCommand {

        private final Deque<CheckReport> queued = new ArrayDeque<>();
        private int runs;
        private int explodeAt = -1;

        void next(Verdict verdict, Finding... findings) {
            queued.add(new CheckReport(verdict, List.of(findings)));
        }

        /** 「얹기 전」은 되고 「얹은 뒤」가 터지는 상황을 만든다 — 되돌리기가 걸리는 자리다. */
        void explodeOnSecondRun() {
            explodeAt = 2;
        }

        @Override
        public CheckReport run(Path checkerHome, Path repoRoot) {
            runs++;
            if (runs == explodeAt) {
                throw new IllegalStateException("검사기가 터졌다");
            }
            CheckReport report = queued.poll();
            if (report == null) {
                throw new IllegalStateException("검사기를 예상보다 많이 불렀다");
            }
            return report;
        }
    }
}
