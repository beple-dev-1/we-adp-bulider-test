package com.bizplay.builder.intake;

import java.time.Instant;

/**
 * 접수 한 건. <b>받은 문서 1건 = 접수 1건</b>이다(→ {@code intake} 의 「정한 것」).
 * 표는 {@code builder.adk_builder_intake} 다.
 *
 * <p>받은 문서를 올린 자리에서 시작해 요구사항·정의서까지 여덟 칸을 걸어간다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 *
 * <p>⛔ <b>{@code processType}(처리 대기·요구사항 대상·참고 문서)을 되살리지 마라 (2026-08-15 폐기).</b>
 * 「참고 목적으로만 두고 싶으면 아무것도 안 하면 된다」가 그 자리를 대신한다 —
 * 분석하지 않은 문서는 {@link RequirementState#NOT_STARTED} 에 <b>중립으로</b> 머문다.
 * 갈래를 다시 만들면 목록에 「아직 안 골랐다」가 되살아나고, 그것이 없애려던 바로 그 강조다.
 *
 * <p>⛔ <b>setter 를 열지 마라. 상태 변경 메서드도 다시 만들지 마라.</b>
 * JPA 때는 찾아온 것을 고치면 트랜잭션 끝에 저장됐지만(더티 체킹) <b>MyBatis 에는 그것이 없다.</b>
 * 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀐다 — <b>예외도 안 난다.</b>
 * 고치는 길은 {@link IntakeMapper} 의 {@code update...} 뿐이다.
 */
public class Intake {

    /**
     * ⛔ DB 에도 {@code default lpad(nextval(...))} 이 있지만 <b>거기에 기대지 마라</b> —
     * 채번은 {@link com.bizplay.builder.id.IdSequence} 가 한다. 까닭은 그 파일에 적어 뒀다.
     */
    private final String id;

    private final String projectId;
    private final String title;
    private final String uploadedBy;
    private final Instant uploadedAt;

    /** 지금 몇 번째 칸인가(1~8). */
    private final short step;

    /**
     * 요구사항 분석 실행을 제어하는 내부 상태. ⛔ <b>문서 내용 분석 상태와 다른 축이다.</b>
     * 문서가 {@link ReceivedDocument.ContentState#READY} 여야 비로소 이 축이 움직인다.
     * 받은 문서 화면에서는 이 상태를 문서 상태로 표시하지 않는다.
     */
    private final RequirementState requirementState;

    /**
     * MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}).
     *
     * <p>⛔ <b>인자 순서를 바꾸지 마라</b> — XML 의 {@code <arg>} 와 자리로 맞춘다.
     * {@code id}·{@code projectId}·{@code uploadedBy} 는 셋 다 일곱 자리 글자라 뒤바뀌어도
     * 컴파일도 되고 예외도 안 난다. 엉뚱한 프로젝트를 가리키는 것으로만 드러난다.
     */
    private Intake(String id, String projectId, String title, String uploadedBy,
                   Instant uploadedAt, short step, RequirementState requirementState) {
        this.id = id;
        this.projectId = projectId;
        this.title = title;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.step = step;
        this.requirementState = requirementState;
    }

    /**
     * 새로 앉힐 것을 만든다.
     * ⚠ {@code uploadedAt} 은 담지 않는다 — DB 의 {@code default now()} 가 채운다.
     */
    public static Intake create(String id, String projectId, String title, String uploadedBy) {
        return new Intake(id, projectId, title, uploadedBy, null, (short) 1,
                RequirementState.NOT_STARTED);
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String title() {
        return title;
    }

    public String uploadedBy() {
        return uploadedBy;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public short step() {
        return step;
    }

    public RequirementState requirementState() {
        return requirementState;
    }

    /**
     * 요구사항 분석의 지금 상태. <b>문서 상태와 별도 축</b>이다.
     *
     * <p>⚠ 중첩 열거라 매퍼 XML 에서는 {@code com.bizplay.builder.intake.Intake$RequirementState} 로
     * 적는다. <b>점이 아니라 {@code $} 다</b> — 점으로 적으면 부팅 때 「그런 클래스가 없다」로 죽는다.
     */
    public enum RequirementState {
        /**
         * 미분석 — 아직 분석을 시키지 않았다.
         * ⛔ <b>오류도 해야 할 일도 아니다.</b> 참고 목적으로만 두는 문서가 여기 그대로 머문다 —
         * 화면에서 붉게 칠하거나 「처리 대기」처럼 재촉하지 마라.
         */
        NOT_STARTED("미분석"),
        RUNNING("요구사항 분석 중"),
        REVIEW_REQUIRED("요구사항 검토 필요"),
        COMPLETED("완료"),
        FAILED("요구사항 분석 오류");

        private final String label;

        RequirementState(String label) {
            this.label = label;
        }

        /** 화면에 뜨는 말. ⛔ 코드값(이름)과 갈라 둔다 — 문구를 다듬는 데 DB 가 안 움직인다. */
        public String label() {
            return label;
        }
    }
}
