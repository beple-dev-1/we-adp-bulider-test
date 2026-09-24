package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 꾸러미의 계약서 본문 {@code dev-request.md} 를 만든다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}
 * 「{@code dev-request.md} 에 무엇이 들어가나」. 그 표가 <b>절마다 재료가 어디서 오는지</b> 못 박았고
 * 이 클래스는 그 표를 그대로 옮긴 것이다.
 * ⛔ <b>재료 없는 절을 만들지 마라</b> — 채우려고 지어내게 된다.
 *
 * <p>⚠ <b>설계 문장과 어긋나는 자리가 하나 있다 — 알고 그렇게 뒀다 (2026-09-22 사용자 확정).</b>
 * 그 절의 첫 문장은 「본문은 재생성물이다 — <b>AI가 쓰고</b> 사람이 손으로 안 고친다」인데,
 * 이 클래스는 <b>기계가 렌더</b>한다. 까닭은 <b>절 열 전부에 이미 재료가 있다</b>는 것이다 —
 * 요구사항·성격·완료 조건·확인 필요·백엔드의 근거와 판정 방법은 앞 단계의 AI 가 만들어 DB 에
 * 넣어 둔 것이고, 8절은 같은 절이 「손으로 적지 말고 {@code manifest.json} 에서 만들라」고 했다.
 * 그래서 본문에서 AI 가 더 할 일은 <b>재료를 잇는 산문</b>뿐인데 그것이 곧 위의 ⛔ 다.
 * ⛔ <b>설계 문서는 고치지 않기로 했다</b> — 그 문장을 보고 「AI 로 바꿔야 하나」 싶으면
 * 이 주석을 먼저 읽어라. 바꾸려면 사용자에게 다시 물어야 한다.
 *
 * <p>⛔ <b>8절은 손으로 적지 않는다.</b> {@code manifest.json} 을 읽어 만든다 — 손으로 적으면
 * {@code screens/} 폴더와 갈리고, 갈린 순간 어느 쪽이 맞는지 아무도 모른다.
 * ⛔ <b>화면별 변경 내용을 여기 다시 펼치지 마라</b> — 상세의 정본은
 * {@code screens/<시스템>/<화면ID>/changes.md} 이고 8절은 <b>색인만</b>이다.
 *
 * <p>⚠ <b>4절이 계약의 심장이다.</b> 다툼은 「했다/안 했다」보다 「이건 범위였다/아니었다」에서 난다.
 * 화면({@code dev-request.html})은 그것을 아래쪽 작은 자리에 두는데, md 에서는
 * <b>개발 범위 바로 뒤 큰 절</b>이다.
 *
 * <p>⛔ <b>DB 를 읽지 않는다</b> — 재료를 값으로 받는 순수한 자리다. 스냅샷과 manifest 만 본다.
 */
public class DevRequestDocument {

    /** ⚠ 8절이 파일마다 「무엇인가」를 한 줄로 적는다 — README 를 넣지 않기로 한 대신이다. */
    private static final Map<String, String> FILE_MEANINGS = fileMeanings();

    /**
     * 표지에 적는 것.
     *
     * @param facets    적용 대상. 비어 있으면 표지에 줄을 내지 않는다
     * @param completedOn 개발 완료 희망일 · {@code deployOn} 배포일 — 없으면 「미정」
     */
    public record Meta(String label, String title, String systemCode, List<String> facets,
                       String frdLabel, String ownerName, LocalDate createdOn,
                       LocalDate completedOn, LocalDate deployOn, String plannerComment,
                       String attachmentName, Long attachmentSize) {
    }

    public String render(Meta meta, DevelopmentRequestContent content, Path manifestJson)
            throws IOException {
        JsonNode manifest = new ObjectMapper().readTree(manifestJson.toFile());
        StringBuilder md = new StringBuilder();
        cover(md, meta);
        requestBody(md, content);
        requirements(md, content);
        developmentScope(md, content);
        excludedScope(md, content);
        acceptance(md, content);
        openIssues(md, content);
        backendChanges(md, content);
        screens(md, manifest);
        delivery(md, meta);
        attachments(md, meta);
        returnGuide(md);
        return md.toString();
    }

    private static void cover(StringBuilder md, Meta meta) {
        md.append("# ").append(meta.label()).append(" · ").append(nvl(meta.title())).append("\n\n");
        md.append("| | |\n|---|---|\n");
        row(md, "시스템", nvl(meta.systemCode()));
        if (meta.facets() != null && !meta.facets().isEmpty()) {
            row(md, "적용 대상", String.join(" · ", meta.facets()));
        }
        row(md, "FRD", nvl(meta.frdLabel()));
        row(md, "담당자", nvl(meta.ownerName()));
        row(md, "만든 날", date(meta.createdOn()));
        md.append('\n');
    }

    private static void requestBody(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 1. 요청 내용\n\n");
        /*
         * ⭐ 원문과 인터뷰 요약을 갈라 둔다 (설계 2026-08-26 변경) — 원문은 사람이 붙여넣은 글이고
         *   요약은 승인 전에 확인한 것이다. 섞으면 뒤에 「내가 그렇게 말했나」를 가릴 수 없다.
         */
        md.append("### 요청 원문\n\n").append(nvl(content.summary())).append("\n\n");
        if (content.interviewSummary() != null && !content.interviewSummary().isBlank()) {
            md.append("### 확인한 요구사항 요약\n\n").append(content.interviewSummary()).append("\n\n");
        }
    }

    private static void requirements(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 2. 요구사항 전체\n\n");
        if (content.requirements() == null || content.requirements().isEmpty()) {
            md.append("요구사항이 없습니다.\n\n");
            return;
        }
        md.append("| 순번 | 요구사항 | 성격 | 비고 |\n|---|---|---|---|\n");
        for (var requirement : content.requirements()) {
            md.append("| ").append(requirement.seq())
                    .append(" | ").append(cell(requirement.requirement()))
                    .append(" | ").append(cell(requirement.natureLabel()))
                    .append(" | ").append(cell(requirement.note())).append(" |\n");
        }
        md.append('\n');
    }

    private static void developmentScope(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 3. 개발 범위\n\n");
        list(md, content.developmentRequirements().stream()
                .map(requirement -> "요구사항 " + requirement.seq() + " — " + nvl(requirement.requirement()))
                .toList(), "개발할 것이 없습니다.");
    }

    private static void excludedScope(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 4. 제외 범위\n\n");
        md.append("**이 절이 계약의 경계다.** 아래는 이 요청으로 개발하지 않는다.\n\n");
        boolean any = false;
        if (!content.operationRequirements().isEmpty()) {
            md.append("### 운영 반영 — 기능이 이미 있어 운영자가 바꾼다\n\n");
            excluded(md, content.operationRequirements());
            any = true;
        }
        if (!content.excludedRequirements().isEmpty()) {
            md.append("### 범위 밖 — 이 기획 저장소의 시스템 밖이다\n\n");
            excluded(md, content.excludedRequirements());
            any = true;
        }
        if (!any) {
            md.append("제외한 것이 없습니다 — 요구사항 전부가 개발 범위다.\n\n");
        }
    }

    private static void excluded(StringBuilder md, List<DevelopmentRequestContent.Requirement> items) {
        for (var requirement : items) {
            md.append("- 요구사항 ").append(requirement.seq()).append(" — ")
                    .append(nvl(requirement.requirement()));
            // ⭐ 까닭(note)이 있으면 반드시 함께 싣는다 — 「왜 빠졌나」가 뒤에 다툼이 되는 자리다.
            if (requirement.note() != null && !requirement.note().isBlank()) {
                md.append(" — ").append(requirement.note());
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void acceptance(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 5. 완료 조건\n\n");
        list(md, content.acceptanceCriteria().stream()
                .map(note -> nvl(note.content())).toList(), "완료 조건이 아직 없습니다.");
    }

    /**
     * 6절 — 정한 것. ⭐ 인터뷰가 확인 필요를 남기지 않으므로 개발은 「질문 → 답」만 받는다 (2026-09-24 사용자 확정).
     * 옛 요청서의 확인 필요는 뒤에 그대로 붙인다.
     */
    private static void openIssues(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 6. 정한 것\n\n");
        List<String> lines = new ArrayList<>();
        for (var decision : content.decisions()) {
            String line = decision.question().isEmpty()
                    ? decision.answer() : decision.question() + " → " + decision.answer();
            lines.add(decision.recommended() ? line + " (AI 권장안)" : line);
        }
        for (var note : content.openIssues()) {
            lines.add("확인 필요: " + nvl(note.content()));
        }
        list(md, lines, "따로 정한 것이 없습니다.");
    }

    private static void backendChanges(StringBuilder md, DevelopmentRequestContent content) {
        md.append("## 7. 화면 외 구현\n\n");
        if (content.backendChanges() == null || content.backendChanges().isEmpty()) {
            md.append("화면 외 구현이 없습니다.\n\n");
            return;
        }
        // ⭐ 갈래로 묶어 보인다 — 한 줄씩 늘어놓으면 개발이 무엇을 맡는지가 안 보인다.
        Map<String, List<DevelopmentRequestContent.BackendChange>> grouped = new LinkedHashMap<>();
        for (var change : content.backendChanges()) {
            grouped.computeIfAbsent(nvl(change.categoryLabel()), key -> new java.util.ArrayList<>())
                    .add(change);
        }
        grouped.forEach((category, changes) -> {
            md.append("### ").append(category).append("\n\n");
            for (var change : changes) {
                md.append("- **").append(nvl(change.target())).append("** — ")
                        .append(nvl(change.changeDetail())).append('\n');
                if (change.evidence() != null && !change.evidence().isBlank()) {
                    md.append("  - 근거: ").append(change.evidence()).append('\n');
                }
                /*
                 * ⭐ 「판정 방법」을 항목마다 싣는다 (설계 2026-08-22 확정) — 없으면 개발이
                 *   무엇을 보여야 끝인지 모르고, 돌아온 뒤 「됐다/안 됐다」를 가릴 근거가 없다.
                 */
                if (change.verification() != null && !change.verification().isBlank()) {
                    md.append("  - 판정 방법: ").append(change.verification()).append('\n');
                }
            }
            md.append('\n');
        });
    }

    private static void screens(StringBuilder md, JsonNode manifest) {
        md.append("## 8. 화면별 산출물 목록\n\n");
        JsonNode screens = manifest.get("screens");
        if (screens == null || screens.isEmpty()) {
            // ⚠ 화면 외 구현도 없을 수 있다(SRT) — 「화면 외 구현만 담는다」고 말하면 7절과 어긋난다.
            md.append("화면 변경이 없습니다.\n\n");
            return;
        }
        for (JsonNode screen : screens) {
            md.append("### ").append(screen.path("screenId").asText())
                    .append(" · ").append(screen.path("displayName").asText());
            if (screen.path("newScreen").asBoolean()) {
                md.append(" (신규 화면 — 바뀌기 전 판이 없습니다)");
            }
            md.append("\n\n| 파일 | 무엇 |\n|---|---|\n");
            for (JsonNode file : screen.path("files")) {
                String path = file.path("path").asText();
                md.append("| `").append(path).append("` | ")
                        .append(FILE_MEANINGS.getOrDefault(file.path("kind").asText(), "—"))
                        .append(" |\n");
            }
            md.append('\n');
        }
    }

    private static void delivery(StringBuilder md, Meta meta) {
        md.append("## 9. 전송 정보\n\n| | |\n|---|---|\n");
        row(md, "개발 완료 희망일", date(meta.completedOn()));
        row(md, "배포일", date(meta.deployOn()));
        row(md, "전달사항", meta.plannerComment() == null || meta.plannerComment().isBlank()
                ? "없음" : cell(meta.plannerComment()));
        md.append('\n');
    }

    private static void attachments(StringBuilder md, Meta meta) {
        md.append("## 10. 첨부 목록\n\n");
        if (meta.attachmentName() == null || meta.attachmentName().isBlank()) {
            md.append("첨부가 없습니다.\n");
            return;
        }
        md.append("- `").append(meta.attachmentName()).append('`');
        if (meta.attachmentSize() != null) {
            md.append(" — ").append(meta.attachmentSize() / 1024).append("KB");
        }
        md.append('\n');
    }

    /**
     * 11절 — 돌려받을 것. ⭐ <b>두 계약 파일을 따르라는 안내만 둔다</b>(꾸러미 설계 「돌려받을 것」).
     * ⛔ 돌려보내는 법을 여기에 다시 적지 않는다 — 그 요청의 값은 {@code expected-back.md} 에 있고,
     * 두 곳에 적으면 갈린다.
     */
    private static void returnGuide(StringBuilder md) {
        md.append("## 11. 돌려받을 것\n\n");
        md.append("개발이 끝나면 같은 폴더의 `expected-back.md` 를 따라 돌려보내 주십시오 — 무엇을 어디에"
                + " 어떤 모양으로 돌려주는지가 이 요청의 값으로 적혀 있습니다.\n");
        md.append("기계가 읽는 목록(파일·해시·커밋)은 `manifest.json` 에 있습니다.\n");
    }

    private static Map<String, String> fileMeanings() {
        Map<String, String> meanings = new LinkedHashMap<>();
        meanings.put("as-is-html", "바뀌기 전 화면");
        meanings.put("as-is-md", "바뀌기 전 기능정의서");
        meanings.put("to-be-html", "바뀐 뒤 화면 — 이대로 보여야 한다");
        meanings.put("to-be-md", "바뀐 뒤 기능정의서");
        meanings.put("changes", "그 화면의 변경 목록과 까닭");
        return Map.copyOf(meanings);
    }

    private static void list(StringBuilder md, List<String> items, String whenEmpty) {
        if (items.isEmpty()) {
            // ⚠ 「없다」를 적는다 — 빈 절은 「아직 안 적었다」와 구분되지 않는다.
            md.append(whenEmpty).append("\n\n");
            return;
        }
        items.forEach(item -> md.append("- ").append(item).append('\n'));
        md.append('\n');
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    /** ⚠ 표 칸에 줄바꿈과 파이프가 들어오면 표가 깨진다 — 사람이 적은 글이 여기로 온다. */
    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "\\|").replaceAll("\\R", " ");
    }

    private static String date(LocalDate date) {
        return date == null ? "미정" : date.toString();
    }

    private static String nvl(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
