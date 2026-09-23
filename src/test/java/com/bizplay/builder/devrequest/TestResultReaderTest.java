package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발이 채워 보낸 테스트 결과 md 를 <b>TC 번호별 한 줄</b>로 읽나.
 *
 * <p>⭐ <b>md 통째로 담으면 화면이 통과/실패를 못 센다.</b> 그린존의 단위·통합테스트 화면이
 * 그 수를 읽으므로 줄 단위로 갈라 담는다.
 *
 * <p>⭐ <b>서식은 우리가 정해 보냈다</b>({@code expected-back.md}) — 그래서 읽을 수 있다.
 * ⛔ 개발이 앞 칸을 고쳐 보내면 짝이 어긋난다. 그것은 읽는 쪽이 아니라 계약이 막는다.
 */
class TestResultReaderTest {

    @Test
    void 채워진_줄을_TC_번호별로_읽는다() {
        List<TestResultReader.Result> results = TestResultReader.read("""
                # DR-009 · 돌려받을 것

                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-001 | 빈 값으로 응답한다 | 없음 | 생년월일 없음 | 조회한다 | 빈 값이 온다 | 빈 값이 왔다 | 통과 | 로그 12행 |
                | TC-002 | 잘못된 토큰을 막는다 | TC-001 | 손상 토큰 | 승인한다 | 거절된다 | 승인돼 버렸다 | 실패 | 재현 캡처 |
                """, "UNIT");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).tcId()).isEqualTo("TC-001");
        assertThat(results.get(0).title()).isEqualTo("빈 값으로 응답한다");
        assertThat(results.get(0).actual()).isEqualTo("빈 값이 왔다");
        assertThat(results.get(0).verdict()).isEqualTo(TestResultReader.PASS);
        assertThat(results.get(0).evidence()).isEqualTo("로그 12행");
        assertThat(results.get(1).verdict()).isEqualTo(TestResultReader.FAIL);
        assertThat(results.get(1).kind()).isEqualTo("UNIT");
    }

    /** ⚠ 안 채운 줄도 <b>읽어 둔다</b> — 「안 왔다」와 「채우다 말았다」를 화면이 갈라 보여야 한다. */
    @Test
    void 안_채운_줄은_판정을_모름으로_둔다() {
        List<TestResultReader.Result> results = TestResultReader.read("""
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-001 | 빈 값으로 응답한다 | 없음 | 생년월일 없음 | 조회한다 | 빈 값이 온다 |  |  |  |
                """, "UNIT");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).verdict()).isEqualTo(TestResultReader.UNKNOWN);
        assertThat(results.get(0).actual()).isNull();
    }

    /** ⚠ 모르는 판정 말은 <b>그대로 두고 모름으로 센다</b> — 지어내 통과로 세면 거짓이 된다. */
    @Test
    void 모르는_판정_말은_모름으로_센다() {
        List<TestResultReader.Result> results = TestResultReader.read("""
                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-001 | 무엇 | 없음 | 조건 | 행위 | 기대 | 실제 | 대충 됨 | 근거 |
                """, "UNIT");

        assertThat(results.get(0).verdict()).isEqualTo(TestResultReader.UNKNOWN);
        assertThat(results.get(0).rawVerdict()).isEqualTo("대충 됨");
    }

    /** ⚠ 표가 아닌 글과 머리줄은 건너뛴다 — 개발이 메모를 위에 적어 보낸다. */
    @Test
    void 표가_아닌_글과_머리줄은_건너뛴다() {
        List<TestResultReader.Result> results = TestResultReader.read("""
                개발 메모: 2026-09-30 에 돌렸습니다.

                | TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |
                |---|---|---|---|---|---|---|---|---|
                | TC-101 | 가입이 끝난다 | 없음 | 회원 있음 | 들어간다 | 채워진다 | 채워졌다 | PASS | 화면 녹화 |

                끝.
                """, "INTEGRATION");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).tcId()).isEqualTo("TC-101");
        assertThat(results.get(0).verdict()).isEqualTo(TestResultReader.PASS);
    }

    /** ⚠ 파일이 비었으면 빈 목록이다 — 「없다」와 「못 읽었다」를 섞지 않는다. */
    @Test
    void 빈_파일은_빈_목록이다() {
        assertThat(TestResultReader.read("", "UNIT")).isEmpty();
        assertThat(TestResultReader.read(null, "UNIT")).isEmpty();
    }
}
