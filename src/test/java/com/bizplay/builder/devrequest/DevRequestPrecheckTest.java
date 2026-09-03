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
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.ScreenMockupReader;
import com.bizplay.builder.frd.ScreenMockupService;
import com.bizplay.builder.frd.TemporaryScreenId;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 전송 전 검증이 <b>차단과 경고를 제대로 가르나</b>.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⭐ <b>가르는 값이 여기서 증명된다</b> — 전부 차단이면 애매한 건이 영영 못 나가고,
 * 전부 경고면 개발이 못 쓰는 것이 계약에 실려 나간다.
 */
class DevRequestPrecheckTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdItemMapper items;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdScreenHistoryMapper histories;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired ScreenMockupService mockups;
    @Autowired ScreenStandardIdMapper standardIds;
    @Autowired DevelopmentRequestService service;
    @Autowired ScreenTobeDocumentWorker tobeDocuments;
    @Autowired ProjectPaths paths;

    // ── 차단 ──────────────────────────────────────────────────────────────

    @Test
    void 신규_화면은_임시ID_대신_개발용_화면ID를_받는다() {
        Project project = readyProject("검증-임시ID");
        String frdId = draftingFrd(project);
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, TemporaryScreenId.of(rowId),
                "새 화면", null, null, "새로 만든다", "webview"));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup("<html></html>", List.of("만든다")));
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        var screen = service.read(project.getId(), requestId).content().screens().get(0);
        assertThat(screen.deliveryScreenId()).doesNotStartWith("tmp-");
        assertThat(screen.deliveryFileName()).isEqualTo(screen.deliveryScreenId() + ".html");
        // 연결 안내가 없는 것은 별도 계약으로 막되, 임시 ID나 관리번호 때문에 막지는 않는다.
        assertThat(gate.sendable()).isFalse();
        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .contains("신규 화면의 연결 안내가 없습니다.")
                .noneMatch(message -> message.contains("임시 이름") || message.contains("관리번호"));
        assertThatThrownBy(() -> service.requestDelivery(project.getId(), requestId, null,
                null, null, null, null)).isInstanceOf(IllegalStateException.class);
    }

    // ── 신규 화면 채번 (2026-08-22 설계 · 2026-08-25 구현) ─────────────────────

    /**
     * ⭐ 설계({@code new-screen-id-design}) — 표준 화면ID 는 <b>개발요청서를 만들 때</b> 난다. 재료는
     * 기준 화면의 시스템·업무영역·기능그룹 + 이 화면의 유형 + 그 묶음의 {@code max + 1}. 설계만 있고 구현이
     * 없어 신규 화면 DR 이 영영 「임시 이름」으로 막혀 있었다(2026-08-25 DR-012 실물).
     */
    @Test
    void 신규_화면은_개발요청서를_만들_때_기준_화면의_묶음에서_표준_화면ID_를_받는다() {
        Project project = readyProject("검증-신규채번");
        String frdId = draftingFrd(project);
        standard(project, "wv-appr-write");
        String rowId = newScreen(frdId, "결재 상세", "wv-appr-write", "상세");
        String requestId = created(project, frdId);

        var minted = standardIds.selectByProject(project.getId()).stream()
                .filter(row -> row.screenId().equals(TemporaryScreenId.of(rowId))).findFirst();
        assertThat(minted).isPresent();
        // 기준 화면 묶음 PS-WV-APR-010 + 유형 「상세」 D + 그 묶음의 첫 번호
        assertThat(minted.get().standardId()).isEqualTo("PS-WV-APR-010-D01");
        assertThat(minted.get().origin()).isEqualTo(ScreenStandardId.Origin.N);

        var gate = service.precheck(project.getId(), requestId);
        // ⭐ screen_id 는 tmp- 그대로다(설계) — 표준 ID 짝이 있으면 임시 이름이 막을 이유가 없다.
        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .noneMatch(message -> message.contains("임시 이름") || message.contains("표준 화면 ID"));
        assertThat(service.read(project.getId(), requestId).standardScreenId(TemporaryScreenId.of(rowId)))
                .isEqualTo("PS-WV-APR-010-D01-N");
    }

    @Test
    void 같은_묶음의_둘째_신규_화면은_다음_번호를_받고_다시_만들어도_번호가_안_바뀐다() {
        Project project = readyProject("검증-신규채번둘");
        String frdId = draftingFrd(project);
        standard(project, "wv-appr-write");
        // 유형을 안 고른 신규 화면은 기준 화면의 유형 글자(L)를 물려받는다 — 기준이 L01 이니 L02 다.
        String first = newScreen(frdId, "결재 목록 2", "wv-appr-write", null);
        String second = newScreen(frdId, "결재 목록 3", "wv-appr-write", "목록");
        String requestId = created(project, frdId);

        assertThat(standardOf(project, TemporaryScreenId.of(first))).isEqualTo("PS-WV-APR-010-L02");
        assertThat(standardOf(project, TemporaryScreenId.of(second))).isEqualTo("PS-WV-APR-010-L03");

        // 되돌린 뒤 다시 완료해도 이미 박힌 번호는 그대로다 — 개발요청서에 찍혀 나간 번호가 바뀌면 안 된다.
        service.returnToFrd(project.getId(), requestId);
        frds.updateState(frdId, Frd.State.DRAFTING); // 워크트리 없는 시험이라 되돌리기가 개발 범위 확인으로 간다 — 상태만 맞춘다
        created(project, frdId);
        assertThat(standardOf(project, TemporaryScreenId.of(first))).isEqualTo("PS-WV-APR-010-L02");
        assertThat(standardIds.selectByProject(project.getId()).stream()
                .filter(row -> row.screenId().equals(TemporaryScreenId.of(first))).count()).isEqualTo(1);
    }

    /**
     * ⭐ 실물 (2026-08-25 FRD-035) — 신규 화면 여섯이 <b>다른 신규 화면</b>({@code tmp-0000053})을 기준으로
     * 만들어졌고(복사본의 복사본까지), 그 기준은 채번됐는데 이것들은 안 됐다. 기준의 기준까지 따라가야 한다.
     */
    @Test
    void 기준이_다른_신규_화면이어도_그_사슬을_따라_채번된다() {
        Project project = readyProject("검증-신규사슬");
        String frdId = draftingFrd(project);
        standard(project, "wv-appr-write");
        String first = newScreen(frdId, "결재 상세", "wv-appr-write", "상세");
        String second = newScreen(frdId, "결재 상세 복사본", TemporaryScreenId.of(first), null);
        String third = newScreen(frdId, "복사본의 복사본", TemporaryScreenId.of(second), null);
        created(project, frdId);

        assertThat(standardOf(project, TemporaryScreenId.of(first))).isEqualTo("PS-WV-APR-010-D01");
        assertThat(standardOf(project, TemporaryScreenId.of(second))).isEqualTo("PS-WV-APR-010-D02");
        assertThat(standardOf(project, TemporaryScreenId.of(third))).isEqualTo("PS-WV-APR-010-D03");
    }

    /** IA 기준 화면 없이 같은 시스템이라는 이유만으로 임의의 표준ID 묶음을 빌리지 않는다. */
    @Test
    void IA_기준_화면이_비어_있으면_다른_화면을_짐작해_채번하지_않는다() {
        Project project = readyProject("검증-신규기준빈");
        String frdId = draftingFrd(project);
        standard(project, "wv-appr-write");
        generated(frdId, "wv-appr-write", "결재 문서 작성");
        String orphan = newScreen(frdId, "결재 목록", null, "목록");
        created(project, frdId);

        assertThat(standardOf(project, TemporaryScreenId.of(orphan))).isNull();
    }

    @Test
    void 기준_화면에_화면_관리번호가_없어도_개발요청은_막히지_않는다() {
        Project project = readyProject("검증-신규기준없음");
        String frdId = draftingFrd(project);
        String rowId = newScreen(frdId, "결재 상세", "wv-appr-write", "상세");
        histories.fillMd(histories.selectLatestByScreenId(rowId).id(), "변경 예정 기능정의");
        String requestId = created(project, frdId);

        assertThat(standardIds.selectByProject(project.getId())).isEmpty();
        var gate = service.precheck(project.getId(), requestId);
        assertThat(gate.sendable()).isTrue();
        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("화면 관리번호"));
        assertThat(service.read(project.getId(), requestId).content().screens().get(0).deliveryScreenId())
                .doesNotStartWith("tmp-");
    }

    @Test
    void 예약한_개발화면ID가_기존_화면과_겹치면_전송을_막는다() throws Exception {
        Project project = readyProject("검증-개발화면ID 충돌");
        String frdId = draftingFrd(project);
        String rowId = newScreen(frdId, "결재 상세", "wv-appr-write", "상세");
        histories.fillMd(histories.selectLatestByScreenId(rowId).id(), "변경 예정 기능정의서");
        String requestId = created(project, frdId);
        var delivered = service.read(project.getId(), requestId).content().screens().get(0);
        var occupied = paths.cloneDir(project.getId()).resolve("core/webview/pages")
                .resolve(delivered.deliveryFileName());
        Files.createDirectories(occupied.getParent());
        Files.writeString(occupied, "<html></html>");

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .contains("개발용 화면 ID가 기존 화면과 겹칩니다.");
        assertThat(gate.sendable()).isFalse();
    }

    @Test
    void 기존_화면의_화면_관리번호가_없어도_경고만_표시한다() {
        Project project = readyProject("검증-표준ID없음");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId, "wv-appr-write", "결재 문서 작성");
        histories.fillMd(histories.selectLatestByScreenId(rowId).id(), "변경 예정 기능정의서");
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.sendable()).isTrue();
        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("화면 관리번호"));
    }

    @Test
    void 미작업_화면이_있으면_전송이_막힌다() {
        Project project = readyProject("검증-미작업");
        String frdId = draftingFrd(project);
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, "wv-appr-write", "결재 문서 작성",
                "wv-appr-write", null, "버튼을 추가한다", "webview"));
        standard(project, "wv-appr-write");
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("수정한 화면이 아직 없습니다"));
    }

    @Test
    void 개발_범위가_없으면_전송이_막힌다() {
        Project project = readyProject("검증-범위0");
        String frdId = draftingFrd(project, FrdItem.Nature.OPERATE);
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("개발이 필요한 항목이 없습니다"));
    }

    // ── 경고 ──────────────────────────────────────────────────────────────

    @Test
    void 완료_조건이_없는_것은_경고이고_전송은_된다() {
        Project project = readyProject("검증-완료조건없음");
        String frdId = draftingFrd(project, FrdItem.Nature.DEVELOP, false);
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.sendable()).as("애매한 건을 영영 못 나가게 하지 않는다").isTrue();
        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("완료 조건이 없습니다"));
    }

    @Test
    void 메뉴구조도에_자리가_없는_것은_경고다() {
        Project project = readyProject("검증-메뉴없음");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId, "wv-appr-write", "결재 문서 작성");
        histories.fillMd(histories.selectLatestByScreenId(rowId).id(), "변경 예정 기능정의서");
        standard(project, "wv-appr-write");
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        // ⚠ 차단으로 올리면 메뉴구조도를 나중에 정리하는 사업에서 전송이 통째로 막힌다.
        assertThat(gate.sendable()).isTrue();
        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("메뉴 위치"));
    }

    @Test
    void 신규_화면에는_현재_기능정의서가_없다는_경고를_표시하지_않는다() {
        Project project = readyProject("검증-신규-as-is없음");
        String frdId = draftingFrd(project);
        standard(project, "wv-appr-write");
        String rowId = newScreen(frdId, "먹깨비 연동 Gate 화면", "wv-appr-write", "상세");
        histories.fillMd(histories.selectLatestByScreenId(rowId).id(), "변경 예정 기능정의서");
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.warnings()).extracting(DevRequestPrecheck.Item::message)
                .noneMatch(message -> message.contains("현재 기능정의서가 저장소에 없습니다"));
    }

    @Test
    void 변경_예정_기능정의서가_없으면_작업트리_꾸러미를_완성할_수_없어_차단한다() {
        Project project = readyProject("검증-tobe md 없음");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성");
        standard(project, "wv-appr-write");
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("변경 예정 기능정의서"));
        assertThat(gate.sendable()).isFalse();
    }

    @Test
    void 변경_예정_기능정의서를_만드는_중이면_없다가_아니라_만드는_중으로_알린다() {
        Project project = readyProject("검증-tobe 만드는 중");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId, "wv-appr-write", "결재 문서 작성");
        standard(project, "wv-appr-write");
        String requestId = created(project, frdId);
        tobeDocuments.markRequested(rowId);

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::message)
                .anyMatch(message -> message.contains("변경 예정 기능정의서를 만들고 있습니다"))
                .noneMatch(message -> message.contains("변경 예정 기능정의서가 없습니다"));
        assertThat(gate.sendable()).isFalse();
    }

    @Test
    void 변경_예정_기능정의서_실패는_없음이_아니라_실패_이유에_맞게_알린다() {
        Project project = readyProject("검증-tobe 실패");
        String frdId = draftingFrd(project);
        String rowId = generated(frdId, "wv-appr-write", "결재 문서 작성");
        standard(project, "wv-appr-write");
        String requestId = created(project, frdId);
        long historyId = histories.selectLatestByScreenId(rowId).id();
        histories.updateTobeDocumentStatus(historyId, "FAILED", "NO_CREDENTIAL");

        var gate = service.precheck(project.getId(), requestId);

        assertThat(gate.blocking()).anySatisfy(item -> {
            assertThat(item.message()).contains("생성에 실패");
            assertThat(item.fix()).contains("Claude 연결");
        });
    }

    @Test
    void 검사기를_못_돌리면_경고이고_초록으로_읽지_않는다() {
        Project project = readyProject("검증-검사기");
        String frdId = draftingFrd(project);
        String requestId = created(project, frdId);

        var gate = service.precheck(project.getId(), requestId);

        // ⚠ 워크트리가 없으면 아예 안 돌린다(화면 0장 FRD 는 정상이다).
        //    돌렸는데 못 돌린 것은 경고로 뜬다 — 차단이면 전송이 영영 안 눌린다.
        assertThat(gate.blocking()).extracting(DevRequestPrecheck.Item::subject)
                .noneMatch(subject -> subject.startsWith("기획 문서 자동 점검"));
    }

    @Test
    void 화면이_없어도_검증이_통과한다() {
        Project project = readyProject("검증-화면0장");
        String frdId = draftingFrd(project);
        String requestId = created(project, frdId);

        assertThat(service.precheck(project.getId(), requestId).sendable())
                .as("화면 0장 FRD 가 정상이다").isTrue();
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    private String created(Project project, String frdId) {
        return service.createFromCompletedFrd(project.getId(), frdId).id();
    }

    private String draftingFrd(Project project) {
        return draftingFrd(project, FrdItem.Nature.DEVELOP, true);
    }

    private String draftingFrd(Project project, FrdItem.Nature nature) {
        return draftingFrd(project, nature, true);
    }

    private String draftingFrd(Project project, FrdItem.Nature nature, boolean withAcceptance) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "전자결재 상신 임시저장 지원", "작성 중인 문서를 임시 저장할 수 있어야 한다.",
                planner().getId()));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), id, 1, "임시저장을 지원한다",
                nature, FrdItem.Verdict.SCREEN, null, null, null));
        if (withAcceptance) {
            notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), id, 1,
                    FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "임시저장한 문서가 목록에 남는다", null));
        }
        return id;
    }

    private String generated(String frdId, String screenId, String screenName) {
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, screenId, screenName, screenId, null,
                "임시저장 버튼이 없습니다", "webview"));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup(
                "<!doctype html><html lang=\"ko\"><body>바뀐 화면</body></html>",
                List.of("임시저장 버튼을 추가한다")));
        return rowId;
    }

    /** 목업까지 만들어진 신규 화면 한 장 — 기준 화면은 AI 가 목업을 만들 때 채운 모양이다. */
    private String newScreen(String frdId, String screenName, String baseScreenId, String screenType) {
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(new FrdScreen(rowId, frdId, TemporaryScreenId.of(rowId), screenName, baseScreenId,
                null, "새로 만든다", FrdScreen.State.WAITING, null, null, null, null, null,
                "webview", screenType, null));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup("<html></html>", List.of("만든다")));
        return rowId;
    }

    private String standardOf(Project project, String screenId) {
        return standardIds.selectByProject(project.getId()).stream()
                .filter(row -> row.screenId().equals(screenId))
                .map(ScreenStandardId::standardId).findFirst().orElse(null);
    }

    private void standard(Project project, String screenId) {
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                project.getId(), screenId, "PS-WV-APR-010-L01",
                ScreenStandardId.Origin.S, 1));
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
        return accounts.selectByLoginId("gateplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "gateplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
