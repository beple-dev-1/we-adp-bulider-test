package com.bizplay.builder.account;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면을 띄우기만 한다. 아이디·비밀번호를 실제로 맞춰 보는 것은 스프링 시큐리티가 한다.
 *
 * <p>⚠ 이 클래스를 지우지 마라. {@code SecurityConfig} 의 {@code loginPage("/login")} 이
 * 스프링이 만들어 주던 기본 로그인 화면을 <b>끈다</b>. 그래서 이 매핑이 없으면
 * 로그인 화면이 404 가 되고 <b>아무도 들어올 수 없다</b>.
 * (계획 1 Task 6 에 이 자리가 빠져 있었다 — 2026-08-09 구현 중 발견해 메웠다.)
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String page() {
        return "login";
    }
}
