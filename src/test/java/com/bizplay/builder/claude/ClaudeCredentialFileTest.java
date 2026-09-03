package com.bizplay.builder.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCredentialFileTest {

    private final ClaudeCredentialFile credentialFile = new ClaudeCredentialFile();

    /** 스파이크가 실물에서 본 모양 — 한 파일에 Claude 자격과 MCP 토큰이 같이 산다. */
    private static final String REAL_FILE = """
            {
              "claudeAiOauth": {
                "accessToken": "액세스", "refreshToken": "갱신",
                "expiresAt": 1, "refreshTokenExpiresAt": 2,
                "scopes": ["a","b"], "subscriptionType": "team", "rateLimitTier": "t"
              },
              "mcpOAuth": { "some-server": { "accessToken": "남의토큰" } }
            }
            """;

    @Test
    void 클로드_자격만_떼어_온다() {
        String extracted = credentialFile.extractOAuthBlock(REAL_FILE);
        assertThat(extracted).contains("claudeAiOauth").contains("액세스");
    }

    @Test
    void MCP_토큰은_안_딸려_온다() {
        String extracted = credentialFile.extractOAuthBlock(REAL_FILE);
        assertThat(extracted).doesNotContain("mcpOAuth").doesNotContain("남의토큰");
    }

    @Test
    void 클로드_자격이_없으면_튕긴다() {
        assertThatThrownBy(() -> credentialFile.extractOAuthBlock("{\"mcpOAuth\":{}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claudeAiOauth");
    }
}
