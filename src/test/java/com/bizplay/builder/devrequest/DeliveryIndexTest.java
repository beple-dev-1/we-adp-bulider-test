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
        assertThat(tree.get("specVersion").asInt()).isEqualTo(2);
        JsonNode row = tree.get("deliveries").get(0);
        assertThat(row.has("project")).as("작업그룹은 저장소가 따로라 목록에 안 적는다").isFalse();
        assertThat(row.get("dr").asText()).isEqualTo("DR-009");
        assertThat(row.get("branch").asText()).isEqualTo("dr/EXW/DR-009");
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

    /**
     * ⭐ <b>브랜치 이름은 IA 의 시스템으로 가른다</b> (2026-09-23 사용자 확정) — {@code dr/EXW/DR-011}.
     * 작업그룹(프로젝트)은 저장소가 따로라 이름에 안 넣는다.
     */
    @Test
    void 브랜치_이름은_시스템으로_가른다() {
        assertThat(DeliveryIndex.deliveryBranch("EXW", "DR-011")).isEqualTo("dr/EXW/DR-011");
        assertThat(DeliveryIndex.returnBranch("EXW", "DR-011")).isEqualTo("feedback/EXW/DR-011");
    }

    /** ⭐ 시스템이 없는 요청서(SRT 로 만든 것)는 {@code SRT} 자리에 간다 (2026-09-23 사용자 확정). */
    @Test
    void 시스템이_없으면_SRT_자리로_간다() {
        assertThat(DeliveryIndex.deliveryBranch(null, "DR-012")).isEqualTo("dr/SRT/DR-012");
        assertThat(DeliveryIndex.returnBranch(" ", "DR-012")).isEqualTo("feedback/SRT/DR-012");
    }

    /** ⛔ 브랜치 이름이 될 수 없는 시스템 값은 받지 않는다 — 조용히 고쳐 쓰면 개발이 못 찾는다. */
    @Test
    void 브랜치_이름이_될_수_없는_시스템은_거절한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DeliveryIndex.deliveryBranch("EX W", "DR-001"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EX W");
    }

    /**
     * ⭐ <b>README 는 개발의 입구다</b> — 개발은 이 브랜치 하나만 알면 나머지를 찾아간다.
     * ⛔ 브랜치 규칙을 글로 따로 적지 않는다 — 이름을 짓는 함수로 그린다. 따로 적으면 한쪽만 고쳐진다.
     * ⛔ 돌려보내는 법은 여기에 다시 적지 않는다 — 요청마다의 값이 {@code expected-back.md} 에 있다.
     */
    @Test
    void README_는_목록의_칸과_브랜치_규칙을_짓는_자리에서_그린다() {
        String readme = DeliveryIndex.readme();

        assertThat(readme)
                .contains(DeliveryIndex.BRANCH).contains(DeliveryIndex.PATH)
                .contains(DeliveryIndex.deliveryBranch("<시스템>", "<dr>"))
                .contains(DeliveryIndex.returnBranch("<시스템>", "<dr>"))
                .contains("expected-back.md").contains("dev-request.md")
                .contains("`dr`").contains("`branch`").contains("`base`").contains("`system`")
                .doesNotContain("`project`")
                .doesNotContain("return.json");
    }

    /**
     * ⭐ <b>개발이 스스로 「받아졌나」를 알 수 있게 한다.</b> 거절 사유는 기획 화면에만 뜨므로, 받기 커밋의
     * 작성자와 꼬리표를 알려 둔다. ⛔ 두 글자는 받는 자리의 것을 그대로 쓴다 — 따로 적으면 한쪽만 고쳐진다.
     * ⚠ 시스템이 없는 요청(SRT)의 자리도 브랜치 꼴로 알린다.
     */
    @Test
    void README_는_받았는지_확인하는_법과_SRT_자리를_알린다() {
        String readme = DeliveryIndex.readme();

        assertThat(readme)
                .contains(DevRequestDeliveryWorkspace.RECEIVER_NAME)
                .contains(DevRequestDeliveryWorkspace.RETURNED_HEAD_TRAILER)
                .contains(DeliveryIndex.deliveryBranch(null, "<dr>"))
                .contains("거절");
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
        return new DeliveryIndex.Entry(dr, DeliveryIndex.deliveryBranch("EXW", dr), commit,
                "370cd63", "EXW", List.of("EXW-UWV-70-30-10-C"), Instant.parse("2026-09-22T08:24:58Z"),
                "key-" + dr.substring(dr.length() - 3));
    }
}
