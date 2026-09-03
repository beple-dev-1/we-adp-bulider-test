package com.bizplay.builder.ai;

import java.time.Instant;

/**
 * AI 실행 한 건. <b>끝 다섯</b> 갈래 중 하나로 끝난다. 표는 {@code builder.adk_builder_ai_run} 이다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 *
 * <p>⛔ <b>이것을 서비스 밖으로 돌려주지 마라.</b> 일은 다른 스레드에서 돈다 —
 * 여기 담긴 상태는 <b>읽어온 그 순간의 사진</b>이라 밖에서 들고 있는 사이에 DB 는 이미 끝나 있다.
 * JPA 때는 「준영속이라 영원히 {@link AiRunState#RUNNING} 으로 보인다」가 이유였고,
 * MyBatis 로 옮긴 지금도 결론은 같다(오히려 언제나 사진이라 더 그렇다). 밖으로 나가는 것은 {@code id} 글자다.
 *
 * <p>⛔ <b>setter 를 열지 마라. {@code markCancelled} 같은 상태 변경 메서드도 만들지 마라.</b>
 * JPA 는 찾아온 것을 고치면 트랜잭션 끝에 저장됐지만(더티 체킹) <b>MyBatis 에는 그것이 없다.</b>
 * 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀐다 — <b>예외도 안 난다.</b>
 * 끝처리로 상태를 바꾸는 길은 {@link AiRunMapper#updateToFinished} 하나뿐이고,
 * 그 자리가 「누가 이겼나」를 DB 에 맡기는 유일한 자리다.
 */
public class AiRun {

    /**
     * ⛔ DB 에도 {@code default lpad(nextval(...))} 이 있지만 <b>거기에 기대지 마라</b> —
     * 채번은 {@link com.bizplay.builder.id.IdSequence} 가 한다. 까닭은 그 파일에 적어 뒀다.
     */
    private final String id;
    private final String projectId;
    private final String accountId;

    /** ⛔ 글자를 만드는 자리는 {@link WorkKey#text()} 하나뿐이다. */
    private final String workKey;

    private final AiRunKind runKind;
    private final AiRunState state;
    private final CheckerResult checkerResult;
    private final String instruction;
    private final String workDir;

    /** ⛔ 화면에 그대로 내지 않는다 — 사람에게 하는 말은 {@link #userMessage()} 가 상태에서 만든다. */
    private final String developerLog;

    private final Instant cancelRequestedAt;
    private final Instant startedAt;
    private final Instant finishedAt;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     *
     * <p>⛔ <b>인자 순서를 바꾸지 마라</b> — XML 의 {@code <arg>} 와 자리로 맞춘다.
     * {@code projectId}·{@code accountId} 는 둘 다 일곱 자리 글자라 뒤바뀌어도
     * 컴파일도 되고 예외도 안 난다. 엉뚱한 프로젝트를 가리키는 것으로만 드러난다.
     */
    private AiRun(String id, String projectId, String accountId, String workKey,
                  AiRunKind runKind, AiRunState state, CheckerResult checkerResult,
                  String instruction, String workDir, String developerLog,
                  Instant cancelRequestedAt, Instant startedAt, Instant finishedAt) {
        this.id = id;
        this.projectId = projectId;
        this.accountId = accountId;
        this.workKey = workKey;
        this.runKind = runKind;
        this.state = state;
        this.checkerResult = checkerResult;
        this.instruction = instruction;
        this.workDir = workDir;
        this.developerLog = developerLog;
        this.cancelRequestedAt = cancelRequestedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /**
     * 새로 앉힐 것을 만든다.
     * ⚠ {@code startedAt} 은 담지 않는다 — DB 의 {@code default now()} 가 채운다.
     */
    public static AiRun start(String id, AiRunRequest request) {
        return new AiRun(id, request.projectId(), request.accountId(), request.work().text(),
                request.kind(), AiRunState.RUNNING, CheckerResult.NOT_RUN,
                request.instruction(), request.workDir().toString(), null,
                null, null, null);
    }

    /**
     * 사람에게 하는 말. <b>실패 원문이 여기로 새지 않는다</b>(Global Constraints).
     *
     * <p>⚠ <b>「다시 해봐라」를 아무 데나 붙이지 않는다.</b> 자격이 끊긴 실행은 다시 해봐야 똑같이 끊긴다 —
     * 그 사람이 할 일은 재시도가 아니라 <b>연결을 다시 맺는 것</b>이다.
     */
    public String userMessage() {
        return switch (state) {
            // ⚠ 상태 값을 새로 만들지 않고 두 열을 봐서 「그만두는 중」을 만든다.
            case RUNNING -> cancelRequestedAt == null ? "AI 가 돌고 있다." : "그만두는 중이다.";
            case SUCCEEDED -> "다 됐다.";
            case FAILED -> "AI 실행이 실패했다. 다시 해보고 그래도 안 되면 개발에 알려라.";
            case TIMED_OUT -> "시간 상한을 넘겨 멈췄다. 지시를 줄여서 다시 해봐라.";
            case CANCELLED -> "그만뒀다. 파일은 실행 전으로 돌아간다.";
            case CREDENTIAL_LOST -> "Claude 연결이 끊겼다. 설정에서 연결을 새로 맺어라.";
        };
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getWorkKey() {
        return workKey;
    }

    public AiRunKind getRunKind() {
        return runKind;
    }

    public AiRunState getState() {
        return state;
    }

    public CheckerResult getCheckerResult() {
        return checkerResult;
    }

    public String getInstruction() {
        return instruction;
    }

    public String getWorkDir() {
        return workDir;
    }

    public String getDeveloperLog() {
        return developerLog;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /**
     * 규격 검사기 결과.
     *
     * <p><b>이 회차는 언제나 {@link #NOT_RUN} 을 넘긴다</b> — 검사기가 아직 초안 하나를 못 본다.
     * 계획 3 이 {@link #GREEN}·{@link #RED} 를 넘기기 시작한다.
     *
     * <p>⚠ <b>값과 열을 지금 만들어 둬야</b> 계획 3 이 시그니처·엔티티·마이그레이션을 다시 안 뜯는다 —
     * 「자리를 비워 둔다」는 말만으로는 아무 자리도 안 생긴다.
     *
     * <p>⚠ 중첩 열거라 매퍼 XML 에서는 {@code com.bizplay.builder.ai.AiRun$CheckerResult} 로 적는다.
     * <b>점이 아니라 {@code $} 다</b> — 점으로 적으면 부팅 때 「그런 클래스가 없다」로 죽는다.
     */
    public enum CheckerResult {
        NOT_RUN, GREEN, RED
    }
}
