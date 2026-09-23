package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발이 돌려보낸 배치가 <b>계약에 맞나</b>를 판정한다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md}
 * 「⛔ ② 는 파일 하나씩 받지 않는다 — 배치로 받는다」.
 *
 * <p>⭐ <b>배치가 다 차고 전부 규격을 지나야 놓는다.</b> 하나라도 떨어지면 <b>통째로 거절하고
 * 아무것도 안 놓는다</b> — 반쪽 상태를 다음 FRD 가 as-is 로 읽는 것이 가장 나쁘다.
 */
class ReturnBatchTest {

    @Test
    void 필수가_다_차고_값이_맞으면_통과한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "changed"}
                ]}
                """), unmoved());

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.rejections()).isEmpty();
        // ⭐ changed 인 것만 놓는다 — unchanged 는 「보았고 바꿀 것이 없었다」이지 파일이 아니다.
        assertThat(verdict.filesToTake()).containsExactly(
                "core/EXW/pages/EXW-1.html", "index.json");
    }

    /**
     * ⭐ <b>기본 브랜치가 움직였어도 다른 파일만 바뀌었으면 받는다</b> (2026-09-23 사용자 확정).
     * 개발은 며칠 뒤에 돌려주고 그 사이 {@code main} 은 바뀔 수 있다 — 판이 같은지로 거절하면
     * 멀쩡한 결과가 매번 떨어진다. ⛔ 「HEAD 와 같아야 받는다」로 되돌리지 마라.
     */
    @Test
    void 기본_브랜치가_움직였어도_다른_파일만_바뀌었으면_받는다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "unchanged"}
                ]}
                """), movedSince("base-1", "core/EXW/pages/EXW-2.html", "core/EXW/ia.md"));

        assertThat(verdict.rejections()).isEmpty();
        assertThat(verdict.filesToTake()).containsExactly("core/EXW/pages/EXW-1.html");
    }

    /**
     * ⛔ <b>갈라 온 뒤 기본 브랜치에서 같은 파일이 바뀌었으면 거절한다.</b> 받으면 그 변경이
     * 아무도 모르게 덮인다. 내용은 대조하지 않고 파일 이름만 본다.
     */
    @Test
    void 갈라_온_뒤_같은_파일이_바뀌었으면_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "changed"}
                ]}
                """), movedSince("base-1", "index.json"));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).singleElement().asString().contains("index.json");
        assertThat(verdict.filesToTake()).isEmpty();
    }

    /**
     * ⛔ <b>화면 md 를 {@code unchanged} 로 적었어도 그 사이 바뀌었으면 거절한다.</b> 설계가 본 것은
     * 「그 배치가 다루는 경로」다. html 만 받으면 개발의 옛 기준 html 과 남의 새 md 가 한 화면에
     * 섞인다 — 설계가 경계한 시점 혼합물이다.
     */
    @Test
    void 화면_md_를_unchanged_로_적었어도_그_사이_바뀌었으면_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "unchanged"}
                ]}
                """), movedSince("base-1", "core/EXW/pages/EXW-1.md"));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).singleElement().asString().contains("core/EXW/pages/EXW-1.md");
    }

    /**
     * ⭐ <b>색인은 받을 때만 본다.</b> 모든 화면의 필수라 늘 다루는 경로에 들고, IA 확정 게시가
     * 게시할 때마다 다시 만든다 — 다루기만 해도 거절하면 IA 한 번에 나가 있는 회신이 전부 떨어진다.
     */
    @Test
    void 색인은_받지_않으면_그_사이_바뀌었어도_거절하지_않는다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "unchanged", "index": "unchanged"}
                ]}
                """), movedSince("base-1", "index.json"));

        assertThat(verdict.accepted()).isTrue();
    }

    /** ⚠ 보호 화면은 화면 md 를 다루지 않는다 — 그 화면에서는 「새 html + 옛 md」가 의도된 정상이다. */
    @Test
    void 보호_화면의_화면_md_는_그_사이_바뀌어도_거절하지_않는다() {
        ExpectedBack guarded = new ExpectedBack("feedback/DR-009", "base-1",
                List.of(new ExpectedBack.Screen("EXW-1", "EXW",
                        List.of(ExpectedBack.PAGES, ExpectedBack.INDEX))),
                List.of(), List.of(), List.of());

        ReturnBatch.Verdict verdict = ReturnBatch.judge(guarded, returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "index": "unchanged"}
                ]}
                """), movedSince("base-1", "core/EXW/pages/EXW-1.md"));

        assertThat(verdict.accepted()).isTrue();
    }

    /**
     * ⛔ <b>기본 브랜치 이력에 없는 기준은 거절한다.</b> 전달 브랜치 위에서 갈라 오면 이렇게 된다 —
     * 거기 든 기획의 to-be 가 사실인 척 섞여 들어온다.
     */
    @Test
    void 기본_브랜치_이력에_없는_기준은_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "전달-브랜치의-판", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "changed", "index": "changed"}
                ]}
                """), unmoved());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("이력에 없습니다"));
    }

    /** ⚠ 기준을 안 적으면 무엇과 견줄지 없다 — 거절이지 「그냥 받기」가 아니다. */
    @Test
    void 기준을_안_적으면_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "changed", "index": "changed"}
                ]}
                """), unmoved());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("base"));
    }

    /** ⚠ 필수인데 안 온 칸이 있으면 배치가 안 찬 것이다 — 「누락」과 「안 바뀜」을 갈라야 한다. */
    @Test
    void 필수_구성요소가_빠지면_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed"}
                ]}
                """), unmoved());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("screen-md"));
    }

    /** ⛔ 목록 밖 대상은 받지 않는다 — 대조 없이 자리를 지키는 유일한 문 지킴이다. */
    @Test
    void 목록_밖_화면은_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "changed", "index": "changed"},
                  {"screenId": "남의-화면", "pages": "changed", "screen-md": "changed", "index": "changed"}
                ]}
                """), unmoved());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("남의-화면"));
    }

    /** ⚠ 아는 값은 둘뿐이다. 「했음」 같은 말을 받아 주면 배치 셈이 무너진다. */
    @Test
    void 모르는_상태값은_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "했음", "screen-md": "unchanged", "index": "changed"}
                ]}
                """), unmoved());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("했음"));
    }

    /** ⛔ 깨진 파일은 거절이지 무시가 아니다 — 못 읽은 것을 통과로 세면 반쪽이 들어온다. */
    @Test
    void 깨진_회신서는_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), "{ 이건 json 이 아니다", unmoved());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("읽지 못했"));
    }

    /**
     * ⛔ <b>보낸 적 없는 TC 가 오면 통째로 거절한다</b> (2026-09-23 사용자 확정).
     * 담으면 테스트 화면이 부푼 숫자를 세고, 그 줄은 어느 완료 조건에도 짝이 없다.
     * 화면의 「목록 밖은 받지 않는다」와 같은 규율이다.
     */
    @Test
    void 보낸_적_없는_TC_가_오면_거절한다() {
        List<String> rejections = ReturnBatch.judgeTests(withTests(List.of("TC-001"), List.of("TC-101")),
                table("TC-001", "TC-777"), table("TC-101"));

        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0)).contains("TC-777").contains("단위테스트");
    }

    /** ⚠ 단위 쪽 번호를 통합 표에 적어도 목록 밖이다 — 갈래를 섞으면 개수가 서로를 오염시킨다. */
    @Test
    void 다른_갈래의_TC_를_적어도_거절한다() {
        List<String> rejections = ReturnBatch.judgeTests(withTests(List.of("TC-001"), List.of("TC-101")),
                table("TC-001"), table("TC-101", "TC-001"));

        assertThat(rejections).singleElement().asString().contains("TC-001").contains("통합테스트");
    }

    /**
     * ⭐ <b>TC 를 안 보냈으면 개발이 적은 것을 다 받는다.</b> 설계가 「시나리오가 없어도 계약은
     * 성립한다」고 했고, {@code expected-back.md} 가 그때 「검증한 항목을 같은 서식으로 적어 달라」고 한다.
     */
    @Test
    void TC_를_안_보냈으면_개발이_적은_것을_다_받는다() {
        assertThat(ReturnBatch.judgeTests(withTests(List.of(), List.of()),
                table("TC-001", "TC-002"), table("TC-101"))).isEmpty();
    }

    /** ⚠ 보낸 것 중 일부만 와도 여기서는 거절하지 않는다 — 이번에 정한 것은 「목록 밖」뿐이다. */
    @Test
    void 보낸_TC_만_오면_지난다() {
        assertThat(ReturnBatch.judgeTests(withTests(List.of("TC-001", "TC-002"), List.of("TC-101")),
                table("TC-001"), null)).isEmpty();
    }

    private static String table(String... tcIds) {
        StringBuilder md = new StringBuilder("""
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                """);
        for (String id : tcIds) {
            md.append("| ").append(id).append(" | 무엇 | 없음 | 조건 | 행위 | 기대 | 실제 | 통과 | 근거 |\n");
        }
        return md.toString();
    }

    private static ExpectedBack withTests(List<String> unit, List<String> integration) {
        return new ExpectedBack("feedback/DR-009", "base-1", List.of(), List.of(), unit, integration);
    }

    /** 기본 브랜치가 {@code base-1} 에서 안 움직인 판. */
    private static ReturnBatch.MainHistory unmoved() {
        return movedSince("base-1");
    }

    /** {@code base} 가 기본 브랜치 이력에 있고, 그 뒤 {@code changed} 파일들이 바뀐 판. */
    private static ReturnBatch.MainHistory movedSince(String base, String... changed) {
        return new ReturnBatch.MainHistory() {
            @Override
            public boolean contains(String commit) {
                return base.equals(commit);
            }

            @Override
            public java.util.Set<String> changedSince(String commit) {
                return java.util.Set.of(changed);
            }
        };
    }

    private static String returned(String json) {
        return json;
    }

    private ExpectedBack expected() {
        return new ExpectedBack("feedback/DR-009", "base-1",
                List.of(new ExpectedBack.Screen("EXW-1", "EXW",
                        List.of(ExpectedBack.PAGES, ExpectedBack.SCREEN_MD, ExpectedBack.INDEX))),
                List.of(), List.of(), List.of());
    }
}
