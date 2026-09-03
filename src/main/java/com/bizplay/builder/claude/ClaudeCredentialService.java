package com.bizplay.builder.claude;

import com.bizplay.builder.secret.Sealed;
import com.bizplay.builder.secret.SecretSealer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

@Service
public class ClaudeCredentialService {

    private final ClaudeCredentialMapper credentials;
    private final SecretSealer sealer;
    private final ClaudeCredentialStore store;

    public ClaudeCredentialService(ClaudeCredentialMapper credentials, SecretSealer sealer,
                                   ClaudeCredentialStore store) {
        this.credentials = credentials;
        this.sealer = sealer;
        this.store = store;
    }

    /**
     * 봉인해 앉힌다.
     *
     * <p>⛔ <b>여기서 계정 잠금을 잡지 마라 (2026-08-17).</b> 이 길은 <b>사람이 브라우저에서
     * 「연결」을 누른 요청 스레드</b>다({@code ClaudeConnectController}). 계정 잠금은
     * {@link com.bizplay.builder.ai.ClaudeCredentialRunner} 가 {@code claude} 가 도는
     * <b>몇 분 내내</b> 쥐고 있으므로, 여기서 그것을 기다리면 <b>재연결 화면이 몇 분간 멈춘다</b> —
     * 게다가 {@code lock()} 은 상한이 없어 톰캣 스레드가 그대로 묶인다.
     * 대신 <b>끝나는 실행 쪽이 양보한다</b>: 그쪽은 저장 직전에 DB 를 다시 읽어,
     * 제가 들고 나갔던 자격이 그대로일 때만 덮는다. <b>사람이 방금 살린 연결이 이긴다.</b>
     *
     * @param claudeAiOauth한칸 <b>`claudeAiOauth` 키 하나만 든 JSON 문서.</b>
     *        ⛔ `.credentials.json` 전체를 넣지 마라 — 그 파일에는 그 사람의 MCP 서버 OAuth 토큰이
     *        함께 살고, 빌더 서버가 그것까지 쥐면 안 된다. 떼는 일은 {@link ClaudeCredentialFile} 이 한다.
     */
    public void store(String accountId, String oauthOnlyJson) {
        Sealed sealed = sealer.seal(oauthOnlyJson);
        // ⛔ 찾아와서 고치기만 하지 마라. JPA 때는 찾은 것에 replaceToken() 만 불러도 트랜잭션 끝에
        //    저장됐지만(더티 체킹), MyBatis 에는 그것이 없다 — 고쳤다고 믿고 DB 는 안 바뀐다.
        //    ⭐ 먼저 update 를 쏘고 「바뀐 줄이 0」이면 없는 것이니 insert 한다.
        //       찾아보고 갈라지는 것보다 왕복이 하나 적고, 사이에 낀 다른 요청과도 안 부딪힌다.
        store.save(accountId, sealed.cipher(), sealed.nonce());
    }

    /** 로그인에서 확인한 실제 계정 식별정보와 자격을 함께 연결한다. */
    public void connect(String accountId, String oauthOnlyJson, ClaudeAccountIdentity identity) {
        credentials.selectAccountIdByEmail(identity.email())
                .filter(existingAccountId -> !existingAccountId.equals(accountId))
                .ifPresent(existingAccountId -> {
                    throw new ClaudeAccountAlreadyConnectedException();
                });
        Sealed sealed = sealer.seal(oauthOnlyJson);
        try {
            store.saveConnected(accountId, sealed.cipher(), sealed.nonce(), identity);
        } catch (DuplicateKeyException duplicate) {
            throw new ClaudeAccountAlreadyConnectedException();
        }
    }

    /**
     * 계획 2 가 `claude -p` 를 돌릴 때 여기서 자격을 꺼낸다.
     *
     * <p>내주는 것은 <b>그대로 `.credentials.json` 으로 쓸 수 있는 한 칸짜리 문서</b>다 —
     * `{"claudeAiOauth": {...}}`. {@link com.bizplay.builder.ai.AiRunWorker} 가 받아
     * <b>그 실행의</b> `CLAUDE_CONFIG_DIR` 에 그대로 쓴다. <b>다른 키를 만들지 않는다.</b>
     */
    @Transactional(readOnly = true)
    public Optional<String> tokenOf(String accountId) {
        return credentials.selectByAccountId(accountId)
                .map(it -> sealer.unseal(new Sealed(it.getSealedToken(), it.getNonce())));
    }

    @Transactional(readOnly = true)
    public boolean isConnected(String accountId) {
        return credentials.selectByAccountId(accountId).isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<ClaudeAccountIdentity> identityOf(String accountId) {
        return credentials.selectByAccountId(accountId).map(ClaudeCredential::identity);
    }
}
