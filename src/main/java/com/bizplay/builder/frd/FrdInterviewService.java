package com.bizplay.builder.frd;

import com.bizplay.builder.id.IdSequence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** FRD 인터뷰의 질문·답변과 최종 분석 결과를 한 상태 전이로 저장한다. */
@Service
public class FrdInterviewService {

    private final FrdMapper frds;
    private final FrdInterviewMessageMapper messages;
    private final FrdBackendChangeMapper backendChanges;
    private final FrdAnalysisNoteMapper notes;
    private final ScreenPickService picks;
    private final IdSequence ids;

    public FrdInterviewService(FrdMapper frds, FrdInterviewMessageMapper messages,
                               FrdBackendChangeMapper backendChanges, FrdAnalysisNoteMapper notes,
                               ScreenPickService picks, IdSequence ids) {
        this.frds = frds;
        this.messages = messages;
        this.backendChanges = backendChanges;
        this.notes = notes;
        this.picks = picks;
        this.ids = ids;
    }

    @Transactional(readOnly = true)
    public List<FrdInterviewMessage> messages(String frdId) {
        return messages.selectByFrdId(frdId);
    }

    /** 마지막 기획자 추가 메시지 이후 AI가 물은 질문 수. 새 인터뷰를 시작하면 다시 0부터 센다. */
    @Transactional(readOnly = true)
    public int currentQuestionRound(String frdId) {
        int count = 0;
        for (FrdInterviewMessage message : messages.selectByFrdId(frdId)) {
            if (message.role() == FrdInterviewMessage.Role.USER
                    && message.kind() == FrdInterviewMessage.Kind.MESSAGE) {
                count = 0;
            } else if (message.role() == FrdInterviewMessage.Role.AI
                    && message.kind() == FrdInterviewMessage.Kind.QUESTION) {
                count++;
            }
        }
        return count;
    }

    @Transactional(readOnly = true)
    public List<FrdBackendChange> backendChanges(String frdId) {
        return backendChanges.selectByFrdId(frdId);
    }

    @Transactional(readOnly = true)
    public List<FrdAnalysisNote> notes(String frdId) {
        return notes.selectByFrdId(frdId);
    }

    @Transactional(readOnly = true)
    public String transcript(String frdId) {
        StringBuilder text = new StringBuilder("# 지금까지의 요구사항 인터뷰\n\n");
        List<FrdInterviewMessage> all = messages.selectByFrdId(frdId);
        if (all.isEmpty()) {
            return text.append("아직 질문과 답변이 없다.\n").toString();
        }
        for (FrdInterviewMessage message : all) {
            text.append(message.role() == FrdInterviewMessage.Role.AI ? "AI" : "사용자")
                    .append(" · ").append(message.kind()).append(": ")
                    .append(message.content()).append("\n");
        }
        return text.toString();
    }

    @Transactional
    public void saveQuestion(String frdId, FrdInterviewReader.Question question) {
        Frd frd = requireAnalyzing(frdId);
        int seq = messages.selectNextSeq(frdId);
        if (question.analysisSummary() != null && !question.analysisSummary().isBlank()) {
            messages.insert(FrdInterviewMessage.summary(
                    ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frd.id(), seq++, question.analysisSummary()));
        }
        messages.insert(FrdInterviewMessage.message(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frd.id(), seq++, FrdInterviewMessage.Role.AI,
                conversationalMessage(question.assistantMessage(),
                        "확인한 내용을 바탕으로 한 가지만 더 여쭤볼게요.")));
        messages.insert(FrdInterviewMessage.question(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frd.id(), seq,
                question.topic(), question.text(), question.reason(), question.options()));
        frds.updateState(frdId, Frd.State.WAITING_ANSWER);
    }

    @Transactional
    public void answer(String frdId, String questionId, String answer) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.WAITING_ANSWER) {
            throw new IllegalStateException("지금은 답변을 받을 수 없습니다.");
        }
        FrdInterviewMessage question = messages.selectLatestQuestion(frdId);
        if (question == null || !question.id().equals(questionId)) {
            throw new IllegalStateException("이미 지난 질문입니다. 최신 질문을 확인해 주세요.");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("답변을 선택하거나 직접 입력해 주세요.");
        }
        messages.insert(FrdInterviewMessage.answer(ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE),
                frdId, messages.selectNextSeq(frdId), answer.strip()));
        frds.updateState(frdId, Frd.State.ANALYZING);
    }

    @Transactional
    public void requestMoreQuestions(String frdId) {
        continueWithMessage(frdId, "분석 결과를 확정하기 전에 추가로 확인할 질문을 해주세요.");
    }

    /** 개발 범위 확인에서 기존 분석 결과를 보존한 채 인터뷰 결과 화면으로 돌아간다. */
    @Transactional
    public void reopen(String frdId) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.SCOPE_REVIEW) {
            throw new IllegalStateException("개발 범위를 확인하는 중에만 인터뷰로 돌아갈 수 있습니다.");
        }
        frds.updateState(frdId, Frd.State.PICKED);
    }

    @Transactional
    public void continueWithMessage(String frdId, String content) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.PICKED) {
            throw new IllegalStateException("분석 결과를 확인하는 중에만 인터뷰를 계속할 수 있습니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("궁금한 내용이나 추가 조건을 입력해 주세요.");
        }
        messages.insert(FrdInterviewMessage.message(ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE),
                frdId, messages.selectNextSeq(frdId), FrdInterviewMessage.Role.USER, content.strip()));
        frds.updateState(frdId, Frd.State.ANALYZING);
    }

    @Transactional
    public void saveResult(String frdId, FrdInterviewReader.Result result) {
        requireAnalyzing(frdId);
        String summary = result.analysisSummary();
        if (summary == null || summary.isBlank()) {
            summary = "요구사항을 %d개 항목으로 나누고, 수정할 프론트 화면 %d개와 백엔드 범위 %d개를 확인했습니다."
                    .formatted(result.pick().items().size(), result.pick().screens().size(),
                            result.backendChanges().size());
        }
        messages.insert(FrdInterviewMessage.summary(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frdId,
                messages.selectNextSeq(frdId), summary));
        messages.insert(FrdInterviewMessage.message(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frdId,
                messages.selectNextSeq(frdId), FrdInterviewMessage.Role.AI,
                conversationalMessage(result.assistantMessage(),
                        "말씀해 주신 내용을 반영해 작업 범위를 정리했습니다.")));
        picks.savePick(frdId, result.pick());

        backendChanges.deleteByFrdId(frdId);
        int seq = 0;
        for (FrdInterviewReader.BackendChange change : result.backendChanges()) {
            backendChanges.insert(new FrdBackendChange(
                    ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE), frdId, ++seq,
                    change.requirementSeq(), change.category(), change.target(),
                    change.changeDetail(), change.evidence(), change.verification(),
                    change.required(), null));
        }

        notes.deleteByFrdId(frdId);
        saveNotes(frdId, FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, result.acceptanceCriteria());
        saveNotes(frdId, FrdAnalysisNote.Kind.OPEN_ISSUE, result.openIssues());
        FrdAnalysisNote.Kind workModeKind = result.workMode() == FrdInterviewReader.WorkMode.FAST_TRACK
                ? FrdAnalysisNote.Kind.WORK_MODE_FAST_TRACK : FrdAnalysisNote.Kind.WORK_MODE_FRD;
        saveNotes(frdId, workModeKind, List.of(result.workModeReason()));
    }

    private void saveNotes(String frdId, FrdAnalysisNote.Kind kind, List<String> contents) {
        int seq = 0;
        for (String content : contents) {
            notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE),
                    frdId, ++seq, kind, content, null));
        }
    }

    private String conversationalMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message.strip();
    }

    private Frd requireAnalyzing(String frdId) {
        Frd frd = frds.selectById(frdId);
        if (frd == null || frd.state() != Frd.State.ANALYZING) {
            throw new IllegalStateException("지금은 요구사항 분석 결과를 저장할 수 없습니다.");
        }
        return frd;
    }
}
