package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 「변경 예정 기능정의서」 — 계획 9 Task 5.
 *
 * <p>⭐ 여기서 재는 것 둘 — ① <b>이미 있는 것을 덮지 않는다</b>(사람이 대화로 만든 것이 더 세다)
 * ② AI 출력을 <b>규격에 안 맞으면 안 받는다</b>(사과문을 계약서에 싣지 않는다).
 *
 * <p>⚠ AI 를 실제로 돌리는 부분은 여기서 안 잰다 — 자격이 필요하다. 잴 수 있는 것은
 * <b>덮지 않는 것</b>과 <b>읽는 규칙</b>이고, 둘이 이 Task 의 계약이다.
 */
class ScreenTobeDocumentTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdScreenHistoryMapper histories;
    @Autowired ScreenMockupService mockups;
    @Autowired ScreenTobeDocumentReader reader;
    @Autowired ScreenTobeDocumentWorker worker;
    @MockitoBean ClaudeCredentialRunner credentialRunner;

    private static final String MD = """
            --- 꼬리표 ---
            id: wv-appr-write / system: webview / 기능: 결재 > 작성

            --- 화면명세 ---
            화면명: 결재 문서 작성
            """;

    // ── 덮지 않는다 ────────────────────────────────────────────────────────

    @Test
    void 이미_기능정의서가_있으면_덮지_않는다() {
        String screenId = generatedScreen();
        long historyId = histories.selectLatestByScreenId(screenId).id();
        assertThat(histories.fillMd(historyId, MD)).isEqualTo(1);

        // ⛔ 캔버스 AI 가 사람과 대화로 만든 것이 더 세다 — 조건부 갱신이 구조로 지킨다.
        assertThat(histories.fillMd(historyId, "덮으려는 다른 글\n---\n")).isZero();
        assertThat(histories.selectLatestByScreenId(screenId).md()).isEqualTo(MD);
    }

    @Test
    void 이미_있는_화면에는_AI_를_돌리지_않는다() {
        String screenId = generatedScreen();
        histories.fillMd(histories.selectLatestByScreenId(screenId).id(), MD);

        // ⚠ 자격이 없어도 여기까지 오기 전에 건너뛴다 — 그것이 이 시험의 요지다.
        assertThat(worker.generateNow(screenId)).isFalse();
    }

    @Test
    void 수정한_화면이_없으면_돌리지_않는다() {
        Project project = readyProject("기능정의서-미작업");
        String frdId = draftingFrd(project);
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, "wv-appr-write", "결재 문서 작성",
                "wv-appr-write", null, "버튼을 추가한다", "webview"));

        assertThat(worker.generateNow(rowId)).isFalse();
    }

    @Test
    void 만들기를_청하면_끝날_때까지_만드는_중으로_읽힌다() {
        String rowId = generatedScreen();

        // ⭐ 청한 순간부터 「만드는 중」이다 — 비동기 일꾼이 아직 안 깨어났어도 화면이 「없다」로 읽으면 안 된다
        //    (2026-08-25 병주 실측: 완료 직후 개발요청서 상세가 「없습니다」를 띄웠다).
        assertThat(worker.isGenerating(rowId)).isFalse();
        worker.markRequested(rowId);
        var requested = histories.selectTobeDocumentStatus(histories.selectLatestByScreenId(rowId).id());
        assertThat(requested.state())
                .isEqualTo("REQUESTED");
        assertThat(requested.updatedAt()).isNotNull();
        assertThat(worker.isGenerating(rowId)).isTrue();

        worker.generateNow(rowId);
        assertThat(worker.isGenerating(rowId)).isFalse();
        assertThat(histories.selectTobeDocumentStatus(histories.selectLatestByScreenId(rowId).id()).state())
                .as("실패도 사라지지 않고 다음 화면에서 설명할 수 있어야 한다")
                .isEqualTo("FAILED");
    }

    @Test
    void 없는_화면에도_던지지_않는다() {
        // ⛔ void @Async 의 예외는 로그만 남는다 — 던지면 조용히 사라진다.
        assertThat(worker.generateNow("9999999")).isFalse();
    }

    // ── 읽는 규칙 ─────────────────────────────────────────────────────────

    @Test
    void 응답_JSON_에서_기능정의서를_읽는다() throws Exception {
        String read = reader.read("{\"md\":" + quoted(MD) + "}");

        assertThat(read).contains("화면명: 결재 문서 작성");
    }

    @Test
    void 코드_울타리로_감싼_응답도_읽는다() throws Exception {
        String read = reader.read("```json\n{\"md\":" + quoted(MD) + "}\n```");

        assertThat(read).contains("--- 화면명세 ---");
    }

    @Test
    void 빈_기능정의서는_거절한다() {
        // ⛔ 「못 만들었다」를 글로 받지 않는다 — 저장하면 계약서가 사과문을 싣는다.
        assertThatThrownBy(() -> reader.read("{\"md\":\"\"}"))
                .hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> reader.read("{\"md\":null}"))
                .hasMessageContaining("비어 있습니다");
    }

    @Test
    void 화면_md_규격이_아니면_거절한다() {
        assertThatThrownBy(() -> reader.read("{\"md\":\"죄송합니다. 만들 수 없었습니다.\"}"))
                .hasMessageContaining("블록 구분");
    }

    @Test
    void 너무_긴_기능정의서는_거절한다() {
        String tooLong = "---\n" + "가".repeat(ScreenTobeDocumentReader.MAX_LENGTH);

        assertThatThrownBy(() -> reader.read("{\"md\":" + quoted(tooLong) + "}"))
                .hasMessageContaining("넘습니다");
    }

    @Test
    void 기능정의서_AI_응답은_JSON_스키마로_강제한다() {
        assertThat(ScreenTobeDocumentWorker.claudeArgs(java.nio.file.Path.of("input")))
                .containsSubsequence("--json-schema", ScreenTobeDocumentWorker.OUTPUT_SCHEMA);
        assertThat(ScreenTobeDocumentWorker.OUTPUT_SCHEMA)
                .as("덧붙인 칸 하나로 실행이 통째로 거절되는 것을 막는다")
                .doesNotContain("additionalProperties");
    }

    @Test
    void 기능정의서_생성은_낮은_추론_수준으로_읽기_도구만_쓴다() {
        var args = ScreenTobeDocumentWorker.claudeArgs(java.nio.file.Path.of("input"));
        assertThat(args)
                .containsSubsequence("--model", "sonnet")
                .containsSubsequence("--effort", "low")
                .containsSubsequence("--permission-mode", "dontAsk")
                .doesNotContain("Edit", "Write");
        assertThat(args.indexOf("--add-dir"))
                .as("값을 여러 개 받는 --add-dir 은 맨 뒤다")
                .isEqualTo(args.size() - 2);
    }

    /** ⚠ as-is 정의서가 없는 새 화면 시험이라 응답 md 에 IA 블록이 있어야 한다 — 종전에는 다른 시험이 남긴 클론 파일에 기대 통과했다(2026-08-27). */
    @Test
    void JSON_형식이_아닌_응답은_한_번_다시_요청한다() throws Exception {
        String rowId = generatedScreen();
        given(credentialRunner.run(anyString(), any(), any(), any(), anyList(), anyString(), any(), any()))
                .willReturn(Optional.of(new ClaudeResult(0, false, null, null,
                        "변경 예정 기능정의서를 작성했습니다.")))
                .willReturn(Optional.of(new ClaudeResult(0, false, null, null,
                        "{\"md\":" + quoted(MD + "\n--- IA ---\n- 종류: 화면\n") + "}")));

        assertThat(worker.generateNow(rowId)).isTrue();
        assertThat(histories.selectLatestByScreenId(rowId).md()).contains("화면명: 결재 문서 작성");
        verify(credentialRunner, times(2)).run(
                anyString(), any(), any(), any(), anyList(), anyString(), any(), any());
    }

    @Test
    void 신규_화면_기능정의서는_IA_화면_블록을_가져야_한다() {
        assertThat(ScreenTobeDocumentWorker.hasIaBlock("""
                --- IA ---
                - 종류: 화면
                """)).isTrue();
        assertThat(ScreenTobeDocumentWorker.hasIaBlock("""
                --- IA ---
                - 종류: 화면 / 상위화면: wv-main-home
                """)).isTrue();
        assertThat(ScreenTobeDocumentWorker.hasIaBlock("""
                --- 화면명세 ---
                화면명: 새 화면
                """)).isFalse();
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    private static String quoted(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String generatedScreen() {
        Project project = readyProject("기능정의서-" + ids.next(IdSequence.Kind.FRD_SCREEN));
        String frdId = draftingFrd(project);
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, "wv-appr-write", "결재 문서 작성",
                "wv-appr-write", null, "버튼을 추가한다", "webview"));
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
        return accounts.selectByLoginId("tobeplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "tobeplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
