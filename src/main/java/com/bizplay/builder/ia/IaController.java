package com.bizplay.builder.ia;

import com.bizplay.builder.account.BuilderUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 메뉴구조도 목록·작업대. 시스템은 작업대를 열 때 이미 고르며 계층의 뿌리로 그리지 않는다. */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/menu-tree")
public class IaController {

    private static final String ARTIFACT_KEY = "menu-tree";
    private final IaService ia;

    public IaController(IaService ia) {
        this.ia = ia;
    }

    @GetMapping
    public String list(@PathVariable String projectId, Model model) {
        model.addAttribute("systems", ia.systems(projectId));
        shell(model, "IA");
        return "artifacts/menu-tree";
    }

    @GetMapping("/{systemCode}")
    public String workbench(@PathVariable String projectId, @PathVariable String systemCode,
                            @RequestParam(required = false) String nodeKey,
                            @RequestParam(required = false) String rowId,
                            @AuthenticationPrincipal BuilderUser me, Model model) {
        IaService.Workbench workbench = null;
        try {
            workbench = ia.findOrImport(projectId, systemCode, me.accountId());
        } catch (IllegalArgumentException | IllegalStateException failed) {
            model.addAttribute("error", failed.getMessage());
        }
        model.addAttribute("systemCode", systemCode);
        model.addAttribute("workbench", workbench);
        if (workbench != null && !workbench.rowViews().isEmpty()) {
            IaService.NodeSelection selected = ia.selectNode(workbench, nodeKey, rowId);
            model.addAttribute("selectedNode", selected.node());
            model.addAttribute("selected", selected.node().row());
            model.addAttribute("parentLabel", selected.parentLabel());
            model.addAttribute("siblingPosition", selected.position());
            model.addAttribute("siblingCount", selected.siblingCount());
            model.addAttribute("canMoveUp", selected.canMoveUp());
            model.addAttribute("canMoveDown", selected.canMoveDown());
            // ⚠ 이름은 프로젝트 등록 자료에서 온다 — 아직 없으면 코드 그대로다.
            model.addAttribute("systemLabel", ia.systemLabel(projectId, systemCode));
        }
        shell(model, "IA");
        return "artifacts/menu-tree-workbench";
    }

    @GetMapping("/{systemCode}/selection")
    @ResponseBody
    public IaService.SelectionView selection(@PathVariable String projectId, @PathVariable String systemCode,
                                             @RequestParam(required = false) String nodeKey,
                                             @RequestParam(required = false) String rowId) {
        return ia.selection(projectId, systemCode, nodeKey, rowId);
    }

    @GetMapping("/{systemCode}/rows/new")
    public String newRow(@PathVariable String projectId, @PathVariable String systemCode,
                         @RequestParam(required = false) String screenId,
                         @RequestParam(defaultValue = "false") boolean group, Model model) {
        IaService.Workbench workbench = requireWorkbench(projectId, systemCode);
        IaService.CreateOptions options = ia.createOptions(projectId, systemCode, screenId);
        model.addAttribute("systemCode", systemCode);
        model.addAttribute("workbench", workbench);
        model.addAttribute("row", null);
        model.addAttribute("editing", false);
        model.addAttribute("group", group);
        model.addAttribute("createOptions", options);
        model.addAttribute("availableScreens", workbench.unlinkedScreens());
        model.addAttribute("formAction", "/projects/%s/artifacts/menu-tree/%s/rows".formatted(projectId, systemCode));
        shell(model, group ? "메뉴 그룹 추가" : "메뉴 연결");
        return "artifacts/menu-tree-row-form";
    }

    @GetMapping("/{systemCode}/rows/{rowId}/edit")
    public String editRow(@PathVariable String projectId, @PathVariable String systemCode,
                          @PathVariable String rowId, Model model) {
        IaService.Workbench workbench = requireWorkbench(projectId, systemCode);
        IaRow row = workbench.rows().stream().filter(item -> item.id().equals(rowId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("수정할 메뉴 행을 찾지 못했습니다."));
        IaService.EditOptions options = ia.editOptions(projectId, systemCode, rowId);
        model.addAttribute("systemCode", systemCode);
        model.addAttribute("workbench", workbench);
        model.addAttribute("row", row);
        model.addAttribute("editing", true);
        model.addAttribute("editOptions", options);
        model.addAttribute("formAction", "/projects/%s/artifacts/menu-tree/%s/rows/%s"
                .formatted(projectId, systemCode, rowId));
        shell(model, "메뉴 수정");
        return "artifacts/menu-tree-row-form";
    }

    @PostMapping("/{systemCode}/import")
    public String importOnce(@PathVariable String projectId, @PathVariable String systemCode,
                             @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        return act(projectId, systemCode, flash, () -> {
            ia.importOnce(projectId, systemCode, me.accountId());
            flash.addFlashAttribute("message", "최초 ia.md를 DB로 가져왔습니다. 이제 DB 내용이 정본입니다.");
        });
    }

    @PostMapping("/{systemCode}/rows")
    public String addRow(@PathVariable String projectId, @PathVariable String systemCode,
                         @RequestParam int version, CreateMenuForm form,
                         @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        return act(projectId, systemCode, flash, () -> {
            ia.addMenu(projectId, systemCode, version, form.input(), me.accountId());
            flash.addFlashAttribute("message", "메뉴를 추가했습니다.");
        });
    }

    @PostMapping("/{systemCode}/rows/{rowId}")
    public String updateRow(@PathVariable String projectId, @PathVariable String systemCode,
                            @PathVariable String rowId, @RequestParam int version, EditRowForm form,
                            @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        String redirect = act(projectId, systemCode, flash, () -> {
            ia.updateMenuLocation(projectId, systemCode, rowId, version, form.input(), me.accountId());
            flash.addFlashAttribute("message", "메뉴 정보를 저장했습니다.");
        });
        return redirect + "?rowId=" + rowId;
    }

    @PostMapping("/{systemCode}/rows/{rowId}/delete")
    public String deleteRow(@PathVariable String projectId, @PathVariable String systemCode,
                            @PathVariable String rowId, @RequestParam int version,
                            @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        return act(projectId, systemCode, flash, () -> {
            ia.deleteRow(projectId, systemCode, rowId, version, me.accountId());
            flash.addFlashAttribute("message", "메뉴 행을 삭제했습니다.");
        });
    }

    @PostMapping("/{systemCode}/rows/{rowId}/move")
    public String moveRow(@PathVariable String projectId, @PathVariable String systemCode,
                          @PathVariable String rowId, @RequestParam int version,
                          @RequestParam String direction, @AuthenticationPrincipal BuilderUser me,
                          RedirectAttributes flash) {
        try {
            ia.moveRow(projectId, systemCode, rowId, version, direction, me.accountId());
            flash.addFlashAttribute("message", "메뉴 순서를 변경했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/menu-tree/%s?rowId=%s"
                .formatted(projectId, systemCode, rowId);
    }

    @PostMapping(value = "/{systemCode}/nodes/move", produces = MediaType.TEXT_HTML_VALUE)
    public String moveNode(@PathVariable String projectId, @PathVariable String systemCode,
                           @RequestParam String nodeKey, @RequestParam int version,
                           @RequestParam String direction, @AuthenticationPrincipal BuilderUser me,
                           RedirectAttributes flash) {
        try {
            ia.moveNode(projectId, systemCode, nodeKey, version, direction, me.accountId());
            flash.addFlashAttribute("message", "같은 단계에서 메뉴 순서를 변경했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/menu-tree/%s?nodeKey=%s"
                .formatted(projectId, systemCode, nodeKey);
    }

    @PostMapping(value = "/{systemCode}/nodes/move", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> moveNodeJson(@PathVariable String projectId, @PathVariable String systemCode,
                                          @RequestParam String nodeKey, @RequestParam int version,
                                          @RequestParam String direction, @AuthenticationPrincipal BuilderUser me) {
        try {
            return ResponseEntity.ok(ia.moveNode(
                    projectId, systemCode, nodeKey, version, direction, me.accountId()));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return ResponseEntity.badRequest().body(new ActionError(rejected.getMessage()));
        }
    }

    @PostMapping("/{systemCode}/confirm")
    public String confirm(@PathVariable String projectId, @PathVariable String systemCode,
                          @RequestParam int version, @AuthenticationPrincipal BuilderUser me,
                          RedirectAttributes flash) {
        return act(projectId, systemCode, flash, () -> {
            IaService.PublishResult result = ia.confirm(projectId, systemCode, version, me.accountId());
            if (result.published()) {
                flash.addFlashAttribute("message", result.revision() + "차 IA를 확정하고 기획 저장소에 게시했습니다.");
            } else {
                flash.addFlashAttribute("error", "IA " + result.revision() + "차 확정은 저장했지만 게시하지 못했습니다. "
                        + result.failure());
            }
        });
    }

    private String act(String projectId, String systemCode, RedirectAttributes flash, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/menu-tree/%s".formatted(projectId, systemCode);
    }

    private IaService.Workbench requireWorkbench(String projectId, String systemCode) {
        return ia.find(projectId, systemCode)
                .orElseThrow(() -> new IllegalStateException("최초 IA를 가져온 뒤 메뉴 행을 편집할 수 있습니다."));
    }

    private void shell(Model model, String title) {
        model.addAttribute("title", title);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
    }

    /** 폼 이름은 코드 식별자라 영문, 화면 label은 템플릿에서 한글로 둔다. */
    public record CreateMenuForm(String menuName, String parentNodeKey, String screenId) {
        IaService.CreateMenuInput input() {
            return new IaService.CreateMenuInput(menuName, parentNodeKey, screenId);
        }
    }

    public record EditRowForm(String parentNodeKey) {
        IaService.MenuLocationInput input() {
            return new IaService.MenuLocationInput(parentNodeKey);
        }
    }

    private record ActionError(String error) {}
}
