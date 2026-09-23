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
 * 받기가 DB 에 남기는 두 가지 — <b>수신 이력</b>과 <b>TC 번호별 테스트 결과</b> — 를 실제 DB 로 돈다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md} 「줄이는 장치 셋」의
 * <b>수신 이력 조회</b> — 「무엇이 언제 어느 배치로 들어왔나」를 사람이 짚는 자리다.
 * ⭐ 설계는 「세 번째가 없으면 나머지 둘도 값이 반이다」라고 적었다 — 잘못 들어온 것을 짚을 자리가
 * 없으면 「덮어쓰기 + git 이력」이 되돌릴 길이라는 말이 공허해진다.
 */
class DevRequestReceiptMapperTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdItemMapper items;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired DevelopmentRequestService requests;
    @Autowired DevRequestReceiptMapper receipts;
    @Autowired DevRequestTestResultMapper testResults;

    /** ⭐ 거절도 남긴다 — 무엇 때문에 몇 번 떨어졌는지가 개발과 말을 맞출 근거다. 새것이 위다. */
    @Test
    void 받기를_누를_때마다_한_줄씩_남기고_새것이_위다() {
        String requestId = sentRequest();
        Account me = planner();

        receipts.insert(DevRequestReceipt.rejected(requestId, "aaaaaaa",
                List.of("갈라 온 기준이 기본 브랜치 이력에 없습니다", "회신서가 없습니다"), me.getId()));
        receipts.insert(DevRequestReceipt.accepted(requestId, "bbbbbbb", "ccccccc", 6, me.getId()));

        List<DevRequestReceipt> rows = receipts.selectByRequestId(requestId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).outcome()).isEqualTo(DevRequestReceipt.ACCEPTED);
        assertThat(rows.get(0).returnedHead()).isEqualTo("bbbbbbb");
        assertThat(rows.get(0).receiveCommit()).isEqualTo("ccccccc");
        assertThat(rows.get(0).testRows()).isEqualTo(6);
        assertThat(rows.get(0).accountId()).isEqualTo(me.getId());
        assertThat(rows.get(0).receivedAt()).isNotNull();
        assertThat(rows.get(1).outcome()).isEqualTo(DevRequestReceipt.REJECTED);
        assertThat(rows.get(1).rejectionList()).containsExactly(
                "갈라 온 기준이 기본 브랜치 이력에 없습니다", "회신서가 없습니다");
    }

    /** ⚠ 이미 받은 판을 다시 누른 것도 남긴다 — 「두 번 눌렀다」가 이력에서 보여야 한다. */
    @Test
    void 이미_받은_판을_다시_누른_것도_남긴다() {
        String requestId = sentRequest();

        receipts.insert(DevRequestReceipt.already(requestId, "bbbbbbb", "ccccccc", 6, null));

        DevRequestReceipt row = receipts.selectByRequestId(requestId).get(0);
        assertThat(row.outcome()).isEqualTo(DevRequestReceipt.ALREADY);
        assertThat(row.accountId()).isNull();
    }

    /** ⭐ 같은 TC 를 다시 받으면 그 줄을 갈아 낀다 — 두 판이 남으면 어느 것이 마지막인지 모른다. */
    @Test
    void 같은_TC_를_다시_받으면_그_줄을_갈아_낀다() {
        String requestId = sentRequest();

        testResults.upsert(requestId, new TestResultReader.Result("INTEGRATION", "TC-101", "열린다",
                "안 열렸다", TestResultReader.FAIL, "실패", "캡처"));
        testResults.upsert(requestId, new TestResultReader.Result("INTEGRATION", "TC-101", "열린다",
                "열렸다", TestResultReader.PASS, "통과", "녹화"));

        List<DevRequestTestResult> rows = testResults.selectByRequestId(requestId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).verdict()).isEqualTo(TestResultReader.PASS);
        assertThat(rows.get(0).actual()).isEqualTo("열렸다");
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private String sentRequest() {
        Project project = readyProject("수신-" + System.nanoTime());
        String frdId = completedFrd(project);
        return requests.createFromCompletedFrd(project.getId(), frdId).id();
    }

    private String completedFrd(Project project) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "회원가입 이메일 선택", "본인인증에서 이메일을 고를 수 있어야 한다.", planner().getId()));
        frds.updateAfterPick(id, "회원가입 이메일 선택", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), id, 1, "이메일을 고른다",
                FrdItem.Nature.DEVELOP, FrdItem.Verdict.SCREEN, "wv-join", null, null));
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), id, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "이메일을 고르면 칸에 든다", null));
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
        return accounts.selectByLoginId("drreceipt").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "drreceipt", "김기획",
                    "planner@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
