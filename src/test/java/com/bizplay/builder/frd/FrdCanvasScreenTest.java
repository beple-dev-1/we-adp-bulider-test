package com.bizplay.builder.frd;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class FrdCanvasScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdFacetMapper frdFacets;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdScreenChatMapper chatMessages;
    @Autowired FrdScreenChatService chatService;
    @Autowired FrdScreenHistoryMapper screenHistories;
    @Autowired ProjectPaths paths;
    @MockitoBean FrdCanvasChatWorker chatWorker;
    @MockitoBean FrdWorkspace workspaces;

    @Test
    void 전체_적용_대상은_요약하고_갈린_화면만_캔버스_상단에서_전환한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        projectFacets.insert(ProjectFacet.create(project.getId(), "iksan", "익산"));
        projectFacets.insert(ProjectFacet.create(project.getId(), "jeju", "제주"));
        Path clone = paths.cloneDir(project.getId());
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/3","screens":{
                  "wv-modal-all-menu":{"system":"webview","ia":{"종류":"화면"}},
                  "wv-common":{"system":"webview","ia":{"종류":"화면"}}
                }}
                """, StandardCharsets.UTF_8);
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "기관별 캔버스", "제주와 익산 화면을 함께 변경한다.", planner.accountId()));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "익산"));
        frdFacets.insert(FrdFacet.create(frdId, project.getId(), "제주"));
        frds.updateAfterPick(frdId, "기관별 캔버스", "webview", null, Frd.State.DRAFTING, null);
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-modal-all-menu", "전체 메뉴", "wv-modal-all-menu", null,
                "기관별 화면", "webview"));
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "wv-common", "공통 화면", "wv-common", null,
                "공통 화면", "webview"));
        Path worktree = paths.frdWorktree(project.getId(), frdId);
        Path pages = worktree.resolve("core/webview/pages");
        Path iksan = worktree.resolve("core/webview/variants-iksan");
        Path jeju = worktree.resolve("core/webview/variants-jeju");
        Files.createDirectories(pages);
        Files.createDirectories(iksan);
        Files.createDirectories(jeju);
        Files.writeString(pages.resolve("wv-common.html"),
                "<!doctype html><html><body>공통 화면</body></html>");
        Files.writeString(iksan.resolve("wv-modal-all-menu.html"),
                "<!doctype html><html><body>익산 전체 메뉴</body></html>");
        Files.writeString(jeju.resolve("wv-modal-all-menu.html"),
                "<!doctype html><html><body>제주 전체 메뉴</body></html>");

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", project.getId(), frdId)
                        .param("facet", "제주").with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("wm-facet-switch")
                .contains("<span class=\"badge badge--outline\">전체</span>")
                .doesNotContain("fc-nav-facet-children")
                .containsOnlyOnce("class=\"wm-canvas-facet-tool\"")
                .contains("<label class=\"sr-only\" for=\"canvas-facet\">기관 화면</label>")
                .contains("<option value=\"제주\" selected=\"selected\">제주</option>")
                .contains("is-current");

        String initialHtml = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas",
                        project.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(initialHtml)
                .contains("<label class=\"sr-only\" for=\"canvas-facet\">기관 화면</label>")
                .contains("<option value=\"익산\" selected=\"selected\">익산</option>");

        String detailHtml = mvc.perform(get("/projects/{p}/artifacts/frds/{f}",
                        project.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(detailHtml)
                .doesNotContain("wm-facet-switch")
                .contains("<span class=\"badge badge--outline\">전체</span>")
                .doesNotContain("fc-nav-facet-children")
                .containsOnlyOnce("class=\"wm-canvas-facet-tool\"")
                .contains("class=\"sr-only\"")
                .contains("name=\"screen\" value=\"wv-modal-all-menu\"")
                .contains("id=\"delete-detail-screen\"")
                .contains("id=\"delete-detail-screen-id\"")
                .contains("name=\"returnTo\" value=\"detail\"")
                .contains("/canvas/screens/exclude")
                .contains("deleteDetailScreenButton.disabled = !workbenchEditable");
    }

    @Test
    void 캔버스_템플릿이_실제_화면과_관계를_렌더링한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        Path clone = paths.cloneDir(project.getId());
        Path pages = clone.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/3","screens":{
                  "screen-a":{"system":"webview","ia":{"종류":"화면"}},
                  "screen-b":{"system":"webview","ia":{"종류":"화면"}},
                  "screen-c":{"system":"webview","ia":{"종류":"화면"}}
                }}
                """, StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-a.html"),
                "<!doctype html><html><head></head><body>화면 A</body></html>");
        Files.writeString(pages.resolve("screen-b.html"),
                "<!doctype html><html><head></head><body>화면 B</body></html>");
        Files.writeString(pages.resolve("screen-c.html"),
                "<!doctype html><html><head></head><body>화면 C</body></html>");
        Files.writeString(pages.resolve("screen-a.md"), """
                화면명: 화면 A
                - 구분: 이동 / 이동: screen-b / 앵커: screen-a-e01 / 라벨: 다음
                """);
        Files.writeString(pages.resolve("screen-b.md"), """
                화면명: 화면 B
                - 구분: 이동 / 이동: screen-c / 앵커: screen-b-e01 / 라벨: 다음
                """);
        Files.writeString(pages.resolve("screen-c.md"), "화면명: 화면 C\n");

        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "결재 화면 흐름 개선", "화면 A에서 화면 B로 이동한다.", planner.accountId()));
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "screen-a", "화면 A", "screen-a", null, "다음 이동을 추가합니다.", "webview"));
        frds.updateAfterPick(frdId, "결재 화면 흐름 개선", "webview", null, Frd.State.DRAFTING, null);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("data-frd-canvas-root")
                .contains("data-layout-key=\"frd-canvas:v4:")
                .contains("<header class=\"rq-head\">")
                .contains("작업공간 초기화")
                .contains("wm-canvas-view-switch")
                .contains("data-detail-view-link")
                .containsSubsequence("상세 화면", "전체 맵")
                .doesNotContain("화면 상세 캔버스")
                .contains("FRD 내용 보기")
                .contains("data-submit-loading=\"FRD 작업을 완료하고 개발요청서를 만드는 중\"")
                .contains("<dl class=\"rq-meta\">")
                .contains("요구사항")
                .contains("생성일")
                .doesNotContain("<header class=\"fc-head\">")
                .contains("rq-card wm-screen-sidebar fc-navigator")
                .contains("wm-work-layout fc-workspace")
                .contains("wm-screen-list-row__link fc-nav-item")
                .contains("wm-screen-origin").contains("기존 화면")
                .contains("wm-screen-name")
                .contains("wm-screen-meta")
                .contains("wm-screen-id")
                .contains("관리번호 없음")
                .contains("작업 대상")
                .contains("fc-system-badge").contains(">webview</span>")
                .doesNotContain("aria-label=\"AI 초안 만들기\"")
                .doesNotContain("wm-ai-draft-mark")
                .doesNotContain(">미작업<")
                .contains("FRD 화면 추가")
                .contains("name=\"addScreen\" value=\"true\"")
                .doesNotContain("FRD 주변")
                .doesNotContain("프로젝트 전체")
                .contains("wm-stage fc-stage")
                .contains("wm-stage-head fc-toolbar")
                .contains("data-canvas-transition").contains("상세 화면을 불러오는 중입니다.")
                .contains("작업 대상을 중심으로 연결된 기존 화면을 확인합니다.")
                .contains("fc-scope-summary")
                .contains("fc-work-target-badge")
                .contains("wm-zoom fc-zoom")
                .contains("data-canvas-workspace-focus")
                .contains("aria-label=\"전체 화면 확대\"")
                .contains("aria-label=\"전체 화면 포커스\"")
                .contains("data-canvas-auto-layout")
                .contains("aria-label=\"화면 자동 정렬\"")
                .contains("data-canvas-related-toggle")
                .contains("aria-label=\"관련 화면 숨기기\"")
                .contains("aria-pressed=\"true\"")
                .contains("icon-button wm-ai-chat-trigger fc-map-ai-trigger")
                .contains("data-canvas-node=\"screen-a\"")
                .contains("solution-mockups/files/webview/pages/screen-a.html")
                .contains("data-preview-src=")
                .contains("data-source=\"screen-a\"")
                .contains("data-target=\"screen-b\"")
                .contains("data-canvas-relation disabled")
                .contains("data-canvas-compare disabled")
                .contains("aria-label=\"변경 내용 비교\"")
                .contains("data-compare-url=")
                .contains("data-canvas-compare-dialog")
                .contains("data-canvas-compare-frame")
                .contains("dialog__header dialog__header--with-meta")
                .contains("data-canvas-compare-close")
                .contains("data-canvas-relation-dialog")
                .contains("dialog__actions fc-relation-actions")
                .contains("잠금 해제하고 수정 화면에 추가")
                .contains("자물쇠를 눌러 수정 화면에 추가")
                .contains("수정 화면에서 제외하고 다시 잠그기")
                .contains("작업 중 변경 내용이 삭제되고 원본 화면으로 돌아갑니다")
                .contains("/canvas/screens/promote")
                .doesNotContain("data-canvas-node=\"screen-c\"")
                .contains("<section class=\"wm-ai-chat\" id=\"frd-map-chat\"")
                .contains("class=\"wm-ai-chat__head\"")
                .contains("class=\"wm-ai-chat__selection\"")
                .contains("class=\"wm-ai-chat__log\"")
                .contains("class=\"wm-ai-chat__composer\"")
                .contains("data-canvas-suggestion-toggle")
                .contains("AI 제안")
                .contains("data-canvas-chat-expand")
                .contains("aria-label=\"대화창 확대\"")
                .contains("Claude Code")
                .contains("id=\"frd-map-chat\" role=\"dialog\" aria-labelledby=\"frd-map-chat-title\" hidden")
                .contains("data-canvas-chat-open")
                .contains("data-chat-status-url=")
                .contains("aria-controls=\"frd-map-chat\" aria-expanded=\"false\"")
                .contains("FRD 맵 AI");

        String canvasScript = Files.readString(
                Path.of("src/main/resources/static/js/frd-canvas.js"), StandardCharsets.UTF_8);
        assertThat(canvasScript)
                .contains("preview.classList.add('is-loading')")
                .contains("preview.classList.add('is-loaded')")
                .contains("canvasTransition.hidden = false")
                .contains("event.stopPropagation()")
                .contains("canvas.setAttribute('aria-busy', 'true')")
                .contains("compareDialog.showModal()")
                .doesNotContain("frd-canvas-compare-${root.dataset.layoutKey}");

        String safetyCss = Files.readString(
                Path.of("src/main/resources/static/css/frd-canvas-safety.css"), StandardCharsets.UTF_8);
        assertThat(safetyCss)
                .contains("width: 340px;")
                .contains("height: 285px;")
                .contains("width: 440px;")
                .contains("height: 365px;")
                .contains("width: min(780px, calc(100vw - 32px));")
                .contains("overflow-x: hidden;")
                .contains(".fc-ia-status-badge::before")
                .doesNotContain(".fc-relation-dialog > form");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/promote",
                        project.getId(), frdId)
                        .param("screenId", "screen-b")
                        .param("screenName", "화면 B")
                        .param("systemCode", "webview")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",
                        "/projects/" + project.getId() + "/artifacts/frds/" + frdId + "/canvas"));

        assertThat(screens.selectByFrdId(frdId))
                .extracting(FrdScreen::screenId)
                .contains("screen-a", "screen-b");
        String promoted = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas",
                        project.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(promoted)
                .contains("data-focus-screen=\"screen-b\"")
                .contains("data-canvas-node=\"screen-c\"");

        FrdScreen promotedScreen = screens.selectByFrdId(frdId).stream()
                .filter(screen -> screen.screenId().equals("screen-b"))
                .findFirst().orElseThrow();
        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/{s}/exclude",
                        project.getId(), frdId, promotedScreen.id())
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(screens.selectByFrdId(frdId))
                .extracting(FrdScreen::screenId)
                .containsExactly("screen-a");
        String relocked = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas",
                        project.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(relocked)
                .contains("data-canvas-node=\"screen-b\"")
                .contains("data-work-target=\"false\"")
                .doesNotContain("data-focus-screen=\"screen-b\"");
    }

    @Test
    void 검토_중인_FRD가_작업_대상_화면_하나뿐이어도_전체_캔버스에_진입한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "단일 화면 작업", "화면 하나만 수정한다.", planner.accountId()));
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.drafted(screenRowId, frdId,
                "tmp-0000036", "단일 신규 화면", "목록", null, "webview"));
        frds.updateAfterPick(frdId, "단일 화면 작업", "webview", null, Frd.State.DRAFTING, null);
        frds.updateState(frdId, Frd.State.REVIEW);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("data-frd-canvas-root")
                .contains("data-canvas-node=\"tmp-0000036\"")
                .contains("<b>1</b> 작업 대상")
                .contains("<b>0</b> 관련 화면")
                .doesNotContain("검토 필요 · 읽기 전용")
                .contains("data-canvas-editable=\"false\"")
                .contains("작업공간 초기화")
                .doesNotContain("aria-label=\"AI 초안 만들기\"")
                .contains("aria-label=\"FRD 화면 추가\"")
                .contains("aria-label=\"새 화면으로 복제\"")
                .contains("aria-label=\"화면 연결\"")
                .contains("aria-label=\"신규 화면 삭제\"")
                .contains("aria-label=\"FRD 맵 AI\"")
                .contains("AI 초안 생성 전")
                .doesNotContain("/screens/" + screenRowId + "/preview")
                .contains("완료된 FRD에서는 화면을 추가할 수 없습니다.")
                .contains("완료된 FRD에서는 맵 AI를 사용할 수 없습니다.");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/exclude",
                        project.getId(), frdId).param("screenRowId", screenRowId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "완료된 FRD에서는 캔버스를 변경할 수 없습니다."));

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/duplicate",
                        project.getId(), frdId)
                        .param("sourceScreenRowId", screenRowId)
                        .param("screenName", "복제하면 안 되는 화면")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "완료된 FRD에서는 캔버스를 변경할 수 없습니다."));

        assertThat(screens.selectByFrdId(frdId)).extracting(FrdScreen::id).containsExactly(screenRowId);
    }

    @Test
    void 작업_대상_화면의_기준_화면과_FRD_수정안을_나란히_비교한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        Path clone = paths.cloneDir(project.getId());
        Path pages = clone.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/3","screens":{
                  "screen-work":{"system":"webview","ia":{"종류":"화면"}}
                }}
                """, StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-work.html"),
                "<!doctype html><html><head></head><body>기준 화면</body></html>", StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-work.md"), "화면명: 작업 화면\n", StandardCharsets.UTF_8);

        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "화면 비교", "기준 화면과 수정안을 비교한다.", planner.accountId()));
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(screenRowId, frdId,
                "screen-work", "작업 화면", "screen-work", null,
                "폐업 상태와 상세 이동을 추가합니다.", "webview"));
        screens.updateGenerated(screenRowId,
                "<!doctype html><html><head></head><body>FRD 수정안</body></html>",
                "폐업 상태 표시를 추가했습니다.\n상세 이동 버튼을 추가했습니다.", java.time.Instant.now());
        frds.updateAfterPick(frdId, "화면 비교", "webview", null, Frd.State.DRAFTING, null);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas/compare",
                        project.getId(), frdId).param("screenRowId", screenRowId)
                        .param("embedded", "true").with(user(planner)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("화면 변경 비교")
                .contains("fc-compare-window--embedded")
                .contains("기준 화면")
                .contains("기획 저장소의 기준 솔루션 목업")
                .contains("FRD 수정안")
                .contains("폐업 상태 표시를 추가했습니다.")
                .contains("상세 이동 버튼을 추가했습니다.")
                .contains("solution-mockups/files/")
                .contains("screen-work.html")
                .contains("/screens/" + screenRowId + "/preview")
                .contains("/css/frd-canvas-compare.css")
                .contains("/js/frd-canvas-compare.js")
                .contains("화면 변경 위치 강조 범례")
                .containsSubsequence("변경", "추가", "삭제")
                .doesNotContain("최신 내용 확인 후 작업 완료")
                .doesNotContain("fc-sync-review");

        String compareScript = Files.readString(
                Path.of("src/main/resources/static/js/frd-canvas-compare.js"), StandardCharsets.UTF_8);
        assertThat(compareScript).contains("addEventListener('scroll'")
                .contains("capture: true")
                .contains("targetElement.scrollTop")
                .contains("targetElement.scrollLeft")
                .contains("matchingScrollableElement")
                .contains("highlightDifferences")
                .contains("'[data-element-id], [id]'")
                .contains("builder-compare-highlight--changed")
                .contains("builder-compare-highlight--added")
                .contains("builder-compare-highlight--removed");

        String canvasHtml = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas",
                        project.getId(), frdId)
                        .param("comparisonScreenRowId", screenRowId)
                        .param("confirmedCloneHead", "abc123")
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(canvasHtml).contains("data-comparison-screen-row-id=\"" + screenRowId + "\"")
                .contains("name=\"confirmedCloneHead\"")
                .contains("value=\"abc123\"");
    }

    @Test
    void 신규_화면_비교에서는_기준_화면이_없는_이유를_표시한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "신규 화면 비교", "신규 화면 수정안을 검토한다.", planner.accountId()));
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.drafted(screenRowId, frdId,
                "tmp-0000099", "폐업 가맹점 조회", "목록", null, "webview"));
        screens.updateGenerated(screenRowId,
                "<!doctype html><html><head></head><body>신규 화면 수정안</body></html>",
                "폐업 가맹점 조회 화면을 구성했습니다.", java.time.Instant.now());
        frds.updateAfterPick(frdId, "신규 화면 비교", "webview", null, Frd.State.DRAFTING, null);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas/compare",
                        project.getId(), frdId).param("screenRowId", screenRowId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("신규 화면이라 기준 화면이 없습니다")
                .contains("오른쪽 FRD 수정안을 기준으로 신규 화면 구성을 검토해 주세요.")
                .doesNotContain("solution-mockups/files/")
                .doesNotContain("화면 변경 위치 강조 범례")
                .contains("/screens/" + screenRowId + "/preview");
    }

    @Test
    void 전체_캔버스_AI_대화를_별도_팝업으로_연다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "전체 화면 대화", "선택 화면을 함께 수정한다.", planner.accountId()));
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "screen-work", "작업 화면", "screen-work", null, null, "webview"));
        frds.updateAfterPick(frdId, "전체 화면 대화", "webview", null, Frd.State.DRAFTING, null);

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas/chat", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"frd-canvas-chat-window-form\"")
                .contains("id=\"frd-canvas-chat-window-selection\"")
                .contains("id=\"frd-canvas-chat-window-suggestion-toggle\"")
                .contains("AI 제안", "디자인 규칙 검토", "사용성 개선 제안", "화면 연결 검토")
                .contains("data-status-url=")
                .contains("data-send-url=")
                .contains("/js/frd-canvas-chat-window.js")
                .contains("window.close()");
        String popupScript = Files.readString(
                Path.of("src/main/resources/static/js/frd-canvas-chat-window.js"), StandardCharsets.UTF_8);
        assertThat(popupScript).contains("frd-canvas-chat-ready")
                .contains("frd-canvas-selection-changed")
                .contains("screenIds: selectedScreens.map")
                .contains("wm-ai-interview")
                .contains("suggestionToggle")
                .contains("event.key !== 'Escape'")
                .contains("/cancel");
        String canvasScript = Files.readString(
                Path.of("src/main/resources/static/js/frd-canvas.js"), StandardCharsets.UTF_8);
        assertThat(canvasScript).contains("window.open(root.dataset.chatUrl")
                .contains("wm-ai-interview")
                .contains("chatSuggestionToggle")
                .contains("frd-canvas-chat-completed");
    }

    @Test
    void 관련_화면_ID를_보내도_맵_AI_수정_대상에서_제외한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "관련 화면 보호", "작업 화면만 수정한다.", planner.accountId()));
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "screen-work", "작업 화면", "screen-work", null, null, "webview"));
        frds.updateAfterPick(frdId, "관련 화면 보호", "webview", null, Frd.State.DRAFTING, null);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/chat", project.getId(), frdId)
                        .with(user(planner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"관련 화면도 바꿔 주세요.","screenIds":["screen-related"]}
                                """))
                .andExpect(status().isAccepted());

        verify(chatWorker).edit(anyString(), eq(java.util.List.of()));
        String thread = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas/chat/messages",
                        project.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(thread).contains("관련 화면도 바꿔 주세요.")
                .contains("\"active\":{")
                .contains("\"busy\":true");
        FrdScreenChatMessage running = chatService.running(frdId);
        assertThat(chatMessages.selectByScreenId(running.frdScreenId())).isEmpty();
        assertThat(chatMessages.selectCanvasByFrdId(frdId)).hasSize(2);
        chatService.complete(running.id(), FrdCanvasInterviewContent.encode("수정 방향을 선택해 주세요.",
                java.util.List.of(new FrdCanvasChatReader.InterviewQuestion("layout", "레이아웃은 무엇인가요?",
                        FrdCanvasChatReader.AnswerType.SINGLE, java.util.List.of("목록형", "카드형"), true))),
                "canvas-session-id");
        String interviewThread = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas/chat/messages",
                        project.getId(), frdId).with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(interviewThread).contains("수정 방향을 선택해 주세요.")
                .contains("\"answerType\":\"SINGLE\"")
                .contains("목록형", "카드형")
                .doesNotContain("CANVAS_INTERVIEW");
        assertThat(chatMessages.selectLatestCanvasSessionId(frdId)).isEqualTo("canvas-session-id");
    }

    @Test
    void 전체_캔버스_대화를_Esc_중단_API로_종료한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "캔버스 대화 중단", "잘못 보낸 요청을 중단한다.", planner.accountId()));
        screens.insert(FrdScreen.pickedIn(ids.next(IdSequence.Kind.FRD_SCREEN), frdId,
                "screen-work", "작업 화면", "screen-work", null, null, "webview"));
        frds.updateAfterPick(frdId, "캔버스 대화 중단", "webview", null, Frd.State.DRAFTING, null);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/chat", project.getId(), frdId)
                        .with(user(planner)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"잘못 보낸 요청\",\"screenIds\":[]}"))
                .andExpect(status().isAccepted());
        FrdScreenChatMessage running = chatService.running(frdId);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/chat/{m}/cancel",
                        project.getId(), frdId, running.id()).with(user(planner)).with(csrf()))
                .andExpect(status().isAccepted());

        assertThat(chatMessages.selectById(running.id()).state()).isEqualTo(FrdScreenChatMessage.State.FAILED);
        assertThat(chatMessages.selectById(running.id()).failure()).contains("중단");
    }

    @Test
    void 전체_캔버스에서_화면을_작업_대상에서_제외해도_대화와_이력_행은_보존한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "작업 대상 제외", "선택한 화면을 작업 대상에서 제외한다.", planner.accountId()));
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(screenRowId, frdId,
                "screen-work", "작업 화면", "screen-work", null, null, "webview"));
        frds.updateAfterPick(frdId, "작업 대상 제외", "webview", null, Frd.State.DRAFTING, null);
        FrdScreenChatMessage running = chatService.start(frdId, screenRowId, "버튼 위치를 검토해 주세요.");
        chatService.complete(running.id(), "검토 내용을 남겼습니다.", "screen-session-id");

        String html = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("수정 화면에서 제외하고 다시 잠그기")
                .contains("/canvas/screens/" + screenRowId + "/exclude")
                .contains("data-canvas-delete disabled")
                .doesNotContain("class=\"fc-exclude-button\"")
                .doesNotContain("class=\"fc-node-exclude\"");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/delete",
                        project.getId(), frdId).param("screenRowId", screenRowId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "삭제할 신규 화면을 찾을 수 없습니다."));
        assertThat(screens.selectById(screenRowId)).isNotNull();

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/exclude",
                        project.getId(), frdId).param("screenRowId", screenRowId)
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(screens.selectByFrdId(frdId)).isEmpty();
        assertThat(screens.selectById(screenRowId)).isNull();
        assertThat(screens.selectIncludingExcludedById(screenRowId)).isNotNull();
        assertThat(chatMessages.selectByScreenId(screenRowId)).hasSize(2);
        verify(workspaces).discardScreenFiles(project.getId(), frdId, "webview", "screen-work");
    }

    @Test
    void 전체_캔버스에서_현재_화면을_독립된_신규_화면으로_복제한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "화면 복제", "현재 화면을 바탕으로 신규 상세 화면을 만든다.", planner.accountId()));
        String sourceRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.drafted(sourceRowId, frdId,
                "screen-source", "원본 화면", "상세", null, "webview"));
        String sourceHtml = "<!doctype html><html><head></head><body data-screen-id=\"screen-source\">"
                + "<button id=\"screen-source-e01\" data-element-id=\"screen-source-e01\" "
                + "data-nav-target=\"screen-next\" onclick=\"openScreen('screen-next')\">확인</button></body></html>";
        screens.updateGenerated(sourceRowId, sourceHtml, "원본 수정", java.time.Instant.now());
        frds.updateAfterPick(frdId, "화면 복제", "webview", null, Frd.State.DRAFTING, null);

        Path pages = paths.frdWorktree(project.getId(), frdId).resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.deleteIfExists(pages.resolve("tmp-0000002.html"));
        Files.deleteIfExists(pages.resolve("tmp-0000002.md"));
        Files.writeString(pages.resolve("screen-source.html"), sourceHtml, StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-source.md"), """
                --- 꼬리표 ---
                id: screen-source / system: webview / 기능: 원본 화면 / 과업: []
                --- 화면명세 ---
                화면명: 원본 화면
                --- 정의 ---
                - 구분: 버튼 / 앵커: screen-source-e01 / 라벨: 확인
                - 구분: 이동 / 앵커: screen-source-e01 / 이동: screen-next
                """, StandardCharsets.UTF_8);

        String canvasHtml = mvc.perform(get("/projects/{p}/artifacts/frds/{f}/canvas", project.getId(), frdId)
                        .with(user(planner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(canvasHtml).contains("data-canvas-duplicate disabled")
                .contains("id=\"fc-duplicate-title\">새 화면으로 복제")
                .contains("화면 유형은 원본과 동일하게 복제합니다.")
                .contains("화면 연결 정보는 복제하지 않습니다.")
                .doesNotContain("name=\"screenType\"")
                .contains("/canvas/screens/duplicate");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/duplicate",
                        project.getId(), frdId)
                        .param("sourceScreenRowId", sourceRowId)
                        .param("screenName", "복제 상세 화면")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        FrdScreen duplicated = screens.selectByFrdId(frdId).stream()
                .filter(screen -> !screen.id().equals(sourceRowId)).findFirst().orElseThrow();
        assertThat(duplicated.isNewScreen()).isTrue();
        assertThat(duplicated.screenName()).isEqualTo("복제 상세 화면");
        assertThat(duplicated.screenType()).isEqualTo("상세");
        assertThat(duplicated.state()).isEqualTo(FrdScreen.State.GENERATED);
        assertThat(duplicated.html()).contains(duplicated.screenId() + "-e01")
                .contains("data-screen-id=\"" + duplicated.screenId() + "\"")
                .doesNotContain("data-nav-target")
                .doesNotContain("onclick");
        assertThat(screenHistories.selectLatestByScreenId(duplicated.id())).isNotNull();
        assertThat(Files.readString(pages.resolve(duplicated.screenId() + ".html"), StandardCharsets.UTF_8))
                .isEqualTo(duplicated.html());
        assertThat(Files.readString(pages.resolve(duplicated.screenId() + ".md"), StandardCharsets.UTF_8))
                .contains("id: " + duplicated.screenId() + " / system: webview")
                .contains("화면명: 복제 상세 화면")
                .contains("앵커: " + duplicated.screenId() + "-e01")
                .doesNotContain("이동: screen-next")
                .contains("--- 원본 글 ---");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/delete",
                        project.getId(), frdId)
                        .param("screenRowId", duplicated.id())
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(screens.selectByFrdId(frdId))
                .extracting(FrdScreen::id)
                .containsExactly(sourceRowId);
        verify(workspaces).discardScreenFiles(
                project.getId(), frdId, "webview", duplicated.screenId());
    }

    @Test
    void 상세_화면에서_기존_솔루션_화면을_작업_대상에서_삭제하고_상세_화면에_남는다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "상세 화면 삭제", "상세 화면에서 작업 대상을 삭제한다.", planner.accountId()));
        String screenRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        FrdScreen screen = FrdScreen.pickedIn(screenRowId, frdId,
                "screen-detail-delete", "삭제할 기존 화면", "screen-detail-delete",
                null, "상세 화면 수정", "webview");
        screens.insert(screen);
        frds.updateAfterPick(frdId, "상세 화면 삭제", "webview", null, Frd.State.DRAFTING, null);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/screens/exclude",
                        project.getId(), frdId)
                        .param("screenRowId", screenRowId)
                        .param("returnTo", "detail")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/%s/artifacts/frds/%s"
                        .formatted(project.getId(), frdId)));

        assertThat(screens.selectByFrdId(frdId)).isEmpty();
        verify(workspaces).discardScreenFiles(project.getId(), frdId, "webview", screen.screenId());
    }

    @Test
    void 전체_캔버스에서_화면_연결을_추가하고_방향을_바꾼_뒤_삭제한다() throws Exception {
        BuilderUser planner = planner();
        Project project = readyProject();
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "화면 연결", "조회 목록과 상세 화면을 연결한다.", planner.accountId()));
        String listRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String detailRowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.drafted(listRowId, frdId,
                "screen-list", "조회 목록", "목록", null, "webview"));
        screens.insert(FrdScreen.drafted(detailRowId, frdId,
                "screen-detail", "가맹점 상세", "상세", null, "webview"));
        String listHtml = "<!doctype html><html><body data-screen-id=\"screen-list\"><button>상세 열기</button></body></html>";
        String detailHtml = "<!doctype html><html><body data-screen-id=\"screen-detail\"><button>목록</button></body></html>";
        screens.updateGenerated(listRowId, listHtml, "목록 초안", java.time.Instant.now());
        screens.updateGenerated(detailRowId, detailHtml, "상세 초안", java.time.Instant.now());
        frds.updateAfterPick(frdId, "화면 연결", "webview", null, Frd.State.DRAFTING, null);

        Path pages = paths.frdWorktree(project.getId(), frdId).resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve("screen-list.html"), listHtml, StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-detail.html"), detailHtml, StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-list.md"), """
                --- 화면명세 ---
                화면명: 조회 목록
                --- 정의 ---
                - 구분: 기능 / 앵커: screen-list-e01 / 해설: 상세 열기
                """, StandardCharsets.UTF_8);
        Files.writeString(pages.resolve("screen-detail.md"), """
                --- 화면명세 ---
                화면명: 가맹점 상세
                --- 정의 ---
                - 구분: 기능 / 앵커: {{draftKey}}-e01 / 해설: 목록으로 이동
                """, StandardCharsets.UTF_8);

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/relations/save", project.getId(), frdId)
                        .param("sourceScreenId", "screen-list")
                        .param("targetScreenId", "screen-detail")
                        .param("anchor", "screen-list-e01")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(Files.readString(pages.resolve("screen-list.md"), StandardCharsets.UTF_8))
                .contains("구분: 이동 / 앵커: screen-list-e01 / 이동: screen-detail / 라벨: 가맹점 상세 열기");
        assertThat(screenHistories.selectLatestByScreenId(listRowId).md()).contains("이동: screen-detail");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/relations/save", project.getId(), frdId)
                        .param("originalSourceScreenId", "screen-list")
                        .param("originalTargetScreenId", "screen-detail")
                        .param("originalAnchor", "screen-list-e01")
                        .param("sourceScreenId", "screen-detail")
                        .param("targetScreenId", "screen-list")
                        .param("anchor", "{{draftKey}}-e01")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(Files.readString(pages.resolve("screen-list.md"), StandardCharsets.UTF_8))
                .doesNotContain("이동: screen-detail");
        assertThat(Files.readString(pages.resolve("screen-detail.md"), StandardCharsets.UTF_8))
                .contains("구분: 이동 / 앵커: {{draftKey}}-e01 / 이동: screen-list / 라벨: 조회 목록 열기");

        mvc.perform(post("/projects/{p}/artifacts/frds/{f}/canvas/relations/delete", project.getId(), frdId)
                        .param("originalSourceScreenId", "screen-detail")
                        .param("originalTargetScreenId", "screen-list")
                        .param("originalAnchor", "{{draftKey}}-e01")
                        .with(user(planner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(Files.readString(pages.resolve("screen-detail.md"), StandardCharsets.UTF_8))
                .doesNotContain("이동: screen-list");
        assertThat(screenHistories.selectLatestByScreenId(detailRowId).changeList())
                .containsExactly("화면 연결을 삭제했습니다.");
    }

    private Project readyProject() {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "캔버스 시험", "https://gitlab.example.com/canvas.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser planner() {
        Account account = accounts.selectByLoginId("canvasplanner").orElseGet(() -> {
            Account created = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "canvasplanner", "김기획",
                    "canvas@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(created);
            return created;
        });
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
