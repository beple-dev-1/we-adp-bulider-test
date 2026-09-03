package com.bizplay.builder.businesslanguage;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;

/** 정책서와 표준용어의 제한된 Markdown 계약을 읽고 쓴다. */
@Component
public class BusinessLanguageMarkdown {

    private static final String TERM_HEADER = "| 표준용어 | 용어 정의 | 동의어·유사어 |";
    private static final String TERM_DIVIDER = "| --- | --- | --- |";

    public String policyHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        for (String raw : normalized(markdown).split("\n", -1)) {
            String line = raw.strip();
            if (line.isBlank()) continue;
            if (line.startsWith("### ")) {
                html.append("<h3>").append(escape(line.substring(4))).append("</h3>");
            } else if (line.startsWith("## ")) {
                html.append("<h2>").append(escape(line.substring(3))).append("</h2>");
            } else if (!line.startsWith("<!--")) {
                html.append("<p>").append(escape(line)).append("</p>");
            }
        }
        return html.toString();
    }

    public List<StandardTerm> terms(String markdown) {
        List<StandardTerm> terms = new ArrayList<>();
        for (String raw : normalized(markdown).split("\n")) {
            String line = raw.strip();
            if (!line.startsWith("|") || line.equals(TERM_HEADER) || line.matches("^\\|[ :|\\-]+\\|$")) continue;
            List<String> cells = splitCells(line.substring(1, line.length() - 1));
            if (cells.size() < 3 || cells.size() > 4) continue;
            if (isHeader(cells)) continue;
            String term = unescapeCell(cells.get(0));
            if (term.isBlank()) continue;
            terms.add(new StandardTerm(term, unescapeCell(cells.get(1)), unescapeCell(cells.get(2)), ""));
        }
        return List.copyOf(terms);
    }

    public String termsMarkdown(List<StandardTerm> terms) {
        StringBuilder markdown = new StringBuilder("# 표준용어\n\n")
                .append(TERM_HEADER).append('\n').append(TERM_DIVIDER).append('\n');
        for (StandardTerm term : terms) {
            if (term == null || clean(term.term()).isBlank()) continue;
            markdown.append("| ").append(cell(term.term())).append(" | ")
                    .append(cell(term.meaning())).append(" | ")
                    .append(cell(term.aliases())).append(" |\n");
        }
        return markdown.toString();
    }

    public String normalizePolicy(String markdown) {
        String value = normalized(markdown).strip();
        if (value.isBlank()) throw new IllegalArgumentException("정책서 내용을 입력해 주세요.");
        return value + "\n";
    }

    public List<String> policyHeadings(String markdown) {
        return normalized(markdown).lines().map(String::strip)
                .filter(line -> line.startsWith("## "))
                .map(line -> line.substring(3).strip()).toList();
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }

    private static String cell(String value) {
        return clean(value).replace("|", "\\|").replace("\n", " ");
    }

    private static String unescapeCell(String value) {
        return value.strip().replace("\\|", "|");
    }

    private static boolean isHeader(List<String> cells) {
        if (!"표준용어".equals(cells.get(0).strip())) return false;
        String definitionHeader = cells.get(1).strip();
        return "용어 정의".equals(definitionHeader) || "뜻".equals(definitionHeader);
    }

    private static List<String> splitCells(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        for (char character : row.toCharArray()) {
            if (character == '|' && !escaped) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
            escaped = character == '\\' && !escaped;
            if (character != '\\') escaped = false;
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
