package com.bizplay.builder.intake;

import com.bizplay.builder.intake.Requirement.ReviewState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사람이 요구사항에 대해 하는 판단 셋 — <b>확정 · 제외 · 내용 수정</b>.
 *
 * <p>⭐ <b>이 클래스의 심장은 {@link #rollUpIntake} 다.</b> 한 접수의 요구사항이 다 정해지는
 * 순간 그 접수가 {@link Intake.RequirementState#COMPLETED} 로 넘어간다 — V7 이 세운 그 값을
 * 여기가 처음 찍는다. 화면 둘은 사람이 그것을 찍게 하는 도구다.
 *
 * <p>⛔ <b>여기에 AI 를 부르는 코드를 넣지 마라.</b> 확정과 「정의서 생성 요청」은 설계가
 * 일부러 가른 두 판단이다(→ {@code intake} 걸음 6·7). 확정이 다음 산출물을 낳지 않는다.
 *
 * <p>⚠ <b>레포 커밋(밀기)도 여기 없다.</b> {@code push} 설계의 「① 요구사항 확정」 커밋은
 * 계획 2 Task 7 이고 그것은 얼려 있다 — 녹일 때 이 자리에서 부른다.
 */
@Service
public class RequirementReviewService {

    private final RequirementMapper requirements;
    private final IntakeMapper intakes;

    public RequirementReviewService(RequirementMapper requirements, IntakeMapper intakes) {
        this.requirements = requirements;
        this.intakes = intakes;
    }

    /**
     * 요구사항 확정. 되굴림까지 <b>한 트랜잭션</b>이다.
     *
     * <p>⚠ 이미 확정인 것을 다시 확정해도 조용히 지난다 — 사람이 두 번 눌렀을 뿐이고 잃는 것이 없다.
     */
    @Transactional
    public void confirm(String requirementId) {
        // ⚠ 제외였던 것이 확정으로 오면 사유를 같이 지운다 — 안 지우면 DB CHECK 가 거절한다.
        requirements.updateReviewState(requirementId, ReviewState.CONFIRMED, null);
        rollUpIntake(requirementId);
    }

    /**
     * 요구사항 제외. <b>사유가 없으면 아무것도 안 바꾼다.</b>
     *
     * <p>⛔ 사유를 선택으로 만들지 마라 ({@code ia} 설계가 사유를 요구한다) — 까닭 없이 뺀 것은
     * 목록에서 「왜 뺐나」를 아무도 모르게 되고, 그 물음이 다시 AI 실행으로 돌아온다.
     *
     * <p>⛔ <b>줄을 지우지 않는다.</b> 번호 재사용 금지가 이 표의 규칙이다.
     *
     * @throws IllegalArgumentException 사유가 비었을 때
     */
    @Transactional
    public void exclude(String requirementId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("제외하려면 까닭을 적어야 합니다.");
        }
        requirements.updateReviewState(requirementId, ReviewState.EXCLUDED, reason.strip());
        rollUpIntake(requirementId);
    }

    /**
     * AI 초안을 사람이 고친다.
     *
     * <p>⚠ <b>검토 상태는 안 움직인다</b> — 내용을 고친 것이 판단은 아니다. 확정이 끝난 뒤에도
     * 고칠 수 있다({@code ia} 의 「접수를 닫은 뒤에도 고칠 수 있다」).
     *
     * @throws IllegalArgumentException 제목이나 본문이 비었을 때. DB {@code CHECK} 가 같은 것을
     *                                 막지만, 거기까지 가면 사람에게는 500 으로 보인다
     */
    @Transactional
    public void editContent(String requirementId, String title, String body) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("요구사항 이름을 적어야 합니다.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("요구사항 내용을 적어야 합니다.");
        }
        requirements.updateContent(requirementId, title.strip(), body.strip());
    }

    /**
     * ⭐ <b>접수 되굴림.</b> 그 접수에 판단 안 한 요구사항이 하나도 없으면 접수를 완료로 넘긴다.
     *
     * <p>⛔ <b>확정만 세지 마라.</b> 제외도 「정해진 것」이다 — 확정만 세면 제외가 하나 있는 접수는
     * 영영 「요구사항 검토 필요」로 남는다.
     *
     * <p>⛔ <b>완료가 아닐 때 상태를 되돌리지 마라.</b> 여기서 {@code REVIEW_REQUIRED} 로 쓰면
     * 분석 중이거나 오류인 접수를 검토 필요로 뒤집는 길이 난다 — <b>올라가는 쪽만</b> 만진다.
     */
    private void rollUpIntake(String requirementId) {
        Requirement requirement = requirements.selectById(requirementId).orElseThrow();
        if (requirements.countUndecidedByIntakeId(requirement.intakeId()) == 0) {
            intakes.updateRequirementState(requirement.intakeId(),
                    Intake.RequirementState.COMPLETED);
        }
    }
}
