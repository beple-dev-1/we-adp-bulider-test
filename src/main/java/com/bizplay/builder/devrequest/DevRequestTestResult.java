package com.bizplay.builder.devrequest;

import java.time.Instant;

/**
 * 돌려받은 테스트 결과 한 줄.
 *
 * @param verdict    {@code PASS} · {@code FAIL} · {@code UNKNOWN}
 * @param rawVerdict 개발이 적은 원문. ⛔ 모르는 말을 통과로 세지 않고 그대로 남긴다
 */
public record DevRequestTestResult(String devRequestId, String kind, String tcId, String title,
                                   String actual, String verdict, String rawVerdict,
                                   String evidence, Instant receivedAt) {

    public boolean passed() {
        return TestResultReader.PASS.equals(verdict);
    }

    public boolean failed() {
        return TestResultReader.FAIL.equals(verdict);
    }
}
