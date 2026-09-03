package com.bizplay.builder.design;

import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.account.BuilderUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 추출기의 디자인 시스템 계약을 Builder 네이티브 화면으로 보여준다.
 *
 * <p>추출기는 검증된 JSON·격리 CSS·HTML fragment를 제공하고 Builder는 이를 시스템별
 * Foundations·Components·Compositions·Layouts·Templates 화면으로 안전하게 구성한다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts/design-guide")
public class DesignGuideController {

    private static final String ARTIFACT_KEY = "design-guide";

    private final PlanningManifestReader manifests;
    private final ProjectPaths paths;
    private final DesignGuideArtifactAccess artifacts;
    private final DesignGuideCatalogReader catalogs;
    private final DesignSystemCurationService curations;

    public DesignGuideController(PlanningManifestReader manifests,
                                 ProjectPaths paths,
                                 DesignGuideArtifactAccess artifacts,
                                 DesignGuideCatalogReader catalogs,
                                 DesignSystemCurationService curations) {
        this.manifests = manifests;
        this.paths = paths;
        this.artifacts = artifacts;
        this.catalogs = catalogs;
        this.curations = curations;
    }

    @GetMapping
    public String guide(@PathVariable String projectId,
                        @RequestParam(defaultValue = "false") boolean edit, Model model) {
        var catalog = catalogs.read(projectId);
        model.addAttribute("guideReady", catalog.ready());
        model.addAttribute("guide", catalog);
        if (catalog.ready()) {
            String ticket = artifacts.issue(projectId);
            String guideDirectory = paths.cloneDir(projectId)
                    .relativize(manifests.designGuideDirectory(projectId))
                    .toString().replace('\\', '/');
            String artifactBase = "/projects/" + projectId + "/artifacts/design-guide/files/"
                    + ticket + "/";
            model.addAttribute("artifactBase", artifactBase);
            model.addAttribute("guideAssetBase", artifactBase + guideDirectory + "/");
        }
        model.addAttribute("title", "디자인가이드");
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", ARTIFACT_KEY);
        model.addAttribute("projectId", projectId);
        model.addAttribute("curationEditing", edit);
        return "artifacts/design-guide";
    }

    @PostMapping("/curation/{systemId}/components/{componentId}")
    public String saveComponent(@PathVariable String projectId, @PathVariable String systemId,
                                @PathVariable String componentId, DesignSystemComponentForm form,
                                @AuthenticationPrincipal BuilderUser me, RedirectAttributes flash) {
        try {
            curations.saveComponent(projectId, systemId, form.getVersion(), form.input(componentId), me.accountId());
            flash.addFlashAttribute("message", "컴포넌트 구성을 저장하고 디자인 시스템에 적용했습니다.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            flash.addFlashAttribute("error", rejected.getMessage());
        }
        return "redirect:/projects/%s/artifacts/design-guide?edit=true#%s/components"
                .formatted(projectId, systemId);
    }
}
