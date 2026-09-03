package com.bizplay.builder.usermanual;

import java.time.Instant;

/**
 * 화면 하나의 사용자 매뉴얼 한 장.
 *
 * <p><b>자리는 {@code (projectId, systemCode, screenId)} 하나다.</b> 다시 만들면 덮어쓰고 이력을
 * 쌓지 않는다 — 매뉴얼은 as-is 화면의 <b>현재</b> 모습을 설명하는 글이라 낡은 판을 되살릴 자리가 없다.
 *
 * <p>⚠ <b>{@code createdAt} 은 넣을 때 안 채운다 — DB 가 정한다.</b> 시계를 두 곳에 두면
 * 「작성일」이 어느 기계의 시각인지 말할 수 없게 된다. 그래서 {@link #of} 에는 그 칸이 없다.
 * {@code html} 과 {@code createdAt} 은 마지막 정상본이고, {@code generationId} 부터는 현재 또는
 * 가장 최근 생성 시도다. 따라서 재생성 실패가 정상본을 지우지 않는다.
 *
 * <p>정본: {@code docs/artifacts.md} 의 「빌더가 만든다」 표.
 */
public record UserManual(String projectId, String systemCode, String screenId,
                         String html, Instant createdAt,
                         UserManualState state, String failedReason,
                         String generationId, Instant generationStartedAt,
                         String sourceFingerprint, String generatorVersion) {

    public static final String CURRENT_GENERATOR_VERSION = "user-manual-4";

    /** 다 만들어진 매뉴얼. 시각은 DB 가 넣으므로 비워 둔다. */
    public static UserManual of(String projectId, String systemCode, String screenId, String html) {
        return new UserManual(projectId, systemCode, screenId, html, null,
                UserManualState.DONE, null, null, null, "direct", CURRENT_GENERATOR_VERSION);
    }
}
