package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** AI가 수정한 화면 파일과 변경 설명을 <b>서버가 검사해서</b> 받는다. */
@Component
public class ScreenMockupReader {

    private final ObjectMapper mapper = new ObjectMapper();

    public record Mockup(String html, List<String> changes) { }

    public Mockup read(String output) throws IOException {
        JsonNode root = mapper.readTree(stripFence(output));
        JsonNode html = root.path("html");
        if (!html.isTextual() || html.asText().isBlank()) {
            throw new IOException("html 이 빈 결과다");
        }
        String document = html.asText().toLowerCase(java.util.Locale.ROOT);
        if (!document.contains("<html") || !document.contains("<head") || !document.contains("<body")) {
            throw new IOException("html 문서의 바깥 구조가 빠진 결과다");
        }
        List<String> changes = new ArrayList<>();
        for (JsonNode each : root.path("changes")) {
            if (each.isTextual() && !each.asText().isBlank()) {
                changes.add(each.asText().strip());
            }
        }
        return new Mockup(html.asText(), List.copyOf(changes));
    }

    /** AI는 본문만 고친다. 원본 head를 서버가 되씌워 CSS·메타·폰트 참조가 사라지지 않게 한다. */
    public Mockup read(String output, String originalHtml) throws IOException {
        Mockup mockup = read(output);
        return preserveHead(mockup, originalHtml);
    }

    /**
     * Claude가 워크트리 파일을 직접 고친 뒤 내놓은 변경 설명과 실제 파일을 합쳐 읽는다.
     * 응답으로 HTML 전체를 다시 받지 않는 것이 이 메서드와 {@link #read(String)}의 차이다.
     */
    public Mockup readEdited(String output, String editedHtml, String originalHtml) throws IOException {
        validateDocument(editedHtml);
        JsonNode root = mapper.readTree(stripFence(output));
        Mockup mockup = new Mockup(editedHtml, changesOf(root));
        return preserveHead(mockup, originalHtml);
    }

    /** 맵 AI처럼 화면별 변경 설명을 이미 구조화해 받은 경우의 파일 검증 경로. */
    public Mockup validateEdited(String editedHtml, String originalHtml, List<String> changes) throws IOException {
        validateDocument(editedHtml);
        return preserveHead(new Mockup(editedHtml, changes == null ? List.of() : List.copyOf(changes)), originalHtml);
    }

    private Mockup preserveHead(Mockup mockup, String originalHtml) throws IOException {
        String originalHead = headOf(originalHtml);
        if (originalHead == null) {
            return mockup;
        }
        String generatedHead = headOf(mockup.html());
        if (generatedHead == null) {
            throw new IOException("생성 html 에 head 가 없다");
        }
        return new Mockup(mockup.html().replace(generatedHead, originalHead), mockup.changes());
    }

    private void validateDocument(String html) throws IOException {
        if (html == null || html.isBlank()) {
            throw new IOException("html 이 빈 결과다");
        }
        String document = html.toLowerCase(java.util.Locale.ROOT);
        if (!document.contains("<html") || !document.contains("<head") || !document.contains("<body")) {
            throw new IOException("html 문서의 바깥 구조가 빠진 결과다");
        }
    }

    private List<String> changesOf(JsonNode root) {
        List<String> changes = new ArrayList<>();
        for (JsonNode each : root.path("changes")) {
            if (each.isTextual() && !each.asText().isBlank()) {
                changes.add(each.asText().strip());
            }
        }
        return List.copyOf(changes);
    }

    private String headOf(String html) {
        if (html == null) return null;
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        int start = lower.indexOf("<head");
        int end = lower.indexOf("</head>", start < 0 ? 0 : start);
        return start >= 0 && end >= start ? html.substring(start, end + "</head>".length()) : null;
    }

    /** ⚠ {@link ScreenPickReader} 와 똑같이 쓴다 — html 안에 닫는 중괄호가 있어도 안전하다. */
    private String stripFence(String output) {
        if (output == null) {
            return "";
        }
        int opens = output.indexOf('{');
        int closes = output.lastIndexOf('}');
        return opens >= 0 && closes > opens ? output.substring(opens, closes + 1) : output;
    }
}
