package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * 개발이 <b>이름을 미리 모르고도</b> 전달을 찾는 목록 — 기본 브랜치의 {@link #PATH}.
 *
 * <p>⭐ <b>이름은 바뀌지만 자리는 고정이다.</b> 전달 브랜치는 {@code dr/DR-009} ·
 * {@code dr/DR-010} 으로 DR 마다 바뀐다. 개발이 그 이름을 미리 알 길이 없으므로,
 * <b>한 경로에 목록을 두고 그것만 보게 한다.</b>
 *
 * <p>⭐ <b>{@code base} 가 역류의 기준이다.</b> 역류 설계는 「기준이 어긋나면 거절한다」를
 * {@code baseSha} 로 정했는데 <b>그 값을 개발에게 알려 줄 자리가 없었다</b> — 여기가 그 자리다.
 *
 * <p>⛔ <b>움직이는 이름 하나로 갈음하지 마라</b>({@code dr/latest} 따위). 이름이 고정되어
 * 편해 보이지만 전달이 둘 겹치면 앞것이 사라지고 무엇을 보냈는지 증명이 안 된다 —
 * 꾸러미 설계의 「DR 하나에 꾸러미 하나 · 판이 남으면 어느 것을 보냈는지 알 수 없다」가 그 자리다.
 *
 * <p>⛔ <b>알림을 여기에 얹지 마라.</b> GitHub Actions 로 개발 시스템을 부르는 길은 러너가
 * 깃허브 쪽에 있어 <b>사내망 안을 못 부른다</b>(2026-09-22 실측 맥락: 이 환경은 사내망이 TLS 를
 * 가로챈다). 개발이 <b>당겨오는</b> 방향이라 막힐 일이 없다.
 */
public final class DeliveryIndex {

    /** ⛔ 이 경로는 <b>영원히 안 바뀐다.</b> 바뀌면 개발이 보던 자리가 사라진다. */
    public static final String PATH = "dev-requests/deliveries.json";

    /** ⚠ 목록 규격의 판. 칸을 바꾸면 이 값을 올리고 개발에 고지한다. */
    private static final int SPEC_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private DeliveryIndex() {
    }

    /**
     * 목록 한 줄.
     *
     * @param base 이 꾸러미를 구운 기준 커밋 — <b>역류가 이 값 위에서 갈라 와야 한다</b>
     */
    public record Entry(String dr, String branch, String commit, String base, String system,
                        List<String> screens, Instant sentAt, String sendKey) {
    }

    /**
     * 기존 목록에 한 줄을 합친다.
     *
     * <p>⭐ <b>같은 {@code dr} 은 갈아 낀다.</b> 다시 보내면 앞 줄이 남으면 안 된다.
     * ⚠ <b>남의 줄과 모르는 칸은 그대로 둔다</b> — 같은 파일을 여러 전달이 나눠 쓴다.
     *
     * @param existing 기존 파일 내용. 없으면 {@code null}
     */
    public static String merge(String existing, Entry entry) {
        ObjectNode root = read(existing);
        ArrayNode deliveries = root.withArray("deliveries");
        ArrayNode merged = JSON.createArrayNode();
        boolean replaced = false;
        for (JsonNode row : deliveries) {
            if (entry.dr().equals(row.path("dr").asText(null))) {
                merged.add(rowOf(entry));
                replaced = true;
                continue;
            }
            merged.add(row);
        }
        if (!replaced) {
            merged.add(rowOf(entry));
        }
        root.put("specVersion", SPEC_VERSION);
        root.set("deliveries", merged);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("전달 목록을 쓰지 못했습니다.", impossible);
        }
    }

    private static ObjectNode read(String existing) {
        if (existing == null || existing.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode tree = JSON.readTree(existing);
            if (!tree.isObject()) {
                throw new IllegalStateException("전달 목록의 꼴이 올바르지 않습니다: " + PATH);
            }
            return (ObjectNode) tree;
        } catch (JsonProcessingException broken) {
            /*
             * ⛔ 깨졌다고 덮어쓰지 않는다 — 남이 적어 둔 줄을 잃는 것이 더 나쁘다.
             *   사람이 그 파일을 고친 뒤 다시 보내면 된다.
             */
            throw new IllegalStateException("전달 목록을 읽지 못했습니다 — 손으로 고친 뒤 다시 보내십시오: "
                    + PATH, broken);
        }
    }

    private static ObjectNode rowOf(Entry entry) {
        ObjectNode row = JSON.createObjectNode();
        row.put("dr", entry.dr());
        row.put("branch", entry.branch());
        row.put("commit", entry.commit());
        row.put("base", entry.base());
        row.put("system", entry.system());
        ArrayNode screens = row.putArray("screens");
        if (entry.screens() != null) {
            entry.screens().forEach(screens::add);
        }
        row.put("sentAt", entry.sentAt() == null ? null : entry.sentAt().toString());
        row.put("sendKey", entry.sendKey());
        return row;
    }
}
