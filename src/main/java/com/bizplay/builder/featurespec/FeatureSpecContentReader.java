package com.bizplay.builder.featurespec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AI의 구조화 응답을 읽고 길이·개수·근거 계약을 검증한다. */
@Component
public class FeatureSpecContentReader {

    static final int MAX_LENGTH = 120_000;
    private static final int MAX_ITEMS = 150;
    private static final int MAX_TEXT = 6_000;
    private final ObjectMapper strictJson;

    public FeatureSpecContentReader(ObjectMapper json) {
        this.strictJson = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public FeatureSpecContent read(String output, Set<String> evidenceCatalog,
                                   Set<String> targetScreenIds) throws IOException {
        String source = stripFence(output);
        if (source.isBlank()) throw new IOException("기능명세서 응답이 비어 있습니다.");
        if (source.length() > MAX_LENGTH) throw new IOException("기능명세서 응답이 너무 큽니다.");
        FeatureSpecContent content;
        try {
            content = strictJson.readValue(source, FeatureSpecContent.class);
        } catch (IOException invalid) {
            throw new IOException("기능명세서 응답 JSON을 읽을 수 없습니다.", invalid);
        }
        validate(content, evidenceCatalog, targetScreenIds);
        return content;
    }

    private void validate(FeatureSpecContent content, Set<String> catalog,
                          Set<String> targetScreenIds) throws IOException {
        required(content.title(), "문서 제목");
        if (content.overview() == null) throw new IOException("화면 개요가 없습니다.");
        required(content.overview().purpose(), "화면 목적");
        required(content.overview().scope(), "적용 범위");
        evidence(content.overview().evidenceIds(), catalog);
        count(content.preconditions(), "선행 조건");
        count(content.functions(), "기능");
        count(content.fields(), "화면 항목");
        count(content.businessRules(), "업무 규칙");
        count(content.permissionRules(), "권한 규칙");
        count(content.messages(), "메시지");
        count(content.transitions(), "화면 이동");
        count(content.integrations(), "연계");
        if (content.functions().isEmpty()) throw new IOException("기능 항목이 없습니다.");

        for (FeatureSpecContent.TextItem item : content.preconditions()) {
            required(item.text(), "선행 조건"); evidence(item.evidenceIds(), catalog);
        }
        for (FeatureSpecContent.FunctionItem item : content.functions()) {
            required(item.name(), "기능명"); required(item.processing(), "처리 내용");
            required(item.result(), "처리 결과"); evidence(item.evidenceIds(), catalog);
        }
        Set<String> fields = new HashSet<>();
        for (FeatureSpecContent.FieldItem item : content.fields()) {
            required(item.name(), "항목명");
            if (!fields.add(item.name().strip().toLowerCase(Locale.ROOT))) throw new IOException("화면 항목이 중복됩니다.");
            required(item.description(), "항목 설명"); evidence(item.evidenceIds(), catalog);
        }
        for (FeatureSpecContent.RuleItem item : content.businessRules()) rule(item, catalog);
        for (FeatureSpecContent.RuleItem item : content.permissionRules()) rule(item, catalog);
        for (FeatureSpecContent.MessageItem item : content.messages()) {
            required(item.situation(), "메시지 발생 상황"); required(item.message(), "메시지 내용");
            evidence(item.evidenceIds(), catalog);
        }
        for (FeatureSpecContent.TransitionItem item : content.transitions()) {
            required(item.action(), "화면 이동 동작"); required(item.result(), "화면 이동 결과");
            if (item.targetScreenId() != null && !item.targetScreenId().isBlank()
                    && !targetScreenIds.contains(item.targetScreenId())) {
                throw new IOException("존재하지 않는 이동 대상 화면이 있습니다.");
            }
            evidence(item.evidenceIds(), catalog);
        }
        for (FeatureSpecContent.IntegrationItem item : content.integrations()) {
            required(item.name(), "연계명"); required(item.direction(), "연계 방향");
            required(item.data(), "연계 데이터"); evidence(item.evidenceIds(), catalog);
        }
    }

    private void rule(FeatureSpecContent.RuleItem item, Set<String> catalog) throws IOException {
        required(item.title(), "규칙명"); required(item.description(), "규칙 내용");
        evidence(item.evidenceIds(), catalog);
    }

    private void required(String value, String label) throws IOException {
        if (value == null || value.isBlank()) throw new IOException(label + "이 비어 있습니다.");
        if (value.length() > MAX_TEXT) throw new IOException(label + "이 너무 깁니다.");
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("<script") || lower.contains("javascript:") || lower.contains("src/main/")) {
            throw new IOException(label + "에 문서에 허용되지 않는 개발 내용이 있습니다.");
        }
    }

    private void evidence(List<String> ids, Set<String> catalog) throws IOException {
        if (ids == null || ids.isEmpty()) throw new IOException("항목의 근거가 없습니다.");
        if (ids.size() > 20 || ids.stream().anyMatch(id -> id == null || !catalog.contains(id))) {
            throw new IOException("입력 자료에서 확인할 수 없는 근거가 있습니다.");
        }
    }

    private void count(List<?> items, String label) throws IOException {
        if (items == null) throw new IOException(label + " 배열이 없습니다.");
        if (items.size() > MAX_ITEMS) throw new IOException(label + " 항목이 너무 많습니다.");
    }

    public List<String> evidenceIds(FeatureSpecContent content) {
        List<String> ids = new ArrayList<>(content.overview().evidenceIds());
        content.preconditions().forEach(v -> ids.addAll(v.evidenceIds()));
        content.functions().forEach(v -> ids.addAll(v.evidenceIds()));
        content.fields().forEach(v -> ids.addAll(v.evidenceIds()));
        content.businessRules().forEach(v -> ids.addAll(v.evidenceIds()));
        content.permissionRules().forEach(v -> ids.addAll(v.evidenceIds()));
        content.messages().forEach(v -> ids.addAll(v.evidenceIds()));
        content.transitions().forEach(v -> ids.addAll(v.evidenceIds()));
        content.integrations().forEach(v -> ids.addAll(v.evidenceIds()));
        return ids.stream().distinct().sorted().toList();
    }

    private String stripFence(String output) {
        String text = output == null ? "" : output.strip();
        if (!text.startsWith("```")) return text;
        int firstBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        return firstBreak < 0 || lastFence <= firstBreak ? text : text.substring(firstBreak + 1, lastFence).strip();
    }
}
