package com.bizplay.builder.devrequest;

import java.time.Instant;

/**
 * 개발요청 꾸러미를 이슈로 여는 GitLab 자리 — 프로젝트마다 하나다.
 *
 * <p>⛔ <b>기획 저장소가 아니다.</b> 개발 조직의 트래커다 — 기획 레포에 개발 이슈를 쌓으면
 * 기획팀 소유 저장소가 남의 작업 목록이 된다.
 *
 * <p>⛔ <b>토큰을 평문으로 두지 않는다.</b> 클론 토큰과도 갈라 둔다 — 이슈 생성은 권한이 따로다.
 */
public record DevIssueTarget(String projectId, String baseUrl, String projectPath,
                             byte[] tokenCipher, byte[] tokenNonce, Instant updatedAt,
                             String updatedBy) {
}
