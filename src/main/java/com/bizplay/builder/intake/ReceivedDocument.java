package com.bizplay.builder.intake;

import java.time.Instant;

/**
 * 접수에 딸린 받은 문서 한 건. 표는 {@code builder.adk_builder_received_document} 다.
 *
 * <p>⛔ <b>원문은 그대로 보존한다.</b> 파일과 직접 입력은 손대지 않고, 뽑아낸 글과 사람이 확인한 글은
 * 별도 자리에 앉는다. 못 읽는 문서도 <b>올라가고 보존된다</b> — 막는 것은 다음 걸음뿐이다.
 *
 * <p>⚠ <b>파일 또는 직접 입력 중 하나 이상</b>이다. 둘 다 있으면 직접 입력은 파일의 보충 설명이다.
 * 그 규칙은 DB {@code CHECK} 가 같이 지킨다 — 자바만 믿지 않는다.
 *
 * <p><b>2026-08-15 에 「AI 가 늘 정리한다」가 폐기됐다.</b> 직접 입력은 AI 를 아예 안 부르고,
 * 서버 텍스트 추출이 되는 첨부도 안 부른다. AI 가 읽는 것은 <b>글자가 안 나오는 문서</b>뿐이다
 * (→ {@link ContentState}). ⛔ {@code normalizedContent}(AI 1차 정리본)를 되살리지 마라 —
 * 그 개념이 통째로 없어졌고, 사람이 보는 정본은 {@link #documentContent()} 하나다.
 *
 * <p>⛔ <b>setter 를 열지 마라. 상태 변경 메서드도 다시 만들지 마라.</b>
 * JPA 때는 찾아온 것을 고치면 트랜잭션 끝에 저장됐지만(더티 체킹) <b>MyBatis 에는 그것이 없다.</b>
 * 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀐다 — <b>예외도 안 난다.</b>
 * 이미 앉은 줄을 고치는 길은 {@link ReceivedDocumentMapper} 의 {@code update...} 뿐이다.
 */
public class ReceivedDocument {

    private final String id;
    private final String intakeId;
    private final DocumentType documentType;
    private final String originalName;
    private final String serverPath;
    private final Long byteSize;
    private final String typedContent;

    /** 회의록일 때만 쓰는 선택 입력. ⛔ AI 가 못 찾은 값을 임의로 채우지 않는다. */
    private final Instant meetingAt;

    private final String attendees;
    private final ContentState contentState;

    /** 읽힘 판정의 한 줄 설명. 읽히면 비운다 — 화면이 이 값이 있을 때만 안내를 띄운다. */
    private final String readCheckReason;

    /** 서버(또는 멀티모달 AI)가 파일 본문에서 뽑아낸 글. 직접 입력만 있으면 {@code null} 이다. */
    private final String extractedContent;

    private final String documentContent;
    private final Instant contentConfirmedAt;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     *
     * <p>⛔ <b>인자 순서를 바꾸지 마라</b> — XML 의 {@code <arg>} 와 자리로 맞춘다.
     * {@code originalName}·{@code serverPath}·{@code typedContent} 는 셋 다 글자라
     * 뒤바뀌어도 컴파일도 되고 예외도 안 난다. 화면에 엉뚱한 값이 뜨는 것으로만 드러난다.
     */
    private ReceivedDocument(String id, String intakeId, DocumentType documentType,
                             String originalName, String serverPath, Long byteSize,
                             String typedContent, Instant meetingAt, String attendees,
                             ContentState contentState, String readCheckReason,
                             String extractedContent, String documentContent,
                             Instant contentConfirmedAt) {
        this.id = id;
        this.intakeId = intakeId;
        this.documentType = documentType;
        this.originalName = originalName;
        this.serverPath = serverPath;
        this.byteSize = byteSize;
        this.typedContent = typedContent;
        this.meetingAt = meetingAt;
        this.attendees = attendees;
        this.contentState = contentState;
        this.readCheckReason = readCheckReason;
        this.extractedContent = extractedContent;
        this.documentContent = documentContent;
        this.contentConfirmedAt = contentConfirmedAt;
    }

    /**
     * 새로 앉힐 것을 만든다. <b>등록하는 그 자리에서 상태가 정해진다</b> —
     * 무엇을 넣을지는 {@link IntakeService} 가 {@link DocumentIntakePlan} 으로 정한다.
     *
     * <p>⛔ 「일단 대기로 넣고 나중에 일꾼이 정한다」로 되돌리지 마라. 직접 입력은 AI 를
     * 아예 안 부르므로 <b>대기를 지나지 않는다</b> — 지나가게 만들면 화면에 「내용 분석 대기」가
     * 한 번 번쩍이고 사라진다.
     */
    public static ReceivedDocument create(String id, String intakeId, DocumentType documentType,
                                          String originalName, String serverPath, Long byteSize,
                                          String typedContent, Instant meetingAt, String attendees,
                                          DocumentIntakePlan plan) {
        return new ReceivedDocument(id, intakeId, documentType, originalName, serverPath, byteSize,
                nullIfBlank(typedContent), meetingAt, nullIfBlank(attendees),
                plan.state(), nullIfBlank(plan.reason()),
                nullIfBlank(plan.extractedContent()), nullIfBlank(plan.documentContent()), null);
    }

    /** ⚠ {@link IntakeService} 도 쓴다 — 빈 글자를 {@code null} 로 만드는 자리를 하나로 둔다. */
    static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 등록하는 순간 정해지는 것 — 상태 · 뽑은 글 · 문서 내용 · 못 읽은 까닭.
     *
     * <p>⚠ 넷이 <b>같이</b> 정해져야 뜻이 맞는다: {@link ContentState#READY} 인데
     * {@code documentContent} 가 비면 「등록 완료인데 읽을 것이 없는 문서」가 된다.
     * 그래서 인자를 넷으로 늘리지 않고 한 묶음으로 받는다.
     */
    public record DocumentIntakePlan(ContentState state, String extractedContent,
                                     String documentContent, String reason) {

        /** 직접 입력만 있다 — AI 를 안 부르고 원문 그대로 등록 완료다. */
        public static DocumentIntakePlan typedOnly(String typed) {
            return new DocumentIntakePlan(ContentState.READY, null, typed, null);
        }

        /** 서버가 파일에서 글자를 뽑았다 — 이것도 AI 를 안 부른다. */
        public static DocumentIntakePlan extracted(String extracted) {
            return new DocumentIntakePlan(ContentState.READY, extracted, extracted, null);
        }

        /** 글자가 안 나온다 — 멀티모달이 읽어야 한다. 줄 세워 두고 일꾼이 가져간다. */
        public static DocumentIntakePlan needsUnderstanding(String reason) {
            return new DocumentIntakePlan(ContentState.QUEUED, null, null, reason);
        }

        /** 멀티모달로도 읽을 수 없는 종류다 — 원본은 보존하되 다음 걸음이 닫힌다. */
        public static DocumentIntakePlan unreadable(String reason) {
            return new DocumentIntakePlan(ContentState.FAILED, null, null, reason);
        }
    }

    /**
     * 서버가 글자로 읽어 냈나. ⚠ 「멀티모달이 읽어야 한다」({@code QUEUED})는 <b>여기서 참이 아니다</b> —
     * 아직 읽은 글이 없다는 뜻이라 {@link #readCheckReason} 이 차 있다.
     */
    public boolean readable() {
        return readCheckReason == null;
    }

    /** 파일이 없으면 뽑을 것이 없다 — 직접 입력만 있는 문서는 등록 즉시 완료다. */
    public boolean hasFile() {
        return serverPath != null;
    }

    /** 올린 파일이 앉은 자리. ⛔ 화면에 내지 마라 — 서버 안쪽 경로다. */
    public String serverPath() {
        return serverPath;
    }

    public String id() {
        return id;
    }

    public String intakeId() {
        return intakeId;
    }

    public DocumentType documentType() {
        return documentType;
    }

    public String originalName() {
        return originalName;
    }

    public Long byteSize() {
        return byteSize;
    }

    public String typedContent() {
        return typedContent;
    }

    public Instant meetingAt() {
        return meetingAt;
    }

    public String attendees() {
        return attendees;
    }

    public ContentState contentState() {
        return contentState;
    }

    public String readCheckReason() {
        return readCheckReason;
    }

    /**
     * 서버나 멀티모달 AI 가 파일에서 뽑아낸 글.
     *
     * <p>⚠ <b>사람이 보는 정본이 아니다</b> — 확인 화면이 원본과 대조할 때만 쓴다.
     * 정본은 {@link #documentContent()} 다.
     */
    public String extractedContent() {
        return extractedContent;
    }

    /**
     * <b>확인된 문서 내용</b> — 상세 화면의 한 칸이 이것이고, 요구사항 분석에 들어가는 것도 이것이다.
     *
     * <p>직접 입력이면 사람이 친 원문 그대로, 서버 텍스트 추출이면 뽑은 글,
     * 멀티모달이면 사람이 확인·수정한 글이다.
     */
    public String documentContent() {
        return documentContent;
    }

    /**
     * 멀티모달 추출 결과를 사람이 확인해 마친 때.
     * ⚠ 직접 입력과 서버 텍스트 추출은 확인할 것이 없어 {@code null} 로 남는다 —
     * <b>비었다고 「덜 된 문서」가 아니다.</b>
     */
    public Instant contentConfirmedAt() {
        return contentConfirmedAt;
    }

    /** 요구사항 분석을 시작해도 되는 문서인가. ⛔ 화면과 서버가 같은 판정을 써야 한다. */
    public boolean readyForRequirements() {
        return contentState == ContentState.READY;
    }

    /**
     * 받은 원문의 형태. 문서의 업무 주제나 요구사항 분류가 아니라 등록·검색에 쓰는 원문 분류다.
     * {@code FLOW} 는 협업 게시물을 게시물 ID로 가져오는 형태이므로 이 축에 둔다.
     *
     * <p>⛔ <b>상수는 영문이고 한글은 표시 이름이다</b>(→ {@code coding-conventions} 예외 2).
     * DB {@code CHECK} 도 이 영문 코드값을 검사한다 — 한쪽만 바꾸면 저장이 조용히 거절된다.
     *
     * <p>⚠ 중첩 열거라 매퍼 XML 에서는
     * {@code com.bizplay.builder.intake.ReceivedDocument$DocumentType} 으로 적는다.
     * <b>점이 아니라 {@code $} 다</b> — 점으로 적으면 부팅 때 「그런 클래스가 없다」로 죽는다.
     */
    public enum DocumentType {
        FLOW("Flow"),
        MEETING_MINUTES("회의록"),
        OTHER("일반문서");

        private final String label;

        DocumentType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * <b>첨부파일 내용 분석</b>의 지금 상태. ⛔ 「정리」가 아니다 —
     * 2026-08-15 에 「AI 가 늘 1차 정리한다」가 폐기되며 이 축이 여기까지 좁아졌다.
     *
     * <p><b>걸음이 있는 것은 멀티모달 대상뿐이다.</b>
     * <pre>
     * 직접 입력              →                                              READY
     * 첨부 · 서버 추출 성공  →                                              READY
     * 첨부 · 글자가 안 나옴  → QUEUED → PROCESSING → READY
     * 첨부 · 읽을 수 없는 종류 →                                            FAILED
     * </pre>
     *
     * <p>⛔ {@code PENDING}·{@code EXTRACTING}·{@code NORMALIZING} 을 되살리지 마라 —
     * 셋 다 「AI 가 늘 무언가 한다」를 전제한 자리였다.
     *
     * <p>⚠ 중첩 열거라 매퍼 XML 에서는 {@code $} 로 적는다(위 {@link DocumentType} 과 같은 까닭).
     */
    public enum ContentState {
        QUEUED("내용 분석 대기"),
        PROCESSING("내용 분석 중"),
        READY("등록 완료"),
        FAILED("문서 처리 오류");

        private final String label;

        ContentState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
