package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Claude가 한 번의 분석에서 반환한 질문 또는 최종 결과를 검사한다. */
@Component
public class FrdInterviewReader {

    private static final int MAX_OPTIONS = 4;
    private static final int MAX_BACKEND_CHANGES = 30;

    private final ObjectMapper json = new ObjectMapper();
    private final ScreenPickReader screenPickReader;

    public FrdInterviewReader(ScreenPickReader screenPickReader) {
        this.screenPickReader = screenPickReader;
    }

    public sealed interface Turn permits Question, Result { }

    public record Question(String analysisSummary, String assistantMessage, String topic, String text,
                           String reason, List<String> options) implements Turn {
        public Question(String analysisSummary, String topic, String text,
                        String reason, List<String> options) {
            this(analysisSummary, null, topic, text, reason, options);
        }
    }

    public record BackendChange(Integer requirementSeq, FrdBackendChange.Category category,
                                String target, String changeDetail, String evidence,
                                String verification, boolean required) { }

    public enum WorkMode { FAST_TRACK, FRD }

    public record Result(String analysisSummary, String assistantMessage, ScreenPickReader.Pick pick,
                         List<BackendChange> backendChanges, List<String> acceptanceCriteria,
                         List<String> openIssues, WorkMode workMode,
                         String workModeReason) implements Turn {
        public Result(String analysisSummary, ScreenPickReader.Pick pick,
                      List<BackendChange> backendChanges, List<String> acceptanceCriteria,
                      List<String> openIssues) {
            this(analysisSummary, null, pick, backendChanges, acceptanceCriteria, openIssues,
                    WorkMode.FRD, "FRD 작업에서 변경 내용을 구체화해야 합니다.");
        }
    }

    public Turn read(String output) throws IOException {
        JsonNode root = json.readTree(stripFence(output));
        String type = text(root, "type");
        if ("QUESTION".equalsIgnoreCase(type)) {
            return question(root);
        }
        if (type != null && !"RESULT".equalsIgnoreCase(type)) {
            throw new IOException("분석 결과 종류(type)를 모르겠습니다 — " + type);
        }
        return result(root);
    }

    private Question question(JsonNode root) throws IOException {
        JsonNode question = root.path("question");
        String content = text(question, "text");
        String reason = text(question, "reason");
        String topic = text(question, "topic");
        if (content == null || reason == null) {
            throw new IOException("인터뷰 질문의 내용(text) 또는 이유(reason)가 비었습니다.");
        }
        JsonNode optionNodes = question.path("options");
        if (!optionNodes.isArray()) {
            throw new IOException("인터뷰 질문의 선택지(options)가 배열이 아닙니다.");
        }
        List<String> options = new ArrayList<>();
        for (JsonNode option : optionNodes) {
            if (option.isTextual() && !option.asText().isBlank()) {
                options.add(option.asText().strip());
            }
        }
        if (options.size() < 2 || options.size() > MAX_OPTIONS) {
            throw new IOException("인터뷰 질문의 선택지는 2개 이상 4개 이하여야 합니다.");
        }
        return new Question(text(root, "analysisSummary"), text(root, "assistantMessage"),
                topic == null ? "확인할 내용" : cut(topic, 255), content, reason, List.copyOf(options));
    }

    private Result result(JsonNode root) throws IOException {
        ScreenPickReader.Pick pick = screenPickReader.read(root.toString());
        List<BackendChange> backendChanges = new ArrayList<>();
        JsonNode changes = root.path("backendChanges");
        if (!changes.isMissingNode() && !changes.isNull()) {
            if (!changes.isArray()) {
                throw new IOException("백엔드 변경(backendChanges)이 배열이 아닙니다.");
            }
            for (JsonNode change : changes) {
                String rawCategory = text(change, "category");
                String target = text(change, "target");
                String detail = text(change, "changeDetail");
                if (rawCategory == null || target == null || detail == null) {
                    throw new IOException("백엔드 변경의 분류·대상·변경 내용 중 빈 값이 있습니다.");
                }
                FrdBackendChange.Category category;
                try {
                    category = FrdBackendChange.Category.valueOf(rawCategory.toUpperCase());
                } catch (IllegalArgumentException unknown) {
                    throw new IOException("백엔드 변경 분류를 모르겠습니다 — " + rawCategory);
                }
                Integer requirementSeq = change.path("requirementSeq").canConvertToInt()
                        ? change.path("requirementSeq").asInt() : null;
                backendChanges.add(new BackendChange(requirementSeq, category, cut(target, 255),
                        detail, text(change, "evidence"), text(change, "verification"),
                        change.path("required").asBoolean(true)));
                if (backendChanges.size() > MAX_BACKEND_CHANGES) {
                    throw new IOException("백엔드 변경 항목이 " + MAX_BACKEND_CHANGES + "건을 넘습니다.");
                }
            }
        }
        WorkMode workMode = workMode(root);
        String workModeReason = text(root, "workModeReason");
        if (workModeReason == null) {
            workModeReason = workMode == WorkMode.FAST_TRACK
                    ? "화면 작업 없이 백엔드 변경만 있어 개발요청서로 바로 진행할 수 있습니다."
                    : "FRD 작업에서 변경 내용을 구체화해야 합니다.";
        }
        return new Result(text(root, "analysisSummary"), text(root, "assistantMessage"),
                pick, List.copyOf(backendChanges),
                strings(root.path("acceptanceCriteria"), "완료 기준"),
                strings(root.path("openIssues"), "확인 필요"), workMode, workModeReason);
    }

    private WorkMode workMode(JsonNode root) throws IOException {
        String raw = text(root, "workMode");
        if (raw == null) {
            return WorkMode.FRD;
        }
        try {
            return WorkMode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IOException("작업 진행 방식(workMode)을 모르겠습니다 — " + raw);
        }
    }

    private List<String> strings(JsonNode node, String label) throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IOException(label + " 목록이 배열이 아닙니다.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText().strip());
            }
        }
        return List.copyOf(values);
    }

    private String stripFence(String output) {
        if (output == null) {
            return "";
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        return start >= 0 && end > start ? output.substring(start, end + 1) : output;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    private String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
