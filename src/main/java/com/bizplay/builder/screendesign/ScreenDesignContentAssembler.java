package com.bizplay.builder.screendesign;

import com.bizplay.builder.screendesign.ScreenDesignContent.Callout;
import com.bizplay.builder.screendesign.ScreenDesignContent.Capture;
import com.bizplay.builder.screendesign.ScreenDesignContent.Navigation;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** 화면 MD·IA와 캡처 결과를 웹·PDF가 공유하는 한 구조화 문서로 조립한다. */
final class ScreenDesignContentAssembler {

    private ScreenDesignContentAssembler() { }

    static ScreenDesignContent assemble(Snapshot snapshot, List<Capture> captures) {
        List<Definition> definitions = definitions(snapshot.md());
        List<Capture> explained = captures.stream().map(capture -> new Capture(capture.name(), capture.label(),
                capture.imageFile(), capture.pdfFile(), capture.width(), capture.height(),
                capture.callouts().stream().map(callout -> explain(callout, definitions)).toList())).toList();
        return new ScreenDesignContent(ScreenDesignMaterialService.displayName(snapshot.screen()),
                purpose(snapshot), text(snapshot.screen().menuPath()), text(snapshot.screen().system()),
                text(snapshot.screen().screenId()), text(snapshot.screen().applicationSummary()),
                navigation(snapshot), snapshot.md(), explained);
    }

    private static Callout explain(Callout callout, List<Definition> definitions) {
        Definition definition = definitions.stream().filter(item -> sameLabel(item.label(), callout.label()))
                .findFirst().orElse(null);
        if (definition == null) {
            return new Callout(callout.number(), callout.kind(), callout.label(), callout.description(),
                    "화면 MD에 별도 검증 조건 없음", callout.description());
        }
        String action = first(definition.value("동작"), callout.description());
        String validation = first(definition.value("검증"), definition.value("조건"),
                definition.value("입력규칙"), definition.value("입력 규칙"), definition.value("필수"),
                "화면 MD에 별도 검증 조건 없음");
        String result = first(definition.value("결과"), definition.value("해설"), callout.description());
        return new Callout(callout.number(), callout.kind(), callout.label(), action, validation, result);
    }

    private static boolean sameLabel(String definition, String captured) {
        String left = normalize(definition);
        String right = normalize(captured);
        return left.equals(right) || (left.length() >= 2 && right.contains(left))
                || (right.length() >= 2 && left.contains(right));
    }

    private static List<Definition> definitions(String markdown) {
        List<Definition> values = new ArrayList<>();
        for (String line : text(markdown).split("\\R")) {
            if (!line.stripLeading().startsWith("- ") || !line.contains("라벨:")) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            for (String part : line.strip().substring(2).split("\\s+/\\s+")) {
                int colon = part.indexOf(':');
                if (colon > 0) fields.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
            }
            String label = fields.get("라벨");
            if (label != null && !label.isBlank()) values.add(new Definition(label, Map.copyOf(fields)));
        }
        return List.copyOf(values);
    }

    private static List<Navigation> navigation(Snapshot snapshot) {
        Stream<Navigation> parent = text(snapshot.screen().parentScreenId()).isBlank() ? Stream.empty()
                : Stream.of(new Navigation("상위 화면", snapshot.screen().parentScreenId()));
        Stream<Navigation> openings = snapshot.screen().openingScreenIds().stream()
                .filter(id -> id != null && !id.isBlank()).map(id -> new Navigation("연결 화면", id));
        return Stream.concat(parent, openings).distinct().toList();
    }

    private static String purpose(Snapshot snapshot) {
        String summary = snapshot.screen().summary();
        return summary == null || summary.isBlank()
                ? ScreenDesignMaterialService.displayName(snapshot.screen()) + "의 화면 구성과 조작 요소를 설명합니다."
                : summary;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String normalize(String value) {
        return text(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String text(String value) { return value == null ? "" : value; }

    private record Definition(String label, Map<String, String> fields) {
        String value(String key) { return fields.get(key); }
    }
}
