package com.bizplay.builder.featurespec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 화면 md 원문을 기획자가 먼저 읽을 순서와 이름으로 바꾼 표시 모델.
 *
 * <p>원문을 고치거나 버리지 않는다. 화면 이름·구분 이름처럼 확실히 대응되는 값만 바꾸고,
 * 추출 해설과 좌표는 근거로 그대로 보존한다.
 */
public final class FeatureSpecPresentation {

    private FeatureSpecPresentation() {
    }

    public static View of(FeatureSpecDocument document, Map<String, String> screenNames) {
        Map<String, String> names = screenNames == null ? Map.of() : screenNames;
        List<Item> actions = new ArrayList<>();
        List<Item> fields = new ArrayList<>();

        for (FeatureSpecDocument.Function function : document.functions()) {
            Item item = itemOf(function, names);
            if ("항목".equals(function.kind())) {
                fields.add(item);
            } else {
                actions.add(item);
            }
        }

        List<Related> related = document.related().stream()
                .map(value -> relatedOf(value, names))
                .toList();
        return new View(actions, fields, entryOf(document.entry(), names),
                relatedOf(document.parent(), names), related);
    }

    private static Item itemOf(FeatureSpecDocument.Function function, Map<String, String> screenNames) {
        String target = function.moveTo();
        String targetName = screenNames.getOrDefault(target, readableTarget(target));
        boolean linkable = !target.isBlank()
                && (function.moveType() == FeatureSpecDocument.MoveType.SCREEN
                || function.moveType() == FeatureSpecDocument.MoveType.MODAL)
                && screenNames.containsKey(target);
        return new Item(
                function.no(),
                kindLabel(function),
                titleOf(function, targetName),
                function.detail(),
                function.locator(),
                function.anchor(),
                target,
                targetName,
                linkable);
    }

    private static String kindLabel(FeatureSpecDocument.Function function) {
        return switch (function.moveType()) {
            case MODAL -> "팝업 열기";
            case NATIVE -> nativeLabel(function.moveTo());
            case UNRESOLVED -> unresolvedLabel(function.moveTo());
            case SCREEN -> "화면 이동";
            case NONE -> switch (function.kind()) {
                case "기능" -> "사용자 동작";
                case "항목" -> "화면 항목";
                default -> function.kind().isBlank() ? "기타" : function.kind();
            };
        };
    }

    private static String nativeLabel(String target) {
        String type = target == null ? "" : target.strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "app-back" -> "이전 화면으로 이동";
            case "app-close" -> "앱 화면 닫기";
            case "app-exit" -> "앱 종료";
            case "app-launch" -> "앱 열기";
            case "biometric" -> "생체 인증";
            case "browser-print", "print" -> "인쇄";
            case "dialer" -> "전화 연결";
            case "external-browser" -> "외부 브라우저 열기";
            case "file-download" -> "파일 내려받기";
            case "file-picker" -> "파일 선택";
            case "payment-qr" -> "QR 결제";
            case "qr-scan" -> "QR 코드 스캔";
            case "secure-keypad" -> "보안 키패드 열기";
            case "simple-pin" -> "간편 비밀번호 인증";
            default -> "앱 기능 실행";
        };
    }

    private static String unresolvedLabel(String target) {
        String value = target == null ? "" : target.toLowerCase(Locale.ROOT);
        if (value.contains("history.back") || value.contains("브라우저 뒤로")) {
            return "이전 화면으로 이동";
        }
        if (value.contains("외부") || value.contains("리다이렉트") || value.contains("sso")
                || value.contains("window.open") || value.contains("런타임 url")) {
            return "외부 화면 열기";
        }
        if (value.contains("앱 딥링크")) {
            return "앱 열기";
        }
        return "이동 대상 확인 필요";
    }

    private static String titleOf(FeatureSpecDocument.Function function, String targetName) {
        if (!function.label().isBlank()) {
            return normalizeLabel(function.label());
        }
        if (function.moveType() == FeatureSpecDocument.MoveType.UNRESOLVED && !targetName.isBlank()) {
            return targetName;
        }
        String detail = function.detail().strip();
        int evidence = detail.indexOf(" (");
        if (evidence >= 0) {
            detail = detail.substring(0, evidence).strip();
        }
        int arrow = detail.indexOf('→');
        if (arrow >= 0) {
            detail = detail.substring(0, arrow).strip();
        }
        detail = detail.replace(" = ", " — ");
        if (!detail.isBlank()) {
            return detail;
        }
        return targetName.isBlank() ? "이름 없는 항목" : targetName;
    }

    private static String normalizeLabel(String label) {
        String value = label.strip();
        if ("close".equalsIgnoreCase(value) || "×".equals(value) || "✕".equals(value)) {
            return "닫기";
        }
        if (value.startsWith("(") && value.endsWith(")") && value.length() > 2) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String readableTarget(String target) {
        if (target == null || target.isBlank()) {
            return "";
        }
        return target.replaceFirst("\\s*\\(런타임[^)]*\\)\\s*$", "").strip();
    }

    private static Related relatedOf(String value, Map<String, String> screenNames) {
        if (value == null || value.isBlank()) {
            return new Related("", "", false);
        }
        if (screenNames.containsKey(value)) {
            return new Related(screenNames.get(value), value, true);
        }
        return new Related(value, "", false);
    }

    private static String entryOf(String entry, Map<String, String> screenNames) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        for (Map.Entry<String, String> screen : screenNames.entrySet()) {
            String prefix = screen.getKey() + " ";
            if (entry.startsWith(prefix)) {
                String action = entry.substring(prefix.length()).strip()
                        .replace("행클릭", "행 선택");
                return screen.getValue() + "에서 " + action;
            }
        }
        return entry;
    }

    public record View(List<Item> actions, List<Item> fields, String entry,
                       Related parent, List<Related> related) {
        public View {
            actions = actions == null ? List.of() : List.copyOf(actions);
            fields = fields == null ? List.of() : List.copyOf(fields);
            entry = entry == null ? "" : entry;
            parent = parent == null ? new Related("", "", false) : parent;
            related = related == null ? List.of() : List.copyOf(related);
        }
    }

    public record Item(String number, String kindLabel, String title, String sourceDetail,
                       String locator, String anchor, String targetScreenId, String targetName,
                       boolean linkable) {

        public boolean hasTarget() {
            return targetName != null && !targetName.isBlank();
        }

        public boolean hasEvidence() {
            return (sourceDetail != null && !sourceDetail.isBlank())
                    || (locator != null && !locator.isBlank())
                    || (anchor != null && !anchor.isBlank());
        }
    }

    public record Related(String label, String screenId, boolean linked) {
    }
}
