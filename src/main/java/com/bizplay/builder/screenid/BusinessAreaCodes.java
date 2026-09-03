package com.bizplay.builder.screenid;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 가 준 업무영역 코드를 우리 규칙으로 걷어낸다. <b>순수 함수라 시험이 여기를 직접 잰다.</b>
 *
 * <p>⛔ <b>구현마다 따로 두지 마라.</b> {@link BusinessAreaCoder} 가 둘 이상이어도
 * 걷어내는 규칙은 하나다 — 갈라 두면 공급자를 바꾼 날 규칙도 같이 갈리고,
 * 그 갈림은 DB 유일 인덱스가 깨질 때에야 드러난다.
 */
final class BusinessAreaCodes {

    private static final Pattern THREE_UPPER = Pattern.compile("^[A-Z]{3}$");
    static final String UNKNOWN = "XXX";

    private BusinessAreaCodes() {
    }

    /**
     * 규칙 셋 — ① 대문자 3글자가 아니면 {@code XXX} ② 앞에서 이미 쓴 코드면 {@code XXX}
     * ③ 답에 없는 slug 도 {@code XXX}. <b>{@code areas} 의 열쇠를 하나도 빠뜨리지 않는다.</b>
     *
     * @param codes {@code {"codes": ...}} 의 안쪽. <b>널이면 「답이 없다」는 뜻이다</b>
     */
    static Map<String, String> of(JsonNode codes, Map<String, String> areas) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, String> area : areas.entrySet()) {
            JsonNode value = codes == null ? null : codes.get(area.getKey());
            String candidate = value == null ? null : value.asText(null);
            String code = candidate != null && THREE_UPPER.matcher(candidate).matches() ? candidate : UNKNOWN;
            if (!UNKNOWN.equals(code) && !used.add(code)) {
                code = UNKNOWN;
            }
            result.put(area.getKey(), code);
        }
        return result;
    }

    /**
     * 업무영역 목록을 보여 주고 3글자를 시키는 말. <b>두 구현이 같은 말을 쓴다</b> —
     * 공급자가 달라도 답의 모양이 같아야 {@link #of} 가 그대로 선다.
     *
     * <p>⚠ <b>한 번에 목록 전체를 준다.</b> 업무영역마다 따로 물으면 AI 가 앞의 답을 모르므로
     * <b>같은 3글자를 두 곳에 준다.</b>
     */
    static String instructionFor(Map<String, String> areas) {
        StringBuilder list = new StringBuilder();
        areas.forEach((slug, label) -> list.append("- ").append(slug).append(" : ")
                .append(label == null ? slug : label).append(System.lineSeparator()));
        return """
                아래는 한 시스템의 업무영역 목록이다. 왼쪽은 영문 slug, 오른쪽은 한글 이름이다.

                %s
                각 업무영역에 **대문자 영문 3글자** 코드를 붙여라.

                규칙:
                - 뜻이 통하는 축약을 고른다 (merchant / 가맹점 -> MRC).
                - 목록 안에서 **코드가 겹치면 안 된다**. 전체를 한 번에 보고 정해라.
                - 못 짓겠으면 지어내지 말고 "XXX" 를 내라.

                다른 말 없이 JSON 하나만 내라:
                {"codes": {"slug": "ABC"}}
                """.formatted(list);
    }
}
