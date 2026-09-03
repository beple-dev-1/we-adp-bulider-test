package com.bizplay.builder.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 로그인 직후 Claude Code 자체 상태 명령으로 실제 계정을 확인한다. */
@Component
public class ClaudeAccountIdentityReader {

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeAccountIdentity read(Path credentialDir) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "auth", "status", "--json");
            pb.environment().put("CLAUDE_CONFIG_DIR", credentialDir.toString());
            pb.environment().remove("ANTHROPIC_API_KEY");
            pb.environment().remove("ANTHROPIC_AUTH_TOKEN");
            pb.environment().remove("CLAUDE_CODE_OAUTH_TOKEN");
            pb.redirectErrorStream(true);
            process = pb.start();
            if (!process.waitFor(STATUS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Claude 로그인 계정 확인 시간이 지났습니다.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("Claude 로그인 계정을 확인하지 못했습니다.");
            }
            return parse(output);
        } catch (IOException e) {
            throw new IllegalStateException("Claude 로그인 계정을 확인하지 못했습니다.", e);
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Claude 로그인 계정 확인이 중단됐습니다.", e);
        }
    }

    ClaudeAccountIdentity parse(String statusJson) {
        try {
            JsonNode root = mapper.readTree(statusJson);
            if (!root.path("loggedIn").asBoolean(false)) {
                throw new IllegalArgumentException("Claude에 로그인되어 있지 않습니다.");
            }
            String email = text(root, "email");
            String organizationId = text(root, "orgId");
            if (email == null) {
                throw new IllegalArgumentException("Claude 계정 식별정보를 확인하지 못했습니다.");
            }
            return new ClaudeAccountIdentity(email, organizationId,
                    text(root, "orgName"), text(root, "subscriptionType"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Claude 로그인 상태 응답을 읽지 못했습니다.", e);
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText().strip();
    }
}
