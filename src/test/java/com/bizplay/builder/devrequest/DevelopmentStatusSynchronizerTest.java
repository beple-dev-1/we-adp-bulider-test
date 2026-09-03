package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevelopmentStatusSynchronizerTest {

    private final DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
    private final DevProgressGateway progress = mock(DevProgressGateway.class);
    private final DevelopmentStatusSynchronizer synchronizer =
            new DevelopmentStatusSynchronizer(requests, progress);

    @Test
    void progress_라벨이면_개발_진행_중으로_옮긴다() {
        DevelopmentStatusCandidate candidate = candidate(DevelopmentState.INTAKE);
        when(requests.selectDevelopmentStatusCandidates()).thenReturn(List.of(candidate));
        when(progress.inspect(candidate.projectId(), candidate.issueUrl(), candidate.deliveryKey()))
                .thenReturn(DevProgressGateway.Inspection.found(DevelopmentState.PROGRESS));
        when(requests.advanceDevelopmentState(candidate, DevelopmentState.PROGRESS)).thenReturn(1);

        synchronizer.syncOnce();

        verify(requests).advanceDevelopmentState(candidate, DevelopmentState.PROGRESS);
    }

    @Test
    void done_라벨이면_개발_완료만_저장하고_자동_병합하지_않는다() {
        DevelopmentStatusCandidate candidate = candidate(DevelopmentState.PROGRESS);
        when(requests.selectDevelopmentStatusCandidates()).thenReturn(List.of(candidate));
        when(progress.inspect(candidate.projectId(), candidate.issueUrl(), candidate.deliveryKey()))
                .thenReturn(DevProgressGateway.Inspection.found(DevelopmentState.DONE));
        when(requests.advanceDevelopmentState(candidate, DevelopmentState.DONE)).thenReturn(1);

        synchronizer.syncOnce();

        verify(requests).advanceDevelopmentState(candidate, DevelopmentState.DONE);
    }

    @Test
    void 이슈_조회_실패는_상태를_움직이지_않고_다음_확인을_위해_기록한다() {
        DevelopmentStatusCandidate candidate = candidate(DevelopmentState.INTAKE);
        when(requests.selectDevelopmentStatusCandidates()).thenReturn(List.of(candidate));
        when(progress.inspect(candidate.projectId(), candidate.issueUrl(), candidate.deliveryKey()))
                .thenReturn(DevProgressGateway.Inspection.failed("이슈 조회 실패"));

        synchronizer.syncOnce();

        verify(requests, never()).advanceDevelopmentState(candidate, DevelopmentState.PROGRESS);
        verify(requests).recordDevelopmentSyncFailure(candidate, "이슈 조회 실패");
    }

    private DevelopmentStatusCandidate candidate(DevelopmentState state) {
        return new DevelopmentStatusCandidate("0000003", "0000001", "0000002",
                "DR-003", "0000004", "DRK-0000003", "http://gitlab.test/issues/3",
                state, "b".repeat(40));
    }
}
