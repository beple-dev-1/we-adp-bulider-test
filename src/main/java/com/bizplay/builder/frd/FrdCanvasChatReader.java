package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 맵 AI가 돌려준 다중 화면 변경 결과를 안전한 값으로 읽는다. */
@Component
public class FrdCanvasChatReader {

    private static final int MAX_NEW_SCREENS = 20;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_QUESTIONS = 5;
    private static final int MAX_OPTIONS = 8;

    public enum Type { ANSWER, CHANGE, INTERVIEW }
    public enum AnswerType { SINGLE, MULTIPLE, TEXT }
    public record ScreenChange(String screenId, List<String> changes) { }
    public record NewScreen(String draftKey, String screenName, String baseScreenId,
                            List<String> changes) { }
    public record InterviewQuestion(String id, String prompt, AnswerType answerType,
                                    List<String> options, boolean required) { }
    public record Reply(Type type, String assistantMessage, List<ScreenChange> screens,
                        List<NewScreen> newScreens, List<InterviewQuestion> questions) { }

    public Reply read(String output) throws IOException {
        JsonNode root = mapper.readTree(stripFence(output));
        if (root == null || !root.isObject()) throw new IOException("맵 AI 응답이 JSON 객체가 아닙니다.");
        Type type;
        try {
            type = Type.valueOf(root.path("type").asText("ANSWER").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            type = Type.ANSWER;
        }
        String message = root.path("assistantMessage").asText("").strip();
        if (message.isBlank()) message = type == Type.ANSWER
                ? "캔버스를 확인했습니다." : "요청한 화면과 연결을 수정했습니다.";
        List<ScreenChange> screens = new ArrayList<>();
        for (JsonNode node : root.path("screens")) {
            String id = node.path("screenId").asText("").strip();
            if (!id.isBlank()) screens.add(new ScreenChange(id, changes(node.path("changes"))));
        }
        List<NewScreen> created = new ArrayList<>();
        Set<String> draftKeys = new LinkedHashSet<>();
        for (JsonNode node : root.path("newScreens")) {
            String key = node.path("draftKey").asText("").strip();
            String base = node.path("baseScreenId").asText("").strip();
            if (created.size() >= MAX_NEW_SCREENS) break;
            if (!key.matches("[a-z][a-z0-9-]{0,30}") || base.isBlank() || !draftKeys.add(key)) continue;
            created.add(new NewScreen(key, node.path("screenName").asText(key).strip(),
                    base, changes(node.path("changes"))));
        }
        List<InterviewQuestion> questions = questions(root.path("questions"));
        if (type == Type.INTERVIEW && questions.isEmpty()) type = Type.ANSWER;
        return new Reply(type, message, List.copyOf(screens), List.copyOf(created), questions);
    }

    private List<InterviewQuestion> questions(JsonNode values) {
        List<InterviewQuestion> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (result.size() >= MAX_QUESTIONS) break;
            String prompt = value.path("prompt").asText("").strip();
            if (prompt.isBlank()) continue;
            String fallbackId = "question-" + (result.size() + 1);
            String id = value.path("id").asText(fallbackId).strip();
            if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,39}") || !ids.add(id)) id = fallbackId;
            AnswerType answerType;
            try {
                answerType = AnswerType.valueOf(value.path("answerType").asText("TEXT")
                        .toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                answerType = AnswerType.TEXT;
            }
            List<String> options = changes(value.path("options")).stream().limit(MAX_OPTIONS).toList();
            if (answerType != AnswerType.TEXT && options.isEmpty()) answerType = AnswerType.TEXT;
            result.add(new InterviewQuestion(id, prompt, answerType, options,
                    value.path("required").asBoolean(true)));
        }
        return List.copyOf(result);
    }

    private List<String> changes(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().strip());
        }
        return List.copyOf(result);
    }

    private String stripFence(String output) {
        if (output == null) return "";
        int first = output.indexOf('{');
        int last = output.lastIndexOf('}');
        return first >= 0 && last > first ? output.substring(first, last + 1) : output;
    }
}
