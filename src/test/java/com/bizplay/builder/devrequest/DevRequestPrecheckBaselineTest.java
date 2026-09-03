package com.bizplay.builder.devrequest;

import com.bizplay.builder.checker.CheckReport;
import com.bizplay.builder.checker.CheckerCommand;
import com.bizplay.builder.checker.PlanningRepoCheckCache;
import com.bizplay.builder.checker.PlanningRepoCheckWorker;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.checker.Finding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전송 전 검증이 <b>기저 red 를 막지 않나</b>.
 *
 * <p>⭐ <b>실물이 낳은 시험이다 (2026-08-25).</b> `DR-003` 전송 화면에 <b>「막는 것 28건」</b>이
 * 떴는데, 같은 때 클론을 대상으로 검사기를 직접 돌리니 <b>red 가 정확히 28</b> 이었다 —
 * <b>그 FRD 가 만든 위반은 0건</b>이고 전부 기획 레포에 원래 깔려 있던 것이었다.
 *
 * <p>⛔ {@link com.bizplay.builder.checker.DraftChecker} 가 2026-08-14 에 같은 벽을 만나
 * 「남이 만든 빨강이 기획자를 영원히 막는다」로 적고 상쇄를 넣었는데,
 * <b>전송 전 검증에만 그 장치가 안 들어와 있었다.</b>
 *
 * <p>⚠ 여기서 재는 것은 <b>상쇄 셈법</b>이지 워크트리 배선이 아니다 — 그래서 DB 를 안 띄운다.
 */
class DevRequestPrecheckBaselineTest {

    @Test
    void 기저에_있던_빨강은_이_개발요청서에_노출하지_않는다() {
        Finding shared = red("core/webview/pages/a.html", 20, "A-8", "통째로 안 보인다");
        var precheck = precheckWith(report(shared));

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        var notes = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(shared), blocking, warnings, notes);

        assertThat(blocking).as("기저에 있던 것은 이 개발요청서 탓이 아니다").isEmpty();
        // 이 요청과 무관한 저장소 전체 수치는 기본 화면에서 빼 진짜 조치할 항목만 남긴다.
        assertThat(warnings).isEmpty();
        assertThat(notes).isEmpty();
    }

    /**
     * ⭐ <b>막는 것은 {@code A-1}·{@code A-2} 둘뿐이다 (2026-08-27 병주 확정).</b> 기준은 하나 —
     * <b>개발이 그것 없이는 일을 못 하나.</b> {@code A-1} 은 화면에 짝이 되는 설명 md(기능정의서)가
     * 없다는 것이고, {@code A-2} 는 화면 파일과 화면 ID 가 안 맞는다는 것이다.
     *
     * <p>⚠ <b>이 시험은 종전에 {@code A-8} 로 재고 있었다</b> — 그때는 「예외로 뺀 것 말고 전부
     * 막는다」였다. 그 방식이 뒤집힌 자리다.
     */
    @Test
    void 새로_생긴_빨강만_막는다() {
        Finding base = red("core/webview/pages/a.html", 20, "A-1", "짝이 되는 설명 md 가 없다");
        Finding fresh = red("core/webview/pages/b.html", 5, "A-1", "필수 화면 파일이 맞지 않는다");
        var precheck = precheckWith(report(base));

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(base, fresh), blocking, warnings, new ArrayList<>());

        assertThat(blocking).singleElement()
                .extracting(DevRequestPrecheck.Item::message, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("자동 점검 오류가 1건");
        assertThat(blocking.get(0).detail()).contains("필수 화면 파일이 맞지 않는다");
    }

    @Test
    void 새로_생긴_정식_IA_오류는_개발요청을_막지_않고_경고한다() {
        Finding formalIa = red("core/webview/pages/tmp-0000042.md", 5, "IA-8", "배치 행이 없다");
        var precheck = precheckWith(report());

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(formalIa), blocking, warnings, new ArrayList<>());

        assertThat(blocking).isEmpty();
        assertThat(warnings).singleElement().satisfies(item -> {
            assertThat(item.message()).contains("정식 IA");
            assertThat(item.detail()).contains("배치 행이 없다");
        });
    }

    /** ⚠ 워크트리가 파일 위에 줄을 더하면 기저 red 의 줄이 밀린다 — 그때 새것으로 오인하면 안 된다. */
    @Test
    void 줄_번호가_밀려도_기저로_알아본다() {
        Finding before = red("core/webview/pages/a.html", 20, "A-8", "통째로 안 보인다");
        Finding shifted = red("core/webview/pages/a.html", 47, "A-8", "통째로 안 보인다");
        var precheck = precheckWith(report(before));

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(shifted), blocking, warnings, new ArrayList<>());

        assertThat(blocking).isEmpty();
    }

    /** ⚠ 기저에 2건인데 3건이면 1건만 새것이다 — 개수로 세야 한다. */
    @Test
    void 같은_열쇠가_여럿이면_개수로_센다() {
        Finding one = red("core/webview/pages/a.html", 1, "A-1", "짝이 되는 설명 md 가 없다");
        var precheck = precheckWith(report(one, one));

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(one, one, one), blocking, warnings, new ArrayList<>());

        assertThat(blocking).as("셋 중 하나만 새것이다").hasSize(1);
    }

    /**
     * ⛔ 기저를 못 재면 막지 않는다 — 누구 탓인지 모르는데 막으면
     * 이 장치가 없애려는 상태(영영 못 나감)로 돌아간다.
     */
    @Test
    void 기저를_못_재면_막지_않고_알린다() {
        Finding any = red("core/webview/pages/a.html", 20, "A-8", "통째로 안 보인다");
        var precheck = precheckWith(CheckReport.unknown());

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(any), blocking, warnings, new ArrayList<>());

        assertThat(blocking).isEmpty();
        assertThat(warnings).singleElement()
                .extracting(DevRequestPrecheck.Item::message, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("원래 있던 것인지 가릴 수 없습니다");
    }

    /**
     * ⭐ <b>실물이 낳은 시험이다 (2026-08-27).</b> `DR-033` 을 만들고 바로 전송했더니
     * 「자동 점검 오류가 1건 늘었습니다」로 <b>막혔다.</b> 늘어난 red 1건의 정체는
     * <b>{@code RATCHET} 그 자신</b>이었다 — 실측: 클론 red 28 · 사람이 볼 68, FRD 작업 자리
     * red 29 · 사람이 볼 69.
     *
     * <p>⛔ <b>{@code RATCHET} 은 「사람이 볼 항목의 총 개수」만 본다</b> — 무엇이 틀렸다는 말이
     * 아니다. 늘어난 실체는 {@code A-9} 의 <b>review</b> 한 건(「이 간선이 실재하는지 사람이 봐야
     * 한다」)이었고, 이 저장소는 2026-08-14 에 이미 정했다 —
     * {@link com.bizplay.builder.checker.DraftChecker} 의 「review 는 빨강이 아니라 사람이 볼
     * 항목이다」. {@code RATCHET} 은 <b>그 review 개수를 red 로 세탁하는 통로</b>라, 원칙이
     * 뒷문으로 뚫려 있었다.
     *
     * <p>⛔ <b>기획자가 풀 수 없는 것으로 막고 있었다.</b> 검사기 자신이 적어 둔 해법은
     * 「{@code index.json} 의 기준선을 함께 올려 커밋해라」이고 그건 기획 레포를 손보는 일이다 —
     * 화면 안내는 「FRD로 돌아가 다시 완료해 주세요」였으니 <b>몇 번 다시 완료해도 똑같이 +1</b> 이다.
     * 실측 시점에 작업 자리 넷 중 {@code frd-0000042}·{@code frd-0000045} 가 이미 같은 상태였다.
     *
     * <p>⛔ <b>경고 목록에 들어가지만 화면에는 안 뜬다 (2026-08-27 병주 지시).</b> 상세 화면은
     * 막는 것만 그린다({@code DevRequestTemplateContractTest}) — 「기준선을 올려라」는 기획자가
     * 볼 수 있는 문서가 아니라서 일부러 그대로 뒀다. ⛔ <b>이 줄을 화면에 끌어올리는 방향으로
     * 「고치지」 마라.</b>
     */
    @Test
    void 사람이_볼_항목이_늘어난_것은_개발요청을_막지_않고_경고한다() {
        Finding ratchet = red("index.json", 1, "RATCHET",
                "사람이 볼 항목이 68건에서 69건으로 늘었다.");
        var precheck = precheckWith(report());

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(ratchet), blocking, warnings,
                new ArrayList<>());

        assertThat(blocking).as("기획자가 FRD 로는 풀 수 없는 조건이다").isEmpty();
        assertThat(warnings).singleElement().satisfies(item -> {
            assertThat(item.message()).contains("사람이 볼 항목");
            assertThat(item.detail()).contains("68건에서 69건으로 늘었다");
        });
    }

    /**
     * ⚠ <b>열쇠에 숫자가 섞이면 기저 상쇄가 통째로 거짓이 된다.</b> 줄 번호를 뺀 것과 같은 함정인데
     * 그때 <b>내용 안의 숫자</b>까지는 안 걷어냈다. {@code RATCHET} 의 내용에는 개수가 들어 있어
     * (「68건에서 <b>70</b>건으로」 ↔ 「68건에서 <b>71</b>건으로」) 같은 위반인데 열쇠가 갈린다.
     *
     * <p>⛔ 그러면 <b>클론에 원래 있던 위반이 이 FRD 탓으로 잡힌다</b> — 이 함수가 없애려는
     * 바로 그 상태다. 실측 시점엔 클론이 정확히 기준선이라 안 드러났지만, 클론이 한 번이라도
     * 기준선을 넘으면 즉시 터진다. 같은 모양의 게이트가 하나 더 있다({@code DESIGN-5} 의
     * 「커버리지 중위가 71.4% 로 목표 85% 에 못 미친다」 — 지금은 review 라 안 막는다).
     */
    @Test
    void 내용_속_숫자가_달라져도_기저로_알아본다() {
        Finding before = red("domain-index.json", 1, "DOMAIN-COVERAGE",
                "가리키는 곳이 없는 번호표가 6건 있다.");
        Finding grown = red("domain-index.json", 1, "DOMAIN-COVERAGE",
                "가리키는 곳이 없는 번호표가 7건 있다.");
        var precheck = precheckWith(report(before));

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(grown), blocking, warnings,
                new ArrayList<>());

        assertThat(blocking).isEmpty();
    }

    /**
     * ⛔ <b>한계 표식이다 — 숫자를 통째로 지우면 이 시험이 빨강이 된다.</b> 내용 속 숫자에는
     * <b>세는 수</b>(「6건」)와 <b>번호표</b>(「flow-01」) 두 종류가 있고, 뭉뚱그려 지우면
     * 번호표까지 같은 열쇠가 되어 <b>새로 깨진 번호표가 기저로 상쇄된다.</b>
     *
     * <p>그래서 지우는 것은 <b>세는 단위가 뒤에 붙은 숫자만</b>이다. 오탐은 사람이 빨강을 보고
     * 판정하지만 이 방향의 실수(fail-open)는 <b>아무도 모른다</b>.
     */
    @Test
    void 번호표만_다른_것은_기저로_보지_않는다() {
        Finding before = red("domain-index.json", 1, "DOMAIN-COVERAGE",
                "번호표 settle/flow-settle#flow-01 를 가리키는 파일이 없다.");
        Finding other = red("domain-index.json", 1, "DOMAIN-COVERAGE",
                "번호표 settle/flow-settle#flow-07 를 가리키는 파일이 없다.");
        var precheck = precheckWith(report(before));

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(other), blocking, warnings,
                new ArrayList<>());

        assertThat(blocking).as("DOMAIN-COVERAGE 는 막는 게이트가 아니다").isEmpty();
        assertThat(warnings).as("다른 번호표가 깨진 것은 새 위반이라 기저로 먹히지 않는다")
                .singleElement().satisfies(item ->
                        assertThat(item.detail()).contains("flow-07"));
    }

    /**
     * ⭐ <b>막는 것을 목록으로 정한다 — 「예외만 뺀다」를 2026-08-27 에 뒤집었다 (병주 확정).</b>
     *
     * <p><b>왜 뒤집었나.</b> 게이트가 <b>30개</b>고 추출기가 계속 늘린다. 종전 방식(예외로 뺀 것
     * 말고 전부 막는다)에서는 <b>새 게이트가 아무도 정하지 않은 채 전송을 막는 힘을 갖는다</b> —
     * {@code RATCHET} 이 정확히 그렇게 들어왔다.
     *
     * <p><b>실측이 그걸 보여 줬다.</b> 작업 자리 18곳을 재니 막혀 있던 3곳에서 막는 것의 대부분이
     * {@code DESIGN-1} 이었고, 그 「고치는 법」은 <b>「{@code node verify/reindex.mjs} 를 다시 돌려
     * 커밋해라. 이 파일은 손으로 고치는 것이 아니다」</b> 였다. {@code DOMAIN-COVERAGE}·
     * {@code DESIGN-6} 은 아예 <b>「추출기를 다시 돌려라」</b> 라고 적혀 있다 — 기획자가 FRD 화면을
     * 고치다가 할 수 있는 일이 아니다.
     *
     * <p>⛔ <b>기준은 하나다 — 개발이 그것 없이는 일을 못 하나.</b> 나머지는 막지 않고 점검 기록에만
     * 남긴다(화면에는 안 뜬다).
     */
    @Test
    void 막을_것_밖의_빨강은_막지_않는다() {
        Finding derived = red("design-index.json", 1, "DESIGN-1", "커밋된 디자인 색인가 실물 css 와 다르다.");
        var precheck = precheckWith(report());

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(derived), blocking, warnings,
                new ArrayList<>());

        assertThat(blocking).as("도구를 돌려 커밋할 일이라 기획자가 FRD 로 풀 수 없다").isEmpty();
        assertThat(warnings).as("숨기지는 않는다 — 점검 기록에는 남는다")
                .singleElement().satisfies(item ->
                        assertThat(item.detail()).contains("실물 css 와 다르다"));
    }

    /** ⭐ 화면 파일과 화면 ID 가 안 맞는 것도 개발이 받아서 못 쓴다 — 막는다. */
    @Test
    void 화면_ID_가_안_맞으면_막는다() {
        Finding mismatched = red("core/webview/pages/b.html", 1, "A-2",
                "html 의 data-screen-id 가 파일명과 다르다.");
        var precheck = precheckWith(report());

        var blocking = new ArrayList<DevRequestPrecheck.Item>();
        var warnings = new ArrayList<DevRequestPrecheck.Item>();
        precheck.blockOnlyNewRed(Path.of("clone"), report(mismatched), blocking, warnings,
                new ArrayList<>());

        assertThat(blocking).singleElement().satisfies(item ->
                assertThat(item.detail()).contains("파일명과 다르다"));
    }

    /** 기저 검사는 red 가 하나도 없으면 아예 안 돈다 — 값을 안 치른다. */
    @Test
    void 빨강이_없으면_기저를_재지_않는다() {
        var counted = new int[1];
        var precheck = new DevRequestPrecheck(null, null, null,
                cache((home, root) -> { counted[0]++; return CheckReport.unknown(); }), null, null, null);

        precheck.blockOnlyNewRed(Path.of("clone"),
                new CheckReport(CheckReport.Verdict.CHECKED, List.of()),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        assertThat(counted[0]).isZero();
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private DevRequestPrecheck precheckWith(CheckReport baseline) {
        CheckerCommand stub = (home, root) -> baseline;
        return new DevRequestPrecheck(null, null, null, cache(stub), null, null, null);
    }

    private CheckReport report(Finding... findings) {
        return new CheckReport(CheckReport.Verdict.CHECKED, List.of(findings));
    }

    private Finding red(String file, int line, String gate, String what) {
        return new Finding(file, line, gate, Finding.Level.RED, what, "고쳐라");
    }

    /** 프록시 없는 일꾼은 동기로 돌고, git 저장소가 아닌 자리는 열쇠가 매번 달라 캐시되지 않는다 — 셈법 시험에 맞는 모양이다. */
    private static PlanningRepoCheckCache cache(CheckerCommand stub) {
        return new PlanningRepoCheckCache(new PlanningRepoCheckWorker(stub), stub, new GitCommand(),
                Duration.ofSeconds(10), Duration.ofMinutes(10));
    }

    /*
     * ⛔ 「기저를 아직 재는 중이면 검사 중」 시험은 2026-08-25 에 지웠다 — 병주 지시로 검사기가
     * 「전송을 누를 때」만 돌고 그때는 결과가 날 때까지 기다린다(PlanningRepoCheckCache.await).
     * 「재는 중」이라는 상태 자체가 없어져 잴 것이 없다.
     */
}
