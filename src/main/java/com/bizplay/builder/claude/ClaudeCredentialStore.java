package com.bizplay.builder.claude;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claude 자격 DB 쓰기의 짧은 트랜잭션.
 * 계정 잠금을 기다리는 동안 DB 커넥션을 물지 않도록 잠금 서비스와 분리한다.
 */
@Component
public class ClaudeCredentialStore {

    private final ClaudeCredentialMapper credentials;

    public ClaudeCredentialStore(ClaudeCredentialMapper credentials) {
        this.credentials = credentials;
    }

    @Transactional
    public void save(String accountId, byte[] cipher, byte[] nonce) {
        if (credentials.updateToken(accountId, cipher, nonce) == 0) {
            credentials.insert(ClaudeCredential.create(accountId, cipher, nonce));
        }
    }

    @Transactional
    public void saveConnected(String accountId, byte[] cipher, byte[] nonce,
                              ClaudeAccountIdentity identity) {
        if (credentials.updateConnectedCredential(accountId, cipher, nonce, identity) == 0) {
            credentials.insert(ClaudeCredential.create(accountId, cipher, nonce, identity));
        }
    }
}
