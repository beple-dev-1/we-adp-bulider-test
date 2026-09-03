package com.bizplay.builder.businesslanguage;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 정책 항목과 표준용어를 사람이 확인할 수 있는 단위로 비교한다. */
@Service
public class BusinessDocumentHistoryService {

    private final BusinessLanguageMarkdown markdown;

    public BusinessDocumentHistoryService(BusinessLanguageMarkdown markdown) {
        this.markdown = markdown;
    }

    public List<BusinessDocumentChange> changes(BusinessDocumentKind kind, String before, String after) {
        Map<String, String> previous = kind == BusinessDocumentKind.POLICY
                ? policySections(before) : termRows(before);
        Map<String, String> current = kind == BusinessDocumentKind.POLICY
                ? policySections(after) : termRows(after);
        Set<String> keys = new LinkedHashSet<>(current.keySet());
        keys.addAll(previous.keySet());
        List<BusinessDocumentChange> changes = new ArrayList<>();
        for (String key : keys) {
            String oldValue = previous.get(key);
            String newValue = current.get(key);
            if (oldValue == null) {
                changes.add(new BusinessDocumentChange(BusinessDocumentChangeType.ADDED, key, "", newValue));
            } else if (newValue == null) {
                changes.add(new BusinessDocumentChange(BusinessDocumentChangeType.REMOVED, key, oldValue, ""));
            } else if (!oldValue.equals(newValue)) {
                changes.add(new BusinessDocumentChange(BusinessDocumentChangeType.MODIFIED, key, oldValue, newValue));
            }
        }
        return List.copyOf(changes);
    }

    private static Map<String, String> policySections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String raw : normalized(content).split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.stripLeading().startsWith("## ")) {
                putSection(sections, title, body);
                title = line.strip().substring(3).strip();
                body.setLength(0);
            } else if (title != null) {
                if (!body.isEmpty()) body.append('\n');
                body.append(line);
            }
        }
        putSection(sections, title, body);
        if (sections.isEmpty() && !normalized(content).isBlank()) {
            sections.put("문서 전체", normalized(content).strip());
        }
        return sections;
    }

    private Map<String, String> termRows(String content) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (StandardTerm term : markdown.terms(content)) {
            rows.put(term.term(), "용어 정의: %s\n동의어·유사어: %s"
                    .formatted(term.meaning(), term.aliases()).stripTrailing());
        }
        return rows;
    }

    private static void putSection(Map<String, String> sections, String title, StringBuilder body) {
        if (title == null || title.isBlank()) return;
        String uniqueTitle = title;
        int duplicate = 2;
        while (sections.containsKey(uniqueTitle)) uniqueTitle = title + " (" + duplicate++ + ")";
        sections.put(uniqueTitle, body.toString().strip());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
