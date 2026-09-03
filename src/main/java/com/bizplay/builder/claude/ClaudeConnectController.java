package com.bizplay.builder.claude;

import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static com.bizplay.builder.web.FirstLoginFilter.CLAUDE_SKIP_SESSION_KEY;

@Controller
public class ClaudeConnectController {

    private static final String SESSION_KEY = "claude.authorization";

    private final ClaudeAuthGateway gateway;
    private final ClaudeCredentialService credentials;
    private final AccountMapper accounts;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public ClaudeConnectController(ClaudeAuthGateway gateway, ClaudeCredentialService credentials,
                                   AccountMapper accounts) {
        this.gateway = gateway;
        this.credentials = credentials;
        this.accounts = accounts;
    }

    @GetMapping("/claude/connect")
    public String page(HttpSession session, Model model) {
        var authorization = (ClaudeAuthGateway.Authorization) session.getAttribute(SESSION_KEY);
        if (authorization != null) {
            model.addAttribute("authorizeUrl", authorization.url());
        }
        return "claude-connect";
    }

    @PostMapping("/claude/connect/start")
    public String start(HttpSession session) {
        // 사용자가 명시로 다시 시작하면 앞의 PKCE 로그인은 더는 쓸 수 없으므로 정리한다.
        discardPreviousLogin(session);
        session.removeAttribute(CLAUDE_SKIP_SESSION_KEY);

        ClaudeAuthGateway.Authorization authorization = gateway.begin();
        session.setAttribute(SESSION_KEY, authorization);
        return "redirect:/claude/connect";
    }

    /**
     * 같은 주소에 걸린 둘째 문이다 — {@code X-Requested-With: fetch} 를 들고 온 것만 여기로 온다.
     * 브라우저의 평범한 폼 전송은 헤더가 없어 위의 리다이렉트로 그대로 간다(스크립트가 꺼져도 된다).
     *
     * <p>⚠ <b>이 문이 있는 까닭은 팝업 차단이다.</b> 승인 창은 <b>사람이 누른 그 순간</b>에 열어야 하고,
     * 그때는 아직 주소가 없다. 그래서 화면이 빈 창을 먼저 띄우고, 여기서 받은 주소로 그 창을 보낸다.
     * 응답을 기다렸다 여는 창은 브라우저가 팝업으로 보고 막는다.
     */
    @PostMapping(value = "/claude/connect/start", headers = "X-Requested-With=fetch")
    @ResponseBody
    public Map<String, String> startForScript(HttpSession session) {
        start(session);
        var authorization = (ClaudeAuthGateway.Authorization) session.getAttribute(SESSION_KEY);
        return Map.of("authorizeUrl", authorization.url());
    }

    @PostMapping("/claude/connect/skip")
    public String skip(HttpSession session) {
        discardPreviousLogin(session);
        session.setAttribute(CLAUDE_SKIP_SESSION_KEY, true);
        return "redirect:/projects";
    }

    @PostMapping("/claude/connect")
    public String submitCode(@AuthenticationPrincipal BuilderUser user,
                          @RequestParam(required = false, defaultValue = "") String code,
                          HttpSession session,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          Model model) {
        var authorization = (ClaudeAuthGateway.Authorization) session.getAttribute(SESSION_KEY);
        if (authorization == null) {
            model.addAttribute("error", "승인을 시작하지 않았습니다. 승인 화면을 열고 다시 시도해 주세요.");
            return "claude-connect";
        }
        try {
            // ⚠ 코드는 대체 길에서만 쓰인다 — 보통은 브라우저 콜백이 끝내고 자식이 죽어 있다(2026-08-14 실측).
            var authenticated = gateway.complete(authorization, code);
            if (authenticated.isEmpty()) {
                // ⛔ 여기서 page() 를 부르지 마라 — 그러면 진행 중인 로그인을 버리고 자식을 새로 띄운다.
                //    사람이 승인을 마치는 중일 수 있다. 주소를 그대로 두고 한 번 더 누르게 한다.
                model.addAttribute("error",
                        "승인이 아직 완료되지 않았습니다. 승인 화면에서 절차를 마친 뒤 다시 확인해 주세요.");
                model.addAttribute("authorizeUrl", authorization.url());
                return "claude-connect";
            }
            credentials.connect(user.accountId(), authenticated.get().oauthOnlyJson(),
                    authenticated.get().identity());
        } catch (ClaudeAccountAlreadyConnectedException duplicate) {
            model.addAttribute("error",
                    "이미 다른 사용자에게 연결된 Claude 계정입니다. 본인의 계정으로 다시 승인해 주세요.");
            discardPreviousLogin(session);
            return "claude-connect";
        } catch (RuntimeException e) {
            // ⛔ 원문을 화면에 내지 않는다. 개발자용 로그로만 간다.
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Claude 연결 실패 accountId={}", user.accountId(), e);
            // 코드가 틀린 것과 상한에 걸려 프로세스가 죽은 것을 한 문구로 덮는다 — 사람이 할 일이 같다.
            model.addAttribute("error",
                    "승인이 완료되지 않았거나 시간이 지났습니다. 승인 화면을 다시 열어 주세요.");
            discardPreviousLogin(session);
            return "claude-connect";
        }
        gateway.discard(authorization.handle());   // 끝난 로그인은 붙잡아 두지 않는다(멱등)
        session.removeAttribute(SESSION_KEY);
        session.removeAttribute(CLAUDE_SKIP_SESSION_KEY);
        refreshPrincipal(user.accountId(), request, response);
        return "redirect:/projects";
    }

    private void discardPreviousLogin(HttpSession session) {
        var previous = (ClaudeAuthGateway.Authorization) session.getAttribute(SESSION_KEY);
        if (previous != null) {
            gateway.discard(previous.handle());
            session.removeAttribute(SESSION_KEY);
        }
    }

    /**
     * ⚠ 홀더만 고치면 안 된다 — Spring Security 6 은 신원을 세션에 자동 저장하지 않는다.
     * 저장을 빼면 `FirstLoginFilter` 가 다음 요청에서 옛 신원(연결 안 됨)을 보고
     * 이 화면으로 되튕기고, 그 GET 이 자식 프로세스를 또 띄운다 — 빠져나갈 수 없는 고리다.
     *
     * ⚠ `PasswordController` 와 <b>한 곳만 다르다</b> — 여기서는 세션 ID 를 돌리지 않는다.
     * 세션 고정 방지의 회전은 <b>로그인 자격이 바뀐 자리</b>에서 한다(비밀번호 변경).
     * Claude 연결은 자격이 아니라 딸린 정보가 붙는 것이라 회전 대상이 아니다. <b>일부러 뺀 것이다.</b>
     */
    private void refreshPrincipal(String accountId,
                            HttpServletRequest request, HttpServletResponse response) {
        Account account = accounts.selectById(accountId).orElseThrow();
        BuilderUser refreshed = BuilderUser.of(account, true);

        SecurityContext newContext = SecurityContextHolder.createEmptyContext();
        newContext.setAuthentication(
                new UsernamePasswordAuthenticationToken(refreshed, null, refreshed.getAuthorities()));
        SecurityContextHolder.setContext(newContext);

        securityContextRepository.saveContext(newContext, request, response);
    }
}
