package com.bizplay.builder.web;

import com.bizplay.builder.account.BuilderUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class FirstLoginFilter extends OncePerRequestFilter {

    public static final String CLAUDE_SKIP_SESSION_KEY = "claude.connection.skipped";

    /**
     * ⚠ <b>관문 둘의 열린 경로를 한 집합으로 합치지 마라.</b> `planner-account` 칸 3 은
     * 「비밀번호 바꾸기 → Claude 계정 연결」이라는 <b>순서</b>를 요구한다. 합쳐 두면
     * 임시 비밀번호만 쥔 사람이 비밀번호를 안 바꾸고 <b>자기 계정에 오래 가는 Claude 자격을 심을 수 있다.</b>
     * (계획 1 Task 7 의 코드가 실제로 그랬다 — 2026-08-09 코덱스 적대검증이 잡았다.)
     */
    private static final Set<String> PASSWORD_GATE_OPEN_PATHS =
            Set.of("/login", "/logout", "/password");

    /** 비밀번호를 바꾼 뒤에 열린다. 연결 화면 자신이 여기 있어야 되튕김이 안 생긴다. */
    private static final Set<String> CONNECT_GATE_OPEN_PATHS =
            Set.of("/login", "/logout", "/password", "/claude/connect",
                    "/claude/connect/start", "/claude/connect/skip");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BuilderUser user) {
            String path = request.getRequestURI();
            // 정적 자원은 관문을 지나지 않는다. 안 그러면 관문에 걸린 사람이 CSS 를 받으려는
            // 요청까지 되튕겨서 비밀번호 화면과 연결 화면이 맨몸으로 뜬다.
            // 경로 목록의 정본은 StaticResources 하나다 — SecurityConfig 도 같은 것을 쓴다.
            if (!com.bizplay.builder.config.StaticResources.matches(path)) {
                // 관문 1 — 비밀번호. 이걸 안 지나면 연결 화면에도 못 간다(순서가 있다).
                if (user.mustChangePassword()) {
                    if (!PASSWORD_GATE_OPEN_PATHS.contains(path)) {
                        response.sendRedirect("/password");
                        return;
                    }
                } else if (!user.claudeConnected()
                        && !Boolean.TRUE.equals(request.getSession().getAttribute(CLAUDE_SKIP_SESSION_KEY))) {
                    // 관문 2 — Claude 연결. 역할과 관계없이 본인 자격을 연결하거나 나중에 연결한다.
                    if (!CONNECT_GATE_OPEN_PATHS.contains(path)) {
                        response.sendRedirect("/claude/connect");
                        return;
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }
}
