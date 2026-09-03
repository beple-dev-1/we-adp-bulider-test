package com.bizplay.builder.project;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 뿌리 주소({@code /})를 프로젝트 고르기로 보낸다. <b>화면이 아니다 — 문패다.</b>
 *
 * <p>로그인 성공과 뿌리 주소의 기본 진입점은 모두 {@code /projects} 다.
 * 고르기 화면 자신은 {@link ProjectPickController} 다 — 프로젝트가 하나면 FRD 작업 목록으로
 * 바로 보내고, 여러 개면 먼저 프로젝트를 고르게 한다. 이 클래스에서 그 판단을 되풀이하지 않는다.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/projects";
    }
}
