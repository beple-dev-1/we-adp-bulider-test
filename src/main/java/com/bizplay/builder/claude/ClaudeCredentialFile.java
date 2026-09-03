package com.bizplay.builder.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * `.credentials.json` 에서 <b>Claude 계정 자격만</b> 떼어낸다.
 *
 * <p>⚠ <b>왜 통째로 안 옮기나</b> — 스파이크(2026-08-08)가 실물에서 확인했다:
 * 그 파일에는 그 사람의 <b>MCP 서버 OAuth 토큰(`mcpOAuth.*`)도 함께 산다.</b>
 * 파일을 통째로 봉인해 DB 에 두면 <b>빌더 서버가 남의 MCP 토큰까지 쥐게 된다.</b>
 * 빌더가 쥘 것은 `claudeAiOauth` 하나뿐이다.
 *
 * <p>내주는 것은 <b>키 하나짜리 `.credentials.json` 문서</b>다 — `{"claudeAiOauth": {...}}`.
 * 계획 2 의 `ClaudeCli` 가 이 문자열을 그 사람의 `CLAUDE_CONFIG_DIR` 자리에
 * `.credentials.json` 으로 <b>그대로</b> 쓴다. <b>다른 키는 만들지 않는다.</b>
 *
 * <p>⛔ 들어온 문자열도 나가는 문자열도 <b>로그에 남기지 않는다.</b>
 */
@Component
public class ClaudeCredentialFile {

    /** 이 키 하나만 옮긴다. */
    static final String KEY = "claudeAiOauth";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param 자격파일_전체 `.credentials.json` 을 읽은 그대로
     * @return `claudeAiOauth` 한 칸만 든 JSON 문서
     */
    public String extractOAuthBlock(String wholeCredentialFile) {
        try {
            JsonNode root = mapper.readTree(wholeCredentialFile);
            JsonNode block = root.get(KEY);
            if (block == null || !block.isObject()) {
                // 값은 절대 메시지에 싣지 않는다.
                throw new IllegalArgumentException("자격 파일에 " + KEY + " 가 없다");
            }
            ObjectNode onlyBlock = mapper.createObjectNode();
            onlyBlock.set(KEY, block);
            return mapper.writeValueAsString(onlyBlock);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("자격 파일이 JSON 이 아니다", e);
        }
    }
}
