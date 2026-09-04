package com.bizplay.builder.ia;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 추출기가 만든 최초 {@code ia.md} 를 읽고, DB 정본을 같은 규격으로 결정적으로 게시한다. */
@Component
public class IaDocumentCodec {

    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+){0,6}$");

    public Parsed parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("가져올 ia.md가 비어 있습니다.");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        List<Placement> placements = new ArrayList<>();
        boolean labelSection = false;
        boolean placementSection = false;
        int rowOrder = 1;

        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.equals("## 이름표")) {
                labelSection = true;
                placementSection = false;
                continue;
            }
            if (line.equals("--- 배치 ---")) {
                labelSection = false;
                placementSection = true;
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            if (labelSection) {
                int colon = line.indexOf(':', 2);
                if (colon > 2) {
                    labels.put(line.substring(2, colon).strip(), line.substring(colon + 1).strip());
                }
            } else if (placementSection) {
                Map<String, String> fields = fields(line.substring(2));
                // 배치 표 아래에 설명 문단이 올 수 있다(lspnoffice 실측 · 2026-08-21) — 경로 칸도
                // 화면 칸도 없는 `- ` 줄은 배치 행이 아니라 산문이다. ⚠ 칸이 있는데 꼴이 틀린 줄은
                // 여전히 던진다 — 게시(serialize)와 짝인 규격이라 조용히 삼키면 안 된다.
                if (!fields.containsKey("경로") && !fields.containsKey("화면")) {
                    continue;
                }
                String path = fields.getOrDefault("경로", "").strip();
                validatePath(path);
                // 저장 순서는 문서의 물리적 행 순서를 1부터 다시 매긴다. 원문의 `순서`는 부모별로
                // 중복될 수 있는 별도 규격이라 전역 행 순서 기본키로 쓰면 안 된다.
                placements.add(new Placement(rowOrder++, path, blankToNull(fields.get("화면")), depths(path, labels)));
            }
        }
        if (placements.isEmpty()) {
            throw new IllegalArgumentException("ia.md의 '--- 배치 ---' 블록에 가져올 행이 없습니다.");
        }
        return new Parsed(List.copyOf(placements), sha256(content));
    }

    /**
     * {@code ## 이름표} 블록만 읽는다. <b>{@code --- 배치 ---} 가 없어도 된다.</b>
     *
     * <p>⭐ <b>메뉴구조도의 뎁스 재료는 2026-08-21 에 색인으로 옮겼다</b>({@link IaTreeBuilder}) —
     * {@code ia.md} 배치는 색인보다 좁았다(백오피스 82줄 대 240장). 그래서 가져오기는 이 파일에서
     * <b>한글 이름표만</b> 가져간다. ⛔ 그 이름표는 <b>사람이 고치는 문서</b>라 색인이 대신할 수 없다.
     */
    public Map<String, String> labels(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("가져올 ia.md가 비어 있습니다.");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        boolean labelSection = false;
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.equals("## 이름표")) {
                labelSection = true;
                continue;
            }
            if (line.startsWith("--- ")) {
                labelSection = false;
                continue;
            }
            if (!labelSection || !line.startsWith("- ")) continue;
            int colon = line.indexOf(':', 2);
            if (colon > 2) {
                labels.put(line.substring(2, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        return Map.copyOf(labels);
    }

    public String serialize(String systemCode, List<IaRow> rows) {
        validateRows(rows);
        Map<String, String> labels = new LinkedHashMap<>();
        for (IaRow row : rows) {
            String[] keys = row.pathKey().split("/");
            List<String> depths = row.depths();
            for (int i = 0; i < keys.length; i++) {
                String prefix = String.join("/", java.util.Arrays.copyOfRange(keys, 0, i + 1));
                String previous = labels.putIfAbsent(prefix, depths.get(i));
                if (previous != null && !previous.equals(depths.get(i))) {
                    throw new IllegalArgumentException("같은 경로 식별자에 서로 다른 메뉴명이 있습니다: " + prefix);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# ").append(systemCode).append(" IA 이름표\n\n")
                .append("> Builder DB에서 확정한 게시용 스냅샷입니다. 직접 고치지 말고 Builder에서 변경하세요.\n\n")
                .append("## 이름표\n");
        labels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("- ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append('\n'));
        out.append("\n--- 배치 ---\n");
        rows.stream().sorted(java.util.Comparator.comparingInt(IaRow::rowOrder).thenComparing(IaRow::id))
                .forEach(row -> out.append("- 순서: ").append("%03d".formatted(row.rowOrder()))
                        .append(" / 경로: ").append(row.pathKey())
                        .append(" / 화면: ").append(row.screenId() == null ? "" : row.screenId()).append('\n'));
        return out.toString();
    }

    public void validateRows(List<IaRow> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("확정할 메뉴 행이 없습니다.");
        }
        Set<Integer> orders = new LinkedHashSet<>();
        Set<String> screens = new LinkedHashSet<>();
        // ⑥ 마지막 그물(브리프 §3-1) — path_key 에는 UNIQUE 도 인덱스도 없다(V19:43-44 실측).
        // 최초 가져오기·재작성은 IaTreeBuilder.of 가 이미 부딪힘을 갈라놓지만, 사람이 손으로
        // 고치는 다섯 경로(:206·:272·:313·:527·:756)는 이 그물 하나로 막는다.
        Set<String> pathKeys = new LinkedHashSet<>();
        for (IaRow row : rows) {
            validatePath(row.pathKey());
            if (!pathKeys.add(row.pathKey())) {
                throw new IllegalArgumentException("같은 경로 식별자가 두 번 있습니다: " + row.pathKey());
            }
            if (row.rowOrder() < 1 || row.rowOrder() > 999) {
                throw new IllegalArgumentException("순서는 001부터 999 사이여야 합니다.");
            }
            if (!orders.add(row.rowOrder())) {
                throw new IllegalArgumentException("같은 순서가 두 번 있습니다: " + row.rowOrder());
            }
            if (row.depth1() == null || row.depth1().isBlank()) {
                throw new IllegalArgumentException("Depth 1은 필수입니다.");
            }
            List<String> depths = row.depths();
            if (depths.size() != row.pathKey().split("/").length) {
                throw new IllegalArgumentException("경로 식별자의 깊이와 Depth 칸 수가 다릅니다: " + row.pathKey());
            }
            if (hasGap(row)) {
                throw new IllegalArgumentException("Depth는 중간을 비울 수 없습니다: " + row.pathKey());
            }
            if (row.hasScreen() && !screens.add(row.screenId())) {
                throw new IllegalArgumentException("같은 화면 ID가 두 행에 연결되어 있습니다: " + row.screenId());
            }
        }
    }

    public String hash(String content) {
        return sha256(content);
    }

    private Map<String, String> fields(String record) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : record.split(" / ")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                fields.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
            }
        }
        return fields;
    }

    private List<String> depths(String path, Map<String, String> labels) {
        String[] keys = path.split("/");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            String prefix = String.join("/", java.util.Arrays.copyOfRange(keys, 0, i + 1));
            values.add(labels.getOrDefault(prefix, keys[i]));
        }
        return List.copyOf(values);
    }

    private void validatePath(String path) {
        if (path == null || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("경로 식별자는 영문·숫자·하이픈·밑줄의 1~7단계여야 합니다: " + path);
        }
    }

    private boolean hasGap(IaRow row) {
        List<String> all = java.util.Arrays.asList(row.depth1(), row.depth2(), row.depth3(), row.depth4(),
                row.depth5(), row.depth6(), row.depth7());
        boolean blankSeen = false;
        for (String value : all) {
            if (value == null || value.isBlank()) {
                blankSeen = true;
            } else if (blankSeen) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
        }
    }

    public record Parsed(List<Placement> placements, String hash) {}
    public record Placement(int order, String pathKey, String screenId, List<String> depths) {}
}
