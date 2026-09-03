package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.checker.CheckReport;
import com.bizplay.builder.checker.CheckerCommand;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdItem;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 검사기를 <b>언제</b> 돌리나 — 병주 지시 2026-08-25.
 *
 * <p>⭐ <b>「화면 들어갈 때마다 검증하지 마라」.</b> 개발요청서 상세는 열 때마다 기획 문서 자동 점검을 걸었고,
 * 결과가 10분마다 만료돼 같은 화면이 열 때마다 다르게 보였다. 상세는 <b>DB 로 아는 것만</b> 말하고,
 * 검사기는 <b>전송을 누를 때</b> 한 번 돈다 — 그때는 사람이 기다릴 각오가 된 자리다.
 */
class DevRequestPrecheckTimingTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdItemMapper items;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired DevelopmentRequestService service;
    @Autowired ProjectPaths paths;

    @MockitoBean CheckerCommand checker;

    @Test
    void 상세를_열_때는_검사기를_돌리지_않는다() {
        Mockito.when(checker.run(any(), any())).thenReturn(CheckReport.unknown());
        String requestId = deliverable("검사시점-상세");
        String projectId = project(requestId);
        worktreeOf(projectId, requestId);

        var gate = service.precheck(projectId, requestId);
        service.precheck(projectId, requestId);
        service.progress(projectId, requestId);

        Mockito.verify(checker, Mockito.never()).run(any(), any());
        // ⭐ 「점검 중」도 안 뜬다 — 돌리지 않으니 기다릴 것이 없다.
        assertThat(gate.checking()).isFalse();
        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::subject)
                .noneMatch(subject -> subject.startsWith("기획 문서 자동 점검"));
        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::subject)
                .noneMatch(subject -> subject.startsWith("기획 문서 자동 점검"));
    }

    @Test
    void 전송을_누를_때는_검사기가_돈다() {
        Mockito.when(checker.run(any(), any())).thenReturn(CheckReport.unknown());
        String requestId = deliverable("검사시점-전송");
        String projectId = project(requestId);
        worktreeOf(projectId, requestId);

        // 창구 설정이 없어 「전송중」에 머물지만, 게이트는 그 앞에서 이미 돌았다.
        service.requestDelivery(projectId, requestId, null, null, null, null, null);

        Mockito.verify(checker, Mockito.atLeastOnce()).run(any(), any());
        assertThat(requests.selectById(requestId).deliveryState())
                .isEqualTo(DevelopmentRequest.DeliveryState.SENDING);
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    /** 검사 대상이 되려면 워크트리 폴더가 있어야 한다 — 없으면 검사기를 아예 안 부른다(정상). */
    private void worktreeOf(String projectId, String requestId) {
        String frdId = requests.selectById(requestId).frdId();
        try {
            Files.createDirectories(paths.frdWorktree(projectId, frdId));
            Files.writeString(paths.frdWorktree(projectId, frdId).resolve("표시.txt"), "있다",
                    StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private String project(String requestId) {
        return requests.selectById(requestId).projectId();
    }

    private String deliverable(String name) {
        Project project = readyProject(name);
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "결재 배치 시간 변경", "정산 배치 실행 시간을 바꿔야 한다.", planner().getId()));
        frds.updateAfterPick(frdId, "결재 배치 시간 변경", "webview", null, Frd.State.PICKED, null);
        frds.updateState(frdId, Frd.State.DRAFTING);
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1,
                "정산 배치 시간을 바꾼다", FrdItem.Nature.DEVELOP, FrdItem.Verdict.NO_SCREEN,
                null, null, null));
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), frdId, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "배치가 바뀐 시간에 돈다", null));
        return service.createFromCompletedFrd(project.getId(), frdId).id();
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account planner() {
        return accounts.selectByLoginId("timingplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "timingplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
