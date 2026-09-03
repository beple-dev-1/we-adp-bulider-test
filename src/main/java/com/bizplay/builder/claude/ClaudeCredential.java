package com.bizplay.builder.claude;

import java.time.Instant;

/**
 * 사람마다 봉인된 「claudeAiOauth 한 칸」. 표는 {@code builder.adk_builder_claude_credential} 이다.
 *
 * <p><b>2026-08-15 에 JPA 엔티티에서 MyBatis 가 읽는 값 묶음으로 바뀌었다.</b>
 *
 * <p>⛔ <b>setter 를 열지 마라. {@code replaceToken} 같은 상태 변경 메서드도 다시 만들지 마라.</b>
 * JPA 때는 찾아온 것을 고치면 트랜잭션 끝에 저장됐지만(더티 체킹), <b>MyBatis 에는 그것이 없다.</b>
 * 여기에 고치는 메서드를 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀐다 — <b>예외도 안 난다.</b>
 * 고치는 길은 {@link ClaudeCredentialMapper#updateToken} 하나다.
 */
public class ClaudeCredential {

    /**
     * 기본키이면서 외래키다 — 사람당 한 줄이라 자기 번호를 따로 갖지 않는다.
     * 그래서 이 표에는 시퀀스가 없다.
     */
    private final String accountId;
    private final byte[] sealedToken;
    private final byte[] nonce;
    private final Instant connectedAt;
    private final String claudeEmail;
    private final String claudeOrgId;
    private final String claudeOrgName;
    private final String claudeSubscriptionType;

    /** MyBatis 가 조회 결과를 담을 때 쓴다 (매퍼 XML 의 {@code <constructor>}). */
    private ClaudeCredential(String accountId, byte[] sealedToken, byte[] nonce, Instant connectedAt,
                             String claudeEmail, String claudeOrgId, String claudeOrgName,
                             String claudeSubscriptionType) {
        this.accountId = accountId;
        this.sealedToken = sealedToken;
        this.nonce = nonce;
        this.connectedAt = connectedAt;
        this.claudeEmail = claudeEmail;
        this.claudeOrgId = claudeOrgId;
        this.claudeOrgName = claudeOrgName;
        this.claudeSubscriptionType = claudeSubscriptionType;
    }

    /**
     * 새로 앉힐 것을 만든다.
     * ⚠ {@code connectedAt} 은 담지 않는다 — DB 의 {@code default now()} 가 채운다.
     */
    public static ClaudeCredential create(String accountId, byte[] sealedToken, byte[] nonce) {
        return new ClaudeCredential(accountId, sealedToken, nonce, null, null, null, null, null);
    }

    public static ClaudeCredential create(String accountId, byte[] sealedToken, byte[] nonce,
                                           ClaudeAccountIdentity identity) {
        return new ClaudeCredential(accountId, sealedToken, nonce, null,
                identity.email(), identity.organizationId(), identity.organizationName(),
                identity.subscriptionType());
    }

    public String getAccountId() { return accountId; }
    public byte[] getSealedToken() { return sealedToken; }
    public byte[] getNonce() { return nonce; }
    public Instant getConnectedAt() { return connectedAt; }
    public String getClaudeEmail() { return claudeEmail; }
    public String getClaudeOrgId() { return claudeOrgId; }
    public String getClaudeOrgName() { return claudeOrgName; }
    public String getClaudeSubscriptionType() { return claudeSubscriptionType; }

    public ClaudeAccountIdentity identity() {
        if (claudeEmail == null) {
            return null;
        }
        return new ClaudeAccountIdentity(
                claudeEmail, claudeOrgId, claudeOrgName, claudeSubscriptionType);
    }
}
