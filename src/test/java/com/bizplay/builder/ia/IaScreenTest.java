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
                "setBusy(true)",
                "ia-detail-loading",
                "window.history.pushState",
                "window.addEventListener(\"popstate\"")
                .doesNotContain("synchronizeTree(nextDocument)", "window.setTimeout(");
        assertThat(styles).contains(
                ".ia-tree-toggle:hover",
                ".ia-tree-tools .button:not(:disabled), .ia-detail-edit { cursor: pointer; }",
                ".ia-tree-tools .button:not(:disabled):hover, .ia-detail-edit:hover",
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
