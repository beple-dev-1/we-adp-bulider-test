package com.bizplay.builder.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeAccountIdentityReaderTest {

    private final ClaudeAccountIdentityReader reader = new ClaudeAccountIdentityReader();

    @Test
    void 인증상태에서_계정_식별정보를_읽는다() {
        ClaudeAccountIdentity identity = reader.parse("""
                {
                  "loggedIn": true,
                  "authMethod": "claude.ai",
                  "email": "Planner@Example.com ",
                  "orgId": "org-123",
                  "orgName": "기획팀",
                  "subscriptionType": "team"
                }
                """);

        assertThat(identity.email()).isEqualTo("planner@example.com");
        assertThat(identity.organizationId()).isEqualTo("org-123");
        assertThat(identity.organizationName()).isEqualTo("기획팀");
        assertThat(identity.subscriptionType()).isEqualTo("team");
    }

    @Test
    void 이메일이_없으면_연결하지_않는다() {
        assertThatThrownBy(() -> reader.parse("""
                {"loggedIn":true,"authMethod":"claude.ai","subscriptionType":"max"}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("식별정보");
    }

    @Test
    void 조직정보가_없는_개인계정도_이메일로_식별한다() {
        ClaudeAccountIdentity identity = reader.parse("""
                {"loggedIn":true,"authMethod":"claude.ai","email":"user@example.com","subscriptionType":"pro"}
                """);

        assertThat(identity.email()).isEqualTo("user@example.com");
        assertThat(identity.organizationId()).isNull();
    }

    @Test
    void 로그인되지_않은_상태는_연결하지_않는다() {
        assertThatThrownBy(() -> reader.parse("""
                {"loggedIn":false,"authMethod":"none"}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("로그인");
    }
}
