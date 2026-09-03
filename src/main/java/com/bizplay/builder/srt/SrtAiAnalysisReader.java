package com.bizplay.builder.srt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Claude가 반환한 SRT 유효성 판정과 개발요청서 정의를 검사한다. */
@Component
public class SrtAiAnalysisReader {

    private static final int MAX_ITEMS = 20;
    private static final int MAX_ITEM_LENGTH = 2000;
    private final ObjectMapper json;

    public SrtAiAnalysisReader(ObjectMapper json) {
        this.json = json;
    }

    public SrtAiAnalysis read(String output) throws IOException {
        JsonNode root = json.readTree(stripFence(output));
        if (!root.has("eligible") || !root.path("eligible").isBoolean()) {
            throw new IOException("SRT 판정값(eligible)이 없습니다.");
        }
        boolean eligible = root.path("eligible").asBoolean();
        String reason = text(root, "rejectionReason");
        if (!eligible) {
            if (reason == null) {
                throw new IOException("개발요청서를 만들 수 없는 이유가 없습니다.");
            }
            return new SrtAiAnalysis(false, cut(reason), null, List.of(), List.of());
        }
        String comment = text(root, "analysisComment");
        if (comment == null) throw new IOException("AI 분석 코멘트가 없습니다.");
        List<String> requirements = strings(root.path("requirements"), "요구사항");
        List<String> criteria = strings(root.path("acceptanceCriteria"), "완료 조건");
        if (requirements.isEmpty() || criteria.isEmpty()) {
            throw new IOException("유효한 SRT에는 요구사항과 완료 조건이 각각 한 건 이상 있어야 합니다.");
        }
        return new SrtAiAnalysis(true, null, cut(comment), requirements, criteria);
    }

    private List<String> strings(JsonNode node, String label) throws IOException {
        if (!node.isArray()) {
            throw new IOException(label + " 목록이 배열이 아닙니다.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(cut(value.asText().strip()));
            }
            if (values.size() > MAX_ITEMS) {
                throw new IOException(label + "이 " + MAX_ITEMS + "건을 넘습니다.");
            }
        }
        return List.copyOf(values);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    private String cut(String value) {
        return value.length() <= MAX_ITEM_LENGTH ? value : value.substring(0, MAX_ITEM_LENGTH);
    }

    private String stripFence(String output) {
        if (output == null) return "";
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        return start >= 0 && end > start ? output.substring(start, end + 1) : output;
    }
}
