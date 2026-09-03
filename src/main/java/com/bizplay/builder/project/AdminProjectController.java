package com.bizplay.builder.project;

import com.bizplay.builder.account.BuilderUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/projects")
@PreAuthorize("hasRole('SUPER')")
public class AdminProjectController {

    private final ProjectService projects;
    private final CloneWorker cloneWorker;
    private final RepositoryUpdateWorker repositoryUpdateWorker;
    private final ProjectSystemService projectSystems;
    private final com.bizplay.builder.devrequest.DevIssueTargetService devIssueTargets;

    public AdminProjectController(ProjectService projects, CloneWorker cloneWorker,
                                  RepositoryUpdateWorker repositoryUpdateWorker,
                                  ProjectSystemService projectSystems,
                                  com.bizplay.builder.devrequest.DevIssueTargetService devIssueTargets) {
        this.projects = projects;
        this.cloneWorker = cloneWorker;
        this.repositoryUpdateWorker = repositoryUpdateWorker;
        this.projectSystems = projectSystems;
        this.devIssueTargets = devIssueTargets;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("projects", projects.all());
        return "admin/projects";
    }

    @GetMapping("/new")
    public String registerForm() {
        return "admin/project-register";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("view", projects.detail(id));
        model.addAttribute("devIssueTarget", devIssueTargets.of(id));
        return "admin/project-detail";
    }

    @PostMapping("/{id}/facets")
    public String replaceFacets(@PathVariable String id,
                                @RequestParam(required = false) List<String> facetCodes,
                                @RequestParam(required = false) List<String> facetNames,
                                @RequestParam(name = "facets", required = false) String legacyFacets,
                                Model model) {
        try {
            projects.replaceFacetSettings(id, facetSettings(facetCodes, facetNames, legacyFacets));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("editingFacet", true);
            model.addAttribute("view", projects.detail(id));   // ⛔ 목록이 아니라 적용 구분 폼이 있는 상세로 되돌아온다
            model.addAttribute("devIssueTarget", devIssueTargets.of(id));
            return "admin/project-detail";
        }
        return "redirect:/admin/projects/" + id;
    }

    /**
     * 시스템의 <b>표시 이름만</b> 받는다.
     *
     * <p>⛔ 코드는 안 받는다 — 코드 목록의 정본은 기획 저장소의 {@code manifest.json} 이고
     * 클론·저장소 업데이트가 그것을 읽어 앉힌다. 사람이 코드를 지어 넣으면 어느 화면의 자료와도
     * 만나지 못하는 줄이 관리 화면에만 앉는다.
     */
    @PostMapping("/{id}/systems")
    public String replaceSystemNames(@PathVariable String id,
                                     @RequestParam(required = false) List<String> systemCodes,
                                     @RequestParam(required = false) List<String> systemNames,
                                     Model model) {
        try {
            projectSystems.replaceNames(id, systemNames(systemCodes, systemNames));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("editingSystem", true);
            model.addAttribute("view", projects.detail(id));   // ⛔ 목록이 아니라 시스템 폼이 있는 상세로
            model.addAttribute("devIssueTarget", devIssueTargets.of(id));
            return "admin/project-detail";
        }
        return "redirect:/admin/projects/" + id;
    }

    @PostMapping
    public String register(@RequestParam String name,
                     @RequestParam String repoUrl,
                     @RequestParam String defaultBranch,
                     @RequestParam String platformCode,
                     @RequestParam String token,
                     @RequestParam(required = false) List<String> facetCodes,
                     @RequestParam(required = false) List<String> facetNames,
                     @RequestParam(name = "facets", required = false) String legacyFacets,
                     @AuthenticationPrincipal BuilderUser me,
                     Model model) {
        Project created;
        try {
            created = projects.registerConfigured(name, repoUrl, defaultBranch, token, platformCode,
                    facetSettings(facetCodes, facetNames, legacyFacets));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "admin/project-register";   // ⛔ 목록이 아니라 등록 화면으로 되돌아온다
        }
        cloneWorker.clone(created.getId(), me.accountId());   // 화면은 바로 돌아온다. 클론은 뒤에서 돈다.
        return "redirect:/admin/projects";
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable String id, @AuthenticationPrincipal BuilderUser me) {
        projects.retry(id);
        cloneWorker.clone(id, me.accountId());
        return "redirect:/admin/projects/" + id;
    }

    @PostMapping("/{id}/repository/update")
    public String updateRepository(@PathVariable String id, @AuthenticationPrincipal BuilderUser me,
                                   RedirectAttributes flash) {
        try {
            if (projects.requestRepositoryUpdate(id)) {
                repositoryUpdateWorker.update(id, me.accountId());
            }
        } catch (IllegalArgumentException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/admin/projects/" + id;
    }

    /**
     * 다시 시도는 네트워크 끊김을 낫게 하지만 <b>만료를 못 낫게 한다</b> — `project-setup` 이 그렇게 정했다.
     * 그래서 토큰 칸만 따로 있다. 이름 · URL · 브랜치는 건드리지 않는다.
     */
    @PostMapping("/{id}/token")
    public String replaceToken(@PathVariable String id,
                              @RequestParam String token,
                              Model model) {
        try {
            projects.replaceToken(id, token);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("editingToken", true);
            model.addAttribute("view", projects.detail(id));   // ⛔ 목록이 아니라 토큰 폼이 있는 상세로 되돌아온다
            model.addAttribute("devIssueTarget", devIssueTargets.of(id));
            return "admin/project-detail";
        }
        return "redirect:/admin/projects/" + id;
    }

    /** 개발요청을 이슈로 여는 자리. ⛔ 기획 저장소가 아니라 개발 조직의 트래커다. */
    @PostMapping("/{id}/dev-issue-target")
    public String saveDevIssueTarget(@PathVariable String id,
                                     @RequestParam String baseUrl,
                                     @RequestParam String projectPath,
                                     @RequestParam String token,
                                     @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             com.bizplay.builder.account.BuilderUser user,
                                     Model model) {
        try {
            devIssueTargets.save(id, baseUrl, projectPath, token,
                    user == null ? null : user.accountId());
        } catch (IllegalArgumentException rejected) {
            model.addAttribute("error", rejected.getMessage());
            model.addAttribute("editingDevIssueTarget", true);
            model.addAttribute("view", projects.detail(id));
            model.addAttribute("devIssueTarget", devIssueTargets.of(id));
            model.addAttribute("devIssueBaseUrl", baseUrl);
            model.addAttribute("devIssueProjectPath", projectPath);
            return "admin/project-detail";
        }
        return "redirect:/admin/projects/" + id;
    }

    /**
     * 폼이 보낸 두 배열을 코드 → 이름 표로 묶는다.
     *
     * <p>⚠ 브라우저는 값이 빈 칸도 보낸다 — 이름을 <b>지운 것</b>과 처음부터 없던 것을 같이
     * 「이름 없음」으로 다루려고 빈 값을 버리지 않고 그대로 넘긴다.
     */
    private java.util.Map<String, String> systemNames(List<String> codes, List<String> names) {
        List<String> safeCodes = codes == null ? List.of() : codes;
        List<String> safeNames = names == null ? List.of() : names;
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        for (int i = 0; i < safeCodes.size(); i++) {
            settings.put(safeCodes.get(i), i < safeNames.size() ? safeNames.get(i) : "");
        }
        return settings;
    }

    private List<ProjectService.FacetSetting> facetSettings(List<String> codes, List<String> names,
                                                            String legacyFacets) {
        if ((codes == null || codes.isEmpty()) && legacyFacets != null) {
            return java.util.Arrays.stream(legacyFacets.split(","))
                    .map(String::strip)
                    .filter(name -> !name.isEmpty())
                    .map(name -> new ProjectService.FacetSetting(name, name))
                    .toList();
        }
        List<String> safeCodes = codes == null ? List.of() : codes;
        List<String> safeNames = names == null ? List.of() : names;
        int size = Math.max(safeCodes.size(), safeNames.size());
        List<ProjectService.FacetSetting> settings = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String code = i < safeCodes.size() ? safeCodes.get(i) : "";
            String name = i < safeNames.size() ? safeNames.get(i) : "";
            settings.add(new ProjectService.FacetSetting(code, name));
        }
        return settings;
    }
}
