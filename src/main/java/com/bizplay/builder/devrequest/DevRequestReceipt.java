package com.bizplay.builder.devrequest;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 수신 이력 한 줄 — 「개발 결과 받기」를 한 번 누른 것.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md} 「줄이는 장치 셋」의
 * <b>수신 이력 조회</b>. ⭐ 설계는 「세 번째가 없으면 나머지 둘도 값이 반이다」라고 적었다 —
 * 잘못 들어온 것을 짚을 자리가 없으면 「덮어쓰기 + git 이력」이 되돌릴 길이라는 말이 공허해진다.
 *
 * <p>⭐ <b>거절도 남긴다.</b> 무엇 때문에 몇 번 떨어졌는지가 개발과 말을 맞출 근거다.
 *
 * @param returnedHead 개발이 돌려보낸 브랜치의 머리. 브랜치를 못 받았으면 {@code null}
 * @param receiveCommit 기본 브랜치에 놓은 받기 커밋. 거절이거나 바뀐 것이 없었으면 {@code null}
 * @param rejections 거절 사유 — 사유마다 한 줄. {@link #rejectionList()} 로 읽는다
 */
public record DevRequestReceipt(Long id, String devRequestId, String returnedHead, String outcome,
                                String receiveCommit, Integer testRows, String rejections,
                                String accountId, Instant receivedAt) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    /** 이미 받은 판을 다시 누름 — git 은 건너뛰고 테스트 결과만 다시 담았다. */
    public static final String ALREADY = "ALREADY";

    private static final String SEPARATOR = "\n";

    public static DevRequestReceipt accepted(String requestId, String returnedHead, String commit,
                                             int testRows, String accountId) {
        return new DevRequestReceipt(null, requestId, returnedHead, ACCEPTED, commit, testRows, null,
                accountId, null);
    }

    public static DevRequestReceipt already(String requestId, String returnedHead, String commit,
                                            int testRows, String accountId) {
        return new DevRequestReceipt(null, requestId, returnedHead, ALREADY, commit, testRows, null,
                accountId, null);
    }

    public static DevRequestReceipt rejected(String requestId, String returnedHead, List<String> reasons,
                                             String accountId) {
        return new DevRequestReceipt(null, requestId, returnedHead, REJECTED, null, 0,
                String.join(SEPARATOR, reasons), accountId, null);
    }

    public List<String> rejectionList() {
        return rejections == null || rejections.isBlank() ? List.of()
                : Arrays.stream(rejections.split(SEPARATOR)).toList();
    }
}
