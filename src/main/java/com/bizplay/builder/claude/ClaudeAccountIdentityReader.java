package com.bizplay.builder.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 로그인 직후 Claude Code 자체 상태 명령으로 실제 계정을 확인한다. */
@Component
public class ClaudeAccountIdentityReader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAccountIdentityReader.class);

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(15);

    /** 고정 낱말뿐이다. 밖에서 들어오는 값이 없다. */
    private static final List<String> STATUS_ARGS = List.of("auth", "status", "--json");

    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeAccountIdentity read(Path credentialDir) {
        Process process = null;
        try {
            // ⛔ 인자가 전부 고정 낱말이라 ClaudeCli.fixedCommand 로 간다 — 까닭은 그쪽에 있다.
            //    004-1 이 로그인 자리만 고쳐서 여기가 남았고, 그래서 승인 코드를 넣는 마지막
            //    걸음에서 CreateProcess error=2 로 터졌다(004-2).
            ProcessBuilder pb = new ProcessBuilder(ClaudeCli.fixedCommand(
                    System.getProperty("os.name"), STATUS_ARGS));
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
                // ⛔ 까닭을 버리지 마라. cmd 를 지나면서 「claude 를 못 찾음」이 예외 메시지가 아니라
                //    자식의 종료코드로 오게 됐다(004-2) — 여기서 안 남기면 아무 데도 안 남는다.
                // ⛔ 성공 응답은 절대 안 남긴다 — 그 JSON 에는 계정 이메일이 들어 있다.
                //    여기는 실패 갈래이고, 첫 줄만 짧게 남긴다.
                log.warn("claude auth status 가 실패했다 exitCode={} 첫줄={}",
                        process.exitValue(), firstLine(output));
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

    /** 진단용 한 줄. ⛔ 길게 남기지 않는다 — 뒤에 무엇이 붙어 나올지 모른다. */
    private static String firstLine(String output) {
        String head = output == null ? "" : output.strip();
        int newline = head.indexOf('\n');
        if (newline >= 0) {
            head = head.substring(0, newline);
        }
        return head.length() > 200 ? head.substring(0, 200) + "…" : head;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText().strip();
    }
}
