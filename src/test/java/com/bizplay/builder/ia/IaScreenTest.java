package com.bizplay.builder.ia;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class IaScreenTest extends AbstractDbTest {

    private Path cloneToClean;

    @Autowired MockMvc mvc;
    @Autowired ProjectMapper projects;
    @Autowired ProjectPaths paths;
    @Autowired AccountMapper accounts;
    @Autowired SecretSealer sealer;
    @Autowired PasswordEncoder encoder;
    @Autowired IaMapper iaRows;
    @Autowired IaService iaService;
    @Autowired ProjectSystemService projectSystems;
    @Autowired ScreenStandardIdMapper standardIds;

    @Test
    void 메뉴를_선택하면_전체_화면_이동_없이_상세만_갱신한다() throws Exception {
        String interaction = Files.readString(
                Path.of("src/main/resources/static/js/menu-tree-workbench.js"), StandardCharsets.UTF_8);
        String styles = Files.readString(
                Path.of("src/main/resources/static/css/screens.css"), StandardCharsets.UTF_8);

        assertThat(interaction).contains(
                "event.preventDefault()",
                "fetch(endpoint",
                "response.json()",
                "selectionCache.get(nodeKey)",
                "applySelection(selection)",
                "openEditDialog(edit)",
                "dialog.showModal()",
                "form[data-menu-dialog-form]",
                "submitEditForm(form)",
                "[data-menu-dialog-open]",
                "replaceChangedWorkbench(nextDocument)",
                "toggleBranch(toggle)",
                "children.hidden = !expanded",
                "button.setAttribute(\"aria-expanded\"",
                "form[data-menu-move-form]",
                "moveMenu(form);\n  }, true);",
                "moveTreeNode(result.nodeKey, result.direction)",
                "\"Accept\": \"application/json\"",
                "data-menu-tree-feedback",
                // ⛔ 「화면 열기」는 최초 렌더와 인플레이스 둘 다 고쳐야 한다(과업 002).
                //   여기가 빠지면 트리에서 마디를 눌렀을 때 버튼이 안 따라온다.
                "selection.canOpenScreen",
                "encodeURIComponent(selection.originalScreenId)",
                // ⚠ basePath 는 menu-tree 경로다 — 그것으로 솔루션 주소를 만들면 죽은 주소가 난다.
                "dataset.openScreenBase",
                "setBusy(true)",
                "ia-detail-loading",
                "window.history.pushState",
                "window.addEventListener(\"popstate\"")
                .doesNotContain("synchronizeTree(nextDocument)", "window.setTimeout(");
        assertThat(styles).contains(
                ".ia-tree-toggle:hover",
                ".ia-tree-tools .button:not(:disabled), .ia-detail-edit, .ia-detail-open-screen { cursor: pointer; }",
                ".ia-tree-tools .button:not(:disabled):hover, .ia-detail-edit:hover",
                // ⚠ 「화면 열기」가 연필과 같은 아이콘 단추라 같은 선택자 묶음에 얹혔다(과업 002).
                //   이 줄에서 빠지면 svg 에 크기 규칙이 안 걸려 머리글이 통째로 밀린다.
                ".ia-tree-tools svg, .ia-detail-edit svg, .ia-detail-open-screen svg { width: 14px;",
                ".ia-tree-tools .button:disabled { transform: none; cursor: not-allowed; }",
                ".ia-tree-label strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;",
                ".ia-detail-loading",
                ".ia-menu-edit-dialog[open]",
                ".ia-menu-edit-dialog__body",
                ".ia-menu-edit-spinner",
                ".ia-menu-current-summary",
                ".ia-menu-information",
                ".ia-menu-edit-dialog .ia-menu-editor { gap: var(--space-3); align-content: start; }",
                "height: min(460px, calc(100dvh - 48px))",
                "overflow: hidden",
                "height: clamp(500px, 68vh, 720px)")
                .doesNotContain(".ia-loading-bar");
    }

    @Test
    void 메뉴_기능_버튼으로_같은_단계의_가지를_옮기고_행을_삭제한다() throws Exception {
        Project project = readyProject("IA 기능 버튼 시험");
        seedPlanningRepo(project.getId());
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        IaService.Workbench imported = iaService.findOrImport(project.getId(), "backoffice", accountId);
        String base = "/projects/" + project.getId() + "/artifacts/menu-tree/backoffice";
        IaRow first = imported.rows().get(0);
        IaRow sibling = new IaRow(ids.next(IdSequence.Kind.IA_ROW), imported.structure().id(), 30,
                "approval/other", "전자결재", "기타", null, null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(sibling);

        mvc.perform(post(base + "/nodes/move")
                        .with(user(superUser())).with(csrf())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("version", Integer.toString(imported.structure().version()))
                        .param("nodeKey", "approval/document")
                        .param("direction", "down"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(imported.structure().version() + 1))
                .andExpect(jsonPath("$.nodeKey").value("approval/document"))
                .andExpect(jsonPath("$.direction").value("down"));

        IaStructure movedStructure = iaRows.selectStructure(project.getId(), "backoffice").orElseThrow();
        java.util.List<IaRow> movedRows = iaRows.selectRows(movedStructure.id());
        assertThat(movedRows).hasSize(3);
        assertThat(movedRows.get(0).id()).isEqualTo(sibling.id());
        assertThat(movedRows.subList(1, 3)).extracting(IaRow::id)
                .containsExactlyElementsOf(imported.rows().stream().map(IaRow::id).toList());
        assertThat(movedRows.get(1).id()).isEqualTo(first.id());

        IaRow deleteTarget = movedRows.get(0);
        mvc.perform(post(base + "/rows/" + deleteTarget.id() + "/delete")
                        .with(user(superUser())).with(csrf())
                        .param("version", Integer.toString(movedStructure.version())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(base));

        assertThat(iaRows.selectRows(movedStructure.id()))
                .extracting(IaRow::id)
                .doesNotContain(deleteTarget.id());
    }

    @Test
    void 사람이_고치지_않은_옛_트리는_현재_화면명까지_자동으로_다시_세운다() throws Exception {
        Project project = readyProject("IA 옛 트리 갱신 시험");
        seedPlanningRepo(project.getId());
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        IaService.Workbench imported = iaService.findOrImport(project.getId(), "backoffice", accountId);
        IaRow current = imported.rows().stream()
                .filter(row -> "bo-appr-detail".equals(row.screenId()))
                .findFirst().orElseThrow();
        java.util.List<String> oldDepths = current.depths().subList(0, current.depths().size() - 1);
        IaRow legacy = new IaRow(current.id(), current.structureId(), current.rowOrder(),
                current.pathKey().substring(0, current.pathKey().lastIndexOf('/')),
                depth(oldDepths, 0), depth(oldDepths, 1), depth(oldDepths, 2), depth(oldDepths, 3),
                depth(oldDepths, 4), depth(oldDepths, 5), depth(oldDepths, 6),
                current.userType(), current.menuType(), current.screenType(), current.screenId(),
                current.updatedAt(), current.updatedBy());
        iaRows.updateRow(legacy);

        IaService.Workbench upgraded = iaService.findOrImport(project.getId(), "backoffice", accountId);
        IaRow detail = upgraded.rows().stream()
                .filter(row -> "bo-appr-detail".equals(row.screenId()))
                .findFirst().orElseThrow();

        assertThat(upgraded.structure().id()).isNotEqualTo(imported.structure().id());
        assertThat(detail.pathKey()).endsWith("/bo-appr-detail");
        assertThat(detail.depths()).endsWith("결재 문서 목록", "결재 상세");

        // ⛔ 가장 값비싼 실패 모드 — 비교가 한 글자라도 어긋나면 열 때마다 다시 세운다(브리프 §6-1).
        //    재작성 뒤에는 저장된 네 값과 prepare() 네 값이 같아지므로, 같은 시스템을 다시 열어도
        //    구조 id 가 그대로여야 한다.
        IaService.Workbench reopened = iaService.findOrImport(project.getId(), "backoffice", accountId);
        assertThat(reopened.structure().id()).isEqualTo(upgraded.structure().id());
    }

    @Test
    void 손댄_구조는_version이_0이_아니면_다시_세우지_않는다() throws Exception {
        Project project = readyProject("IA 손댄 구조 유지 시험");
        seedPlanningRepo(project.getId());
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        IaService.Workbench imported = iaService.findOrImport(project.getId(), "backoffice", accountId);
        String base = "/projects/" + project.getId() + "/artifacts/menu-tree/backoffice";

        // 이동할 형제가 있어야 옮길 수 있다 — 메뉴_기능_버튼으로... 시험과 같은 자리다.
        IaRow sibling = new IaRow(ids.next(IdSequence.Kind.IA_ROW), imported.structure().id(), 30,
                "approval/other", "전자결재", "기타", null, null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(sibling);

        // 사람이 메뉴를 한 번 옮겨 version 을 0에서 올린다 — ④는 그 뒤로 파일을 읽지도 않아야 한다.
        mvc.perform(post(base + "/nodes/move")
                        .with(user(superUser())).with(csrf())
                        .accept(MediaType.APPLICATION_JSON)
                        .param("version", Integer.toString(imported.structure().version()))
                        .param("nodeKey", "approval/document")
                        .param("direction", "down"))
                .andExpect(status().isOk());

        IaStructure touched = iaRows.selectStructure(project.getId(), "backoffice").orElseThrow();
        assertThat(touched.version()).isGreaterThan(0);

        // 옛 모양(마지막 마디 제거)으로 행 하나를 되돌린다 — version 이 0이 아니므로 비교 없이 거짓이어야 한다.
        IaRow current = iaRows.selectRows(touched.id()).stream()
                .filter(row -> "bo-appr-detail".equals(row.screenId()))
                .findFirst().orElseThrow();
        java.util.List<String> oldDepths = current.depths().subList(0, current.depths().size() - 1);
        IaRow legacy = new IaRow(current.id(), current.structureId(), current.rowOrder(),
                current.pathKey().substring(0, current.pathKey().lastIndexOf('/')),
                depth(oldDepths, 0), depth(oldDepths, 1), depth(oldDepths, 2), depth(oldDepths, 3),
                depth(oldDepths, 4), depth(oldDepths, 5), depth(oldDepths, 6),
                current.userType(), current.menuType(), current.screenType(), current.screenId(),
                current.updatedAt(), current.updatedBy());
        iaRows.updateRow(legacy);

        IaService.Workbench reopened = iaService.findOrImport(project.getId(), "backoffice", accountId);

        assertThat(reopened.structure().id()).isEqualTo(touched.id());
        IaRow untouchedDetail = reopened.rows().stream()
                .filter(row -> "bo-appr-detail".equals(row.screenId()))
                .findFirst().orElseThrow();
        assertThat(untouchedDetail.pathKey()).isEqualTo(legacy.pathKey())
                .doesNotEndWith("/bo-appr-detail");
    }

    /**
     * 과업 002 의 끝 조건이 지나는 자리 — IA 에서 솔루션 템플릿으로 건너가는 길.
     *
     * <p>⚠ <b>{@code canOpenScreen} 은 색인 유무이지 실물(html) 유무가 아니다.</b> 실물이 없어도
     * 버튼은 뜨고, 눌러 닿은 상세가 「아직 없다」를 말한다. 감추면 기획자가 <b>왜 없는지</b>를
     * 읽을 자리가 사라진다(002 계획서 §4 확정 결정).
     *
     * <p>⛔ 화면이 안 걸린 <b>그룹 마디</b>에서는 감춘다 — 누르면 404 로 가는 단추다.
     */
    @Test
    void 화면이_걸린_마디만_솔루션_템플릿으로_가는_길을_낸다() throws Exception {
        Project project = readyProject("IA 화면 열기 시험");
        seedPlanningRepo(project.getId());
        String base = "/projects/" + project.getId() + "/artifacts/menu-tree/backoffice";

        String onScreen = mvc.perform(get(base)
                        .queryParam("nodeKey", "approval/document/write/basic/bo-appr-list")
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(onScreen)
                .as("화면이 걸린 마디는 그 화면ID 의 솔루션 템플릿 상세를 가리킨다")
                .contains("ia-detail-open-screen", "aria-label=\"화면 열기\"", "title=\"화면 열기\"",
                        "/projects/" + project.getId() + "/artifacts/solution-mockups/bo-appr-list");
        assertThat(openScreenAnchor(onScreen))
                .as("화면이 걸렸으면 안 감춘다")
                .doesNotContain("hidden");
        assertThat(onScreen)
                .as("⚠ JS 는 menu-tree 경로를 못 쓴다 — 기준 주소를 앵커에 심어 넘긴다")
                .contains("data-open-screen-base=\"/projects/" + project.getId()
                        + "/artifacts/solution-mockups\"");

        String onGroup = mvc.perform(get(base)
                        .queryParam("nodeKey", "approval/document")
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(openScreenAnchor(onGroup))
                .as("화면이 안 걸린 그룹 마디에서는 감추고 죽은 주소를 안 남긴다")
                .contains("hidden")
                .contains("href=\"#\"");
    }

    /** 「화면 열기」 앵커 한 개를 여는 태그까지만 떼어 온다. */
    private String openScreenAnchor(String html) {
        int at = html.indexOf("ia-detail-open-screen");
        assertThat(at).as("「화면 열기」 앵커가 화면에 있어야 한다").isNotNegative();
        return html.substring(html.lastIndexOf('<', at), html.indexOf('>', at) + 1);
    }

    @Test
    void 최초_ia를_가져오면_depth_작업대가_DB_행을_그린다() throws Exception {
        Project project = readyProject("IA 화면 시험");
        seedPlanningRepo(project.getId());
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID), project.getId(),
                "bo-appr-list", "PS-BO-APR-010-L01", ScreenStandardId.Origin.S, 1));
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID), project.getId(),
                "bo-appr-detail", "PS-BO-APR-010-D01", ScreenStandardId.Origin.S, 2));
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID), project.getId(),
                "bo-orphan-guide", "PS-BO-GDE-010-G01", ScreenStandardId.Origin.S, 3));
        String base = "/projects/" + project.getId() + "/artifacts/menu-tree";

        String list = mvc.perform(get(base).with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).contains("백오피스", "최종 수정일", "최종 수정자",
                "data-table--dense", "artifact-list-link", "/artifacts/menu-tree/backoffice")
                .doesNotContain("document-link")
                .doesNotContain("작업 상태", "화면 색인", "구조 행", "확정 차수",
                        "status-badge--waiting", "ia-system-table", "ia-system-name__icon", "메뉴구조도 시작");

        String workbench = mvc.perform(get(base + "/backoffice").with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String editorName = accounts.selectByLoginId("admin").orElseThrow().getName();
        assertThat(workbench).contains("최종 수정일", "최종 수정자", editorName,
                        "목록으로", "메뉴 구조", "전자결재", "결재 문서", "문서 작성", "기본 정보",
                        "bo-appr-list", "상위 메뉴", "뎁스", "Depth 1", "메뉴 유형", "그룹", "적용 대상", "화면 유형", "화면관리번호", "화면 ID",
                        "미연결 화면", "미연결 안내", "bo-orphan-guide", "메뉴 연결",
                        "화면 이름을 기준으로 분류됨", "ia-split--readable", "ia-readable-tree", "ia-tree--lines",
                        "data-menu-tree-workbench", "data-menu-tree-tools", "data-menu-tree-detail",
                        "data-menu-dialog", "data-menu-dialog-open", "data-menu-edit-content", "data-menu-dialog-close",
                        "data-menu-tree-feedback", "data-menu-node-key", "data-menu-move-form", "ia-detail-loading", "data-tree-toggle",
                         "aria-expanded=\"true\"", "하위 메뉴 접기",
                         "aria-expanded=\"false\"", "하위 메뉴 펼치기",
                        "/js/menu-tree-workbench.js",
                        "PS-BO-APR-010-L01-S", "PS-BO-APR-010-D01-S")
                .doesNotContain("작업 상태", "확정 차수", "같은 단계 위치", "사용자 유형", "기술 정보", "노드 경로", "원본 화면 ID", "id=\"linked-screen-title\"", "name=\"depth1\"", "행 저장",
                        "최초 IA 가져오기", "IA 확정 및 게시", "<th scope=\"col\">순서</th>")
                .doesNotContain("시스템 목록", "메뉴구조도 초기화")
                .doesNotContainPattern("하위 \\d+개");

        String selectedMenu = mvc.perform(get(base + "/backoffice")
                        .queryParam("nodeKey", "approval/document/write/basic/bo-appr-list")
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(selectedMenu).contains("메뉴 수정", "메뉴 정보", "뎁스", "Depth 5", "메뉴 유형", "화면",
                "적용 대상", "전체", "PS-BO-APR-010-L01-S", "화면 유형", "목록",
                "화면 요약", "결재 문서를 조건에 따라 조회하고 작성 화면으로 이동한다.")
                .doesNotContain("같은 단계 위치", "사용자 유형", "표준 화면 ID");

        mvc.perform(get(base + "/backoffice/selection")
                        .queryParam("nodeKey", "approval/document/write/basic/bo-appr-list")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeKey").value("approval/document/write/basic/bo-appr-list"))
                .andExpect(jsonPath("$.label").value("결재 문서 목록"))
                .andExpect(jsonPath("$.depth").value(5))
                .andExpect(jsonPath("$.menuType").value("화면"))
                .andExpect(jsonPath("$.applicationTarget").value("전체"))
                .andExpect(jsonPath("$.standardScreenId").value("PS-BO-APR-010-L01-S"))
                .andExpect(jsonPath("$.screenType").value("목록"))
                .andExpect(jsonPath("$.screenSummary").value("""
                        결재 문서를 조건에 따라 조회하고 작성 화면으로 이동한다.
                        주요 기능: 조건 조회 · 결재 문서 작성
                        연결 화면: 결재 상세"""));

        String importedList = mvc.perform(get(base).with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(importedList).contains(editorName);

        IaStructure structure = iaRows.selectStructure(project.getId(), "backoffice").orElseThrow();
        IaService.Workbench coverage = iaService.find(project.getId(), "backoffice").orElseThrow();
        assertThat(coverage.unlinkedScreens()).extracting(screen -> screen.screenId())
                .containsExactly("bo-orphan-guide");
        assertThat(coverage.sharedScreens()).extracting(screen -> screen.screenId())
                .containsExactly("bo-shared-pop");
        assertThat(iaRows.selectScreenProfiles(structure.id()))
                .extracting(IaScreenProfile::screenId, IaScreenProfile::screenKind,
                        IaScreenProfile::screenType, IaScreenProfile::typeSource)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("bo-appr-list", IaScreenProfile.ScreenKind.SCREEN,
                                IaScreenProfile.ScreenType.LIST, IaScreenProfile.TypeSource.ID),
                        org.assertj.core.groups.Tuple.tuple("bo-orphan-guide", IaScreenProfile.ScreenKind.SCREEN,
                                IaScreenProfile.ScreenType.GUIDE, IaScreenProfile.TypeSource.NAME));

        Path index = paths.cloneDir(project.getId()).resolve("index.json");
        Files.writeString(index, Files.readString(index).replace("\"화면유형\":\"목록\"", "\"화면유형\":\"상세\""));
        Files.setLastModifiedTime(index, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        IaService.Workbench afterReindex = iaService.find(project.getId(), "backoffice").orElseThrow();
        assertThat(afterReindex.rowViews().get(0).screen().screenType()).isEqualTo("목록");
        String rowId = iaRows.selectRows(structure.id()).get(0).id();

        String editor = mvc.perform(get(base + "/backoffice/rows/" + rowId + "/edit")
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(editor).contains("메뉴 수정", "메뉴 정보", "메뉴명", "결재 문서 목록",
                "화면 ID", "PS-BO-APR-010-L01-S",
                "name=\"parentNodeKey\"", "전자결재 &gt; 결재 문서", ">저장</button>")
                .doesNotContain("name=\"rowOrder\"", "name=\"pathKey\"", "name=\"depth1\"",
                        "name=\"userType\"", "name=\"screenType\"", "name=\"menuName\"",
                        "name=\"menuType\"", "name=\"screenId\"", "화면 찾기", "화면 선택",
                        "메뉴 위치 변경", "이동할 메뉴", "이동할 위치", "연결 화면", "메뉴 행 삭제");

        String newRow = mvc.perform(get(base + "/backoffice/rows/new?screenId=bo-orphan-guide")
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(newRow).contains("메뉴 연결", "메뉴 정보", "name=\"menuName\"", "value=\"미연결 안내\"",
                        "name=\"parentNodeKey\"", "type=\"hidden\" name=\"screenId\"", "value=\"bo-orphan-guide\"",
                        "PS-BO-GDE-010-G01-S", "미연결 화면 목록에서 선택한 화면으로 고정됩니다.", ">추가</button>")
                .doesNotContain("name=\"rowOrder\"", "name=\"pathKey\"", "name=\"depth1\"",
                        "name=\"userType\"", "name=\"menuType\"", "name=\"screenType\"", "<select class=\"field__control\" id=\"screen-id\"");

        String groupForm = mvc.perform(get(base + "/backoffice/rows/new?group=true").with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(groupForm).contains("메뉴 그룹 추가", "메뉴 그룹 정보", "그룹은 화면을 연결하지 않고 하위 메뉴를 묶습니다.",
                        "name=\"menuName\"", "name=\"parentNodeKey\"", ">그룹 추가</button>")
                .doesNotContain("name=\"screenId\"", "연결 화면");

        mvc.perform(post(base + "/backoffice/rows").with(user(superUser())).with(csrf())
                        .param("version", "0")
                        .param("menuName", "미연결 안내")
                        .param("parentNodeKey", "approval/document")
                        .param("screenId", "bo-orphan-guide"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(base + "/backoffice"));
        IaRow addedMenu = iaRows.selectRows(structure.id()).stream()
                .filter(row -> "bo-orphan-guide".equals(row.screenId())).findFirst().orElseThrow();
        assertThat(addedMenu.pathKey()).isEqualTo("approval/document/bo-orphan-guide");
        assertThat(addedMenu.depths()).containsExactly("전자결재", "결재 문서", "미연결 안내");

        mvc.perform(post(base + "/backoffice/rows/" + rowId).with(user(superUser())).with(csrf())
                        .param("version", "1")
                        .param("parentNodeKey", "approval/document"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(base + "/backoffice?rowId=" + rowId));
        IaRow updatedMenu = iaRows.selectRows(structure.id()).stream()
                .filter(row -> row.id().equals(rowId)).findFirst().orElseThrow();
        assertThat(updatedMenu.pathKey()).isEqualTo("approval/document/bo-appr-list");
        assertThat(updatedMenu.depths()).containsExactly("전자결재", "결재 문서", "결재 문서 목록");
        assertThat(updatedMenu.screenId()).isEqualTo("bo-appr-list");
        assertThat(updatedMenu.screenType()).isEqualTo("목록");
        IaRow updatedChild = iaRows.selectRows(structure.id()).stream()
                .filter(row -> "bo-appr-detail".equals(row.screenId())).findFirst().orElseThrow();
        assertThat(updatedChild.pathKey()).isEqualTo("approval/document/bo-appr-list/bo-appr-detail");
        assertThat(updatedChild.depths()).containsExactly("전자결재", "결재 문서", "결재 문서 목록", "결재 상세");

        mvc.perform(post(base + "/backoffice/reset").with(user(superUser())).with(csrf())
                        .param("version", "1"))
                .andExpect(status().isNotFound());
        assertThat(iaRows.selectStructure(project.getId(), "backoffice")).isPresent();
        assertThat(iaRows.selectRows(structure.id())).isNotEmpty();
        assertThat(iaRows.selectScreenProfiles(structure.id())).isNotEmpty();
    }

    @Test
    void 상위_메뉴를_옮길_때_이름이_달라도_경로키가_같아지면_거절한다() throws Exception {
        // ⑤ 경로키 중복 그물(브리프 §3-1) — 라벨은 다르지만 새 pathKey 마지막 마디가 같아지는
        // 경우를 짠다. 형제 이름 중복 검사(IaService:287-289)가 먼저 걸리면 이 시험은 실패해야
        // 정상이다 — 라벨을 일부러 다르게 둬서 그 검사를 통과시킨 뒤 경로키 검사만 재는지 본다.
        Project project = readyProject("IA 경로키 충돌 시험");
        seedPlanningRepo(project.getId());
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        IaService.Workbench imported = iaService.findOrImport(project.getId(), "backoffice", accountId);
        String structureId = imported.structure().id();

        // approval/target/dup — 옮길 자리에 이미 앉아 있는 행. 라벨은 "기존메뉴".
        IaRow occupied = new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId, 100,
                "approval/target/dup", "전자결재", "타겟", "기존메뉴", null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(occupied);

        // approval/dup — 옮길 행. 마지막 마디("dup")가 occupied 와 같지만 라벨("이동할메뉴")은 다르다.
        String movingId = ids.next(IdSequence.Kind.IA_ROW);
        IaRow moving = new IaRow(movingId, structureId, 110,
                "approval/dup", "전자결재", "이동할메뉴", null, null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(moving);

        int version = iaRows.selectStructure(project.getId(), "backoffice").orElseThrow().version();
        assertThatThrownBy(() -> iaService.updateMenuLocation(project.getId(), "backoffice", movingId, version,
                new IaService.MenuLocationInput("approval/target"), accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이동할 위치")
                .hasMessageContaining("기존메뉴")
                .hasMessageNotContaining("동일한 메뉴명");
    }

    @Test
    void 상위_메뉴를_옮길_때_옮길_자리_마디가_이미_차_있으면_거절한다() throws Exception {
        // 🟡 ⑤의 자손 경로 충돌 — 지금까지 시험은 옮기는 행 자신(newPrefix)만 쟀다
        // (코드리뷰 지적, 2026-09-04). 여기는 옮기는 행 자신은 안 부딪히고, 딸려 옮겨지는
        // 자손의 새 경로키만 기존 행과 부딪히는 경우를 짠다(IaService:326-327).
        Project project = readyProject("IA 자손 경로 충돌 시험");
        seedPlanningRepo(project.getId());
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        IaService.Workbench imported = iaService.findOrImport(project.getId(), "backoffice", accountId);
        String structureId = imported.structure().id();

        // approval/movegroup — 옮길 그룹. 라벨은 "이동할그룹".
        String movingGroupId = ids.next(IdSequence.Kind.IA_ROW);
        IaRow movingGroup = new IaRow(movingGroupId, structureId, 500,
                "approval/movegroup", "전자결재", "이동할그룹", null, null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(movingGroup);

        // approval/movegroup/mv-child — 그 아래 딸린 자손. 그룹을 옮기면 함께 옮겨진다.
        IaRow movingChild = new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId, 510,
                "approval/movegroup/mv-child", "전자결재", "이동할그룹", "자식메뉴", null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(movingChild);

        // approval/target — 옮길 자리. 라벨 "타겟그룹"이라 이동할그룹과 이름이 안 겹친다.
        IaRow targetGroup = new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId, 520,
                "approval/target", "전자결재", "타겟그룹", null, null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(targetGroup);

        // approval/target/movegroup/mv-child — 옮기는 그룹 자신(approval/target/movegroup)이
        // 아니라 그 자손이 새로 얻을 경로키에 이미 앉아 있는 행. 라벨은 "기존자식".
        IaRow occupiedDescendant = new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId, 530,
                "approval/target/movegroup/mv-child", "전자결재", "타겟그룹", "예전그룹", "기존자식", null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(occupiedDescendant);

        int version = iaRows.selectStructure(project.getId(), "backoffice").orElseThrow().version();
        // ⚠ 2차 코드리뷰(2026-09-04)로 「마디」 검사가 들어오면서 이 자리가 **더 일찍** 걸린다.
        //    옮기는 그룹의 새 자리 approval/target/movegroup 이 이미 「예전그룹」 마디로 차 있어서,
        //    자손 mv-child 가 옮겨지기 전에 그 마디에서 거절된다. 그래서 문구에 담기는 이름은
        //    잎(「기존자식」)이 아니라 **자리를 차지한 마디(「예전그룹」)** 다 — 사람이 어디를
        //    비워야 하는지 알려면 잎이 아니라 그 마디를 알아야 하므로 이쪽이 맞다.
        assertThatThrownBy(() -> iaService.updateMenuLocation(project.getId(), "backoffice", movingGroupId, version,
                new IaService.MenuLocationInput("approval/target"), accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이동할 위치")
                .hasMessageContaining("예전그룹");
    }

    @Test
    void 상위_메뉴를_옮길_때_옮길_자리에_행_없이_자손만_있어도_거절한다() throws Exception {
        // 🟡 ⑤가 「행」만 보고 「마디」를 안 본다 (코드리뷰 2차, 2026-09-04) — untouchedByPathKey 는
        // pathKey 정확 일치 맵이다. 옮길 자리 approval/target/movegroup 에는 행이 없고 그 자손
        // approval/target/movegroup/other-child 만 있으면 정확 일치로는 못 잡는다 — 옮기면 남의
        // 자손 밑으로 조용히 접붙는다. 새 경로키가 다른 행의 경로키 앞머리인지도 재는지 잰다.
        Project project = readyProject("IA 빈 자리 자손 충돌 시험");
        seedPlanningRepo(project.getId());
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();
        IaService.Workbench imported = iaService.findOrImport(project.getId(), "backoffice", accountId);
        String structureId = imported.structure().id();

        // approval/movegroup — 옮길 행. 옮기면 자기 leafKey "movegroup" 을 그대로 쓴다.
        String movingId = ids.next(IdSequence.Kind.IA_ROW);
        IaRow moving = new IaRow(movingId, structureId, 500,
                "approval/movegroup", "전자결재", "이동할그룹", null, null, null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(moving);

        // approval/target/movegroup/other-child — 옮길 자리(approval/target/movegroup) 행은
        // 없고 그 자손만 있다. 이 행 하나로도 approval/target 마디는 트리에 이미 서 있다.
        IaRow occupiedDescendant = new IaRow(ids.next(IdSequence.Kind.IA_ROW), structureId, 510,
                "approval/target/movegroup/other-child", "전자결재", "타겟그룹", "예전그룹", "기존자식", null, null, null,
                null, null, null, null, null, accountId);
        iaRows.insertRow(occupiedDescendant);

        int version = iaRows.selectStructure(project.getId(), "backoffice").orElseThrow().version();
        assertThatThrownBy(() -> iaService.updateMenuLocation(project.getId(), "backoffice", movingId, version,
                new IaService.MenuLocationInput("approval/target"), accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이동할 위치")
                .hasMessageContaining("예전그룹");
    }

    @AfterEach
    void deletePlanningRepositoryTestClone() throws Exception {
        if (cloneToClean != null) FileSystemUtils.deleteRecursively(cloneToClean);
    }

    private void seedPlanningRepo(String projectId) throws Exception {
        Path clone = paths.cloneDir(projectId);
        FileSystemUtils.deleteRecursively(clone);
        cloneToClean = clone;
        Files.createDirectories(clone.resolve("core/backoffice/pages"));
        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/4","screens":{
                  "bo-appr-list":{"system":"backoffice","ia":{"경로":"approval/document/write/basic","종류":"화면","화면유형":"목록","유형근거":"ID"}},
                  "bo-appr-detail":{"system":"backoffice","ia":{"종류":"화면","상위화면":"bo-appr-list","화면유형":"상세","유형근거":"ID"}},
                  "bo-shared-pop":{"system":"backoffice","ia":{"종류":"팝업","여는화면":["bo-appr-list"],"화면유형":"미분류"}},
                  "bo-orphan-guide":{"system":"backoffice","ia":{"종류":"화면","화면유형":"안내","유형근거":"이름"}}
                },"iaShared":{"backoffice":["bo-shared-pop"]}}
                """, StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("core/backoffice/pages/bo-appr-list.md"), """
                --- 화면명세 ---
                화면명: 결재 문서 목록
                목적: 결재 문서를 조건에 따라 조회하고 작성 화면으로 이동한다.
                --- 꼬리표 ---
                id: bo-appr-list / system: backoffice / 기능: 전자결재 > 결재 문서
                --- 정의 ---
                - 구분: 기능 / 해설: `client.execute()` 호출 뒤 서버 응답의 내부 코드와 긴 기술 분석을 그대로 기록한 설명은 화면 요약에서 제외한다
                - 구분: 기능 / 좌표: id=btnSearch / 해설: 조건 조회 실행 (목록을 다시 읽음)
                - 구분: 기능 / 좌표: id=btnWrite / 해설: 결재 문서 작성 (작성 화면을 엶)
                - 구분: 기능 / 좌표: id=btnClose / 해설: 팝업 닫기 (보조 조작)
                - 구분: 이동 / 이동: bo-appr-detail / 해설: 행 클릭 → 결재 상세로 이동
                """, StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("core/backoffice/pages/bo-appr-detail.md"), "화면명: 결재 상세\n",
                StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("core/backoffice/pages/bo-shared-pop.md"), "화면명: 공용 검색\n",
                StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("core/backoffice/pages/bo-orphan-guide.md"), "화면명: 미연결 안내\n",
                StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("core/backoffice/ia.md"), """
                # backoffice IA 이름표
                ## 이름표
                - approval: 전자결재
                - approval/document: 결재 문서
                - approval/document/write: 문서 작성
                - approval/document/write/basic: 기본 정보
                --- 배치 ---
                - 순서: 010 / 경로: approval/document/write/basic / 화면: bo-appr-list
                <!-- ⚠ 2026-08-21: 뎁스 재료는 색인으로 옮겼다. 이 블록은 이제 안 읽는다. -->
                """, StandardCharsets.UTF_8);

        // ⚠ 시스템 한글 이름은 프로젝트 등록 자료에서 온다(2026-08-21) — 코드에 박힌 표가 아니다.
        //   실물에서는 클론·저장소 업데이트가 manifest.json 을 읽어 앉히고 관리자가 이름을 넣는다.
        Files.writeString(clone.resolve("manifest.json"),
                """
                {"schema":"we-adk-planning-repo/1","systems":[{"id":"backoffice","prefix":"bo"}]}
                """, StandardCharsets.UTF_8);
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId, java.util.Map.of("backoffice", "백오피스"));
    }

    private static String depth(java.util.List<String> depths, int index) {
        return index < depths.size() ? depths.get(index) : null;
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git", "main",
                "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
