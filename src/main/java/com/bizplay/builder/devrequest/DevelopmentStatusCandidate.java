package com.bizplay.builder.devrequest;

/** 정기 확인 대상 개발요청서에 필요한 값만 읽은 줄. */
public record DevelopmentStatusCandidate(String requestId, String projectId, String frdId,
                                         String requestLabel, String deliveryAttemptId,
                                         String deliveryKey, String issueUrl,
                                         DevelopmentState developmentState,
                                         String workspaceHeadSha) {
}
