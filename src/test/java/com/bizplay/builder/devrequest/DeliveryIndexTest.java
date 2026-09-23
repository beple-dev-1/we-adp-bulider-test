package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발이 <b>이름을 미리 모르고도</b> 전달을 찾는 목록.
 *
 * <p>⭐ <b>이름은 DR 마다 바뀌지만 목록이 있는 자리는 고정이다.</b> 전달 브랜치는
 * {@code dr/DR-009} · {@code dr/DR-010} 으로 계속 바뀌는데, 개발은 기본 브랜치의
 * {@code dev-requests} 브랜치의 {@code deliveries.json} <b>한 자리만</b> 본다.
 *
 * <p>⭐ <b>{@code base} 가 역류의 기준이다.</b> 설계는 「기준이 어긋나면 거절한다」를 {@code baseSha}
 * 로 정했는데, 그 값을 개발에게 알려 줄 자리가 없었다 — 이 목록이 그 자리다.
 */
class DeliveryIndexTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 빈_목록에_첫_줄을_넣는다() throws IOException {
        String merged = DeliveryIndex.merge(null, entry("DR-009", "9db65709"));

        JsonNode tree = json.readTree(merged);
        assertThat(tree.get("specVersion").asInt()).isEqualTo(1);
        JsonNode row = tree.get("deliveries").get(0);
        assertThat(row.get("dr").asText()).isEqualTo("DR-009");
        assertThat(row.get("branch").asText()).isEqualTo("dr/DR-009");
        assertThat(row.get("commit").asText()).isEqualTo("9db65709");
        assertThat(row.get("base").asText()).isEqualTo("370cd63");
        assertThat(row.get("system").asText()).isEqualTo("EXW");
        assertThat(row.get("screens").get(0).asText()).isEqualTo("EXW-UWV-70-30-10-C");
        assertThat(row.get("sendKey").asText()).isEqualTo("key-009");
    }

    @Test
    void 다른_DR_은_뒤에_붙는다() throws IOException {
        String first = DeliveryIndex.merge(null, entry("DR-009", "9db65709"));

        String merged = DeliveryIndex.merge(first, entry("DR-010", "aaaaaaa"));

        JsonNode rows = json.readTree(merged).get("deliveries");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("dr").asText()).isEqualTo("DR-009");
        assertThat(rows.get(1).get("dr").asText()).isEqualTo("DR-010");
    }

    /**
     * ⭐ <b>같은 DR 을 다시 보내면 그 줄을 갈아 낀다.</b> 개발요청서 하나에 꾸러미 하나이고,
     * 판이 둘 남으면 어느 것을 보냈는지 알 수 없다 — 꾸러미 설계가 못 박은 자리다.
     */
    @Test
    void 같은_DR_을_다시_보내면_그_줄을_갈아_낀다() throws IOException {
        String first = DeliveryIndex.merge(null, entry("DR-009", "9db65709"));

        String merged = DeliveryIndex.merge(first, entry("DR-009", "bbbbbbb"));

        JsonNode rows = json.readTree(merged).get("deliveries");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("commit").asText()).isEqualTo("bbbbbbb");
    }

    /** ⚠ 남이 먼저 적어 둔 줄을 지우지 않는다 — 같은 파일을 여러 전달이 나눠 쓴다. */
    @Test
    void 모르는_칸이_있어도_남의_줄을_지우지_않는다() throws IOException {
        String existing = """
                {"specVersion": 1, "deliveries": [
                  {"dr": "DR-001", "branch": "dr/DR-001", "commit": "old", "메모": "손으로 적은 것"}
                ]}
                """;

        String merged = DeliveryIndex.merge(existing, entry("DR-009", "9db65709"));

        JsonNode rows = json.readTree(merged).get("deliveries");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("dr").asText()).isEqualTo("DR-001");
        assertThat(rows.get(0).get("메모").asText()).isEqualTo("손으로 적은 것");
    }

    /** ⛔ 깨진 파일을 만나면 <b>덮어쓰지 않고 거절한다</b> — 남의 줄을 잃는 것이 더 나쁘다. */
    @Test
    void 깨진_목록은_덮어쓰지_않고_거절한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> DeliveryIndex.merge("{ 이건 json 이 아니다", entry("DR-009", "9db65709")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전달 목록");
    }

    private DeliveryIndex.Entry entry(String dr, String commit) {
        return new DeliveryIndex.Entry(dr, "dr/" + dr, commit, "370cd63", "EXW",
                List.of("EXW-UWV-70-30-10-C"), Instant.parse("2026-09-22T08:24:58Z"),
                "key-" + dr.substring(dr.length() - 3));
    }
}
