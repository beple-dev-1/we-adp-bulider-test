package com.bizplay.builder.devrequest;

import org.springframework.stereotype.Service;

/** 개발 완료가 확인된 FRD를 사용자의 명시적인 요청으로 기본 브랜치에 병합한다. */
@Service
public class DevelopmentRequestMergeService {

    private final DevelopmentRequestMapper requests;
    private final DevRequestBranchMerger merger;

    public DevelopmentRequestMergeService(DevelopmentRequestMapper requests,
                                          DevRequestBranchMerger merger) {
        this.requests = requests;
        this.merger = merger;
    }

    public void merge(String projectId, String requestId) {
        DevelopmentRequest request = requests.selectById(requestId);
        if (request == null || !request.projectId().equals(projectId)) {
            throw new IllegalArgumentException("개발요청서를 찾을 수 없습니다.");
        }
        if (request.developmentState() != DevelopmentState.DONE) {
            throw new IllegalStateException("개발 완료인 개발요청서만 병합할 수 있습니다.");
        }
        if (request.developmentMergedSha() != null) {
            throw new IllegalStateException("이미 기본 브랜치에 병합된 개발요청서입니다.");
        }

        DevelopmentStatusCandidate candidate =
                requests.selectDevelopmentStatusCandidate(projectId, requestId);
        if (candidate == null) {
            throw new IllegalStateException("병합할 최신 개발요청 전송 정보를 확인할 수 없습니다.");
        }
        DevRequestBranchMerger.Result merged = merger.merge(candidate);
        if (!merged.succeeded()) {
            throw new IllegalStateException(merged.failure());
        }
        if (requests.markDevelopmentMerged(candidate, merged.commitSha()) != 1) {
            throw new IllegalStateException("병합 중 개발요청 정보가 바뀌었습니다. 목록을 새로고침해 확인해 주세요.");
        }
    }
}
