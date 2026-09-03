package com.bizplay.builder.frd;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 기획 저장소 화면 정의서의 최소 구조와 전달용 화면 식별자를 정규화한다. */
public final class ScreenDefinitionDocument {

    private static final Pattern TAG_SECTION = Pattern.compile(
            "(?ms)(^---\\s*꼬리표\\s*---\\s*\\R)(.*?)(?=^---\\s*[^\\r\\n]+\\s*---\\s*$|\\z)");
    private static final Pattern DEFINITION_HEADER = Pattern.compile(
            "(?m)^---\\s*정의\\s*---\\s*$");
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?m)^---\\s*[^\\r\\n]+\\s*---\\s*$");

    private ScreenDefinitionDocument() {
    }

    /** Builder가 만든 문서를 기획 저장소의 기존 화면 정의서 블록 계약에 맞춘다. */
    public static String normalizeStructure(String document) {
        if (document == null || document.isBlank()) return document == null ? "" : document;
        String normalized = normalizeTaskTag(document);
        Matcher definition = DEFINITION_HEADER.matcher(normalized);
        if (!definition.find()) {
            String lineBreak = lineBreak(normalized);
            return normalized.stripTrailing() + lineBreak + lineBreak
                    + "--- 정의 ---" + lineBreak + lineBreak
                    + "--- 원본 글 ---" + lineBreak;
        }

        Matcher following = SECTION_HEADER.matcher(normalized);
        following.region(definition.end(), normalized.length());
        if (!following.find()) {
            String lineBreak = lineBreak(normalized);
            normalized = normalized.stripTrailing() + lineBreak + lineBreak
                    + "--- 원본 글 ---" + lineBreak;
        }
        return normalized.stripTrailing() + lineBreak(normalized);
    }

    /** FRD 내부 임시 화면 ID를 개발자에게 전달할 화면 ID로 바꾼다. */
    public static String forDelivery(String document, Map<String, String> screenIds) {
        return replaceScreenIds(normalizeStructure(document), screenIds);
    }

    /** HTML과 화면 정의서 안의 FRD 내부 화면 ID를 전달 화면 ID로 바꾼다. */
    public static String replaceScreenIds(String content, Map<String, String> screenIds) {
        if (content == null || content.isEmpty() || screenIds == null || screenIds.isEmpty()) return content;
        String replaced = content;
        List<Map.Entry<String, String>> replacements = screenIds.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .filter(entry -> !entry.getKey().equals(entry.getValue()))
                .sorted(Map.Entry.<String, String>comparingByKey(
                        Comparator.comparingInt(String::length).reversed()))
                .toList();
        for (Map.Entry<String, String> replacement : replacements) {
            replaced = replaced.replace(replacement.getKey(), replacement.getValue());
        }
        return replaced;
    }

    private static String normalizeTaskTag(String document) {
        Matcher matcher = TAG_SECTION.matcher(document);
        if (!matcher.find()) return document;
        String body = matcher.group(2).replaceAll("(?m)(^| / )작업\\s*:", "$1과업:");
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + body));
    }

    private static String lineBreak(String document) {
        return document.contains("\r\n") ? "\r\n" : "\n";
    }
}
