package com.bizplay.builder.featurespec;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.List;

/** 검증된 구조화 내용을 안전한 공식 문서 HTML로 조립한다. */
@Component
public class FeatureSpecRenderer {

    public String renderBody(FeatureSpecContent content) {
        Document document = Document.createShell("");
        Element main = document.body().appendElement("article").addClass("feature-document");
        section(main, "1. 화면 개요");
        definition(main, "화면 목적", content.overview().purpose());
        definition(main, "적용 범위", content.overview().scope());
        textList(main, "2. 선행 조건", content.preconditions().stream().map(FeatureSpecContent.TextItem::text).toList());
        functions(main, content.functions());
        fields(main, content.fields());
        rules(main, "5. 업무 규칙", content.businessRules());
        rules(main, "6. 권한 규칙", content.permissionRules());
        messages(main, content.messages());
        transitions(main, content.transitions());
        integrations(main, content.integrations());
        return main.outerHtml();
    }

    public String renderStandalone(String title, String metadata, String body) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>"
                + escape(title) + "</title><style>" + style() + "</style></head><body><main>"
                + "<header class=\"document-head\"><p>기능명세서</p><h1>" + escape(title)
                + "</h1><div>" + escape(metadata) + "</div></header>" + body + "</main></body></html>";
    }

    private void functions(Element root, List<FeatureSpecContent.FunctionItem> items) {
        if (items.isEmpty()) return;
        section(root, "3. 기능 명세");
        Element table = table(root, List.of("기능 ID", "기능명", "동작·조건", "처리 내용", "결과"));
        Element body = table.appendElement("tbody");
        for (int i = 0; i < items.size(); i++) {
            FeatureSpecContent.FunctionItem item = items.get(i);
            Element row = body.appendElement("tr");
            cell(row, "FN-%03d".formatted(i + 1)); cell(row, item.name());
            cell(row, join(item.trigger(), item.precondition())); cell(row, item.processing()); cell(row, item.result());
        }
    }

    private void fields(Element root, List<FeatureSpecContent.FieldItem> items) {
        if (items.isEmpty()) return;
        section(root, "4. 화면 항목");
        Element table = table(root, List.of("항목 ID", "항목명", "유형", "필수", "입력 규칙", "설명"));
        Element body = table.appendElement("tbody");
        for (int i = 0; i < items.size(); i++) {
            FeatureSpecContent.FieldItem item = items.get(i);
            Element row = body.appendElement("tr");
            cell(row, "FD-%03d".formatted(i + 1)); cell(row, item.name()); cell(row, item.type());
            cell(row, item.required()); cell(row, item.inputRule()); cell(row, item.description());
        }
    }

    private void rules(Element root, String title, List<FeatureSpecContent.RuleItem> items) {
        if (items.isEmpty()) return;
        section(root, title);
        Element list = root.appendElement("dl").addClass("feature-document__definitions");
        items.forEach(item -> { list.appendElement("dt").text(item.title()); list.appendElement("dd").text(item.description()); });
    }

    private void messages(Element root, List<FeatureSpecContent.MessageItem> items) {
        if (items.isEmpty()) return;
        section(root, "7. 메시지");
        Element table = table(root, List.of("발생 상황", "표시 내용"));
        Element body = table.appendElement("tbody");
        items.forEach(item -> { Element row = body.appendElement("tr"); cell(row, item.situation()); cell(row, item.message()); });
    }

    private void transitions(Element root, List<FeatureSpecContent.TransitionItem> items) {
        if (items.isEmpty()) return;
        section(root, "8. 화면 이동");
        Element table = table(root, List.of("사용자 동작", "대상 화면", "결과"));
        Element body = table.appendElement("tbody");
        items.forEach(item -> { Element row = body.appendElement("tr"); cell(row, item.action()); cell(row, item.targetScreenId()); cell(row, item.result()); });
    }

    private void integrations(Element root, List<FeatureSpecContent.IntegrationItem> items) {
        if (items.isEmpty()) return;
        section(root, "9. 외부 연계");
        Element table = table(root, List.of("연계명", "방향", "데이터", "조건"));
        Element body = table.appendElement("tbody");
        items.forEach(item -> { Element row = body.appendElement("tr"); cell(row, item.name()); cell(row, item.direction()); cell(row, item.data()); cell(row, item.condition()); });
    }

    private void textList(Element root, String title, List<String> items) {
        if (items.isEmpty()) return;
        section(root, title); Element list = root.appendElement("ol");
        items.forEach(item -> list.appendElement("li").text(item));
    }

    private void definition(Element root, String term, String value) {
        Element list = root.children().last().tagName().equals("dl") ? root.children().last() : root.appendElement("dl");
        list.addClass("feature-document__definitions"); list.appendElement("dt").text(term); list.appendElement("dd").text(value);
    }

    private Element table(Element root, List<String> headers) {
        Element wrap = root.appendElement("div").addClass("feature-document__table-wrap");
        Element table = wrap.appendElement("table"); Element row = table.appendElement("thead").appendElement("tr");
        headers.forEach(header -> row.appendElement("th").attr("scope", "col").text(header));
        return table;
    }

    private void section(Element root, String title) { root.appendElement("h2").text(title); }
    private void cell(Element row, String value) { row.appendElement("td").text(value == null || value.isBlank() ? "—" : value); }
    private String join(String left, String right) {
        String a = left == null ? "" : left.strip(); String b = right == null ? "" : right.strip();
        return a.isBlank() ? b : b.isBlank() ? a : a + " / " + b;
    }
    private String escape(String value) { return new Element("span").text(value == null ? "" : value).html(); }

    private String style() {
        return """
                @page { size: A4; margin: 18mm 16mm; }
                * { box-sizing: border-box; }
                body { margin:0; color:#20242b; font:10.5pt/1.55 'Malgun Gothic',sans-serif; }
                main { max-width:178mm; margin:0 auto; }
                .document-head { border-bottom:2px solid #17233c; padding-bottom:8mm; margin-bottom:10mm; }
                .document-head p { margin:0 0 2mm; font-size:9pt; color:#526079; }
                h1 { margin:0 0 4mm; font-size:22pt; } h2 { margin:9mm 0 3mm; font-size:14pt; break-after:avoid; }
                dl { margin:0; } dt { font-weight:700; margin-top:3mm; } dd { margin:1mm 0 0; }
                table { width:100%; border-collapse:collapse; font-size:9pt; }
                th,td { border:1px solid #aeb7c6; padding:2.5mm; text-align:left; vertical-align:top; }
                th { background:#eef1f5; } tr { break-inside:avoid; }
                @media screen { body { background:#edf0f4; padding:24px; } main { background:white; padding:16mm; box-shadow:0 4px 18px #0002; } }
                @media print { .feature-document__table-wrap { overflow:visible; } }
                """;
    }
}
