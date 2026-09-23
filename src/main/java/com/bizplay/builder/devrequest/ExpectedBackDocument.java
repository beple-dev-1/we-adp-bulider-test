package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「돌려받을 것」 계약서 {@code expected-back.md} — <b>사람용 정본</b>이다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md} ·
 * {@code 2026-08-22-dev-request-package-design.md} 「돌려받을 대상만 넣는다」.
 * 기계용 정본은 {@code manifest.json} 의 {@code expectedBack} 이고, <b>둘 다
 * {@link ExpectedBack} 을 그린다</b> — 계산은 그 한 자리에만 있다.
 *
 * <p>⭐ <b>목록이 아니라 표다.</b> 대상마다 <b>어느 구성요소가 필수인가</b>({@code pages} ·
 * {@code screen-md} · {@code index})를 적고, 개발은 필수마다 {@code changed} 또는
 * {@code unchanged} 를 <b>정확히 하나</b> 채운다. 그래야 <b>「누락」과 「봤는데 안 바뀜」이 갈린다</b> —
 * 회신이 끝났다는 신호가 없으면 아무것도 안 보낸 것과 다 봤는데 안 바뀐 것이 같아 보인다.
 * ⚠ {@code unchanged} 는 <b>배치가 찼는지 세는 값</b>이지 실제로 안 바뀌었다는 증명이 아니다.
 * ⛔ 이 둘을 뭉치지 마라 — 완결성 계산과 내용의 참거짓은 다른 물음이다.
 *
 * <p>⭐ <b>테스트 시나리오는 우리가 먼저 적는다</b>(2026-08-27 확정). 개발은 실제 결과·판정·근거만
 * 채운다. 빈 양식으로 보내면 돌아오는 것이 기계가 못 읽는 모양이 된다.
 * ⚠ <b>시나리오가 없어도 계약은 성립한다</b> — 없으면 빈 양식이 그대로 나가고, 전송 전 검증은
 * 차단이 아니라 경고로만 말한다.
 *
 * <p>⛔ <b>DB 를 읽지 않는다</b> — 재료를 값으로 받는 순수한 자리다.
 */
public class ExpectedBackDocument {

    /**
     * 회신서 견본에서 개발이 골라 바꿀 자리.
     * ⭐ 판정기가 모르는 값이라 <b>고르지 않고 그대로 보내면 거절된다</b> — 견본을 그냥 올린 것이
     * 통과로 세어지면 「누락」과 「봤는데 안 바뀜」이 다시 뭉친다.
     */
    public static final String PICK = "changed 또는 unchanged";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** ⚠ 줄바꿈을 {@code \n} 으로 못 박는다 — 기본값은 윈도우에서 {@code \r\n} 이라 문서가 섞인다. */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    /** @param label {@code DR-009} 꼴. 나머지 재료는 {@link ExpectedBack} 이 갖는다. */
    public record Meta(String label) {
    }

    /**
     * ⛔ <b>여기서 「무엇이 필수인가」를 다시 계산하지 마라.</b> {@link ExpectedBack} 이 그 한 자리이고
     * {@code manifest.expectedBack} 도 같은 것을 그린다 — 두 곳에서 계산하면 개발이 본 표와
     * 우리가 검사하는 목록이 갈린다.
     */
    public String render(Meta meta, ExpectedBack back, DevelopmentRequestContent content) {
        StringBuilder md = new StringBuilder();
        head(md, meta, back);
        howToReturn(md, meta, back);
        screens(md, back);
        backend(md, content);
        unitTests(md, content);
        integrationTests(md, content);
        return md.toString();
    }

    private static void head(StringBuilder md, Meta meta, ExpectedBack back) {
        line(md, "# " + meta.label() + " · 돌려받을 것");
        line(md, "");
        line(md, "개발이 끝나면 아래 **「돌려보내는 법」대로** 돌려보내 주십시오. 이 문서는 무엇을 돌려받을지");
        line(md, "알리는 안내서이고, 빌더가 읽는 것은 그 절에 적힌 파일들입니다.");
        line(md, "");
        line(md, "| | |");
        line(md, "|---|---|");
        line(md, "| 돌려보낼 브랜치 | `" + back.returnBranch() + "` |");
        /*
         * ⭐ base 를 적는 자리가 여기다. 받는 쪽은 기준이 어긋나면 배치를 통째로 거절하는데,
         *   그 기준값을 개발이 알 길이 없으면 매번 헛걸음한다.
         */
        line(md, "| 갈라 올 기준 커밋 | `" + back.base() + "` |");
        line(md, "");
        line(md, "⛔ **기준 커밋에서 갈라 주십시오.** 다른 자리에서 갈라 오면 그 사이의 남의 변경을 덮게 되어");
        line(md, "**배치를 통째로 거절**합니다. 거절되면 그 대상만 새 기준으로 다시 만들어 보내 주십시오 —");
        line(md, "전체를 다시 만들 필요는 없습니다.");
        line(md, "");
        line(md, "⛔ **전달 브랜치 위에 얹지 마십시오.** 거기에는 기획이 그린 화면(to-be)이 들어 있어");
        line(md, "그 위에서 갈라 오면 그것이 **사실인 척** 섞여 들어옵니다.");
        line(md, "");
    }

    /**
     * 「돌려보내는 법」 — 빌더가 <b>실제로 읽는 파일</b>의 자리와 회신서 견본.
     *
     * <p>⭐ 이 절이 없던 판(2026-09-23 실물 시험)은 「이 문서의 칸을 채워 돌려보내라」만 적었다.
     * 빌더는 이 문서를 안 읽으므로 그대로 따르면 매번 「회신서가 없다」로 거절된다.
     *
     * <p>⛔ <b>경로를 여기서 짓지 마라</b> — 받는 자리({@link ReturnBatch})의 것을 그대로 옮긴다.
     * 글로 따로 적으면 한쪽만 고쳐지고, 그때 개발은 문서대로 했는데 거절을 받는다.
     */
    private static void howToReturn(StringBuilder md, Meta meta, ExpectedBack back) {
        String label = meta.label();
        line(md, "## 돌려보내는 법");
        line(md, "");
        line(md, "⛔ 빌더는 **이 문서를 읽지 않습니다.** 아래 표의 자리에 놓인 파일만 읽습니다.");
        line(md, "");
        line(md, "1. 기획 저장소 기본 브랜치의 기준 커밋 `" + back.base() + "` 에서 `"
                + back.returnBranch() + "` 브랜치를 땁니다.");
        line(md, "2. 아래 표의 자리에 파일을 놓고 그 브랜치에 커밋합니다.");
        line(md, "3. 그 브랜치를 기획 저장소에 올리고 기획에 알려 주십시오. 기획이 「개발 결과 받기」를 누르면");
        line(md, "   빌더가 판정해 받습니다. 거절되면 사유가 기획 화면에 뜹니다.");
        line(md, "");
        line(md, "| 무엇 | 자리 | 언제 |");
        line(md, "|---|---|---|");
        for (ExpectedBack.Screen screen : back.screens()) {
            for (String part : screen.required()) {
                line(md, "| `" + screen.screenId() + "` " + partName(part) + " | `"
                        + ReturnBatch.pathOf(screen, part) + "` | 회신서에 `" + part
                        + "` 를 `changed` 로 적었을 때만 |");
            }
        }
        line(md, "| 회신서 | `" + label + "/" + ReturnBatch.FILE + "` | 늘 — 없으면 통째로 거절합니다 |");
        line(md, "| 단위테스트 결과 | `" + label + "/" + ReturnBatch.UNIT_TESTS
                + "` | 3절 표를 채워서 |");
        line(md, "| 통합테스트 결과 | `" + label + "/" + ReturnBatch.INTEGRATION_TESTS
                + "` | 4절 표를 채워서 |");
        line(md, "");
        line(md, "⚠ 회신서와 테스트 결과는 기본 브랜치에 들어가지 않습니다 — 빌더가 읽어 판정하고, 테스트 결과는");
        line(md, "빌더에 담습니다. 테스트 결과 표는 **앞의 칸을 고치지 말고** 실제 결과·판정·근거만 채워 주십시오.");
        line(md, "");
        line(md, "회신서 견본입니다. `" + PICK + "` 를 칸마다 **하나로 바꿔** 주십시오 —");
        line(md, "그대로 두면 모르는 값으로 거절합니다. 뜻은 1절에 있습니다.");
        line(md, "");
        line(md, "```json");
        line(md, returnTemplate(label, back));
        line(md, "```");
        line(md, "");
    }

    /**
     * ⭐ 판정기({@link ReturnBatch#judge})가 읽는 꼴 그대로다 — 칸만 고르면 지난다.
     * ⚠ 필수 구성요소 목록은 {@link ExpectedBack} 의 것을 그대로 쓴다. 보호 화면이면 화면 md 칸이 없다.
     */
    private static String returnTemplate(String label, ExpectedBack back) {
        List<Map<String, String>> screens = new ArrayList<>();
        for (ExpectedBack.Screen screen : back.screens()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("screenId", screen.screenId());
            screen.required().forEach(part -> row.put(part, PICK));
            screens.add(row);
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("dr", label);
        template.put("base", back.base());
        template.put("screens", screens);
        try {
            return JSON.writer(PRETTY).writeValueAsString(template);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("회신서 견본을 만들지 못했습니다.", impossible);
        }
    }

    private static String partName(String part) {
        return switch (part) {
            case ExpectedBack.PAGES -> "화면(html)";
            case ExpectedBack.SCREEN_MD -> "화면 md";
            case ExpectedBack.INDEX -> "색인";
            default -> part;
        };
    }

    private static void screens(StringBuilder md, ExpectedBack back) {
        line(md, "## 1. 화면 — 실제로 만든 것을 돌려주십시오");
        line(md, "");
        if (back.screens().isEmpty()) {
            line(md, "이 요청에는 화면 변경이 없습니다.");
            line(md, "");
            return;
        }
        line(md, "필수 구성요소마다 `changed` 또는 `unchanged` 를 **정확히 하나** 적어 주십시오.");
        line(md, "⚠ `unchanged` 는 「보았고 바꿀 것이 없었다」는 뜻입니다 — 안 본 것과 다릅니다.");
        line(md, "");
        line(md, "| 화면ID | 시스템 | 필수 구성요소 | 채울 칸 |");
        line(md, "|---|---|---|---|");
        boolean anyGuarded = false;
        for (ExpectedBack.Screen screen : back.screens()) {
            anyGuarded |= !screen.expectsScreenMd();
            String required = join(screen.required(), part -> "`" + part + "`", " · ");
            String blanks = join(screen.required(), part -> part + " ____", " / ");
            line(md, "| `" + screen.screenId() + "` | " + nvl(screen.systemCode())
                    + " | " + required + " | " + blanks + " |");
        }
        line(md, "");
        if (anyGuarded) {
            line(md, "⚠ **`screen-md` 가 빠진 화면은 사람이 손댄 화면입니다.** 그 화면의 화면 md 는 받지");
            line(md, "않습니다 — 보내 주셔도 반영하지 않습니다. 화면(html)과 색인만 돌려주십시오.");
            line(md, "");
        }
        line(md, "⛔ **`ia.md`(메뉴구조도)는 받지 않습니다.**");
        line(md, "");
    }

    private static void backend(StringBuilder md, DevelopmentRequestContent content) {
        line(md, "## 2. 화면 외 구현 — 무엇을 어떻게 했는지 적어 주십시오");
        line(md, "");
        if (content.backendChanges() == null || content.backendChanges().isEmpty()) {
            line(md, "이 요청에는 화면 외 구현이 없습니다.");
            line(md, "");
            return;
        }
        /*
         * ⭐ 대상이 domains/<도메인>/<모듈>.md 꼴이 아니어도 목록에 남긴다 (설계 2026-08-25 교정).
         *   꼴로 거르면 「배치 서버 설정」 같은 대상이 회신 목록에서 조용히 사라진다.
         */
        line(md, "| 대상 | 요청한 변경 | 실제 구현 | 판정 | 근거 |");
        line(md, "|---|---|---|---|---|");
        for (var change : content.backendChanges()) {
            line(md, "| `" + nvl(change.target()) + "` | " + cell(change.changeDetail()) + " |  |  |  |");
        }
        line(md, "");
        line(md, "⚠ 도메인 문서(`domains/…`)를 고쳐 보내실 때는 **통째로 갈아 끼우지 마십시오.**");
        line(md, "그 파일에는 사람이 적은 문장이 섞여 있어 통째 덮기로는 잃습니다 — 병합기를 지난");
        line(md, "결과만 보내 주십시오.");
        line(md, "");
    }

    private static void unitTests(StringBuilder md, DevelopmentRequestContent content) {
        line(md, "## 3. 단위테스트 — 화면 외 구현 항목마다");
        line(md, "");
        scenarioTable(md, content.testScenarios().stream()
                .filter(DevelopmentRequestContent.TestScenario::isUnit).toList());
    }

    private static void integrationTests(StringBuilder md, DevelopmentRequestContent content) {
        line(md, "## 4. 통합테스트 — 완료 조건마다");
        line(md, "");
        // ⭐ 완료 조건 ↔ TC 대응표가 절 머리에 앉는다 (설계 2026-08-27).
        var criteria = content.acceptanceCriteria();
        if (!criteria.isEmpty()) {
            line(md, "| 완료 조건 | TC |");
            line(md, "|---|---|");
            for (int i = 0; i < criteria.size(); i++) {
                String ids = join(content.integrationScenarios(i + 1).stream()
                        .map(DevelopmentRequestContent.TestScenario::id).toList(), id -> id, " · ");
                line(md, "| " + cell(criteria.get(i).content()) + " | "
                        + (ids.isBlank() ? "—" : ids) + " |");
            }
            line(md, "");
        }
        scenarioTable(md, content.testScenarios().stream()
                .filter(scenario -> !scenario.isUnit()).toList());
    }

    private static void scenarioTable(StringBuilder md,
                                      List<DevelopmentRequestContent.TestScenario> scenarios) {
        if (scenarios.isEmpty()) {
            // ⚠ 시나리오가 없어도 계약은 성립한다 — 빈 양식이 그대로 나간다.
            line(md, "아직 시나리오가 없습니다. 개발에서 검증한 항목을 같은 서식으로 적어 주십시오 —");
            line(md, "`TC-번호` · 의존 · 조건 · 행위 · 기대 결과 · **실제 결과** · **판정** · **근거**.");
            line(md, "");
            return;
        }
        line(md, "| TC | 무엇을 보나 | 의존 | 조건 | 행위 | 기대 결과 | 실제 결과 | 판정 | 근거 |");
        line(md, "|---|---|---|---|---|---|---|---|---|");
        for (var scenario : scenarios) {
            line(md, "| " + nvl(scenario.id())
                    + " | " + cell(scenario.title())
                    + " | " + cell(scenario.dependency())
                    + " | " + cell(scenario.condition())
                    + " | " + cell(scenario.action())
                    + " | " + cell(scenario.expected())
                    + " |  |  |  |");
        }
        line(md, "");
        line(md, "**실제 결과·판정·근거만 채워 주십시오.** 앞의 칸은 고치지 마십시오 —");
        line(md, "고치면 어느 것을 검증했는지 짝이 어긋납니다.");
        line(md, "");
    }

    private static void line(StringBuilder md, String text) {
        md.append(text).append(System.lineSeparator().equals("\r\n") ? "\n" : "\n");
    }

    private static <T> String join(List<T> items, java.util.function.Function<T, String> render,
                                   String separator) {
        return items.stream().map(render).reduce((a, b) -> a + separator + b).orElse("");
    }

    /** ⚠ 표 칸에 줄바꿈과 파이프가 들어오면 표가 깨진다 — 사람이 적은 글이 여기로 온다. */
    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "\\|").replaceAll("\\R", " ");
    }

    private static String nvl(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
