package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdBackendChange;
import com.bizplay.builder.frd.FrdBackendChangeMapper;
import com.bizplay.builder.frd.FrdItem;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdInterviewMessage;
import com.bizplay.builder.frd.FrdInterviewMessageMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenIaPlacement;
import com.bizplay.builder.frd.FrdScreenIaPlacementMapper;
import com.bizplay.builder.frd.FrdScreenMarker;
import com.bizplay.builder.frd.FrdScreenMarkerMapper;
import com.bizplay.builder.frd.FrdScreenMemoComment;
import com.bizplay.builder.frd.FrdScreenMemoCommentMapper;
import com.bizplay.builder.frd.ScreenMockupReader;
import com.bizplay.builder.frd.ScreenMockupService;
import com.bizplay.builder.ia.IaMapper;
import com.bizplay.builder.ia.IaRow;
import com.bizplay.builder.ia.IaStructure;
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

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개발요청서 스냅샷이 <b>계약으로 서기에 필요한 것</b>을 다 담나.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md} .
 *
 * <p>⚠ <b>스냅샷은 다시 만들지 않는다.</b> 그래서 <b>옛 모양이 읽히는 것</b>이 이 시험의 첫 몫이다 —
 * 이미 나간 개발요청서는 새 칸 없이 저장돼 있다.
 */
class DevRequestSnapshotTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired ProjectPaths paths;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdScreenHistoryMapper histories;
    @Autowired FrdScreenIaPlacementMapper iaPlacements;
    @Autowired FrdItemMapper items;
    @Autowired FrdInterviewMessageMapper interviewMessages;
    @Autowired FrdBackendChangeMapper backendChanges;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired FrdScreenMarkerMapper markers;
    @Autowired FrdScreenMemoCommentMapper memos;
    @Autowired IaMapper ia;
    @Autowired ScreenMockupService mockups;
    @Autowired ScreenStandardIdMapper standardIds;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired DevelopmentRequestService service;

    // ── 백엔드 항목 ────────────────────────────────────────────────────────

    @Test
    void 인터뷰에서_정리한_최종_요구사항_요약을_개발요청서에_고정한다() {
        Project project = readyProject("계약-인터뷰요약");
        String frdId = draftingFrd(project);
        interviewMessages.insert(FrdInterviewMessage.summary(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frdId, 1,
                "직접 저장과 자동 저장을 지원하고 저장한 문서를 다시 열 수 있어야 합니다."));

        var content = created(project, frdId).content();

        assertThat(content.summary()).contains("작성 중인 문서를 임시 저장");
        assertThat(content.interviewSummary()).isEqualTo(
                "직접 저장과 자동 저장을 지원하고 저장한 문서를 다시 열 수 있어야 합니다.");
    }

    @Test
    void 백엔드_항목의_근거와_요구사항_순번과_판정_방법이_스냅샷에_실린다() {
        Project project = readyProject("계약-백엔드");
        String frdId = draftingFrd(project);
        backendChange(frdId, 1, 2, FrdBackendChange.Category.API, "임시저장 API",
                "초안 저장 엔드포인트를 새로 만든다", "화면 md 에 저장 흐름이 없다",
                "같은 문서를 두 번 저장해도 한 줄만 남는다", true);

        var content = created(project, frdId).content();

        assertThat(content.backendChanges()).hasSize(1);
        var change = content.backendChanges().get(0);
        assertThat(change.category()).isEqualTo("API");
        assertThat(change.requirementSeq()).isEqualTo(2);
        assertThat(change.evidence()).isEqualTo("화면 md 에 저장 흐름이 없다");
        assertThat(change.verification()).isEqualTo("같은 문서를 두 번 저장해도 한 줄만 남는다");
        assertThat(change.required()).isTrue();
    }

    @Test
    void 변경_없음_백엔드_항목도_스냅샷에_담기고_구현_목록에서는_빠진다() {
        Project project = readyProject("계약-변경없음");
        String frdId = draftingFrd(project);
        backendChange(frdId, 1, null, FrdBackendChange.Category.API, "임시저장 API",
                "엔드포인트를 새로 만든다", null, null, true);
        backendChange(frdId, 2, null, FrdBackendChange.Category.PERMISSION, "결재 권한",
                "확인했고 바꿀 것이 없다", null, null, false);

        var content = created(project, frdId).content();

        // ⭐ 「확인했고 변경 없다」가 백엔드의 as-is 대응물이다 — 개발이 되묻지 않게 한다.
        assertThat(content.backendChanges()).hasSize(2);
        assertThat(content.requiredChanges()).extracting(
                DevelopmentRequestContent.BackendChange::target).containsExactly("임시저장 API");
        assertThat(content.unchangedChanges()).extracting(
                DevelopmentRequestContent.BackendChange::target).containsExactly("결재 권한");
    }

    // ── 화면 ──────────────────────────────────────────────────────────────

    @Test
    void 화면마다_IA_메뉴_경로가_실리고_연결이_없으면_비어_있다() {
        Project project = readyProject("계약-메뉴");
        String frdId = draftingFrd(project);
        String linked = screen(frdId, "wv-appr-write", "결재 문서 작성");
        screen(frdId, "wv-appr-new", "결재 문서 신규");
        menuRow(project, "webview", "wv-appr-write", "전자결재", "상신", "문서 작성");

        var content = created(project, frdId).content();

        var byId = content.screens().stream().collect(
                java.util.stream.Collectors.toMap(DevelopmentRequestContent.Screen::screenId, s -> s));
        assertThat(byId.get("wv-appr-write").menuPath()).isEqualTo("전자결재 > 상신 > 문서 작성");
        // ⚠ IA 에 아직 안 넣은 신규 화면은 빈다 — 차단이 아니라 검증 경고 자리다.
        assertThat(byId.get("wv-appr-new").menuPath()).isNull();
        assertThat(linked).isNotBlank();
    }

    @Test
    void 화면의_마커와_메모가_스냅샷에_실린다() {
        Project project = readyProject("계약-마커");
        String frdId = draftingFrd(project);
        String screenId = screen(frdId, "wv-appr-write", "결재 문서 작성");
        String author = planner().getId();
        markers.insert(new FrdScreenMarker(ids.next(IdSequence.Kind.FRD_SCREEN_MARKER), screenId,
                1, author, "이영희", "#save-draft", "임시저장 버튼",
                0.42d, 0.11d, 0.31d, 0.09d, "이 버튼을 상단 오른쪽으로 옮긴다",
                Instant.now(), Instant.now()));
        memos.insert(new FrdScreenMemoComment(ids.next(IdSequence.Kind.FRD_SCREEN_MEMO_COMMENT),
                screenId, author, "이영희", "임시저장은 5분마다 자동으로도 돈다", Instant.now()));

        var content = created(project, frdId).content();
        var screen = content.screens().get(0);

        assertThat(screen.markers()).hasSize(1);
        assertThat(screen.markers().get(0).markerNo()).isEqualTo(1);
        assertThat(screen.markers().get(0).elementLabel()).isEqualTo("임시저장 버튼");
        assertThat(screen.markers().get(0).description()).contains("상단 오른쪽");
        assertThat(screen.memos()).hasSize(1);
        assertThat(screen.memos().get(0).content()).contains("5분마다");
        assertThat(screen.memos().get(0).authorName()).isEqualTo("이영희");
    }

    // ── 옛 모양 ───────────────────────────────────────────────────────────

    @Test
    void 옛_모양_스냅샷은_새_칸이_비어_있는_것으로_읽힌다() {
        Project project = readyProject("계약-옛모양");
        String frdId = draftingFrd(project);
        String requestId = ids.next(IdSequence.Kind.DEV_REQUEST);
        // 2026-08-22 이전에 저장된 모양 그대로다 — 마커·메모·메뉴 경로·근거·판정 방법이 없다.
        String oldShape = """
                {"summary":"임시저장이 필요하다",
                 "requirements":[{"seq":1,"requirement":"임시저장","nature":"DEVELOP",
                                  "natureLabel":"개발","note":null}],
                 "screens":[{"frdScreenId":"0000001","screenId":"wv-appr-write",
                             "screenName":"결재 문서 작성","systemCode":"webview",
                             "changes":["임시저장 버튼을 추가한다"]}],
                 "backendChanges":[{"category":"API","categoryLabel":"API",
                                    "target":"임시저장 API","changeDetail":"새로 만든다"}],
                 "notes":[{"kind":"ACCEPTANCE_CRITERION","content":"임시저장이 남는다"}]}
                """;
        requests.insert(new DevelopmentRequest(requestId, project.getId(),
                requests.allocateNumber(project.getId()), frdId, 1, "옛 개발요청서", "webview", null,
                oldShape, DevelopmentRequest.DeliveryState.NOT_SENT, null,
                null, null, null, null, null, null, null, null, null, null, null));

        assertThatCode(() -> service.read(project.getId(), requestId)).doesNotThrowAnyException();
        var content = service.read(project.getId(), requestId).content();

        // ⛔ 널이 아니라 빈 목록이다 — 널이면 화면과 md 렌더가 통째로 죽는다.
        assertThat(content.screens().get(0).markers()).isEmpty();
        assertThat(content.interviewSummary()).isNull();
        assertThat(content.screens().get(0).memos()).isEmpty();
        assertThat(content.screens().get(0).connections()).isEmpty();
        assertThat(content.screens().get(0).deliveryScreenId()).isEqualTo("wv-appr-write");
        assertThat(content.screens().get(0).deliveryFileName()).isEqualTo("wv-appr-write.html");
        assertThat(content.screens().get(0).menuPath()).isNull();
        assertThat(content.backendChanges().get(0).evidence()).isNull();
        assertThat(content.backendChanges().get(0).verification()).isNull();
        assertThat(content.backendChanges().get(0).requirementSeq()).isNull();
        // 옛 스냅샷은 만들 때 이미 「변경 없음」을 버렸다 — 남은 것은 다 구현할 것이다.
        assertThat(content.requiredChanges()).hasSize(1);
        assertThat(content.unchangedChanges()).isEmpty();
    }

    @Test
    void 기존_개발요청서도_FRD에_남은_인터뷰_요약을_상세에_표시한다() {
        Project project = readyProject("계약-기존요청-인터뷰요약");
        String frdId = draftingFrd(project);
        interviewMessages.insert(FrdInterviewMessage.summary(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frdId, 1,
                "인터뷰에서 확정한 기존 요청의 요구사항 요약입니다."));
        String requestId = ids.next(IdSequence.Kind.DEV_REQUEST);
        String oldShape = """
                {"summary":"사용자가 처음 입력한 요청 원문입니다.",
                 "requirements":[],"screens":[],"backendChanges":[],"notes":[]}
                """;
        requests.insert(new DevelopmentRequest(requestId, project.getId(),
                requests.allocateNumber(project.getId()), frdId, 1, "기존 개발요청서", "webview", null,
                oldShape, DevelopmentRequest.DeliveryState.NOT_SENT, null,
                null, null, null, null, null, null, null, null, null, null, null));

        var content = service.read(project.getId(), requestId).content();

        assertThat(content.interviewSummary()).isEqualTo("인터뷰에서 확정한 기존 요청의 요구사항 요약입니다.");
        assertThat(content.summary()).isEqualTo("사용자가 처음 입력한 요청 원문입니다.");
    }

    @Test
    void 옛_스냅샷의_신규_화면은_전달_ID와_연결_안내를_현재_작업에서_복구한다() throws Exception {
        Project project = readyProject("계약-옛신규화면");
        String frdId = draftingFrd(project);
        String rowId = screen(frdId, "tmp-0000067", "폐업가맹점 목록 조회");
        String detailRowId = screen(frdId, "tmp-0000068", "폐업가맹점 상세 조회");
        iaPlacements.upsert(new FrdScreenIaPlacement(
                rowId, FrdScreenIaPlacement.PlacementMode.UNRESOLVED, null, null, null,
                FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.AI, "backoffice-list-67", Instant.now(), null));
        iaPlacements.upsert(new FrdScreenIaPlacement(
                detailRowId, FrdScreenIaPlacement.PlacementMode.CHILD, null, null, "tmp-0000067",
                FrdScreenIaPlacement.ScreenKind.SCREEN, FrdScreenIaPlacement.Status.PROPOSED,
                FrdScreenIaPlacement.Source.AI, "backoffice-detail-68", Instant.now(), null));
        Path pages = paths.frdWorktree(project.getId(), frdId).resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve("tmp-0000067.md"),
                "- 구분: 이동 / 이동: tmp-0000068 / 앵커: #detail / 라벨: 상세\n");
        String requestId = ids.next(IdSequence.Kind.DEV_REQUEST);
        String oldShape = """
                {"summary":"신규 화면 개발",
                 "requirements":[],
                 "screens":[{"frdScreenId":"%s","screenId":"tmp-0000067",
                             "screenName":"폐업가맹점 목록 조회","systemCode":"backoffice",
                             "changes":["신규 화면을 만든다"]},
                            {"frdScreenId":"%s","screenId":"tmp-0000068",
                             "screenName":"폐업가맹점 상세 조회","systemCode":"backoffice",
                             "changes":["신규 상세 화면을 만든다"]}],
                 "backendChanges":[],"notes":[]}
                """.formatted(rowId, detailRowId);
        requests.insert(new DevelopmentRequest(requestId, project.getId(),
                requests.allocateNumber(project.getId()), frdId, 1, "신규 화면 개발요청", "backoffice", null,
                oldShape, DevelopmentRequest.DeliveryState.NOT_SENT, null,
                null, null, null, null, null, null, null, null, null, null, null));

        var restored = service.read(project.getId(), requestId).content().screens();

        assertThat(restored.get(0).deliveryScreenId()).isEqualTo("backoffice-list-67");
        assertThat(restored.get(0).deliveryFileName()).isEqualTo("backoffice-list-67.html");
        assertThat(restored.get(0).connections()).singleElement()
                .extracting(DevelopmentRequestContent.Connection::targetScreenId)
                .isEqualTo("backoffice-detail-68");
        assertThat(restored.get(1).entryPoint()).contains("backoffice-list-67");
    }

    // ── 앞 개발요청서 ─────────────────────────────────────────────────────

    @Test
    void 화면이_겹치는_앞선_개발요청서만_후보로_뜬다() {
        Project project = readyProject("계약-앞DR");
        String earlier = draftingFrd(project);
        screen(earlier, "wv-appr-write", "결재 문서 작성");
        String earlierRequest = created(project, earlier).request().id();

        String unrelated = draftingFrd(project);
        screen(unrelated, "wv-card-list", "보유 카드 조회");
        created(project, unrelated);

        String mine = draftingFrd(project);
        screen(mine, "wv-appr-write", "결재 문서 작성");
        String myRequest = created(project, mine).request().id();

        var candidates = service.previousCandidates(project.getId(), myRequest);

        // ⛔ 화면이 겹치는 것과 같은 업무인 것은 다르다 — 그래서 후보만 댄다.
        assertThat(candidates).extracting(DevelopmentRequest::id).containsExactly(earlierRequest);
    }

    @Test
    void 자기_자신과_뒤에_생긴_것은_후보가_아니다() {
        Project project = readyProject("계약-앞DR순서");
        String first = draftingFrd(project);
        screen(first, "wv-appr-write", "결재 문서 작성");
        String firstRequest = created(project, first).request().id();

        String second = draftingFrd(project);
        screen(second, "wv-appr-write", "결재 문서 작성");
        String secondRequest = created(project, second).request().id();

        assertThat(service.previousCandidates(project.getId(), firstRequest)).isEmpty();
        assertThat(service.previousCandidates(project.getId(), secondRequest))
                .extracting(DevelopmentRequest::id).containsExactly(firstRequest);
    }

    @Test
    void 화면이_없는_개발요청서는_후보가_빈_목록이다() {
        Project project = readyProject("계약-화면0장");
        String frdId = draftingFrd(project);
        String requestId = created(project, frdId).request().id();

        // ⚠ 화면 0장 FRD 가 정상이다 — 오류가 아니라 빈 목록이어야 한다.
        assertThat(service.previousCandidates(project.getId(), requestId)).isEmpty();
    }

    @Test
    void 고른_앞_개발요청서가_전송_정보와_함께_저장된다() {
        Project project = readyProject("계약-앞DR저장");
        String earlier = draftingFrd(project);
        screen(earlier, "wv-appr-write", "결재 문서 작성");
        String earlierRequest = created(project, earlier).request().id();
        String mine = draftingFrd(project);
        String mineScreen = screen(mine, "wv-appr-write", "결재 문서 작성");
        histories.fillMd(histories.selectLatestByScreenId(mineScreen).id(), "변경 예정 기능정의서");
        String myRequest = created(project, mine).request().id();

        service.requestDelivery(project.getId(), myRequest, "일정 협의 필요",
                null, null, earlierRequest, null);

        var saved = requests.selectById(myRequest);
        assertThat(saved.previousRequestId()).isEqualTo(earlierRequest);
        assertThat(saved.deliveryState()).isEqualTo(DevelopmentRequest.DeliveryState.SENDING);
    }

    @Test
    void 남의_프로젝트_개발요청서는_앞것으로_고를_수_없다() {
        Project mineProject = readyProject("계약-내프로젝트");
        Project other = readyProject("계약-남의프로젝트");
        String otherFrd = draftingFrd(other);
        String otherRequest = created(other, otherFrd).request().id();
        String frdId = draftingFrd(mineProject);
        String requestId = created(mineProject, frdId).request().id();

        assertThatThrownBy(() -> service.requestDelivery(mineProject.getId(), requestId, null,
                null, null, otherRequest, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    private DevelopmentRequestService.View created(Project project, String frdId) {
        DevelopmentRequest request = service.createFromCompletedFrd(project.getId(), frdId);
        return service.read(project.getId(), request.id());
    }

    private String draftingFrd(Project project) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "전자결재 상신 임시저장 지원", "상신 화면에서 작성 중인 문서를 임시 저장할 수 있어야 한다.",
                planner().getId()));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), id, 1, "임시저장을 지원한다",
                FrdItem.Nature.DEVELOP, FrdItem.Verdict.SCREEN, "wv-appr-write", null, null));
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), id, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "임시저장한 문서가 목록에 남는다", null));
        return id;
    }

    /**
     * 짚힌 화면 한 장. ⚠ <b>목업과 표준 화면ID까지 앉힌다</b> — 전송 게이트(계획 9 Task 7)가
     * 둘을 요구한다. 여기 시험의 관심은 스냅샷이지 게이트가 아니다.
     */
    private String screen(String frdId, String screenId, String screenName) {
        String id = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(id, frdId, screenId, screenName, screenId, null,
                "임시저장 버튼이 없습니다", "webview"));
        mockups.markGenerated(id, new ScreenMockupReader.Mockup(
                "<!doctype html><html lang=\"ko\"><body>바뀐 화면</body></html>",
                List.of("임시저장 버튼을 추가한다")));
        // ⚠ (프로젝트, 화면ID) 와 (프로젝트, 표준ID) 가 둘 다 unique 다 — 같은 화면을 여러 FRD 가
        //    건드리는 시험이 있어서 이미 있으면 넘어간다.
        String projectId = frds.selectById(frdId).projectId();
        boolean already = standardIds.selectByProject(projectId).stream()
                .anyMatch(row -> row.screenId().equals(screenId));
        if (!already) {
            standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                    projectId, screenId, "PS-WV-" + screenId.toUpperCase().replace('-', '_'),
                    ScreenStandardId.Origin.S, 1));
        }
        return id;
    }

    private void backendChange(String frdId, int seq, Integer requirementSeq,
                               FrdBackendChange.Category category, String target, String detail,
                               String evidence, String verification, boolean required) {
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                frdId, seq, requirementSeq, category, target, detail, evidence, verification,
                required, null));
    }

    /**
     * IA 정본은 빌더 DB 다 — ⛔ 클론의 {@code ia.md} 를 읽는 시험을 쓰지 마라.
     *
     * <p>⚠ {@code path_key} 는 <b>영문 슬러그</b>만 받는다(V41 체크). 한글 Depth 를 그대로
     * 이어 붙이면 제약에서 튕긴다.
     */
    private void menuRow(Project project, String systemCode, String screenId, String... depths) {
        String structureId = ids.next(IdSequence.Kind.IA_STRUCTURE);
        ia.insertStructure(new IaStructure(structureId, project.getId(), systemCode,
                IaStructure.State.DRAFT, 0, 1, "0".repeat(64), null, null, null, null, null, null, null));
        ia.insertRow(new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId, 1,
                "appr/draft/write", depths[0],
                depths.length > 1 ? depths[1] : null, depths.length > 2 ? depths[2] : null,
                null, null, null, null, null, null, null, screenId, null, null));
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
        return accounts.selectByLoginId("drplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "drplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
