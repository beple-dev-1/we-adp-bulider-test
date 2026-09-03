package com.bizplay.builder.devrequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** 완료한 FRD에서 만든 개발요청서. 본문은 생성 시점의 스냅샷이다. */
public record DevelopmentRequest(String id, String projectId, int number, String frdId, int frdNumber,
                                 String title, String systemCode, String facets,
                                 String contentJson, DeliveryState deliveryState,
                                 String plannerComment, String attachmentName, String attachmentPath,
                                 Long attachmentSize, LocalDate developmentCompletedOn, LocalDate deploymentOn,
                                 String previousRequestId, String precheckJson,
                                 String workspaceBaseSha, String workspaceHeadSha,
                                 DevelopmentState developmentState,
                                 Instant developmentCheckedAt, String developmentSyncError,
                                 String developmentMergedSha, Instant developmentMergedAt,
                                 Instant createdAt, Instant updatedAt) {

    /** 개발 상태 열을 추가하기 전 호출자가 쓰는 생성 계약. 새 요청은 전송 전이라 개발 상태가 비어 있다. */
    public DevelopmentRequest(String id, String projectId, int number, String frdId, int frdNumber,
                              String title, String systemCode, String facets,
                              String contentJson, DeliveryState deliveryState,
                              String plannerComment, String attachmentName, String attachmentPath,
                              Long attachmentSize, LocalDate developmentCompletedOn, LocalDate deploymentOn,
                              String previousRequestId, String precheckJson,
                              String workspaceBaseSha, String workspaceHeadSha,
                              Instant createdAt, Instant updatedAt) {
        this(id, projectId, number, frdId, frdNumber, title, systemCode, facets, contentJson,
                deliveryState, plannerComment, attachmentName, attachmentPath, attachmentSize,
                developmentCompletedOn, deploymentOn, previousRequestId, precheckJson,
                workspaceBaseSha, workspaceHeadSha, null, null, null, null, null,
                createdAt, updatedAt);
    }

    /*
     * previousRequestId — 같은 업무를 앞서 넘긴 개발요청서.
     *
     * ⛔ 빌더가 자동으로 채우지 않는다. Frd 에 앞 FRD 연결이 없고 sourceRef 는 조인 금지
     *    글자 도장이다(2026-08-18 병주 확정). 화면 겹침으로 후보만 대고 사람이 고른다 —
     *    화면이 겹치는 것과 같은 업무인 것은 다르다.
     * ⚠ 비어 있는 것이 정상이다 — 첫 요청에는 앞것이 없다.
     */

    /**
     * 개발 조직에 나갔나. ⚠ 화면에 뜨는 말은 {@link #deliveryStateLabel()} 이다 — 코드값과 갈라 둔다.
     *
     * <p>⛔ <b>창구의 답으로 넷째 값을 만들지 마라 (2026-08-21 병주 확정).</b> 「거절됨」·「재전송 필요」는
     * 이 셋 안에 이미 있다 — 명확한 거절은 {@code NOT_SENT} 고, 모르면 {@code SENDING} 이다.
     *
     * <p>⚠ <b>{@code WITHDRAWN} 은 그 금지의 예외다 (2026-08-25 병주 지시).</b> 위 셋은
     * <b>창구가 뭐라 답했나</b>를 옮긴 값이고, 철회는 <b>사람이 무른 것</b>이라 축이 다르다.
     * 그리고 셋 중 어느 것으로도 참이 안 된다:
     * {@code NOT_SENT} 는 「아직 안 갔다」인데 갔다 물린 것이고, {@code SENT} 로 두면 다시 못 보내고,
     * {@code SENDING} 은 「모른다」인데 우리는 안다.
     * ⛔ <b>이것을 근거로 다섯째를 만들지 마라</b> — 창구 축은 여전히 셋이다.
     */
    public enum DeliveryState { NOT_SENT, SENDING, SENT, WITHDRAWN }

    public String label() {
        return "DR-%03d".formatted(number);
    }

    public String businessLabel() {
        return systemCode == null || systemCode.isBlank() ? title : title + " · " + systemCode;
    }

    public String frdLabel() {
        return "FRD-%03d".formatted(frdNumber);
    }

    /**
     * 적용 구분을 배지 하나씩으로 나눈 것. {@code facets} 는 만들 때 쉼표로 이어 붙인 스냅샷이다.
     *
     * <p>⚠ 비면 빈 목록이다 — 그 프로젝트가 접수처를 안 쓰거나 이 FRD 가 하나도 안 골랐다는 뜻이다.
     */
    public List<String> facetList() {
        if (facets == null || facets.isBlank()) {
            return List.of();
        }
        return Arrays.stream(facets.split(",")).map(String::strip).filter(name -> !name.isEmpty()).toList();
    }

    /** 화면에 뜨는 말. 코드값은 안 바꾼다 — 뜻이 그대로이므로 마이그레이션이 없다. */
    public String deliveryStateLabel() {
        return switch (deliveryState) {
            case NOT_SENT -> "대기";
            case SENDING -> "전송중";
            case SENT -> "전송완료";
            case WITHDRAWN -> "취소";
        };
    }

    public String deliveryStateClass() {
        return switch (deliveryState) {
            case NOT_SENT -> "status-badge--waiting";
            case SENDING -> "status-badge--progress";
            case SENT -> "status-badge--complete";
            case WITHDRAWN -> "status-badge--waiting";
        };
    }

    /** 전송 전에는 개발 조직의 상태가 없으므로 대기 상태로 표시한다. */
    public String developmentStateLabel() {
        return developmentState == null ? "대기" : developmentState.label();
    }

    public String developmentStateClass() {
        return developmentState == null ? "status-badge--waiting" : developmentState.cssClass();
    }

    /** 개발 완료가 확인됐고 아직 기본 브랜치에 반영하지 않은 경우에만 수동 병합을 허용한다. */
    public boolean canMergeDevelopment() {
        return deliveryState == DeliveryState.SENT
                && developmentState == DevelopmentState.DONE
                && developmentMergedSha == null;
    }

    public boolean developmentMerged() {
        return developmentMergedSha != null;
    }
}
