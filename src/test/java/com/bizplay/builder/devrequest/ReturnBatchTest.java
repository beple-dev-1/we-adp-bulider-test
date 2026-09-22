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
