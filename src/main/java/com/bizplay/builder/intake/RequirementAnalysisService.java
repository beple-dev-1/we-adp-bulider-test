package com.bizplay.builder.intake;

import com.bizplay.builder.id.IdSequence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요구사항 분석의 <b>DB 토막</b>. {@code claude} 를 띄우는 일은
 * {@link RequirementAnalysisWorker} 가 한다.
 *
 * <p>⛔ <b>여기에 프로세스를 띄우는 코드를 넣지 마라.</b> 트랜잭션을 연 채로 몇 분짜리 일을 하면
 * 커넥션을 그동안 물고 있는다 — {@code AiRunWorker} 주석이 같은 함정을 이미 적어 뒀다.
 */
@Service
public class RequirementAnalysisService {

    private final IntakeMapper intakes;
    private final RequirementMapper requirements;
    private final IdSequence ids;

    public RequirementAnalysisService(IntakeMapper intakes, RequirementMapper requirements,
                                      IdSequence ids) {
        this.intakes = intakes;
        this.requirements = requirements;
        this.ids = ids;
    }

    /**
     * 초안을 앉히고 접수를 <b>검토 필요</b>로 넘긴다. <b>한 트랜잭션이다.</b>
     *
     * <p>받은 문서 한 건에는 요구사항 한 건만 저장한다. 삭제 후 다시 분석하더라도
     * 프로젝트의 번호 카운터는 되돌리지 않으므로 이전 번호를 재사용하지 않는다.
     */
    @Transactional
    public void saveDraft(String projectId, String intakeId, RequirementDraftReader.Draft draft) {
        int number = requirements.allocateNumber(projectId);
        requirements.insert(Requirement.draft(
                ids.next(IdSequence.Kind.REQUIREMENT), projectId, intakeId, number,
                draft.title(), draft.body(), draft.screenHints()));
        intakes.updateRequirementState(intakeId, Intake.RequirementState.REVIEW_REQUIRED);
    }

    /**
     * 분석이 실패했다고 적는다.
     *
     * <p>⛔ <b>{@code NOT_STARTED} 로 되돌리지 마라.</b> 그러면 「해 봤는데 안 됐다」와
     * 「아직 안 했다」가 섞여서, 사람이 다시 눌러야 한다는 것을 화면이 말할 수 없게 된다.
     */
    @Transactional
    public void markFailed(String intakeId) {
        intakes.updateRequirementState(intakeId, Intake.RequirementState.FAILED);
    }
}
