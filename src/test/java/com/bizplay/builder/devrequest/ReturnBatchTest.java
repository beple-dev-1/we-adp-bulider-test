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
                """), "base-1");

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.rejections()).isEmpty();
        // ⭐ changed 인 것만 놓는다 — unchanged 는 「보았고 바꿀 것이 없었다」이지 파일이 아니다.
        assertThat(verdict.filesToTake()).containsExactly(
                "core/EXW/pages/EXW-1.html", "index.json");
    }

    /** ⭐ 기준이 어긋나면 통째로 거절한다 — 그 사이 남이 올린 것을 덮지 않기 위해서다. */
    @Test
    void 기준이_어긋나면_통째로_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "낡은-기준", "screens": [
                  {"screenId": "EXW-1", "pages": "changed", "screen-md": "changed", "index": "changed"}
                ]}
                """), "base-1");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("기준"));
        assertThat(verdict.filesToTake()).isEmpty();
    }

    /** ⚠ 필수인데 안 온 칸이 있으면 배치가 안 찬 것이다 — 「누락」과 「안 바뀜」을 갈라야 한다. */
    @Test
    void 필수_구성요소가_빠지면_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), returned("""
                {"dr": "DR-009", "base": "base-1", "screens": [
                  {"screenId": "EXW-1", "pages": "changed"}
                ]}
                """), "base-1");

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
                """), "base-1");

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
                """), "base-1");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejections()).anyMatch(reason -> reason.contains("했음"));
    }

    /** ⛔ 깨진 파일은 거절이지 무시가 아니다 — 못 읽은 것을 통과로 세면 반쪽이 들어온다. */
    @Test
    void 깨진_회신서는_거절한다() {
        ReturnBatch.Verdict verdict = ReturnBatch.judge(expected(), "{ 이건 json 이 아니다", "base-1");

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
