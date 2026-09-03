package com.bizplay.builder.artifact;

import com.bizplay.builder.shell.ShellContract;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * 산출물 목록 — IA 의 5번 화면. <b>모든 산출물 열쇠가 이 화면 하나를 쓴다.</b>
 *
 * <p>⚠ 프로젝트 이름·번호·알림은 <b>여기서 안 담는다.</b>
 * {@link com.bizplay.builder.web.ProjectContextInterceptor} 가 한 자리에서 얹는다.
 */
@Controller
@RequestMapping("/projects/{projectId}/artifacts")
public class ArtifactListController {

    @GetMapping("/{key}")
    public String list(@PathVariable String key, Model model) {
        String name = ShellContract.ARTIFACT_NAMES.get(key);
        if (name == null) {
            // 열쇠 오타를 계약이 잡기 전에 여기서 잡는다 — 계약이 잡으면 500 이 되고,
            // 낡은 링크를 누른 사람에게 500 은 틀린 말이다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 산출물이 없다: " + key);
        }
        model.addAttribute("title", name);
        model.addAttribute("shape", "산출물");
        model.addAttribute("current", key);
        model.addAttribute("greenZone", ShellContract.GREEN_ZONE_KEYS.contains(key));
        return "artifacts/list";
    }
}
