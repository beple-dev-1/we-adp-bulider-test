package com.bizplay.builder.intake;

import java.time.Instant;

/**
 * 받은 문서 하나를 대표하는 <b>상위 업무 요구</b>. 표는 {@code builder.adk_builder_requirement} 다.
 *
 * <p>새 분석에서는 받은 문서 1건당 이 요구사항 1건을 만든다. 세부 업무 요구로 나누는 일은
 * 요구사항정의서가 맡는다(→ {@code docs/artifacts.md}).
 *
 * <p>⛔ <b>setter 를 열지 마라. 상태 변경 메서드도 만들지 마라.</b> MyBatis 에는 더티 체킹이 없어
 * 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀐다 — <b>예외도 안 난다.</b>
 */
public class Requirement {

    /** ⛔ 사람이 보는 REQ 번호가 아니다 — 그것은 {@link #number} 다. */
    private final String id;

    private final String projectId;
    private final String intakeId;

    /**
     * 사람이 보는 순번. 화면에는 {@code REQ-001} 꼴로 적는다.
     *
     * <p>⛔ <b>숫자다.</b> 글자로 두고 정렬하면 {@code REQ-10} 이 {@code REQ-9} 보다 앞에 선다.
     * ⛔ <b>재사용하지 않는다</b> — 지우거나 제외해도 번호는 그대로 두고, 채번은 프로젝트 줄의
     * 카운터가 한다({@link RequirementMapper#allocateNumber}).
     */
    private final int number;

    private final String title;
    private final String body;

    /**
     * AI 가 기획 저장소에서 찾은 <b>관련 화면 후보</b>.
     * ⚠ BRD 의 최종 대상 화면과 <b>다른 값</b>이다 — 참고 정보라 관계 표로 만들지 않는다
     * ({@code data-model} 의 미결 그대로다).
     */
    private final String screenHints;

    private final ReviewState reviewState;

    /**
     * 제외한 까닭. <b>제외와 짝이다</b> — {@link ReviewState#EXCLUDED} 가 아니면 언제나 {@code null} 이고,
     * DB 의 {@code CHECK} 도 그 짝을 지킨다.
     */
    private final String excludedReason;

    private final Instant createdAt;

    /**
     * 사람이 내용을 마지막으로 고친 때. ⚠ <b>널이면 한 번도 안 고친 것이다</b> —
     * 그때 화면은 {@link #createdAt} 을 대신 쓴다({@link #lastTouchedAt}).
     */
    private final Instant updatedAt;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     *
     * <p>⛔ <b>인자 순서를 바꾸지 마라</b> — XML 의 {@code <arg>} 와 자리로 맞춘다.
     * {@code id}·{@code projectId}·{@code intakeId} 는 셋 다 일곱 자리 글자라 뒤바뀌어도
     * 컴파일도 되고 예외도 안 난다. {@code createdAt}·{@code updatedAt} 도 같은 종류라
     * 뒤바뀌어도 조용하다 — <b>「마지막 수정」이 만든 때로 보이는 것</b>으로만 드러난다.
     */
    private Requirement(String id, String projectId, String intakeId, int number,
                        String title, String body, String screenHints,
                        ReviewState reviewState, String excludedReason,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.intakeId = intakeId;
        this.number = number;
        this.title = title;
        this.body = body;
        this.screenHints = screenHints;
        this.reviewState = reviewState;
        this.excludedReason = excludedReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 요구사항 분석이 뽑은 초안 한 건.
     * ⚠ {@code createdAt} 은 담지 않는다 — DB 의 {@code default now()} 가 채운다.
     */
    public static Requirement draft(String id, String projectId, String intakeId, int number,
                                    String title, String body, String screenHints) {
        return new Requirement(id, projectId, intakeId, number, title, body, screenHints,
                ReviewState.DRAFTED, null, null, null);
    }

    /** 화면에 뜨는 번호. {@code REQ-001} 꼴이고 <b>세 자리 아래로는 0 을 채운다.</b> */
    public String code() {
        return "REQ-%03d".formatted(number);
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String intakeId() {
        return intakeId;
    }

    public int number() {
        return number;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public String screenHints() {
        return screenHints;
    }

    public ReviewState reviewState() {
        return reviewState;
    }

    public String excludedReason() {
        return excludedReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * 화면의 「마지막 수정」 한 칸.
     *
     * <p>⚠ <b>고친 적이 없으면 만든 때를 낸다.</b> 널을 그대로 화면에 보내면 그 칸이 빈 채로 서고,
     * 사람은 「값이 없다」와 「아직 안 고쳤다」를 못 가린다.
     */
    public Instant lastTouchedAt() {
        return updatedAt == null ? createdAt : updatedAt;
    }

    /** 확정도 제외도 아닌 것 — 사람이 아직 판단하지 않았다. <b>접수 되굴림이 이것을 센다.</b> */
    public boolean undecided() {
        return reviewState == ReviewState.DRAFTED;
    }

    /**
     * 낱개의 검토 상태. <b>산출물 규칙 그대로 「생성 완료 → 확정 완료」</b>다.
     *
     * <p>⛔ 제외해도 <b>줄을 지우지 않는다</b> — 번호를 유지해야 한다.
     *
     * <p>⚠ 중첩 열거라 매퍼 XML 에서는 {@code $} 로 적는다.
     */
    public enum ReviewState {
        DRAFTED("생성 완료"),
        CONFIRMED("확정 완료"),
        EXCLUDED("제외");

        private final String label;

        ReviewState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
