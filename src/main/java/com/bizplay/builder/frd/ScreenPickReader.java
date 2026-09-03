package com.bizplay.builder.frd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * AI 가 짚은 화면 목록을 <b>서버가 검사해서</b> 받는다.
 *
 * <p>⛔ <b>모르면 실패다.</b> 반쯤 건져 저장하면 화면ID 가 빈 줄이 앉는데, 그 줄은 목업을 만들 수도
 * 지울 수도 없다 — {@link com.bizplay.builder.intake.RequirementDraftReader} 와 같은 규칙이다.
 *
 * <p>⚠ <b>울타리({@code ```json})를 걷어내 준다.</b> 지시문이 못 박아도 모델은 종종 두른다.
 *
 * <p>⭐ <b>2026-08-18 실측이 계약을 넓혔다 — {@code items} 가 생겼다.</b> 요구사항 6건짜리가
 * 화면 1장으로 끝나고 나머지 다섯이 <b>아무 말 없이 사라진</b> 것을 봤다. 화면 목록만 받으면
 * 무엇이 버려졌는지 아무도 모른다 — <b>항목마다 판정을 받아야 조용한 누락이 드러난다.</b>
 *
 * <p>⛔ <b>모양이 틀렸을 때 「화면ID 가 빈 줄이 있다」로 뭉뚱그리지 마라 (2026-08-18 실측).</b>
 * {@code index.json} 의 {@code screens} 가 <b>화면ID 를 키로 한 객체</b>라, 모델이 방금 읽은
 * 그 파일을 따라 객체로 내는 일이 있다. Jackson 은 객체도 값만 훑어 주므로 그때도 똑같이
 * 「화면ID 가 비었다」로 보였다 — <b>무엇이 틀렸는지 말하지 않으면 아무도 못 고친다.</b>
 */
@Component
public class ScreenPickReader {

    /** ⚠ 열 장이 상한이다 — 지시문도 같은 수를 말한다. 같이 고쳐라. */
    private static final int MAX_SCREENS = 10;

    /** ⚠ 항목 상한. 요구사항 한 건이 이보다 잘게 쪼개졌으면 FRD 를 나눌 때다. */
    private static final int MAX_ITEMS = 50;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 항목의 <b>성격</b> — 무엇을 바꾸는 일인가. 정본은 {@link FrdItem.Nature} 다.
     *
     * <p>⭐ 가르는 질문은 하나다 — <b>「그 일을 할 기능이 이미 있나」.</b>
     */
    public enum Nature { DEVELOP, OPERATE, OUTSIDE }

    /**
     * 요구사항 항목 하나의 <b>화면</b> 판정.
     *
     * <p>⚠ <b>{@link Nature#DEVELOP} 인 항목에만 뜻이 있다</b> — 「개발이냐」는 {@link Nature} 가 답한다.
     *
     * <p>⛔ <b>넷째 값을 더하지 마라.</b> 셋이 요구사항 항목이 갈 수 있는 자리 전부다 —
     * 「모르겠다」를 값으로 만들면 AI 가 거기로 도망친다.
     */
    public enum Verdict {
        /** 고칠 화면을 찾았다. */
        SCREEN,
        /** 화면 일이 아니다 — 배치·API·알림 따위. {@code domains/} 에 근거가 있다. */
        NO_SCREEN,
        /** 화면 일인데 그 화면이 {@code index.json} 에 없다(아직 추출 안 됐다). */
        NOT_INDEXED
    }

    public record Picked(String screenId, String system, String screenName, String reason,
                         boolean newScreen, String screenType) {
        /** 이전 출력과 단위 테스트가 쓰는 기존 화면 계약. */
        public Picked(String screenId, String system, String screenName, String reason) {
            this(screenId, system, screenName, reason, false, null);
        }
    }

    /**
     * 요구사항 항목 하나와 그 판정 둘.
     *
     * <p>⚠ <b>{@code screenIds} 는 성격과 무관하게 찬다</b> — {@link Nature#OPERATE} 항목도
     * 「일이 일어나는 화면」을 가리킨다(운영자가 어디서 지우나). 다만 <b>작업 단위로 승격되지는
     * 않는다</b> — 그 갈림은 {@link Pick#screens()} 가 진다.
     */
    public record Item(String requirement, Nature nature, Verdict verdict,
                       List<String> screenIds, String note) { }

    /**
     * ⛔ <b>{@code screens} 는 {@link Nature#DEVELOP} 항목의 화면만 담는다.</b> 이 목록이
     * {@code adk_builder_frd_screen} 으로 앉고 <b>화면마다 to-be 목업을 만든다</b> —
     * 고칠 것이 없는 화면을 여기 담으면 AI 가 헛일을 한다. 나머지는 {@link Item#screenIds()} 에만 산다.
     */
    public record Pick(String title, List<Item> items, List<Picked> screens, String noScreenReason) { }

    public Pick read(String output) throws IOException {
        JsonNode root = mapper.readTree(stripFence(output));
        String title = text(root, "title");
        if (title == null) {
            throw new IOException("제목(title)이 빈 결과다");
        }

        /*
         * ⭐ 화면은 **항목 안에** 산다. 최상위 screens 도 있으면 같이 거둔다 —
         *   중복은 화면ID 로 한 번만 센다.
         */
        List<Picked> screens = new ArrayList<>();
        List<Item> items = readItems(itemsIn(root), screens);
        collectScreens(root.path("screens"), "화면", screens);
        for (Picked screen : screens) {
            if (screen.reason() == null || screen.reason().isBlank()) {
                throw new IOException("화면 " + screen.screenId()
                        + "의 구체적인 수정 내용(reason)이 비었다");
            }
        }

        return new Pick(cut(title, 255), items, List.copyOf(screens), text(root, "noScreenReason"));
    }

    /**
     * ⛔ <b>빈 항목을 통과시키지 마라.</b> 항목이 없다는 것은 요구사항을 안 읽었다는 뜻이고,
     * 그것이 조용한 누락의 가장 큰 꼴이다.
     *
     * @param screens 항목 안에서 만난 화면을 여기에 거둔다 — <b>항목 차례가 화면 차례다.</b>
     */
    private List<Item> readItems(JsonNode node, List<Picked> screens) throws IOException {
        if (!node.isArray()) {
            throw new IOException("요구사항 항목(items)이 배열이 아니다 — " + shapeOf(node));
        }
        List<Item> items = new ArrayList<>();
        int at = 0;
        for (JsonNode each : node) {
            at++;
            if (!each.isObject()) {
                throw new IOException("요구사항 항목 " + at + "번째 칸이 객체가 아니다 — " + shapeOf(each));
            }
            String requirement = requirementOf(each);
            if (requirement == null) {
                throw new IOException("요구사항 항목 " + at + "번째의 원문(requirement)이 비었다");
            }
            Nature nature = natureOf(each);
            /*
             * ⛔ 성격이 DEVELOP 이 아니면 **승격 목록에 담지 않는다** — 화면은 근거로만 남는다.
             *   담을 자리를 버리는 임시 목록으로 바꾸면 screenIds 는 그대로 차고
             *   작업 단위만 걸러진다. ⚠ 같은 화면을 뒤의 DEVELOP 항목이 가리키면 그때 승격된다.
             */
            List<Picked> promoteInto = nature == Nature.DEVELOP ? screens : new ArrayList<>();
            items.add(new Item(requirement, nature, verdictOf(each, at),
                    collectScreens(screensOf(each), "항목 " + at + "번째의 화면", promoteInto),
                    text(each, "note")));
            if (items.size() > MAX_ITEMS) {
                throw new IOException("요구사항 항목이 " + MAX_ITEMS + "건을 넘는다");
            }
        }
        if (items.isEmpty()) {
            throw new IOException("요구사항 항목(items)이 하나도 없다");
        }
        return List.copyOf(items);
    }

    /**
     * 요구사항 항목들이 실제로 앉은 배열을 찾는다.
     *
     * <p>⚠ <b>이것은 보험이다.</b> 모양을 못박는 것은 {@code claude} 의 {@code --json-schema} 몫이고
     * ({@link ScreenPickWorker} 가 준다) 그쪽이 서면 이 길은 첫 줄에서 끝난다.
     *
     * <p>⛔ <b>그래도 필요하다 (2026-08-18 세 번째 실측).</b> 요구사항 원문이 두 표제로 나뉘어
     * 있으니 모델이 {@code sections} 로 <b>한 겹 더 감쌌다.</b> 그런 껍데기는 이름을 미리 알 수 없다 —
     * 그래서 이름을 맞히는 대신 <b>{@code verdict} 를 든 객체</b>를 찾는다. 그 표식은 매우 특징적이라
     * 엉뚱한 것을 항목으로 잡을 자리가 없고, <b>있는 값을 찾아 읽는 것</b>이라 「모르면 실패」와
     * 부딪히지 않는다.
     */
    private JsonNode itemsIn(JsonNode root) {
        JsonNode items = root.path("items");
        if (items.isArray()) {
            return items;
        }
        var found = mapper.createArrayNode();
        gatherVerdictBearers(root, found, 0);
        return found.isEmpty() ? items : found;
    }

    /** ⚠ 깊이를 묶어 둔다 — 이상한 출력에서 트리를 끝없이 파고들지 않게 한다. */
    private void gatherVerdictBearers(JsonNode node, com.fasterxml.jackson.databind.node.ArrayNode into,
                                      int depth) {
        if (depth > 6 || into.size() > MAX_ITEMS) {
            return;
        }
        if (node.isObject() && node.path("verdict").isTextual()) {
            into.add(node);
            return;   // 항목 안으로는 더 파지 않는다 — 화면은 항목이 아니다.
        }
        for (JsonNode child : node) {
            gatherVerdictBearers(child, into, depth + 1);
        }
    }

    /**
     * 항목 원문. ⚠ <b>{@code title} 로도 받는다 (2026-08-18 실측).</b> 최상위에 {@code title} 이
     * 있어서 모델이 항목에도 같은 이름을 쓴다 — 값을 지어내는 것이 아니라 같은 값을 다른
     * 이름에서 읽는 것이라 「모르면 실패」와 부딪히지 않는다.
     */
    private String requirementOf(JsonNode item) {
        return firstOf(item, "requirement", "title", "text");
    }

    /** 여러 이름 중 먼저 찬 것. ⚠ 값을 지어내지 않는다 — <b>있는 값을 다른 이름에서 읽을 뿐이다.</b> */
    private String firstOf(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 항목이 가리키는 화면들. ⚠ {@code screenIds}(화면ID 문자열 배열)로도 받는다 —
     * 그쪽이 2026-08-18 첫 판의 계약이었고 모델이 아직 그 꼴로 낼 수 있다.
     */
    private JsonNode screensOf(JsonNode item) {
        JsonNode screens = item.path("screens");
        return screens.isMissingNode() || screens.isNull() ? item.path("screenIds") : screens;
    }

    /**
     * 항목의 성격.
     *
     * <p>⚠ <b>없으면 {@link Nature#DEVELOP} 이다.</b> 그것이 성격 축이 없던 옛 계약의 뜻과
     * <b>정확히 같다</b> — 그때는 짚힌 화면이 모두 작업 대상으로 승격됐다. 값을 지어내는 것이
     * 아니라 <b>옛 뜻을 그대로 두는 것</b>이라 「모르면 실패」와 부딪히지 않는다.
     *
     * <p>⛔ <b>그래도 모르는 값은 거절한다</b> — 「모르겠다」가 슬쩍 들어오는 문을 막는다.
     */
    private Nature natureOf(JsonNode item) throws IOException {
        String raw = text(item, "nature");
        if (raw == null) {
            return Nature.DEVELOP;
        }
        try {
            return Nature.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IOException("요구사항 항목의 성격(nature)을 모르겠다 — " + raw);
        }
    }

    /** ⛔ 모르는 판정은 통과시키지 않는다 — 무슨 뜻인지 아무도 모르는 줄이 화면에 앉는다. */
    private Verdict verdictOf(JsonNode item, int at) throws IOException {
        String raw = text(item, "verdict");
        if (raw == null) {
            throw new IOException("요구사항 항목 " + at + "번째의 판정(verdict)이 비었다");
        }
        try {
            return Verdict.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IOException("요구사항 항목 " + at + "번째의 판정을 모르겠다 — " + raw);
        }
    }

    /**
     * 화면 목록 한 덩어리를 읽어 {@code into} 에 <b>거둔다</b> — 이미 있는 화면ID 는 다시 담지 않는다.
     *
     * <p>⛔ <b>모양을 먼저 가린다.</b> 객체나 문자열로 온 것을 「화면ID 가 비었다」로 뭉뚱그리면
     * 사람이 프롬프트의 무엇을 고쳐야 하는지 알 길이 없다.
     *
     * <p>⚠ 같은 화면이 두 항목에 걸리면 화면은 한 번만 세되, 항목별 수정 내용은 모두 합친다.
     * 화면 표에는 {@code (frd_id, screen_id)} 유일 제약이 있지만 요구사항은 여러 개일 수 있다.
     *
     * @param where 사람이 읽는 자리 이름. 항목 안이면 몇 번째 항목인지가 들어온다
     * @return 이 덩어리가 가리킨 화면ID 목록(중복 없이 이 덩어리 안의 차례로)
     */
    private List<String> collectScreens(JsonNode node, String where, List<Picked> into)
            throws IOException {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IOException(where + " 목록(screens)이 배열이 아니다 — " + shapeOf(node)
                    + ". index.json 처럼 화면ID 를 키로 한 객체로 내면 안 된다");
        }
        List<String> ids = new ArrayList<>();
        int at = 0;
        for (JsonNode each : node) {
            at++;
            // ⚠ 화면ID 문자열만 늘어놓는 꼴도 받아 준다 — 이름·까닭이 없을 뿐 뜻은 온전하다.
            //   ⚠ id·screen_id 로도 받는다(2026-08-18 세 번째 실측에서 id 로 왔다).
            String screenId = each.isTextual()
                    ? blankToNull(each.asText()) : firstOf(each, "screenId", "id", "screen_id");
            if (screenId == null && !each.isObject()) {
                throw new IOException(where + " " + at + "번째 칸이 객체가 아니다 — " + shapeOf(each));
            }
            if (screenId == null) {
                throw new IOException(where + " " + at + "번째의 화면ID 가 비었다");
            }
            // ⚠ screen_id 열이 varchar(100) 이다 — 안 자르면 DB 가 이 짚기 전체를 거절한다.
            String cutScreenId = cut(screenId, 100);
            ids.add(cutScreenId);
            Picked found = new Picked(cutScreenId,
                    // ⚠ system_code 열이 varchar(50) 이다 — 널일 수 있어 cutOrNull 로 자른다.
                    cutOrNull(firstOf(each, "system", "systemCode", "system_code"), 50),
                    cut(orElse(firstOf(each, "screenName", "name", "screen_name"), cutScreenId), 255),
                    firstOf(each, "reason", "why"),
                    booleanOf(each, "newScreen", "isNewScreen"),
                    cutOrNull(firstOf(each, "screenType", "type", "screen_type"), 50));
            int already = indexOfScreen(into, cutScreenId);
            if (already >= 0) {
                // ⚠ 이름·시스템은 먼저 만난 값을 지키고 빈 칸만 채운다. 수정 까닭은 요구사항마다
                //   다를 수 있으므로 같은 문장만 제거하고 항목 차례대로 모두 보존한다.
                into.set(already, fillBlanks(into.get(already), found));
                continue;
            }
            into.add(found);
            if (into.size() > MAX_SCREENS) {
                throw new IOException("짚은 화면이 " + MAX_SCREENS + "장을 넘는다");
            }
        }
        return List.copyOf(ids);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int indexOfScreen(List<Picked> screens, String screenId) {
        for (int at = 0; at < screens.size(); at++) {
            if (screens.get(at).screenId().equals(screenId)) {
                return at;
            }
        }
        return -1;
    }

    /**
     * 먼저 만난 화면 메타데이터를 지키면서 빈 칸과 추가 수정 내용을 늦게 온 것으로 채운다.
     *
     * <p>⚠ 화면명은 <b>화면ID 로 갈음된 것도 빈 것으로 본다</b> — 문자열로만 온 화면이 뒤에서
     * 제 이름을 달고 다시 나타나면 그 이름을 쓴다.
     */
    private static Picked fillBlanks(Picked kept, Picked late) {
        boolean namelessBefore = kept.screenName() == null || kept.screenName().equals(kept.screenId());
        return new Picked(kept.screenId(),
                kept.system() != null ? kept.system() : late.system(),
                namelessBefore ? late.screenName() : kept.screenName(),
                mergeDistinctLines(kept.reason(), late.reason()),
                kept.newScreen() || late.newScreen(),
                kept.screenType() != null ? kept.screenType() : late.screenType());
    }

    private static String mergeDistinctLines(String first, String second) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        addLines(lines, first);
        addLines(lines, second);
        return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
    }

    private static void addLines(LinkedHashSet<String> lines, String value) {
        if (value == null) return;
        value.lines().map(String::strip).filter(line -> !line.isBlank()).forEach(lines::add);
    }

    private static boolean booleanOf(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                return Boolean.parseBoolean(value.asText().strip());
            }
        }
        return false;
    }

    /** 사람이 「무엇으로 왔나」를 아는 한 마디. ⚠ 값을 싣지 않는다 — 모양만 말한다. */
    private static String shapeOf(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return "아예 없다";
        }
        if (node.isObject()) {
            return "객체로 왔다";
        }
        if (node.isArray()) {
            return "배열로 왔다";
        }
        return node.isTextual() ? "문자열로 왔다" : "값 하나로 왔다";
    }

    private String stripFence(String output) {
        if (output == null) {
            return "";
        }
        int opens = output.indexOf('{');
        int closes = output.lastIndexOf('}');
        return opens >= 0 && closes > opens ? output.substring(opens, closes + 1) : output;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    private String orElse(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** ⚠ 제목·화면명 열이 {@code varchar(255)} 다 — 넘치면 DB 가 통째로 거절한다. */
    private String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** {@link #cut(String, int)} 의 널 허용판 — {@code system} 처럼 없어도 되는 값에 쓴다. */
    private String cutOrNull(String value, int max) {
        return value == null ? null : cut(value, max);
    }
}
