package com.bizplay.builder.frd;

import java.time.Instant;
import java.util.List;

/**
 * FRD 하나가 고치는 화면 한 장.
 *
 * <p><b>기존 화면</b>은 기획 저장소의 화면ID 를 그대로 쓴다 — 빌더가 지어내지 않는다.
 * <b>신규 화면</b>은 {@link TemporaryScreenId} 가 이 행의 기본키로 이름을 짓는다
 * (2026-08-22 병주 확정 · {@code docs/superpowers/specs/2026-08-22-new-screen-id-design.md}).
 *
 * @param baseScreenId 시작점으로 삼을 화면. <b>기존 화면이면 자기 자신</b>,
 *                     <b>신규 화면이면 처음에 비어 있고</b> 목업을 만들 때 AI 가 그 시스템의
 *                     같은 {@code screenType} 화면 중에서 골라 채운다.
 *                     ⛔ 이것으로 신규 여부를 가리지 마라 — {@link #isNewScreen()} 을 보라
 * @param screenType   신규 화면의 유형(목록·상세·등록·수정·안내). 사람이 「화면 추가」에서 고른다.
 *                     ⚠ <b>기존 화면은 비어 있다</b> — 그쪽 유형은 기획 저장소 색인이 안다
 * @param scopeChange  이 요구사항 때문에 이 화면에서 신규·수정할 내용. 선택 출처인
 *                     {@code pickReason}과 분리해 분석 결과와 개발 범위 확인에 표시한다.
 */
public record FrdScreen(String id, String frdId, String screenId, String screenName,
                        String baseScreenId, String facet, String pickReason,
                        State state, String html, String changes, String failure,
                        Instant generatedAt, Instant createdAt, String systemCode,
                        String screenType, String scopeChange) {

    public enum State { WAITING, GENERATING, GENERATED, FAILED }

    /**
     * AI 가 짚었거나 사람이 더한 화면. 아직 목업은 없다.
     *
     * <p>⚠ <b>시스템은 여기서 안 받는다</b> — 사람이 손으로 더하는 자리(「화면 직접 고르기」·
     * 「새 화면 만들기」)는 시스템을 물어보지 않는다. AI 가 짚은 것만
     * {@link #pickedIn(String, String, String, String, String, String, String, String)} 로 앉힌다.
     */
    public static FrdScreen picked(String id, String frdId, String screenId, String screenName,
                                   String baseScreenId, String facet, String pickReason) {
        return pickedIn(id, frdId, screenId, screenName, baseScreenId, facet, pickReason, null);
    }

    /** AI 가 짚은 화면 — 어느 시스템에 사는지까지 안다. 유형은 색인이 아니까 안 받는다. */
    public static FrdScreen pickedIn(String id, String frdId, String screenId, String screenName,
                                     String baseScreenId, String facet, String pickReason,
                                     String systemCode) {
        return new FrdScreen(id, frdId, screenId, screenName, baseScreenId, facet, pickReason,
                State.WAITING, null, null, null, null, null, systemCode, null, pickReason);
    }

    /**
     * 사람이 「화면 추가」에서 만든 <b>신규 화면</b> — 시스템과 유형은 정해져 있고 기준 화면은 아직 없다.
     *
     * <p>⚠ {@code baseScreenId} 를 비워 두는 것은 일부러다. 사람에게 500장에서 하나를 고르게 하는
     * 대신, 목업을 만들 때 <b>AI 가 그 시스템의 같은 유형 화면 중에서 고른다</b>
     * (2026-08-22 병주 확정). 사람은 「무엇을 만드나」만 답한다.
     */
    public static FrdScreen drafted(String id, String frdId, String screenId, String screenName,
                                    String screenType, String facet, String systemCode) {
        return new FrdScreen(id, frdId, screenId, screenName, null, facet, null,
                State.WAITING, null, null, null, null, null, systemCode, screenType, null);
    }

    /** AI 분석에서 새로 찾은 신규 화면 — 재분석 때 사용자 추가 화면과 구분할 선택 근거를 남긴다. */
    public static FrdScreen draftedByAnalysis(String id, String frdId, String screenId,
                                              String screenName, String screenType, String facet,
                                              String systemCode, String pickReason) {
        return new FrdScreen(id, frdId, screenId, screenName, null, facet, pickReason,
                State.WAITING, null, null, null, null, null, systemCode, screenType, pickReason);
    }

    /**
     * <b>AI 초안 버튼의 상태</b> — 지금 누를 수 있나 · 돌고 있나.
     *
     * <p>⛔ <b>산출물을 보여 주는 자리에 쓰지 마라</b> — 「FRD 내용 보기」처럼 개발요청서로 실려 나갈
     * 것을 훑는 자리에서는 {@link #workLabel()} 이다. AI 초안은 <b>작업 도구의 사정</b>이지
     * 화면이 됐느냐가 아니다. 사람이 손으로 더한 화면은 초안 대상이 아닌데도 이 말이 붙고,
     * {@code WAITING} 과 {@code GENERATED} 가 한 말로 뭉쳐 <b>다 된 화면과 안 된 화면이 같아 보인다.</b>
     */
    public String stateLabel() {
        return switch (state) {
            case WAITING, GENERATED -> "AI 초안 생성 가능";
            case GENERATING -> "AI 초안 만드는 중";
            case FAILED -> "AI 초안 생성 실패";
        };
    }

    /**
     * <b>이 화면 작업이 어디까지 갔나</b> — 무엇이 만들었는지는 묻지 않는다.
     *
     * <p>AI 초안 · 화면 대화 · 문구 직접 수정 <b>셋 다</b> {@code GENERATED} 로 앉는다.
     * 개발에 나가는 것은 「누가 고쳤나」가 아니라 <b>「고쳐진 화면이 있나」</b>다.
     * 말은 목업 {@code docs/mockups/05a-frd-workbench.html} 을 따른다.
     */
    public String workLabel() {
        return switch (state) {
            case WAITING -> "미작업";
            case GENERATING -> "만드는 중";
            case GENERATED -> "완료";
            case FAILED -> "실패";
        };
    }

    /**
     * 새로 만드는 화면인가 — <b>이름을 빌더가 지었으면 그렇다.</b>
     *
     * <p>⚠ <b>종전에는 「베이스가 자기 자신이 아니면」이었다 (2026-08-22 에 바꿨다).</b>
     * 신규 화면의 기준 화면은 이제 <b>목업을 만들 때 AI 가 채우므로 처음에 비어 있다</b> —
     * 옛 잣대로는 갓 만든 신규 화면이 조용히 「기존 화면」이 되어 뱃지 · 미리보기 ·
     * 개발요청서가 한꺼번에 어긋났다. 이름은 만들어지는 순간부터 있으므로 흔들리지 않는다.
     */
    public boolean isNewScreen() {
        return TemporaryScreenId.isTemporary(screenId);
    }

    /** 사용자가 화면 추가에서 고른 화면인가 — AI 선택은 선택 근거가 함께 저장된다. */
    public boolean isUserSelected() {
        return pickReason == null || pickReason.isBlank();
    }

    /**
     * AI 초안을 만들 수 있는 화면인가.
     *
     * <p>기존 화면은 AI가 선택 근거를 남겨 고른 경우에만 대상이다. 다만 신규 화면은 사람이
     * 이름과 유형을 등록하더라도 기준 화면이 없으므로, AI가 처음부터 초안을 만들어야 한다.
     */
    public boolean isAiDraftEligible() {
        return isNewScreen() || !isUserSelected();
    }

    /** 지금 AI 초안 만들기 또는 실패 재시도가 가능한 화면인가. */
    public boolean canGenerateDraft() {
        return isAiDraftEligible() && (state == State.WAITING || state == State.FAILED);
    }

    /** 현재 화면 버전에 실제로 적용했다고 기록한 변경 내용. */
    public List<String> changeList() {
        return changes == null ? List.of()
                : changes.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
    }
}
