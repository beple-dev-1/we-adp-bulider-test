package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** AI 화면 수정 결과에서 변경 설명과 사용자에게 보여 줄 답을 읽는다. */
@Component
public class FrdScreenChatReader {

    private final ObjectMapper mapper = new ObjectMapper();

    public enum Type { ANSWER, EDIT, CREATE_SCREEN }

    public record NewScreen(String screenId, String screenName) { }

    public record Reply(Type type, List<String> changes, String assistantMessage, NewScreen newScreen) { }

    public Reply read(String output) throws IOException {
        JsonNode root = mapper.readTree(stripFence(output));
        if (root == null || !root.isObject()) {
            throw new IOException("AI 응답이 JSON 객체가 아닙니다.");
        }
        List<String> changes = new ArrayList<>();
        for (JsonNode each : root.path("changes")) {
            if (each.isTextual() && !each.asText().isBlank()) {
                changes.add(each.asText().strip());
            }
        }
        Type type = typeOf(root.path("type"), changes);
        String assistantMessage = root.path("assistantMessage").isTextual()
                ? normalizeLineBreaks(root.path("assistantMessage").asText()).strip() : "";
        if (assistantMessage.isBlank()) {
            assistantMessage = switch (type) {
                case ANSWER -> "화면을 확인했습니다. 궁금한 내용을 조금 더 구체적으로 말씀해 주세요.";
                case EDIT -> "요청한 내용을 화면에 반영했습니다.";
                case CREATE_SCREEN -> "요청한 신규 화면을 만들었습니다.";
            };
        }
        JsonNode newScreenNode = root.path("newScreen");
        NewScreen newScreen = newScreenNode.isObject()
                ? new NewScreen(textOf(newScreenNode.path("screenId")), textOf(newScreenNode.path("screenName")))
                : null;
        return new Reply(type, List.copyOf(changes), assistantMessage, newScreen);
    }

    private Type typeOf(JsonNode node, List<String> changes) {
        if (node.isTextual()) {
            try {
                return Type.valueOf(node.asText().strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 이전 형식의 응답은 실제 변경 목록으로 안전하게 판별한다.
            }
        }
        return changes.isEmpty() ? Type.ANSWER : Type.EDIT;
    }

    private String textOf(JsonNode node) {
        return node.isTextual() ? node.asText().strip() : "";
    }

    private String normalizeLineBreaks(String text) {
        return text.replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n");
    }

    private String stripFence(String output) {
        if (output == null) return "";
        int opens = output.indexOf('{');
        int closes = output.lastIndexOf('}');
        return opens >= 0 && closes > opens ? output.substring(opens, closes + 1) : output;
    }
}
