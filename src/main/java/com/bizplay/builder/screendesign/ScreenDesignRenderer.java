package com.bizplay.builder.screendesign;

import org.jsoup.nodes.Entities;
import org.springframework.stereotype.Component;

/** 구조화된 화면설계서를 Builder 상세와 인쇄 화면에서 함께 쓰는 HTML로 렌더한다. */
@Component
public class ScreenDesignRenderer {

    public String renderBody(ScreenDesignContent content) {
        StringBuilder html = new StringBuilder("<article class=\"screen-design-document\">");
        html.append("<h2>3. 화면 요소 명세</h2>");
        for (int captureIndex = 0; captureIndex < content.captures().size(); captureIndex++) {
            ScreenDesignContent.Capture capture = content.captures().get(captureIndex);
            html.append("<section class=\"screen-design-sheet\"><h3>3.")
                    .append(captureIndex + 1).append(" ").append(escape(capture.label())).append("</h3>")
                    .append("<div class=\"screen-design-document__table-wrap\"><table><thead><tr>")
                    .append("<th scope=\"col\">번호</th><th scope=\"col\">구분</th>")
                    .append("<th scope=\"col\">화면 요소</th><th scope=\"col\">동작</th>")
                    .append("<th scope=\"col\">검증</th><th scope=\"col\">결과</th></tr></thead><tbody>");
            for (ScreenDesignContent.Callout callout : capture.callouts()) {
                html.append("<tr><td>").append(callout.number()).append("</td><td>")
                        .append(escape(callout.kind())).append("</td><td>")
                        .append(escape(callout.label())).append("</td><td>")
                        .append(escape(callout.description())).append("</td>")
                        .append("<td>").append(escape(callout.validation())).append("</td><td>")
                        .append(escape(callout.result())).append("</td></tr>");
            }
            html.append("</tbody></table></div></section>");
        }
        html.append("<section class=\"screen-design-navigation\"><h2>4. 화면 이동</h2>");
        if (content.navigation().isEmpty()) {
            html.append("<p class=\"screen-design-document__empty\">확인된 연결 화면이 없습니다.</p>");
        } else {
            html.append("<div class=\"screen-design-document__table-wrap\"><table><thead><tr>")
                    .append("<th scope=\"col\">관계</th><th scope=\"col\">화면 ID</th></tr></thead><tbody>");
            for (ScreenDesignContent.Navigation item : content.navigation()) {
                html.append("<tr><td>").append(escape(item.relation())).append("</td><td>")
                        .append(escape(item.screenId())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        html.append("</section>");

        html.append("<section class=\"screen-design-variants\"><h2>5. 화면 변형</h2>");
        if (content.captures().size() <= 1) {
            html.append("<p class=\"screen-design-document__empty\">별도로 구분된 화면 변형이 없습니다.</p>");
        } else {
            html.append("<div class=\"screen-design-document__table-wrap\"><table><thead><tr>")
                    .append("<th scope=\"col\">변형</th><th scope=\"col\">기준 화면과의 차이</th></tr></thead><tbody>");
            ScreenDesignContent.Capture standard = content.captures().get(0);
            for (int index = 0; index < content.captures().size(); index++) {
                ScreenDesignContent.Capture capture = content.captures().get(index);
                html.append("<tr><td>").append(escape(capture.label())).append("</td><td>")
                        .append(index == 0 ? "비교 기준 화면" : difference(standard, capture)).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        html.append("</section>");
        return html.append("</article>").toString();
    }

    /** 표지 다음에 들어가는 화면 개요. 웹 미리보기와 PDF가 같은 정의표를 쓴다. */
    public String renderOverview(ScreenDesignContent content) {
        return """
                <section class="screen-design-overview">
                  <h2>1. 화면 개요</h2>
                  <dl class="screen-design-document__definitions">
                    <dt>화면 목적</dt><dd>%s</dd>
                    <dt>시스템</dt><dd>%s</dd>
                    <dt>화면 ID</dt><dd>%s</dd>
                    <dt>IA 메뉴 경로</dt><dd>%s</dd>
                    <dt>적용 구분</dt><dd>%s</dd>
                  </dl>
                </section>
                """.formatted(escape(content.purpose()), escape(content.systemCode()),
                escape(content.screenId()), value(content.menuPath()), value(content.applicationScope()));
    }

    public String renderStandalone(String title, String meta, String body) {
        return """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title><style>
                @page{size:A4 landscape;margin:12mm}body{font-family:Arial,'Noto Sans KR',sans-serif;color:#172033;margin:0}
                .meta{color:#596579;font-size:12px;margin-bottom:16px}.screen-design-sheet{break-after:page}
                .screen-design-sheet:last-child{break-after:auto}img{display:block;max-width:100%%;max-height:130mm;margin:12px auto;border:1px solid #d6dce5}
                table{width:100%%;border-collapse:collapse;font-size:11px}th,td{border:1px solid #d6dce5;padding:6px;text-align:left;vertical-align:top}
                th{background:#f3f6f9}h2,h3{margin:0 0 8px}p{margin:4px 0 12px}
                </style></head><body><p class="meta">%s</p>%s<script>window.addEventListener('load',()=>window.print())</script></body></html>
                """.formatted(escape(title), escape(meta), body);
    }

    private static String escape(String value) {
        return Entities.escape(value == null ? "" : value);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : escape(value);
    }

    private static String difference(ScreenDesignContent.Capture standard,
                                     ScreenDesignContent.Capture variant) {
        java.util.Map<String, ScreenDesignContent.Callout> baseline = standard.callouts().stream()
                .collect(java.util.stream.Collectors.toMap(ScreenDesignContent.Callout::label,
                        item -> item, (left, right) -> left));
        java.util.Map<String, ScreenDesignContent.Callout> changed = variant.callouts().stream()
                .collect(java.util.stream.Collectors.toMap(ScreenDesignContent.Callout::label,
                        item -> item, (left, right) -> left));
        java.util.List<String> parts = new java.util.ArrayList<>();
        String added = changed.keySet().stream().filter(label -> !baseline.containsKey(label))
                .map(ScreenDesignRenderer::escape).collect(java.util.stream.Collectors.joining(", "));
        String removed = baseline.keySet().stream().filter(label -> !changed.containsKey(label))
                .map(ScreenDesignRenderer::escape).collect(java.util.stream.Collectors.joining(", "));
        String modified = changed.entrySet().stream().filter(entry -> baseline.containsKey(entry.getKey()))
                .filter(entry -> !entry.getValue().description().equals(baseline.get(entry.getKey()).description())
                        || !entry.getValue().result().equals(baseline.get(entry.getKey()).result()))
                .map(java.util.Map.Entry::getKey).map(ScreenDesignRenderer::escape)
                .collect(java.util.stream.Collectors.joining(", "));
        if (!added.isBlank()) parts.add("추가 · " + added);
        if (!removed.isBlank()) parts.add("제외 · " + removed);
        if (!modified.isBlank()) parts.add("설명 변경 · " + modified);
        return parts.isEmpty() ? "구조화 조작 요소 차이 없음(레이아웃은 화면 이미지 비교)"
                : String.join(" / ", parts);
    }
}
