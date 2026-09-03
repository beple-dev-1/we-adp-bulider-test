package com.bizplay.builder.usermanual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** 사용자 매뉴얼 AI 출력을 읽고 Builder가 표시할 안전한 HTML로 바꾼다. */
@Component
public class UserManualReader {

    /** 화면 한 장의 사용법만 받도록 AI 응답 전체 크기를 제한한다. */
    static final int MAX_LENGTH = 60_000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_TEXT_LENGTH = 4_000;
    private static final int MAX_ITEMS = 100;

    private static final Set<String> ROOT_FIELDS = Set.of(
            "title", "overview", "overviewEvidence", "openingSteps", "tasks", "fields", "nextScreens");
    private static final Set<String> OPENING_FIELDS = Set.of("text", "evidence");
    private static final Set<String> TASK_FIELDS = Set.of("title", "steps", "result", "evidence");
    private static final Set<String> FIELD_FIELDS = Set.of("name", "description", "evidence");
    private static final Set<String> NEXT_SCREEN_FIELDS = Set.of("name", "description", "evidence");
    private static final Safelist LEGACY_TAGS = Safelist.none().addTags(
            "h1", "h2", "h3", "p", "ol", "ul", "li", "dl", "dt", "dd",
            "strong", "em", "br", "blockquote", "table", "thead", "tbody", "tr", "th", "td");

    private static final String DOCUMENT_STYLE = """
            :root {
              color-scheme: light; --ink: #080808; --muted: #64616a;
              --line: #dedbe0; --soft: #f4f3f1; --paper: #ffffff; --workspace: #f7f6f8;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; padding: 24px; color: var(--ink); background: var(--workspace);
              font: 14px/1.55 "Malgun Gothic", "Noto Sans KR", sans-serif;
              overflow-wrap: anywhere;
            }
            main {
              width: 100%; max-width: 1120px; margin: 0 auto; padding: clamp(32px, 5vw, 64px);
              border: 1px solid var(--line); background: var(--paper);
              box-shadow: 0 12px 32px rgb(8 8 8 / 10%); counter-reset: manual-section;
            }
            .manual-head {
              margin: 0 0 32px; padding: 24px 0 32px;
              border-top: 4px solid var(--ink); border-bottom: 2px solid var(--ink);
            }
            .manual-kicker {
              margin: 0 0 12px; color: var(--muted); font-size: 12px;
              font-weight: 700; letter-spacing: .08em;
            }
            h1, h2, h3 { color: var(--ink); }
            h1 {
              margin: 0 0 16px; padding: 0; border: 0;
              font-size: clamp(28px, 4vw, 36px); line-height: 1.3; letter-spacing: -.03em;
            }
            .manual-meta {
              display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
              gap: 16px 32px; margin: 0; padding: 0;
            }
            .manual-meta div { min-width: 0; }
            .manual-meta dt { margin: 0 0 4px; color: var(--muted); font-size: 12px; }
            .manual-meta dd {
              margin: 0; color: var(--ink); font-size: 14px; font-weight: 700; overflow-wrap: anywhere;
            }
            h2 {
              margin: 32px 0 12px; padding: 0 0 8px; border-bottom: 1px solid var(--line);
              font-size: 18px; line-height: 1.4; counter-increment: manual-section; break-after: avoid;
            }
            main > h2:first-of-type { margin-top: 0; }
            h2::before { content: counter(manual-section) ". "; }
            h3 { margin: 20px 0 8px; padding: 0; border: 0; font-size: 15px; line-height: 1.45; }
            p, ol, ul, blockquote, table { margin: 0 0 12px; }
            ol, ul { padding-left: 24px; }
            li + li { margin-top: 8px; }
            h3 + ol { margin-bottom: 12px; }
            h3 + ol + p {
              margin-bottom: 16px; padding: 12px 16px; border-left: 3px solid var(--ink); background: var(--soft);
            }
            main > dl {
              display: grid; grid-template-columns: 160px minmax(0, 1fr);
              margin: 0 0 12px; border-top: 1px solid var(--line);
            }
            main > dl dt, main > dl dd {
              min-width: 0; margin: 0; padding: 12px 16px; border-bottom: 1px solid var(--line);
            }
            main > dl dt { background: var(--soft); font-weight: 700; }
            .manual-screen { break-inside: avoid; }
            .manual-capture {
              width: 100%; margin: 0; padding: 12px; border: 1px solid var(--line); background: var(--soft);
            }
            .manual-capture img {
              display: block; width: 100%; max-width: 100%; height: auto; border: 1px solid var(--line);
            }
            .manual-capture figcaption { margin-top: 8px; color: var(--muted); font-size: 12px; text-align: center; }
            table {
              width: 100%; display: block; overflow-x: auto; border-collapse: collapse; font-size: 13px;
            }
            th, td {
              padding: 12px; border: 1px solid var(--line); text-align: left; vertical-align: top; line-height: 1.55;
            }
            th { background: var(--soft); font-weight: 700; }
            strong { color: var(--ink); }
            .manual-foot {
              display: flex; justify-content: space-between; gap: 16px;
              margin-top: 48px; padding-top: 16px; border-top: 1px solid var(--line);
              color: var(--muted); font-size: 12px;
            }
            @media (max-width: 600px) {
              body { padding: 0; background: var(--paper); }
              main { min-height: 100vh; padding: 24px 16px 48px; border: 0; box-shadow: none; }
              .manual-head { margin-bottom: 32px; padding-top: 16px; }
              .manual-meta { grid-template-columns: minmax(0, 1fr); gap: 12px; }
              main > dl { grid-template-columns: minmax(0, 1fr); }
              main > dl dt { border-bottom: 0; }
              .manual-foot { flex-direction: column; gap: 4px; }
            }
            @media print {
              @page { size: A4; margin: 18mm 16mm; }
              body { padding: 0; background: #ffffff; }
              main { max-width: none; margin: 0; padding: 0; border: 0; box-shadow: none; }
              table { display: table; overflow: visible; }
              li, tr, .manual-screen { break-inside: avoid; }
            }
            """;

    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final ObjectMapper json;

    public UserManualReader(ObjectMapper json) {
        this.json = json;
    }

    /** 구조화 응답을 읽고 저장 가능한 본문 HTML을 만든다. */
    public String read(String output) throws IOException {
        return renderBody(readDocument(output));
    }

    /** 구조화 응답을 읽고 각 항목을 검증한다. */
    public UserManualDocument readDocument(String output) throws IOException {
        String source = stripFence(output);
        if (source.isBlank()) {
            throw new IOException("사용자 매뉴얼 응답이 비어 있습니다.");
        }
        if (source.length() > MAX_LENGTH) {
            throw new IOException("사용자 매뉴얼 응답이 " + MAX_LENGTH + "자를 넘습니다.");
        }

        JsonNode root;
        try {
            root = json.readTree(source);
        } catch (IOException invalidJson) {
            throw new IOException("사용자 매뉴얼 응답 JSON을 읽을 수 없습니다.", invalidJson);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("사용자 매뉴얼 응답은 JSON 객체여야 합니다.");
        }
        rejectUnknown(root, ROOT_FIELDS, "사용자 매뉴얼");

        String title = requiredText(root, "title", "제목", MAX_TITLE_LENGTH);
        String overview = requiredText(root, "overview", "개요", MAX_TEXT_LENGTH);
        String overviewEvidence = evidence(root, "개요 근거", "overviewEvidence");
        List<UserManualDocument.OpeningStep> openingSteps = openingSteps(root);
        List<UserManualDocument.Task> tasks = tasks(root);
        List<UserManualDocument.Field> fields = fields(root);
        List<UserManualDocument.NextScreen> nextScreens = nextScreens(root);
        return new UserManualDocument(title, overview, overviewEvidence, openingSteps, tasks, fields, nextScreens);
    }

    /** 구조화 내용을 허용된 태그만 사용해 본문 HTML로 만든다. */
    public String renderBody(UserManualDocument manual) {
        Document document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        Element body = document.body();
        body.appendElement("h1").text(manual.title());

        section(body, "화면 개요").appendElement("p").text(manual.overview());
        if (!manual.openingSteps().isEmpty()) {
            appendList(section(body, "화면 접근 경로"), "ol",
                    manual.openingSteps().stream().map(UserManualDocument.OpeningStep::text).toList());
        }
        if (!manual.tasks().isEmpty()) {
            Element section = section(body, "주요 사용 방법");
            for (UserManualDocument.Task task : manual.tasks()) {
                section.appendElement("h3").text(task.title());
                appendList(section, "ol", task.steps());
                labelledParagraph(section, "결과", task.result());
            }
        }
        if (!manual.fields().isEmpty()) {
            Element definitions = section(body, "화면 항목").appendElement("dl");
            for (UserManualDocument.Field field : manual.fields()) {
                definitions.appendElement("dt").text(field.name());
                definitions.appendElement("dd").text(field.description());
            }
        }
        if (!manual.nextScreens().isEmpty()) {
            Element list = section(body, "연관 화면").appendElement("ul");
            for (UserManualDocument.NextScreen next : manual.nextScreens()) {
                Element item = list.appendElement("li");
                item.appendElement("strong").text(next.name());
                item.appendText(" — " + next.description());
            }
        }
        return body.html();
    }

    /** 저장된 본문을 독립적으로 열거나 인쇄할 수 있는 완전한 HTML 문서로 감싼다. */
    public String renderStandalone(String storedBody) {
        return renderStandaloneDocument(storedBody, null, null);
    }

    /** 과거 정상본처럼 캡처가 없는 매뉴얼에도 문서 정보를 넣어 같은 산출물 양식으로 만든다. */
    public String renderStandalone(String storedBody, StandaloneMeta meta) {
        requireMeta(meta);
        return renderStandaloneDocument(storedBody, meta, null);
    }

    /** 검증된 실제 화면 한 장과 문서 정보를 넣어 독립 산출물로 만든다. */
    public String renderStandalone(String storedBody, StandaloneMeta meta, VerifiedCapture capture) {
        requireMeta(meta);
        requireCapture(capture);
        return renderStandaloneDocument(storedBody, meta, capture);
    }

    private String renderStandaloneDocument(String storedBody, StandaloneMeta meta, VerifiedCapture capture) {
        String safeBody = sanitizeLegacy(storedBody);
        if (safeBody.isBlank()) {
            safeBody = "<h1>사용자 매뉴얼</h1><p>매뉴얼 내용이 없습니다.</p>";
        }

        Document content = Jsoup.parseBodyFragment(safeBody);
        Element sourceTitle = content.selectFirst("h1");
        String title = sourceTitle == null || sourceTitle.text().isBlank()
                ? "사용자 매뉴얼" : sourceTitle.text();
        if (meta != null) {
            if (sourceTitle != null) sourceTitle.remove();
            if (capture != null) insertCaptureAfterOverview(content, capture, title);
        }

        Document document = Document.createShell("");
        document.outputSettings().prettyPrint(true);
        document.prependChild(new DocumentType("html", "", ""));
        document.selectFirst("html").attr("lang", "ko");
        Element head = document.head();
        head.appendElement("meta").attr("charset", "utf-8");
        head.appendElement("meta").attr("name", "viewport")
                .attr("content", "width=device-width, initial-scale=1");
        head.appendElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", "default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        head.appendElement("title").text(title);
        head.appendElement("style").appendChild(new DataNode(DOCUMENT_STYLE));
        Element main = document.body().appendElement("main");
        if (meta == null) {
            main.html(safeBody);
        } else {
            appendHeader(main, title, meta);
            main.appendChildren(new ArrayList<>(content.body().children()));
            appendFooter(main, meta);
        }
        return document.outerHtml();
    }

    private void appendHeader(Element main, String title, StandaloneMeta meta) {
        Element header = main.appendElement("header").addClass("manual-head");
        header.appendElement("p").addClass("manual-kicker").text("사용자 매뉴얼");
        header.appendElement("h1").text(title);
        Element details = header.appendElement("dl").addClass("manual-meta");
        meta(details, "시스템", meta.systemLabel());
        meta(details, "관리번호", meta.managementNumber());
        meta(details, "화면 ID", meta.screenId());
        meta(details, "작성일", meta.createdDate().toString());
    }

    private void appendFooter(Element main, StandaloneMeta meta) {
        Element footer = main.appendElement("footer").addClass("manual-foot");
        footer.appendElement("span").addClass("manual-source").text("운영 화면 · 화면 명세 기준");
        footer.appendElement("span").addClass("manual-date").text("사용자 매뉴얼 · " + meta.createdDate());
    }

    private void meta(Element details, String label, String value) {
        Element item = details.appendElement("div");
        item.appendElement("dt").text(label);
        item.appendElement("dd").text(value);
    }

    private void insertCaptureAfterOverview(Document content, VerifiedCapture capture, String title) {
        Element section = content.body().appendElement("section").addClass("manual-screen");
        section.appendElement("h2").text("화면 이미지");
        Element figure = section.appendElement("figure").addClass("manual-capture");
        figure.appendElement("img")
                .attr("src", capture.dataUrl())
                .attr("alt", title + " · " + capture.variantLabel() + " 실제 화면")
                .attr("width", String.valueOf(capture.width()))
                .attr("height", String.valueOf(capture.height()));
        figure.appendElement("figcaption").text("실제 화면 · " + capture.variantLabel());

        Element overview = content.select("h2").stream()
                .filter(heading -> "화면 개요".equals(heading.text()) || "개요".equals(heading.text()))
                .findFirst().orElse(null);
        if (overview == null) {
            content.body().prependChild(section);
            return;
        }
        Element nextSection = overview.nextElementSibling();
        while (nextSection != null && !"h2".equals(nextSection.tagName())) {
            nextSection = nextSection.nextElementSibling();
        }
        if (nextSection != null) nextSection.before(section);
    }

    private void requireMeta(StandaloneMeta meta) {
        if (meta == null || blank(meta.systemLabel()) || blank(meta.managementNumber())
                || blank(meta.screenId()) || meta.createdDate() == null) {
            throw new IllegalArgumentException("사용자 매뉴얼 문서 정보가 비어 있습니다.");
        }
    }

    private void requireCapture(VerifiedCapture capture) {
        if (capture == null || blank(capture.variantLabel()) || capture.width() <= 0 || capture.height() <= 0
                || !validPngDataUrl(capture.dataUrl())) {
            throw new IllegalArgumentException("검증된 PNG 화면 캡처만 사용할 수 있습니다.");
        }
    }

    private boolean validPngDataUrl(String dataUrl) {
        String prefix = "data:image/png;base64,";
        if (dataUrl == null || !dataUrl.startsWith(prefix)) return false;
        try {
            byte[] decoded = Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
            if (decoded.length < PNG_SIGNATURE.length) return false;
            for (int index = 0; index < PNG_SIGNATURE.length; index++) {
                if (decoded[index] != PNG_SIGNATURE[index]) return false;
            }
            return true;
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record StandaloneMeta(String systemLabel, String managementNumber,
                                 String screenId, LocalDate createdDate) { }

    public record VerifiedCapture(String dataUrl, int width, int height, String variantLabel) { }

    /** 과거에 저장된 HTML에서 내용만 남기고 실행 요소와 주소를 모두 버린다. */
    public String sanitizeLegacy(String legacyHtml) {
        Document source = Jsoup.parseBodyFragment(legacyHtml == null ? "" : legacyHtml);
        source.select("script, style, iframe, object, embed, link, meta, base").remove();
        source.select("form").forEach(Element::unwrap);
        source.select("input, button, textarea, select, option").remove();
        Document cleaned = new Cleaner(LEGACY_TAGS).clean(source);
        cleaned.outputSettings().prettyPrint(false);
        return cleaned.body().html().strip();
    }

    private List<UserManualDocument.Task> tasks(JsonNode root) throws IOException {
        JsonNode nodes = requiredArray(root, "tasks", "할 수 있는 일");
        List<UserManualDocument.Task> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = objectItem(nodes.get(index), "할 수 있는 일", index);
            rejectUnknown(node, TASK_FIELDS, "할 수 있는 일");
            UserManualDocument.Task task = new UserManualDocument.Task(
                    requiredText(node, "title", "할 수 있는 일 제목", MAX_TITLE_LENGTH),
                    stringList(node, "steps", "할 수 있는 일 단계"),
                    requiredText(node, "result", "할 수 있는 일 결과", MAX_TEXT_LENGTH),
                    evidence(node, "할 수 있는 일 근거"));
            if (task.steps().isEmpty()) {
                throw new IOException("할 수 있는 일 단계가 비어 있습니다.");
            }
            result.add(task);
        }
        return List.copyOf(result);
    }

    private List<UserManualDocument.OpeningStep> openingSteps(JsonNode root) throws IOException {
        JsonNode nodes = requiredArray(root, "openingSteps", "화면 여는 순서");
        List<UserManualDocument.OpeningStep> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = objectItem(nodes.get(index), "화면 여는 순서", index);
            rejectUnknown(node, OPENING_FIELDS, "화면 여는 순서");
            result.add(new UserManualDocument.OpeningStep(
                    requiredText(node, "text", "화면 여는 단계", MAX_TEXT_LENGTH),
                    evidence(node, "화면 여는 단계 근거")));
        }
        return List.copyOf(result);
    }

    private List<UserManualDocument.Field> fields(JsonNode root) throws IOException {
        JsonNode nodes = requiredArray(root, "fields", "항목 설명");
        List<UserManualDocument.Field> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = objectItem(nodes.get(index), "항목 설명", index);
            rejectUnknown(node, FIELD_FIELDS, "항목 설명");
            result.add(new UserManualDocument.Field(
                    requiredText(node, "name", "항목 이름", MAX_TITLE_LENGTH),
                    requiredText(node, "description", "항목 설명", MAX_TEXT_LENGTH),
                    evidence(node, "항목 설명 근거")));
        }
        return List.copyOf(result);
    }

    private List<UserManualDocument.NextScreen> nextScreens(JsonNode root) throws IOException {
        JsonNode nodes = requiredArray(root, "nextScreens", "이어지는 화면");
        List<UserManualDocument.NextScreen> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = objectItem(nodes.get(index), "이어지는 화면", index);
            rejectUnknown(node, NEXT_SCREEN_FIELDS, "이어지는 화면");
            result.add(new UserManualDocument.NextScreen(
                    requiredText(node, "name", "이어지는 화면 이름", MAX_TITLE_LENGTH),
                    requiredText(node, "description", "이어지는 화면 설명", MAX_TEXT_LENGTH),
                    evidence(node, "이어지는 화면 근거")));
        }
        return List.copyOf(result);
    }

    private List<String> stringList(JsonNode parent, String field, String label) throws IOException {
        JsonNode values = requiredArray(parent, field, label);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IOException(label + "의 " + (index + 1) + "번째 내용이 비어 있거나 글이 아닙니다.");
            }
            result.add(bounded(value.asText().strip(), label, MAX_TEXT_LENGTH));
        }
        return List.copyOf(result);
    }

    private JsonNode requiredArray(JsonNode parent, String field, String label) throws IOException {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IOException(label + "은 배열이어야 합니다.");
        }
        if (value.size() > MAX_ITEMS) {
            throw new IOException(label + "은 " + MAX_ITEMS + "개를 넘을 수 없습니다.");
        }
        return value;
    }

    private JsonNode objectItem(JsonNode value, String label, int index) throws IOException {
        if (value == null || !value.isObject()) {
            throw new IOException(label + "의 " + (index + 1) + "번째 항목은 객체여야 합니다.");
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field, String label, int maxLength) throws IOException {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException(label + "이 비어 있거나 글이 아닙니다.");
        }
        return bounded(value.asText().strip(), label, maxLength);
    }

    private String bounded(String value, String label, int maxLength) throws IOException {
        if (value.length() > maxLength) {
            throw new IOException(label + "이 " + maxLength + "자를 넘습니다.");
        }
        return value;
    }

    private String evidence(JsonNode parent, String label) throws IOException {
        return evidence(parent, label, "evidence");
    }

    private String evidence(JsonNode parent, String label, String field) throws IOException {
        String value = requiredText(parent, field, label, 220);
        int separator = value.indexOf(':');
        String source = separator < 0 ? "" : value.substring(0, separator);
        String anchor = separator < 0 ? "" : value.substring(separator + 1).strip();
        if (!Set.of("md", "html", "ia").contains(source) || anchor.isBlank()
                || anchor.length() > 200 || anchor.chars().anyMatch(Character::isISOControl)) {
            throw new IOException(label + "는 md, html, ia 중 하나와 실제 근거 문자열을 '종류:근거'로 써야 합니다.");
        }
        return source + ":" + anchor;
    }

    private void rejectUnknown(JsonNode object, Set<String> allowed, String label) throws IOException {
        Set<String> unknown = new HashSet<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IOException(label + "에 알 수 없는 항목이 있습니다: " + String.join(", ", unknown));
        }
    }

    private Element section(Element body, String title) {
        body.appendElement("h2").text(title);
        return body;
    }

    private void appendList(Element parent, String tag, List<String> items) {
        Element list = parent.appendElement(tag);
        items.forEach(item -> list.appendElement("li").text(item));
    }

    private void labelledParagraph(Element parent, String label, String value) {
        Element paragraph = parent.appendElement("p");
        paragraph.appendElement("strong").text(label + ": ");
        paragraph.appendText(value);
    }

    /** 모델이 코드 울타리로 감싸는 경우 JSON 본문만 꺼낸다. */
    private String stripFence(String output) {
        String text = output == null ? "" : output.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) {
            return text;
        }
        return text.substring(firstBreak + 1, lastFence).strip();
    }
}
