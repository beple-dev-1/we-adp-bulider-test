package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdItem;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.ScreenMockupReader;
import com.bizplay.builder.frd.ScreenMockupService;
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발요청서 「생성중」 — 변경 예정 기능정의서를 AI 가 만드는 동안 목록은 생성 상태를 알리고,
 * 상세 상단은 개발요청 업무 상태인 「대기」를 유지한다. 상세의 생성 진행은 보내기 전 확인 영역이 맡다
 * (2026-08-26 개정).
 *
 * <p>⛔ <b>{@code DeliveryState} 에 값을 더하지 않는다.</b> 그 축은 「창구가 뭐라 답했나」이고 넷째 값 금지가
 * 걸려 있다. 「생성중」은 <b>보이는 상태</b>다 — {@code NOT_SENT} 이면서 만드는 중인 것을 화면이 그렇게 읽는다.
 * 생성 작업의 상태는 화면 이력에 남기되 제한 시간을 넘긴 진행 상태는 「생성중」으로 읽지 않는다.
 */
class DevRequestGeneratingStateTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdItemMapper items;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired ScreenMockupService mockups;
    @Autowired DevelopmentRequestService service;
    @Autowired DevRequestDeliveryMapper attempts;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired ScreenTobeDocumentWorker tobeDocuments;
    @Autowired IdSequence ids;

    @Test
    void 기능정의서를_만들어도_상세_상단은_개발요청_업무_상태를_유지한다() {
        Project project = readyProject("생성중-상세");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId);
        String requestId = service.createFromCompletedFrd(project.getId(), frdId).id();

        var before = service.read(project.getId(), requestId);
        assertThat(before.generating()).isFalse();
        assertThat(before.stateLabel()).isEqualTo("대기");

        tobeDocuments.markRequested(rowId);

        var during = service.read(project.getId(), requestId);
        assertThat(during.generating()).isTrue();
        assertThat(during.stateLabel()).isEqualTo("대기");
        assertThat(during.stateClass()).isEqualTo("status-badge--waiting");
    }

    @Test
    void 목록에도_생성중이_뜬다() {
        Project project = readyProject("생성중-목록");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId);
        String requestId = service.createFromCompletedFrd(project.getId(), frdId).id();
        tobeDocuments.markRequested(rowId);

        var row = service.list(project.getId()).stream()
                .filter(candidate -> candidate.request().id().equals(requestId)).findFirst().orElseThrow();

        assertThat(row.generating()).isTrue();
        assertThat(row.stateLabel()).isEqualTo("생성중");
    }

    @Test
    void 이미_보낸_것은_만드는_중이어도_생성중으로_읽지_않는다() {
        Project project = readyProject("생성중-전송완료");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId);
        String requestId = service.createFromCompletedFrd(project.getId(), frdId).id();
        requests.requestDelivery(requestId, null, null, null, null, null, null, null, null);
        assertThat(attempts.moveFromSending(requestId, DeliveryOutcome.SENT)).isEqualTo(1);
        tobeDocuments.markRequested(rowId);

        var view = service.read(project.getId(), requestId);
        assertThat(view.generating()).isFalse();
        assertThat(view.stateLabel()).isEqualTo("전송완료");
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private String generated(String frdId) {
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, "wv-appr-write", "결재 문서 작성",
                "wv-appr-write", null, "임시저장 버튼이 없습니다", "webview"));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup(
                "<!doctype html><html lang=\"ko\"><body>바뀐 화면</body></html>",
                List.of("임시저장 버튼을 추가한다")));
        return rowId;
    }

    private String draftingFrd(Project project) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "전자결재 상신 임시저장 지원", "작성 중인 문서를 임시 저장할 수 있어야 한다.",
                planner().getId()));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), id, 1, "임시저장을 지원한다",
                FrdItem.Nature.DEVELOP, FrdItem.Verdict.SCREEN, "wv-appr-write", null, null));
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), id, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "임시저장한 문서가 목록에 남는다", null));
        return id;
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/" + name + ".git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account planner() {
        return accounts.selectByLoginId("drgenerating").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "drgenerating", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
