package com.bizplay.builder.devrequest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevelopmentRequestMergeServiceTest {

    private final DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
    private final DevRequestBranchMerger merger = mock(DevRequestBranchMerger.class);
    private final DevelopmentRequestMergeService service =
            new DevelopmentRequestMergeService(requests, merger);

    @Test
    void 개발_완료_요청을_사용자가_선택하면_기본_브랜치에_병합한다() {
        DevelopmentRequest request = request(DevelopmentState.DONE, null);
        DevelopmentStatusCandidate candidate = candidate();
        when(requests.selectById(request.id())).thenReturn(request);
        when(requests.selectDevelopmentStatusCandidate(request.projectId(), request.id()))
                .thenReturn(candidate);
        when(merger.merge(candidate)).thenReturn(
                new DevRequestBranchMerger.Result(true, "a".repeat(40), null));
        when(requests.markDevelopmentMerged(candidate, "a".repeat(40))).thenReturn(1);

        service.merge(request.projectId(), request.id());

        verify(merger).merge(candidate);
        verify(requests).markDevelopmentMerged(candidate, "a".repeat(40));
    }

    @Test
    void 개발_완료_전에는_수동_병합할_수_없다() {
        DevelopmentRequest request = request(DevelopmentState.PROGRESS, null);
        when(requests.selectById(request.id())).thenReturn(request);

        assertThatThrownBy(() -> service.merge(request.projectId(), request.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("개발 완료인 개발요청서만 병합할 수 있습니다.");
    }

    @Test
    void 이미_병합한_요청은_다시_병합하지_않는다() {
        DevelopmentRequest request = request(DevelopmentState.DONE, "a".repeat(40));
        when(requests.selectById(request.id())).thenReturn(request);

        assertThatThrownBy(() -> service.merge(request.projectId(), request.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 기본 브랜치에 병합된 개발요청서입니다.");
    }

    private DevelopmentRequest request(DevelopmentState state, String mergedSha) {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new DevelopmentRequest("0000003", "0000001", 3, "0000002", 2,
                "알림 조건", "web", null, "{}", DevelopmentRequest.DeliveryState.SENT,
                null, null, null, null, null, null, null, null,
                "b".repeat(40), "c".repeat(40), state, now, null,
                mergedSha, mergedSha == null ? null : now, now, now);
    }

    private DevelopmentStatusCandidate candidate() {
        return new DevelopmentStatusCandidate("0000003", "0000001", "0000002",
                "DR-003", "0000004", "DRK-0000003", "http://gitlab.test/issues/3",
                DevelopmentState.DONE, "c".repeat(40));
    }
}
