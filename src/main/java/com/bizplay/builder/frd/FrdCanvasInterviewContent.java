package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 캔버스 인터뷰를 기존 대화 내용 열에 보존하고 화면 모델로 복원한다. */
final class FrdCanvasInterviewContent {

    private static final String KIND = "CANVAS_INTERVIEW";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FrdCanvasInterviewContent() { }

    record Content(String message, List<FrdCanvasChatReader.InterviewQuestion> questions) { }

    static String encode(String message, List<FrdCanvasChatReader.InterviewQuestion> questions) {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", KIND);
        root.put("message", message == null ? "" : message);
        ArrayNode values = root.putArray("questions");
        for (FrdCanvasChatReader.InterviewQuestion question : questions) {
            ObjectNode value = values.addObject();
            value.put("id", question.id());
            value.put("prompt", question.prompt());
            value.put("answerType", question.answerType().name());
            value.put("required", question.required());
            ArrayNode options = value.putArray("options");
            question.options().forEach(options::add);
        }
        return root.toString();
    }

    static Optional<Content> decode(String stored) {
        if (stored == null || stored.isBlank() || stored.charAt(0) != '{') return Optional.empty();
        try {
            JsonNode root = JSON.readTree(stored);
            if (!KIND.equals(root.path("kind").asText())) return Optional.empty();
            List<FrdCanvasChatReader.InterviewQuestion> questions = new ArrayList<>();
            for (JsonNode value : root.path("questions")) {
                FrdCanvasChatReader.AnswerType answerType;
                try {
                    answerType = FrdCanvasChatReader.AnswerType.valueOf(value.path("answerType").asText());
                } catch (IllegalArgumentException invalid) {
                    answerType = FrdCanvasChatReader.AnswerType.TEXT;
                }
                List<String> options = new ArrayList<>();
                value.path("options").forEach(option -> {
                    if (option.isTextual() && !option.asText().isBlank()) options.add(option.asText().strip());
                });
                questions.add(new FrdCanvasChatReader.InterviewQuestion(
                        value.path("id").asText(), value.path("prompt").asText(), answerType,
                        List.copyOf(options), value.path("required").asBoolean(true)));
            }
            if (questions.isEmpty()) return Optional.empty();
            return Optional.of(new Content(root.path("message").asText("확인이 필요한 내용이 있습니다."),
                    List.copyOf(questions)));
        } catch (IOException invalid) {
            return Optional.empty();
        }
    }

    static String conversationText(String stored) {
        return decode(stored).map(content -> {
            StringBuilder text = new StringBuilder(content.message());
            for (int index = 0; index < content.questions().size(); index++) {
                FrdCanvasChatReader.InterviewQuestion question = content.questions().get(index);
                text.append("\n").append(index + 1).append(". ").append(question.prompt());
                if (!question.options().isEmpty()) text.append(" (").append(String.join(" / ", question.options())).append(")");
            }
            return text.toString();
        }).orElse(stored);
    }
}
