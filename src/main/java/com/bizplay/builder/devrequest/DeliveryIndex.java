package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * 개발이 <b>이름을 미리 모르고도</b> 전달을 찾는 목록 — {@link #BRANCH} 브랜치의 {@link #PATH}.
 *
 * <p>⛔ <b>기본 브랜치에 두지 마라</b> (2026-09-23 사용자 확정). 한때 {@code main:dev-requests/deliveries.json}
 * 에 두었더니 넘기기가 목록을 커밋할 때마다 기본 브랜치가 한 판 앞으로 가, 꾸러미가 알린 기준
 * 커밋이 곧바로 낡았다. 역류 설계가 「받을 때 HEAD 와 다르면 거절」이라 문서대로 한 개발이 늘 거절됐다.
 *
 * <p>⭐ <b>이름은 바뀌지만 자리는 고정이다.</b> 전달 브랜치는 {@code dr/EXW/DR-009} ·
 * {@code dr/EXW/DR-010} 으로 DR 마다 바뀐다. 개발이 그 이름을 미리 알 길이 없으므로,
 * <b>한 경로에 목록을 두고 그것만 보게 한다.</b>
 *
 * <p>⭐ <b>브랜치 이름의 가운데 마디는 IA 의 시스템이다</b> (2026-09-23 사용자 확정) — {@code dr/EXW/DR-011}.
 * 개발은 시스템별로 나뉘어 일하니 그 축으로 모인다. ⛔ <b>작업그룹(프로젝트)을 넣지 마라</b> — 작업그룹은
 * 기획 저장소가 따로라 한 저장소 안에서 DR 번호만으로 하나다. 한때 프로젝트 ID 를 넣었다가 되돌렸다.
 * ⚠ 시스템이 없는 요청서(SRT 로 만든 것)는 {@link #NO_SYSTEM} 자리에 간다 — {@code dr/SRT/DR-012}.
 * ⛔ 이름은 {@link #deliveryBranch} · {@link #returnBranch} 로만 짓는다 — README 도 이것으로 그린다.
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

    /**
     * ⛔ 이 브랜치 이름과 경로는 <b>함부로 안 바꾼다.</b> 바뀌면 개발이 보던 자리가 사라진다.
     * 2026-09-23 에 한 번 옮겼다(위 까닭) — 다시 옮기려면 개발에 먼저 고지한다.
     */
    public static final String BRANCH = "dev-requests";
    /** ⚠ 전용 브랜치의 뿌리에 목록과 그 입구 README 둘만 산다. */
    public static final String PATH = "deliveries.json";
    public static final String README = "README.md";

    /**
     * ⚠ 목록 규격의 판. 칸을 바꾸면 이 값을 올리고 개발에 고지한다.
     * 2 — 브랜치 이름에 시스템 마디가 붙었다({@code dr/EXW/DR-011}, 2026-09-23).
     */
    private static final int SPEC_VERSION = 2;

    /** 시스템이 없는 요청서(SRT 로 만든 것)가 앉는 자리 (2026-09-23 사용자 확정). IA 시스템 코드와 안 겹친다. */
    public static final String NO_SYSTEM = "SRT";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DeliveryIndex() {
    }

    /** 전달 브랜치 — 꾸러미가 사는 자리. {@code dr/<시스템>/<번호>}. */
    public static String deliveryBranch(String systemCode, String label) {
        return "dr/" + systemSegment(systemCode) + "/" + label;
    }

    /** 돌려보낼 브랜치 — 개발이 기본 브랜치에서 따서 미는 자리. {@code feedback/<시스템>/<번호>}. */
    public static String returnBranch(String systemCode, String label) {
        return "feedback/" + systemSegment(systemCode) + "/" + label;
    }

    /**
     * ⛔ 브랜치 이름이 될 수 없는 값은 거절한다 — 조용히 고쳐 쓰면 개발이 그 이름을 못 찾는다.
     * ⚠ {@code <시스템>} 꼴은 README 에서 자리 표시로만 쓴다.
     */
    private static String systemSegment(String systemCode) {
        if (systemCode == null || systemCode.isBlank()) {
            return NO_SYSTEM;
        }
        if (!systemCode.matches("[A-Za-z0-9_-]+|<[^/\\s]+>")) {
            throw new IllegalStateException("브랜치 이름이 될 수 없는 시스템 값입니다: " + systemCode);
        }
        return systemCode;
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
     * 개발의 입구 — 전용 브랜치 뿌리의 {@link #README}.
     *
     * <p>⭐ <b>개발은 이 브랜치 이름 하나만 알면 된다</b> — 나머지는 여기서 찾아간다.
     * ⛔ <b>돌려보내는 법을 여기에 다시 적지 마라.</b> 요청마다의 값(브랜치 · 기준 · 파일 자리 · 회신서
     * 견본)은 그 꾸러미의 {@code expected-back.md} 에 있다 — 여기에도 적으면 둘이 갈린다.
     * 꾸러미 설계가 「README 를 꾸러미마다 복사하지 마라」고 막은 것도 그 까닭이다 — 이것은 한 벌이고,
     * 넘길 때마다 빌더가 다시 써서 코드와 어긋나지 않는다.
     */
    public static String readme() {
        String dr = deliveryBranch("<시스템>", "<dr>");
        String back = returnBranch("<시스템>", "<dr>");
        return String.join(NL_JOIN,
                "# 개발요청 받는 곳",
                "",
                "이 브랜치(`" + BRANCH + "`)는 **WE-ADP Builder 가 씁니다.** 손으로 고치지 마십시오 —"
                        + " 넘길 때마다 다시 씁니다.",
                "",
                "## 1. 무엇이 나와 있나 — `" + PATH + "`",
                "",
                "줄 하나가 개발요청서 하나입니다.",
                "",
                "| 칸 | 뜻 |",
                "|---|---|",
                "| `dr` | 개발요청서 번호. 이 저장소 안에서 하나뿐입니다 |",
                "| `branch` | 꾸러미가 든 브랜치 — `" + dr + "` |",
                "| `commit` | 꾸러미 커밋 |",
                "| `base` | 기본 브랜치에서 갈라 올 기준 커밋 |",
                "| `system` · `screens` | 대상 시스템(IA 기준 — 브랜치 이름의 가운데 마디)과 화면."
                        + " 화면 없는 요청은 `" + NO_SYSTEM + "` 자리에 갑니다 |",
                "| `sentAt` · `sendKey` | 보낸 시각과 전송 키. 다시 보내도 키는 같습니다 |",
                "",
                "같은 `dr` 을 다시 보내면 그 줄이 새것으로 바뀝니다.",
                "",
                "## 2. 무엇을 하나",
                "",
                "1. `branch` 를 받아 `<dr>/dev-request.md`(무엇을 만드나)와 `<dr>/expected-back.md`"
                        + "(무엇을 돌려주나)를 엽니다.",
                "2. 돌려보내는 법은 `expected-back.md` 의 「돌려보내는 법」에 **그 요청의 값으로** 적혀 있습니다."
                        + " 이 문서가 아니라 그쪽을 따르십시오.",
                "3. 돌려보낼 브랜치는 `" + back + "` 꼴입니다.",
                "");
    }

    /** ⚠ 줄바꿈을 못 박는다 — 운영체제에 따라 README 가 섞이지 않게. */
    private static final String NL_JOIN = "\n";

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
