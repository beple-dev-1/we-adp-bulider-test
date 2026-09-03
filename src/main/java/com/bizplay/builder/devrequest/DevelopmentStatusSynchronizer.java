package com.bizplay.builder.devrequest;

import com.bizplay.builder.git.GitCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** GitLab 개발 라벨을 읽어 개발 상태를 갱신하는 한 번의 동기화 본체. */
@Service
public class DevelopmentStatusSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentStatusSynchronizer.class);

    private final DevelopmentRequestMapper requests;
    private final DevProgressGateway progress;

    public DevelopmentStatusSynchronizer(DevelopmentRequestMapper requests,
                                         DevProgressGateway progress) {
        this.requests = requests;
        this.progress = progress;
    }

    /** 요청 하나의 실패가 다음 요청 확인을 막지 않는다. */
    public void syncOnce() {
        for (DevelopmentStatusCandidate candidate : requests.selectDevelopmentStatusCandidates()) {
            try {
                synchronize(candidate);
            } catch (RuntimeException failure) {
                fail(candidate, "개발 상태 동기화 중 오류가 났습니다: "
                        + GitCommand.mask(String.valueOf(failure.getMessage())));
                log.warn("개발 상태 동기화 실패 requestId={}", candidate.requestId(), failure);
            }
        }
    }

    private void synchronize(DevelopmentStatusCandidate candidate) {
        DevProgressGateway.Inspection inspected = progress.inspect(
                candidate.projectId(), candidate.issueUrl(), candidate.deliveryKey());
        if (!inspected.succeeded()) {
            fail(candidate, inspected.failure());
            return;
        }
        if (!inspected.state().canAdvanceFrom(candidate.developmentState())) {
            fail(candidate, "GitLab 개발 상태가 이전 단계로 바뀌어 기존 상태를 유지했습니다.");
            return;
        }
        int advanced = requests.advanceDevelopmentState(candidate, inspected.state());
        if (advanced != 1) {
            log.info("개발 상태 확인 중 요청의 전송 세대가 바뀌어 결과를 버린다 requestId={}",
                    candidate.requestId());
            return;
        }
    }

    private void fail(DevelopmentStatusCandidate candidate, String failure) {
        String safe = GitCommand.mask(failure == null ? "개발 상태를 확인하지 못했습니다." : failure.strip());
        if (safe.length() > 1000) {
            safe = safe.substring(0, 1000);
        }
        requests.recordDevelopmentSyncFailure(candidate, safe);
        log.warn("개발 상태 동기화를 다음 주기에 다시 시도한다 requestId={} reason={}",
                candidate.requestId(), safe);
    }
}
