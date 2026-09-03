package com.bizplay.builder.featurespec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면 md 한 장을 <b>읽기 좋은 절</b>로 가른 것 — 기능명세서 상세가 이것을 그린다.
 *
 * <p>⛔ <b>새 글을 만들지 않는다.</b> 기능명세서는 2026-08-25 에 as-is 화면 md 로 흡수됐고
 * (`docs/artifacts.md` D절), 이 화면은 그것을 <b>보여 주는 창</b>이다. 여기서 하는 일은
 * 블록을 갈라 담는 것뿐이고 문장을 새로 쓰거나 고치지 않는다.
 *
 * <p>⚠ <b>화면 md 는 마크다운이 아니다.</b> {@code --- 이름 ---} 으로 갈리는 블록 규격이라
 * 마크다운 파서로는 아무것도 안 된다(그래서 의존성도 안 들였다).
 *
 * <p>⭐ <b>{@code --- 정의 ---} 의 앵커 줄이 bzp 빌더의 「구현 기능 FN-n」 자리다.</b>
 * 줄마다 {@code 구분 / 좌표 / 앵커 / 이동 / 해설} 이고, 그것이 곧 이 화면이 하는 일 목록이다.
 *
 * @param screenName 화면명
 * @param path       경로(서버 주소). 없으면 빈 글
 * @param purpose    목적
 * @param entry      진입 — 어디서 들어오나
 * @param related    연관 화면ID
 * @param iaKind     종류(화면·팝업·모달)
 * @param parent     상위화면ID. 없으면 널
 * @param functions  정의 줄
 * @param sourceNotes {@code --- 원본 글 ---} 의 인용 줄
 * @param raw        md 전문. <b>「원문 보기」가 이것을 그대로 낸다</b>
 */
public record FeatureSpecDocument(String screenName, String path, String purpose, String entry,
                                  List<String> related, String iaKind, String parent,
                                  List<Function> functions, List<String> sourceNotes, String raw) {

    /**
     * 블록 머리 {@code --- 이름 ---}.
     *
     * <p>⚠ <b>앞의 공백과 BOM 을 허용한다</b>(2026-08-27 코덱스 지적) — 파일 첫 글자가 BOM 이면
     * 블록을 <b>하나도</b> 못 찾는데 {@code raw} 는 안 비어서 <b>「명세 있음」인데 전부 빈 화면</b>이 된다.
     */
    private static final Pattern BLOCK = Pattern.compile(
            "(?m)^[ \\t\\uFEFF]*---[ \\t]*([^\\r\\n-][^\\r\\n]*?)[ \\t]*---[ \\t]*$");

    /** 앵커 꼬리의 번호 — {@code bo-delivery-detail-e01} → {@code e01}. */
    private static final Pattern ANCHOR_NO = Pattern.compile("-(e\\d+)$");

    /**
     * 정의 줄에서 <b>칸이 시작하는 자리</b> — 줄 머리이거나 슬래시 뒤다. 칸 순서가 규격이고 해설이 마지막이다.
     *
     * <p>⛔ <b>칸 이름을 글자로 찾지 마라</b>(2026-08-27 코덱스 2회차) — {@code indexOf("해설:")} 는
     * 좌표 값 안의 {@code 해설:} 에도 걸리고, {@code " / "} 로 자르면 해설 <b>안</b>의
     * {@code / 이동: …} 이 진짜 이동 칸으로 둔갑한다. 그래서 <b>자리</b>로 찾는다.
     *
     * <p>⚠ 슬래시 둘레의 공백을 강제하지 않는다 — 실물은 {@code " / "} 지만 한 칸이 없거나
     * 탭이 섞여도 값이 조용히 통째로 합쳐지면 안 된다.
     */
    private static final Pattern FIELD = Pattern.compile(
            "(?:^|/)[ \\t]*(구분|좌표|라벨|앵커|이동modal|이동native|이동unresolved|이동|해설)[ \\t]*:");

    public FeatureSpecDocument {
        // ⛔ 널을 빈 글로 눌러 둔다 — 화면이 값마다 널 검사를 하지 않게 한다(2026-08-27 코덱스 지적).
        screenName = screenName == null ? "" : screenName;
        path = path == null ? "" : path;
        purpose = purpose == null ? "" : purpose;
        entry = entry == null ? "" : entry;
        iaKind = iaKind == null ? "" : iaKind;
        raw = raw == null ? "" : raw;
        related = related == null ? List.of() : List.copyOf(related);
        functions = functions == null ? List.of() : List.copyOf(functions);
        sourceNotes = sourceNotes == null ? List.of() : List.copyOf(sourceNotes);
    }

    /**
     * 기능 한 줄.
     *
     * @param no       화면 안에서의 번호({@code e01}). 앵커에서 뗀다
     * @param kind     구분 — 이동 · 기능 · 항목
     * @param locator  좌표 — 소스의 어느 요소인가({@code id=btnSave} 꼴). 없으면 빈 글
     * @param label    운영 화면에 보이는 이름. 없으면 빈 글
     * @param anchor   앵커 전체
     * @param moveType 이동 종류
     * @param moveTo   이동 대상 화면ID·앱 경계·외부 목적지. 이동 줄이 아니면 빈 글
     * @param detail   해설
     */
    public record Function(String no, String kind, String locator, String label, String anchor,
                           MoveType moveType, String moveTo, String detail) {

        public Function {
            no = no == null ? "" : no;
            kind = kind == null ? "" : kind;
            locator = locator == null ? "" : locator;
            label = label == null ? "" : label;
            anchor = anchor == null ? "" : anchor;
            moveType = moveType == null ? MoveType.NONE : moveType;
            moveTo = moveTo == null ? "" : moveTo;
            detail = detail == null ? "" : detail;
        }

        public boolean movesToScreen() {
            return moveType == MoveType.SCREEN && !moveTo.isBlank();
        }
    }

    /** 화면 md 가 구분하는 이동 경계. */
    public enum MoveType {
        NONE,
        SCREEN,
        MODAL,
        NATIVE,
        UNRESOLVED
    }

    /** 명세가 아예 없는 화면. 색인에는 있는데 md 파일이 없는 자리가 실물에 있다. */
    public static FeatureSpecDocument empty() {
        return new FeatureSpecDocument("", "", "", "", List.of(), "", null, List.of(), List.of(), "");
    }

    public boolean isEmpty() {
        return raw == null || raw.isBlank();
    }

    public boolean hasFunctions() {
        return !functions.isEmpty();
    }

    /**
     * md 전문을 블록으로 갈라 담는다.
     *
     * <p>⚠ <b>모르는 블록은 조용히 버린다.</b> 규격은 기획 저장소가 정하고 우리는 읽기만 한다 —
     * 새 블록이 생겼다고 화면이 깨지면 고칠 사람이 여기 없다.
     */
    public static FeatureSpecDocument parse(String md) {
        if (md == null || md.isBlank()) {
            return empty();
        }
        Map<String, String> blocks = split(md);

        String spec = blocks.getOrDefault("화면명세", "");
        String ia = blocks.getOrDefault("IA", "");

        List<String> related = new ArrayList<>();
        // ⛔ 슬래시를 구분자로 쓰지 마라(2026-08-27 코덱스 2회차) — 값에 경로가 오면 한 화면이 여럿으로 갈린다.
        for (String piece : lineValue(spec, "연관").split("[,·]")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                related.add(trimmed);
            }
        }

        return new FeatureSpecDocument(
                lineValue(spec, "화면명"),
                lineValue(spec, "경로"),
                lineValue(spec, "목적"),
                lineValue(spec, "진입"),
                related,
                fieldOf(bulletWith(ia, "종류"), "종류"),
                blankToNull(fieldOf(bulletWith(ia, "상위화면"), "상위화면")),
                functionsOf(blocks.getOrDefault("정의", "")),
                notesOf(blocks.getOrDefault("원본 글", "")),
                md);
    }

    // ── 가르기 ────────────────────────────────────────────────────────────

    /** 블록 이름 → 본문. 같은 이름이 둘이면 뒤엣것이 이긴다(실물에 없지만 깨지지는 않게 둔다). */
    private static Map<String, String> split(String md) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher matcher = BLOCK.matcher(md);
        String name = null;
        int bodyFrom = 0;
        while (matcher.find()) {
            if (name != null) {
                blocks.put(name, md.substring(bodyFrom, matcher.start()).strip());
            }
            name = matcher.group(1).strip();
            bodyFrom = matcher.end();
        }
        if (name != null) {
            blocks.put(name, md.substring(bodyFrom).strip());
        }
        return blocks;
    }

    private static List<Function> functionsOf(String block) {
        List<Function> functions = new ArrayList<>();
        for (String line : block.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("- ")) {
                continue;
            }
            Map<String, String> fields = fieldsOf(trimmed.substring(2));
            String anchor = fields.getOrDefault("앵커", "");
            Move move = moveOf(fields);
            functions.add(new Function(
                    numberOf(anchor),
                    fields.getOrDefault("구분", ""),
                    fields.getOrDefault("좌표", ""),
                    fields.getOrDefault("라벨", ""),
                    anchor,
                    move.type(),
                    move.target(),
                    fields.getOrDefault("해설", "")));
        }
        return functions;
    }

    /** 한 줄에는 이동 종류 하나만 온다. 더 구체적인 경계를 먼저 고른다. */
    private static Move moveOf(Map<String, String> fields) {
        if (fields.containsKey("이동modal")) {
            return new Move(MoveType.MODAL, fields.get("이동modal"));
        }
        if (fields.containsKey("이동native")) {
            return new Move(MoveType.NATIVE, fields.get("이동native"));
        }
        if (fields.containsKey("이동unresolved")) {
            return new Move(MoveType.UNRESOLVED, fields.get("이동unresolved"));
        }
        if (fields.containsKey("이동")) {
            return new Move(MoveType.SCREEN, fields.get("이동"));
        }
        return new Move(MoveType.NONE, "");
    }

    private record Move(MoveType type, String target) {
    }

    private static List<String> notesOf(String block) {
        List<String> notes = new ArrayList<>();
        for (String line : block.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(">")) {
                notes.add(trimmed.substring(1).strip());
            } else if (!trimmed.isEmpty()) {
                notes.add(trimmed);
            }
        }
        return notes;
    }

    /** {@code 화면명: 값} 꼴 한 줄에서 값을 뗀다. */
    private static String lineValue(String block, String key) {
        for (String line : block.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key + ":")) {
                return trimmed.substring(key.length() + 1).strip();
            }
        }
        return "";
    }

    /**
     * IA 블록에서 그 칸을 가진 항목 줄을 고른다.
     *
     * <p>⛔ <b>첫 항목으로 찍지 마라</b>(2026-08-27 코덱스 지적) — 앞에 설명 줄이 하나 붙으면
     * {@code 종류}·{@code 상위화면} 이 통째로 빈 값이 되고, 그러면 <b>화면 가족이 조용히 사라진다.</b>
     */
    private static String bulletWith(String block, String key) {
        for (String line : block.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ") && trimmed.contains(key + ":")) {
                return trimmed.substring(2);
            }
        }
        return "";
    }

    /**
     * 정의 줄 하나를 칸 지도로 뗀다 — <b>왼쪽에서 오른쪽으로 자리를 훑는다.</b>
     *
     * <p>⭐ <b>해설을 만나면 거기서 멈추고 줄 끝까지가 해설이다.</b> 규격이 해설을 마지막에 두므로,
     * 이렇게 해야 해설 안의 {@code / 이동: …} 이 진짜 칸으로 둔갑하지 않는다.
     * 같은 칸이 두 번 나오면 <b>먼저 나온 것</b>이 이긴다.
     */
    private static Map<String, String> fieldsOf(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(line);
        String key = null;
        int valueFrom = 0;
        while (matcher.find()) {
            if (key != null) {
                fields.putIfAbsent(key, line.substring(valueFrom, matcher.start()).strip());
            }
            key = matcher.group(1);
            valueFrom = matcher.end();
            if ("해설".equals(key)) {
                break;
            }
        }
        if (key != null) {
            fields.putIfAbsent(key, line.substring(valueFrom).strip());
        }
        // ⚠ 값 끝의 슬래시는 칸 구분자가 남은 것이다 — 값에 붙여 두면 화면ID 가 안 맞는다.
        fields.replaceAll((name, value) -> value.endsWith("/") && !"해설".equals(name)
                ? value.substring(0, value.length() - 1).strip() : value);
        return fields;
    }

    /** IA 항목 줄({@code 종류: 화면 / 상위화면: …})에서 칸 하나. ⚠ 여기 값에는 슬래시가 없다. */
    private static String fieldOf(String line, String key) {
        for (String part : line.split("/")) {
            String piece = part.strip();
            if (piece.startsWith(key + ":")) {
                return piece.substring(key.length() + 1).strip();
            }
        }
        return "";
    }

    private static String numberOf(String anchor) {
        Matcher matcher = ANCHOR_NO.matcher(anchor == null ? "" : anchor.strip());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
