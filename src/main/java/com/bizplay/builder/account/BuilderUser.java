package com.bizplay.builder.account;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public record BuilderUser(String accountId, String loginId, String name, String email,
                          String passwordHash, boolean superAccount,
                          boolean mustChangePassword, boolean claudeConnected)
        implements UserDetails {

    public static BuilderUser of(Account account, boolean claudeConnected) {
        return new BuilderUser(account.getId(), account.getLoginId(), account.getName(),
                account.getEmail(), account.getPasswordHash(), account.isSuperAccount(),
                account.isMustChangePassword(), claudeConnected);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return superAccount
                ? List.of(new SimpleGrantedAuthority("ROLE_SUPER"))
                : List.of(new SimpleGrantedAuthority("ROLE_PLANNER"));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return loginId; }
}
