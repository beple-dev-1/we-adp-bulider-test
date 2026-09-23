package com.bizplay.builder.devrequest;

import com.bizplay.builder.devrequest.DevelopmentRequestContent.TestScenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 테스트 시나리오 AI 출력을 읽는다 — 규격에 안 맞으면 안 받는다.
 *
 * <p>⛔ <b>사과문을 계약서에 싣지 않는다.</b> 행위·결과가 빈 건, 순번이 대상 범위를 벗어난 건,
 * 같은 ID 가 둘인 건은 통째로 거절한다 — 한 건만 버리면 개발이 「TC-003 은 어디 갔나」를 되묻는다.
 */
@Component
public class DevRequestTestScenarioReader {

    static final int MAX_SCENARIOS = 200;
    static final int MAX_FIELD_LENGTH = 1_000;
    private static final Pattern ID = Pattern.compile("TC-\\d{3}");

    private final ObjectMapper json;

    public DevRequestTestScenarioReader(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param unitTargets        화면 외 구현 항목 수 — {@code targetSeq} 상한
     * @param integrationTargets 완료 조건 수 — {@code targetSeq} 상한
     */
    public List<TestScenario> read(String output, int unitTargets, int integrationTargets) throws IOException {
        JsonNode root = json.readTree(stripFence(output));
        JsonNode items = root.path("scenarios");
        if (!items.isArray() || items.isEmpty()) {
            throw new IOException("테스트 시나리오(scenarios)가 비어 있습니다.");
        }
        if (items.size() > MAX_SCENARIOS) {
            throw new IOException("테스트 시나리오가 " + MAX_SCENARIOS + "건을 넘습니다.");
        }
        List<TestScenario> scenarios = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : items) {
            String kind = text(item, "kind");
            boolean unit = TestScenario.UNIT.equals(kind);
            if (!unit && !TestScenario.INTEGRATION.equals(kind)) {
                throw new IOException("kind 는 UNIT 또는 INTEGRATION 이어야 합니다: " + kind);
            }
            int seq = item.path("targetSeq").asInt(0);
            int limit = unit ? unitTargets : integrationTargets;
            if (seq < 1 || seq > limit) {
                throw new IOException("targetSeq 가 대상 범위를 벗어났습니다: " + kind + " " + seq);
            }
            String id = text(item, "id");
            if (id == null || !ID.matcher(id).matches() || !ids.add(id)) {
                throw new IOException("TC 번호가 규격(TC-001)에 맞지 않거나 겹칩니다: " + id);
            }
            String action = text(item, "action");
            String expected = text(item, "expected");
            if (action == null || expected == null) {
                throw new IOException(id + " 의 행위 또는 결과가 비어 있습니다.");
            }
            scenarios.add(new TestScenario(kind, seq, id,
                    text(item, "title") == null ? expected : text(item, "title"),
                    text(item, "dependency"), text(item, "condition"), action, expected));
        }
        return scenarios;
    }

    /** 빈 글은 널로 — 「없음」을 글자로 받으면 양식에 「없음」이 찍힌다. */
    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText().strip();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_FIELD_LENGTH) {
            throw new IOException(field + " 가 " + MAX_FIELD_LENGTH + "자를 넘습니다.");
        }
        return text;
    }

    /** ⚠ 모델이 코드 울타리로 감싸는 일이 잦다. */
    private static String stripFence(String output) {
        String text = output == null ? "" : output.strip();
        if (!text.startsWith("```")) return text;
        int firstBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) return text;
        return text.substring(firstBreak + 1, lastFence).strip();
    }
}
