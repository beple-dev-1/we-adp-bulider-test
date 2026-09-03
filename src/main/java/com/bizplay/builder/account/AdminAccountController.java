package com.bizplay.builder.account;

import com.bizplay.builder.claude.ClaudeCredentialMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.secret.TemporaryPasswords;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/admin/accounts")
@PreAuthorize("hasRole('SUPER')")
public class AdminAccountController {

    private static final int LOGIN_ID_MAX_LENGTH = 64;
    private static final int NAME_MAX_LENGTH = 64;
    private static final int EMAIL_MAX_LENGTH = 255;
    private static final String ROLE_PLANNER = "PLANNER";
    private static final String ROLE_SUPER = "SUPER";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

    private final AccountMapper accounts;
    private final ClaudeCredentialMapper credentials;
    private final PasswordEncoder encoder;
    private final IdSequence ids;

    public AdminAccountController(AccountMapper accounts, ClaudeCredentialMapper credentials,
                                  PasswordEncoder encoder, IdSequence ids) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.encoder = encoder;
        this.ids = ids;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       Model model) {
        // ⛔ Locale.ROOT 로 고정한다 — 기본 로케일에 맡기면(터키어 등) "ID" 가 "ıd" 로 내려가는
        //    로케일이 있어 로그인 아이디 검색이 조용히 빗나간다.
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);

        var rows = accounts.selectAll().stream()
                .map(a -> new AccountListView(a.getId(), a.getLoginId(), a.getName(), a.getEmail(),
                        a.isSuperAccount() ? "슈퍼 관리자" : "기획자",
                        claudeStateOf(a)))
                .filter(v -> needle.isEmpty()
                        || v.loginId().toLowerCase(Locale.ROOT).contains(needle)
                        || v.name().toLowerCase(Locale.ROOT).contains(needle)
                        || v.email().toLowerCase(Locale.ROOT).contains(needle))
                .toList();

        model.addAttribute("accounts", rows);
        model.addAttribute("q", q);
        return "admin/accounts";
    }

    @GetMapping("/new")
    public String createForm() {
        return "admin/account-register";
    }

    /**
     * ⛔ <b>다듬고 나서 검사한다</b> — {@code ProjectService.register} 가 이름에 하는 것과 같은 모양이다.
     * 안 다듬으면 {@code " younghee"}(앞 공백)가 DB 유니크 제약을 피해 {@code "younghee"}와
     * 나란히 앉는다 — 겉보기엔 같은 아이디 둘인데 로그인 폼이 다듬지 않고 그대로 보내는 한
     * 하나는 영원히 로그인이 안 된다. 이 컨트롤러가 유일한 계정 생성 문이라 서비스 계층 없이
     * 여기서 다듬는다(프로젝트 쪽은 {@code ProjectService} 가 있어 거기서 하지만, 계정 쪽엔
     * 아직 그런 서비스가 없다 — 이 수정만으로 새로 만들지 않는다).
     */
    @PostMapping
    public String create(@RequestParam String loginId,
                      @RequestParam String name,
                      @RequestParam String email,
                      @RequestParam(required = false) String role,
                      @RequestParam String temporaryPassword,
                      Model model,
                      RedirectAttributes redirect) {
        String trimmedLoginId = loginId == null ? "" : loginId.strip();
        String trimmedName = name == null ? "" : name.strip();
        String trimmedEmail = email == null ? "" : email.strip();
        String selectedRole = role == null ? "" : role.strip();
        // 오류가 나도 사용자가 이미 입력한 비밀번호 외 정보는 다시 입력하지 않게 돌려준다.
        model.addAttribute("loginId", trimmedLoginId);
        model.addAttribute("name", trimmedName);
        model.addAttribute("email", trimmedEmail);
        model.addAttribute("role", selectedRole);

        if (trimmedLoginId.isEmpty()) {
            return registrationError(model, "loginId", "로그인 아이디를 입력해 주세요.");
        }
        if (trimmedLoginId.length() > LOGIN_ID_MAX_LENGTH) {
            return registrationError(model, "loginId", "로그인 아이디가 너무 깁니다. 64자 이하로 입력해 주세요.");
        }
        if (trimmedName.isEmpty()) {
            return registrationError(model, "name", "이름을 입력해 주세요.");
        }
        if (trimmedName.length() > NAME_MAX_LENGTH) {
            return registrationError(model, "name", "이름이 너무 깁니다. 64자 이하로 입력해 주세요.");
        }
        if (trimmedEmail.isEmpty()) {
            return registrationError(model, "email", "이메일을 입력해 주세요.");
        }
        if (trimmedEmail.length() > EMAIL_MAX_LENGTH) {
            return registrationError(model, "email", "이메일이 너무 깁니다. 255자 이하로 입력해 주세요.");
        }
        if (!EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            return registrationError(model, "email", "이메일 형식이 올바르지 않습니다. 이름@도메인 형식으로 입력해 주세요.");
        }
        if (!ROLE_PLANNER.equals(selectedRole) && !ROLE_SUPER.equals(selectedRole)) {
            return registrationError(model, "role", "등록할 권한을 선택해 주세요.");
        }
        if (accounts.selectByLoginId(trimmedLoginId).isPresent()) {
            return registrationError(model, "loginId",
                    "같은 로그인 아이디가 이미 등록되어 있습니다. 다른 아이디를 입력해 주세요: " + trimmedLoginId);
        }
        if (temporaryPassword.length() < 8) {
            return registrationError(model, "temporaryPassword",
                    "임시 비밀번호가 너무 짧습니다. 8자 이상으로 입력해 주세요.");
        }
        // ⛔ 이 자리에서 Claude 는 안 건드린다 — 자격은 본인이 로그인해서 연결한다.
        accounts.insert(Account.create(ids.next(IdSequence.Kind.ACCOUNT), trimmedLoginId, trimmedName, trimmedEmail,
                encoder.encode(temporaryPassword), ROLE_SUPER.equals(selectedRole)));
        redirect.addFlashAttribute("success",
                "사용자가 등록되었습니다. 로그인 아이디와 임시 비밀번호를 사용자에게 별도로 전달해 주세요.");
        return "redirect:/admin/accounts";
    }

    private String registrationError(Model model, String errorField, String message) {
        model.addAttribute("errorField", errorField);
        model.addAttribute("error", message);
        return "admin/account-register";
    }

    // ⛔ issuedPassword 를 인자로 받지 마라. flash 속성은 Spring 이 모델에 이미 넣어 준다 —
    //    @ModelAttribute 로 또 받으면 없는 요청에서 빈 문자열로 덮어써 버린다.
    @GetMapping("/{id}")
    public String detail(@PathVariable String id, @AuthenticationPrincipal BuilderUser me, Model model) {
        Account account = accounts.selectById(id).orElseThrow();
        populateDetail(model, account, me);
        return "admin/account-detail";
    }

    /**
     * ⛔ 본인 계정은 여기서 재발급을 거절한다. 슈퍼관리자가 자기 화면에서 눌러 새 비밀번호를
     * 한 번 본 뒤 못 적어 두면(브라우저를 벗어나거나 세션이 끊기면) 옛 비밀번호는 이미 죽었고
     * 새 비밀번호는 어디에도 안 남는다 — 유일한 슈퍼계정이면 관리 화면 전체가 잠긴다.
     * {@code SuperAccountBootstrap} 은 이미 있는 계정을 되살리지 않아 되돌릴 길이 DB 손질뿐이다.
     * 본인 비밀번호를 바꾸는 문은 이미 {@code /password} 에 따로 있다 — 잃는 능력이 없다.
     */
    @PostMapping("/{id}/temporary-password")
    public String reissue(@PathVariable String id, @AuthenticationPrincipal BuilderUser me,
                          Model model, RedirectAttributes redirect) {
        Account account = accounts.selectById(id).orElseThrow();
        if (id.equals(me.accountId())) {
            model.addAttribute("error", "본인 계정의 임시 비밀번호는 이 화면에서 재발급할 수 없습니다. "
                    + "비밀번호 변경 화면을 이용해 주세요.");
            populateDetail(model, account, me);
            return "admin/account-detail";
        }
        String issued = TemporaryPasswords.next();
        // ⚠ 2026-08-15 까지 여기 있던 account.resetToTemporary(...) + accounts.save(account) 는
        //    JPA 더티 체킹에 기대던 모양이다. MyBatis 엔 그것이 없어 매퍼의 update 하나로 고친다.
        // ⛔ updatePassword 를 부르지 마라 — 그쪽은 must_change_password 를 거짓으로 내린다.
        //    재발급은 최초 로그인 흐름을 한 번 더 밟게 하는 것이라 참으로 올려야 한다.
        // ⚠ 다른 자리(PasswordController)와 달리 여기서는 되읽지 않는다 — 바로 리다이렉트라
        //    고쳐진 계정을 이 요청에서 더 쓰지 않는다. 되읽어 봤자 아무도 안 보는 왕복이 는다.
        accounts.updateToTemporaryPassword(id, encoder.encode(issued));
        // ⛔ 한 번만 보여 준다. 어디에도 다시 안 남는다 — 해시만 DB 에 있다.
        redirect.addFlashAttribute("issuedPassword", issued);
        return "redirect:/admin/accounts/" + id;
    }

    private void populateDetail(Model model, Account account, BuilderUser me) {
        model.addAttribute("account", account);
        model.addAttribute("claudeState", claudeStateOf(account));
        model.addAttribute("setupState", setupStateOf(account));
        model.addAttribute("own", me.accountId().equals(account.getId()));
    }

    /** 권한과 관계없이 계정별 Claude 자격이 저장되어 있는지로 연결 상태를 계산한다. */
    String claudeStateOf(Account account) {
        return credentials.selectByAccountId(account.getId()).isPresent() ? "연결 완료" : "미연결";
    }

    /** 비밀번호가 먼저다 — 그것을 안 바꿨으면 Claude 는 아직 물을 차례가 아니다. */
    String setupStateOf(Account account) {
        if (account.isMustChangePassword()) {
            return "비밀번호 설정 필요";
        }
        return credentials.selectByAccountId(account.getId()).isPresent()
                ? "설정 완료" : "Claude 연결 필요";
    }
}
