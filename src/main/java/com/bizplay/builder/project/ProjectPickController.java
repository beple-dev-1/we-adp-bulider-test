package com.bizplay.builder.project;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 프로젝트 고르기 — IA 의 4번 화면이고 <b>틀 밖</b>이다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-10-project-context-design.md}
 */
@Controller
@RequestMapping("/projects")
public class ProjectPickController {

    private final ProjectService projects;

    public ProjectPickController(ProjectService projects) {
        this.projects = projects;
    }

    /**
     * @param gone 낡은 주소로 들어와 되돌려진 표시.
     *            ⚠ 이것이 달려 오면 <b>하나뿐이어도 건너뛰지 않는다</b> —
     *            건너뛰면 사람이 아무 말도 못 듣고 다른 프로젝트에 앉는다.
     */
    @GetMapping
    public String pick(@RequestParam(name = "gone", required = false) String gone,
                       @RequestParam(name = "from", required = false) String from,
                       Authentication who, Model model) {
        List<Project> readyProjects = projects.ready();

        if (!readyProjects.isEmpty() && gone == null) {
            return "redirect:/projects/" + readyProjects.get(0).getId() + "/artifacts/frds";
        }

        // 열린 것이 없는데 등록할 수 있는 사람이면 등록하는 자리로 바로 보낸다.
        // 안내 카드는 아무것도 못 하는 사람(기획자)에게만 뜻이 있다.
        // ⚠ 「없다」를 달고 오면 보내지 않는다 — 까닭을 못 듣고 다른 화면에 앉는다.
        if (readyProjects.isEmpty() && gone == null && isSuper(who)) {
            if ("admin".equals(from)) {
                return "redirect:/admin/projects?builderUnavailable";
            }
            return "redirect:/admin/projects";
        }

        if (readyProjects.isEmpty() && gone == null) {
            return "project-empty";
        }

        model.addAttribute("title", "프로젝트 고르기");
        model.addAttribute("projects", readyProjects);
        model.addAttribute("gone", gone != null);
        return "projects";
    }

    private static boolean isSuper(Authentication who) {
        return who != null && who.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER"::equals);
    }
}
