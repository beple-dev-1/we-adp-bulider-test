package com.bizplay.builder.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordController {

    private final AccountMapper accounts;
    private final PasswordEncoder encoder;

    /**
     * ⚠ Spring Security 6 은 신원을 세션에 **자동으로 저장하지 않는다**({@code requireExplicitSave} 기본값).
     * 이것 없이 {@code SecurityContextHolder} 만 고치면 다음 요청에서 옛 신원이 되살아나
     * 비밀번호를 바꿔도 이 화면으로 **무한히 되튕긴다**. 이웃 저장소 {@code we-adk-admin} 이 같은 자리에서
     * 이미 겪고 고친 모양을 그대로 쓴다.
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public PasswordController(AccountMapper accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    @GetMapping("/password")
    public String page() {
        return "password";
    }

    @PostMapping("/password")
    public String change(@AuthenticationPrincipal BuilderUser user,
                      @RequestParam String newPassword,
                      @RequestParam String confirm,
                      HttpServletRequest request,
                      HttpServletResponse response,
                      Model model) {
        if (newPassword.isBlank()) {
            model.addAttribute("passwordError", "새 비밀번호를 입력해 주세요.");
            return "password";
        }
        if (newPassword.length() < 8) {
            model.addAttribute("passwordError", "비밀번호는 8자 이상 입력해 주세요.");
            return "password";
        }
        if (confirm.isBlank()) {
            model.addAttribute("confirmError", "새 비밀번호를 한 번 더 입력해 주세요.");
            return "password";
        }
        if (!newPassword.equals(confirm)) {
            model.addAttribute("confirmError", "입력한 비밀번호가 서로 다릅니다.");
            return "password";
        }

        Account currentAccount = accounts.selectById(user.accountId()).orElseThrow();
        if (encoder.matches(newPassword, currentAccount.getPasswordHash())) {
            model.addAttribute("passwordError", "임시 비밀번호와 다른 비밀번호를 입력해 주세요.");
            return "password";
        }

        // ⚠ 2026-08-15 까지 여기 있던 account.changePassword(...) + accounts.save(account) 는
        //    JPA 더티 체킹에 기대던 모양이다. MyBatis 엔 그것이 없어 매퍼의 update 하나로 고친다.
        // ⛔ updateToTemporaryPassword 를 부르지 마라 — 그쪽은 must_change_password 를 참으로 올려
        //    방금 비밀번호를 바꾼 사람을 이 화면으로 도로 가둔다.
        accounts.updatePassword(user.accountId(), encoder.encode(newPassword));

        // ⚠ 넣고 다시 읽는다. Account 는 불변이라 방금 고친 값을 들고 있지 않다 —
        //    옛 객체로 신원을 다시 만들면 mustChangePassword 가 참인 채로 세션에 앉아
        //    비밀번호를 바꿔도 이 화면으로 무한히 되튕긴다.
        Account account = accounts.selectById(user.accountId()).orElseThrow();

        // 세션이 든 신원을 바로 갱신한다 — 안 하면 바꾼 뒤에도 계속 이 화면으로 튕긴다.
        // ⚠ 홀더만 고치면 안 된다. 세션에 **명시로 저장**해야 다음 요청이 새 신원을 본다.
        BuilderUser refreshed = BuilderUser.of(account, user.claudeConnected());
        Authentication newAuth =
                new UsernamePasswordAuthenticationToken(refreshed, null, refreshed.getAuthorities());

        SecurityContext newContext = SecurityContextHolder.createEmptyContext();
        newContext.setAuthentication(newAuth);
        SecurityContextHolder.setContext(newContext);

        // 세션 고정 공격 방지 — **비밀번호가 바뀐 자리에서만** 세션 ID 를 돌린다.
        // ⚠ 세션이 없으면 changeSessionId() 는 IllegalStateException 을 던진다.
        //    MockMvc 테스트는 세션 없이 요청을 만들 수 있어 실제로 걸린다. 반드시 확인하고 부른다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        securityContextRepository.saveContext(newContext, request, response);

        return "redirect:/projects";
    }
}
