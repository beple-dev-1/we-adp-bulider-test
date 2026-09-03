package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.devrequest.DevelopmentRequestContent.TestScenario;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdBackendChange;
import com.bizplay.builder.frd.FrdBackendChangeMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 개발요청서 테스트 시나리오 — 우리가 「무엇을 검증하나」를 먼저 적어 보낸다 (2026-08-27 병주 지시).
 *
 * <p>재는 것 넷 — ① 옛 스냅샷(시나리오 칸 없음)이 그대로 읽힌다 ② AI 출력을 규격에 안 맞으면 안 받는다
 * ③ 채운 시나리오가 {@code expected-back.md} 의 회신 양식에 TC 로 실리고 없으면 옛 빈 양식 그대로다
 * ④ 이미 있으면 덮지 않고, 전송 전 검증은 차단이 아니라 경고로만 말한다.
 *
 * <p>⚠ AI 를 실제로 돌리는 부분은 여기서 안 잰다 — 자격이 필요하다.
 */
class DevRequestTestScenarioTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired FrdBackendChangeMapper backendChanges;
    @Autowired DevelopmentRequestService service;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired DevRequestDocumentWriter writer;
    @Autowired DevRequestTestScenarioReader reader;
    @Autowired DevRequestTestScenarioWorker worker;
    @Autowired DevRequestPrecheck precheck;
    @Autowired ObjectMapper json;
    @MockitoBean ClaudeCredentialRunner credentialRunner;

    private static final String VALID = """
            {"scenarios":[
              {"kind":"INTEGRATION","targetSeq":1,"id":"TC-001","title":"임시저장을 누르면 목록에 남는다",
               "action":"작성 중인 문서에서 임시저장을 누른다","expected":"목록에 임시저장 상태로 문서가 보인다"},
              {"kind":"INTEGRATION","targetSeq":1,"id":"TC-002","title":"임시저장이 실패하면 안내가 뜬다",
               "condition":"임시저장 요청이 서버 오류로 끝난다 (mock)","dependency":"임시저장을 누르면 목록에 남는다",
               "action":"임시저장을 누른다","expected":"오류 안내가 뜨고 목록은 바뀌지 않는다"},
              {"kind":"UNIT","targetSeq":1,"id":"TC-003","title":"임시저장 API 가 문서를 저장한다",
               "action":"임시저장 API 를 호출한다","expected":"문서가 저장 상태로 기록된다"}
            ]}
            """;

    // ── 옛 모양 ────────────────────────────────────────────────────────────

    @Test
    void 시나리오_칸이_없는_옛_스냅샷은_빈_목록으로_읽힌다() throws Exception {
        String old = """
                {"summary":"요약","requirements":[],"screens":[],"backendChanges":[],
                 "notes":[{"kind":"ACCEPTANCE_CRITERION","content":"임시저장한 문서가 목록에 남는다"}]}
                """;
        DevelopmentRequestContent content = json.readValue(old, DevelopmentRequestContent.class);

        assertThat(content.testScenarios()).isEmpty();
        assertThat(content.hasTestScenarios()).isFalse();
        assertThat(content.acceptanceCriteria()).hasSize(1);
    }

    @Test
    void 시나리오를_채워도_다른_칸은_한_글자도_안_바뀐다() throws Exception {
        DevelopmentRequestContent before = json.readValue(
                "{\"summary\":\"요약\",\"notes\":[{\"kind\":\"ACCEPTANCE_CRITERION\",\"content\":\"완료 조건\"}]}",
                DevelopmentRequestContent.class);
        List<TestScenario> scenarios = reader.read(VALID, 1, 1);

        DevelopmentRequestContent after = before.withTestScenarios(scenarios);

        assertThat(after.summary()).isEqualTo(before.summary());
        assertThat(after.notes()).isEqualTo(before.notes());
        assertThat(after.testScenarios()).hasSize(3);
        // 저장하고 다시 읽어도 같다 — 스냅샷은 JSON 으로 산다.
        DevelopmentRequestContent reread = json.readValue(json.writeValueAsString(after),
                DevelopmentRequestContent.class);
        assertThat(reread.integrationScenarios(1)).extracting(TestScenario::id)
                .containsExactly("TC-001", "TC-002");
        assertThat(reread.unitScenarios(1)).extracting(TestScenario::id).containsExactly("TC-003");
    }

    // ── 읽는 규칙 ─────────────────────────────────────────────────────────

    @Test
    void 응답_JSON_에서_시나리오를_읽고_빈_칸은_널이다() throws Exception {
        List<TestScenario> read = reader.read("```json\n" + VALID + "\n```", 1, 1);

        assertThat(read).hasSize(3);
        assertThat(read.get(0).condition()).isNull();
        assertThat(read.get(0).dependency()).isNull();
        assertThat(read.get(1).condition()).endsWith("(mock)");
        assertThat(read.get(1).dependency()).isEqualTo("임시저장을 누르면 목록에 남는다");
    }

    @Test
    void 순번이_대상_범위를_벗어나면_통째로_거절한다() {
        // ⛔ 한 건만 버리면 개발이 「TC-002 는 어디 갔나」를 되묻는다.
        assertThatThrownBy(() -> reader.read(VALID, 0, 1)).hasMessageContaining("targetSeq");
        assertThatThrownBy(() -> reader.read(VALID.replace("\"targetSeq\":1,\"id\":\"TC-001\"",
                "\"targetSeq\":7,\"id\":\"TC-001\""), 1, 1)).hasMessageContaining("targetSeq");
    }

    @Test
    void TC_번호가_규격에_안_맞거나_겹치면_거절한다() {
        assertThatThrownBy(() -> reader.read(VALID.replace("TC-002", "TC-001"), 1, 1))
                .hasMessageContaining("겹칩니다");
        assertThatThrownBy(() -> reader.read(VALID.replace("TC-003", "3"), 1, 1))
                .hasMessageContaining("규격");
    }

    @Test
    void 행위나_결과가_비면_거절하고_종류가_틀리면_거절한다() {
        assertThatThrownBy(() -> reader.read(VALID.replace("\"action\":\"임시저장 API 를 호출한다\"",
                "\"action\":\"\""), 1, 1)).hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> reader.read(VALID.replace("\"kind\":\"UNIT\"", "\"kind\":\"E2E\""), 1, 1))
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> reader.read("{\"scenarios\":[]}", 1, 1)).hasMessageContaining("비어 있습니다");
    }

    // ── 회신 양식 ─────────────────────────────────────────────────────────

    @Test
    void 시나리오가_있으면_회신_양식에_TC_가_실리고_개발은_결과만_채운다() throws Exception {
        Project project = readyProject("시나리오-양식");
        String frdId = draftingFrd(project);
        DevelopmentRequest request = service.createFromCompletedFrd(project.getId(), frdId);
        assertThat(worker.save(request.id(), reader.read(VALID, 1, 1))).isTrue();
        DevelopmentRequestService.View view = service.read(project.getId(), request.id());

        String back = writer.writeExpectedBack(view, "key", "abc123",
                DevRequestExpectedBack.of(view.content()));

        assertThat(back)
                .contains("## 테스트 시나리오 안내")
                .contains("| 완료 조건 | TC |", "| 1. 임시저장한 문서가 목록에 남는다 | TC-001, TC-002 |")
                .contains("#### TC-001 — 임시저장을 누르면 목록에 남는다")
                .contains("- 조건: 임시저장 요청이 서버 오류로 끝난다 (mock)")
                .contains("- 의존: 임시저장을 누르면 목록에 남는다")
                .contains("- 행위: 작성 중인 문서에서 임시저장을 누른다")
                .contains("- 결과: 목록에 임시저장 상태로 문서가 보인다")
                .contains("#### TC-003 — 임시저장 API 가 문서를 저장한다")
                .contains("- 판정: `<성공 | 실패 | 미수행>`")
                // 빈 칸 양식은 시나리오가 있는 자리에서 사라진다.
                .doesNotContain("- 시나리오: `<검증할 사용자 흐름>`")
                .doesNotContain("- 수행 방법: `<테스트 종류·입력·조건>`");
    }

    @Test
    void 시나리오가_없으면_종전_빈_양식_그대로다() {
        Project project = readyProject("시나리오-없음");
        String frdId = draftingFrd(project);
        DevelopmentRequestService.View view = created(project, frdId);

        String back = writer.writeExpectedBack(view, "key", "abc123",
                DevRequestExpectedBack.of(view.content()));

        assertThat(back)
                .doesNotContain("## 테스트 시나리오 안내")
                .contains("- 시나리오: `<검증할 사용자 흐름>`")
                .contains("- 수행 방법: `<테스트 종류·입력·조건>`");
    }

    // ── 덮지 않는다 · 경고로만 ────────────────────────────────────────────

    @Test
    void 이미_시나리오가_있으면_AI_를_돌리지_않고_덮지_않는다() throws Exception {
        Project project = readyProject("시나리오-덮기");
        String frdId = draftingFrd(project);
        DevelopmentRequest request = service.createFromCompletedFrd(project.getId(), frdId);
        List<TestScenario> first = reader.read(VALID, 1, 1);
        assertThat(worker.save(request.id(), first)).isTrue();

        assertThat(worker.save(request.id(), first.subList(0, 1))).isFalse();
        assertThat(worker.generateNow(request.id())).isFalse();
        assertThat(service.read(project.getId(), request.id()).content().testScenarios()).hasSize(3);
    }

    @Test
    void 자격이_없으면_실패로_남고_전송은_막지_않는다() throws Exception {
        given(credentialRunner.run(anyString(), any(), any(), any(), anyList(), anyString(), any()))
                .willReturn(Optional.empty());
        Project project = readyProject("시나리오-자격없음");
        String frdId = draftingFrd(project);
        DevelopmentRequestService.View view = created(project, frdId);

        assertThat(worker.generateNow(view.request().id())).isFalse();
        assertThat(worker.hasFailed(view.request().id())).isTrue();
        assertThat(worker.requestIfMissing(view.request(), view.content()))
                .as("실패 뒤에는 열 때마다 다시 돌리지 않는다")
                .isFalse();

        var gate = precheck.check(view);
        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .doesNotContain("테스트 시나리오를 만들지 못했습니다.");
        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::message)
                .contains("테스트 시나리오를 만들지 못했습니다.");
    }

    @Test
    void 만들기를_청하면_끝날_때까지_만드는_중으로_읽힌다() {
        Project project = readyProject("시나리오-청함");
        String frdId = draftingFrd(project);
        DevelopmentRequestService.View view = created(project, frdId);

        assertThat(worker.isGenerating(view.request().id())).isFalse();
        worker.markRequested(view.request().id());
        assertThat(worker.isGenerating(view.request().id())).isTrue();
    }

    @Test
    void 없는_개발요청서에도_던지지_않는다() {
        assertThat(worker.generateNow("9999999")).isFalse();
    }

    @Test
    void 재료에는_순번이_그대로_박힌다() throws Exception {
        DevelopmentRequestContent content = json.readValue("""
                {"summary":"요약","backendChanges":[{"category":"API","categoryLabel":"API","target":"POST /drafts",
                 "changeDetail":"임시저장","verification":"저장 뒤 조회된다","required":true}],
                 "notes":[{"kind":"ACCEPTANCE_CRITERION","content":"임시저장한 문서가 목록에 남는다"}]}
                """, DevelopmentRequestContent.class);

        String material = DevRequestTestScenarioWorker.material(content);

        assertThat(material)
                .contains("### 1. POST /drafts", "- 판정 방법: 저장 뒤 조회된다")
                .contains("1. 임시저장한 문서가 목록에 남는다");
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private DevelopmentRequestService.View created(Project project, String frdId) {
        DevelopmentRequest request = service.createFromCompletedFrd(project.getId(), frdId);
        return service.read(project.getId(), request.id());
    }

    private String draftingFrd(Project project) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "전자결재 상신 임시저장 지원", "작성 중인 문서를 임시 저장할 수 있어야 한다.",
                planner().getId()));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), id, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "임시저장한 문서가 목록에 남는다", null));
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                id, 1, null, FrdBackendChange.Category.API, "POST /drafts", "임시저장 저장",
                null, "저장 뒤 조회된다", true, null));
        return id;
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
        return accounts.selectByLoginId("tsplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "tsplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
