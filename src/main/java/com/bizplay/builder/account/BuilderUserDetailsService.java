package com.bizplay.builder.account;

import com.bizplay.builder.claude.ClaudeCredentialService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class BuilderUserDetailsService implements UserDetailsService {

    private final AccountMapper accounts;
    private final ClaudeCredentialService credentials;

    public BuilderUserDetailsService(AccountMapper accounts,
                                     ClaudeCredentialService credentials) {
        this.accounts = accounts;
        this.credentials = credentials;
    }

    @Override
    public BuilderUser loadUserByUsername(String loginId) {
        Account account = accounts.selectByLoginId(loginId)
                .orElseThrow(() -> new UsernameNotFoundException("없는 계정이다"));
        boolean connected = credentials.isConnected(account.getId());
        return BuilderUser.of(account, connected);
    }
}
