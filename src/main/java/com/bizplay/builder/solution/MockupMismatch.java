package com.bizplay.builder.solution;

import java.time.Instant;

/**
 * 솔루션 목업이 운영 화면과 어긋난다고 사람이 짚어 둔 표시 한 줄.
 *
 * <p>표의 뜻은 {@code V13__mockup_mismatch.sql} 의 COMMENT 가 정본이다.
 *
 * <p>⛔ <b>고친 기록이 아니다.</b> 고치는 것은 「보정」이고 그쪽은 워크트리 → 커밋 → 푸시로
 * git 에 남는다. 여기는 <b>발견했다</b>만 담는다 — 설계가 「짚는 것과 고치는 것을 나눈다」로
 * 정했다(2026-08-14). 발견한 사람이 그 자리에서 못 고칠 때가 많아서다.
 *
 * @param reporterName 짚은 사람의 표시 이름. 계정 표에서 붙여 온 값이라 표에는 없다
 */
public record MockupMismatch(
        String id,
        String projectId,
        String screenId,
        String reason,
        String reporterId,
        String reporterName,
        Instant createdAt) {
}
