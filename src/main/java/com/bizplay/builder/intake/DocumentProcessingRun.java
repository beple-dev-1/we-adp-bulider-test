package com.bizplay.builder.intake;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 받은 문서 하나를 추출하거나 정리한 <b>시도 한 번</b>.
 *
 * <p>⛔ <b>재시도는 이전 행을 덮어쓰지 않는다.</b> 두 번째 시도가 첫 번째를 지우면
 * 무엇이 왜 실패했는지 사라진다 — {@code V4} 의 표 주석이 정본이다.
 *
 * <p>⚠ <b>{@code ai_run} 과 다른 표인 것이 뜻이 있다.</b> 가르는 기준은
 * {@code specs/2026-08-15-ai-conversation-track-design.md} 가 세운 <b>「파일을 고치나」</b> 하나다.
 * {@code ai_run} 은 워크트리에 <b>쓰는</b> 실행이라 {@code work_dir}·스냅샷·되돌리기·검사기 결과를
 * 짊어진다. 여기 셋은 <b>하나도 안 쓴다</b> — 뽑기와 내용 분석은 원본을 읽기만 하고,
 * 요구사항 분석도 기획 저장소를 <b>읽기 전용</b>으로 본다.
 * ⭐ 덕분에 이 길은 {@code AiRunRequest} 의 {@code workDir} 강제(=얼린 Task 6)에 안 걸린다.
 *
 * <p>⛔ <b>「요구사항 분석은 산출물 층 전이니 {@code ai_run} 이 맞다」로 옮기지 마라.</b>
 * 그 표의 {@code EXTRACT_REQUIREMENTS} 는 <b>결과 본문을 안 들고 있다</b>({@code developer_log} 는
 * 실패 원문이다) — 옮기려면 얼려 둔 {@code AiRunWorker} 를 뜯어 결과를 받아 오게 고쳐야 한다.
 *
 * <p>⚠ <b>토큰·비용 셋은 아직 안 채운다.</b> {@code claude -p --output-format json} 의 사용량 필드를
 * 아직 안 쟀다 — 열은 {@code V4} 가 이미 만들어 뒀으니 재고 나서 채운다.
 */
public record DocumentProcessingRun(
        String id,
        String documentId,
        Kind kind,
        State state,
        String providerRunId,
        String errorMessage,
        Long inputTokens,
        Long outputTokens,
        BigDecimal costAmount,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {

    /**
     * 무엇을 한 시도인가.
     * ⚠ DB 의 {@code CHECK (run_kind in ('EXTRACT','UNDERSTAND','ANALYZE_REQUIREMENTS'))} 와 짝이다.
     *
     * <p>⛔ <b>{@code NORMALIZE}(1차 정리)를 되살리지 마라 (2026-08-15 폐기).</b>
     * 받은 문서를 AI 가 늘 정리하던 것이 없어졌다 — 직접 입력과 서버 추출이 되는 첨부는
     * AI 를 아예 안 부른다.
     */
    public enum Kind {
        /** 파일에서 본문 뽑기. <b>서버가 한다 — AI 가 아니다</b>({@link DocumentTextExtractor}). */
        EXTRACT("본문 뽑기"),
        /**
         * 스캔 PDF·그림을 <b>멀티모달 AI 가 읽는다</b>
         * ({@link com.bizplay.builder.ai.DocumentUnderstandingClient}).
         * ⛔ 요약도 요구사항 생성도 아니다 — 글자를 옮기는 것뿐이다.
         */
        UNDERSTAND("내용 분석"),
        /**
         * 확인된 문서 내용에서 <b>요구사항 초안을 뽑는다</b>. 올린 기획자 자격으로 {@code claude} 가 돌고,
         * 기획 저장소를 <b>읽기 전용</b>으로 참고한다.
         */
        ANALYZE_REQUIREMENTS("요구사항 분석");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** 화면에 뜨는 말. ⛔ 코드값(이름)과 갈라 둔다. */
        public String label() {
            return label;
        }
    }

    /** 이 시도의 끝. ⚠ DB 의 {@code CHECK} 와 짝이다. */
    public enum State { WAITING, RUNNING, SUCCEEDED, FAILED }

    /** 줄 서는 모습으로 새로 만든다. 시작·끝 시각과 사용량은 아직 비어 있다. */
    public static DocumentProcessingRun waiting(String id, String documentId, Kind kind) {
        return new DocumentProcessingRun(id, documentId, kind, State.WAITING,
                null, null, null, null, null, null, null, null);
    }
}
