package com.bizplay.builder.devrequest;

import org.springframework.stereotype.Component;

/**
 * 화면별 {@code changes.md} — 목업이 말 못 하는 것을 담는다.
 *
 * <p>⭐ <b>마커가 여기 있어야 하는 까닭</b>: 「이 버튼을 여기로」는 변경 목록에 안 적히고
 * 목업 그림만으로도 안 읽힌다. 마커는 <b>어느 요소인지</b>를 들고 있다.
 *
 * <p>⛔ <b>이 파일이 화면 변경의 정본이다.</b> {@code dev-request.md} 는 <b>색인만</b> 적는다 —
 * 둘 다 펼치면 사본 둘이 되고, 갈리는 순간 어느 쪽이 맞는지 아무도 모른다.
 */
@Component
public class ScreenChangeNoteWriter {

    public String write(DevelopmentRequestContent.Screen screen, String standardScreenId) {
        return write(screen, standardScreenId, null, null);
    }

    public String write(DevelopmentRequestContent.Screen screen, String standardScreenId,
                        String developmentFileName, String iaPlacement) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(screen.displayName()).append(" 변경 내용\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| 화면 ID | `").append(screen.deliveryScreenId()).append("` |\n");
        String managementNumber = screen.managementNumber() == null
                ? standardScreenId : screen.managementNumber();
        out.append("| 화면 관리번호 | ").append(value(managementNumber)).append(" |\n");
        out.append("| 파일명 | `").append(screen.deliveryFileName()).append("` |\n");
        if (screen.entryPoint() != null && !screen.entryPoint().isBlank()) {
            out.append("| 진입 안내 | ").append(cell(screen.entryPoint())).append(" |\n");
        }
        out.append("| 시스템 | ").append(value(screen.systemCode())).append(" |\n");
        out.append("| 화면 유형 | ").append(value(screen.screenType())).append(" |\n");
        // ⚠ 비어 있을 수 있다 — 기획자가 메뉴구조도에 아직 안 넣은 신규 화면이다.
        out.append("| 메뉴 경로 | ").append(value(screen.menuPath())).append(" |\n\n");

        out.append("## 변경 내용\n\n");
        if (screen.changes().isEmpty()) {
            out.append("기록된 변경 내용이 없습니다.\n");
        } else {
            screen.changes().forEach(change -> out.append("- ").append(change).append('\n'));
        }

        if (!screen.markers().isEmpty()) {
            out.append("\n## 화면에 표시한 지시\n\n");
            out.append("목업의 특정 요소를 가리키는 지시입니다.\n\n");
            out.append("| 번호 | 요소 | 지시 | 선택자 |\n|---|---|---|---|\n");
            for (var marker : screen.markers()) {
                out.append("| ").append(marker.markerNo())
                        .append(" | ").append(value(marker.elementLabel()))
                        .append(" | ").append(cell(marker.description()))
                        .append(" | `").append(value(marker.selector())).append("` |\n");
            }
        }

        if (!screen.memos().isEmpty()) {
            out.append("\n## 메모\n\n");
            for (var memo : screen.memos()) {
                out.append("- **").append(value(memo.authorName())).append("** — ")
                        .append(cell(memo.content())).append('\n');
            }
        }
        if (!screen.connections().isEmpty()) {
            out.append("\n## 화면 연결 안내\n\n");
            out.append("| 클릭 요소 | 이동 화면 | 방식 | 라벨 | 조건 |\n|---|---|---|---|---|\n");
            for (var connection : screen.connections()) {
                out.append("| `").append(value(connection.anchor())).append("` | `")
                        .append(value(connection.targetScreenId())).append("` | ")
                        .append(value(connection.kind())).append(" | ")
                        .append(cell(connection.label())).append(" | ")
                        .append(cell(connection.condition())).append(" |\n");
            }
        }
        return out.toString();
    }

    private static String value(String raw) {
        return raw == null || raw.isBlank() ? "—" : raw;
    }

    /** ⚠ 표 안에서는 {@code |} 와 줄바꿈이 칸을 깨뜨린다. */
    private static String cell(String raw) {
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        return raw.replace("|", "\\|").replaceAll("\\R", " ");
    }
}
