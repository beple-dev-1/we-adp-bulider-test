package com.bizplay.builder.design;

import com.bizplay.builder.project.PlanningManifestReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code core/<시스템>/shell.md} — <b>셸·공용 조각 계약서</b>를 읽는 한 자리.
 *
 * <p>⭐ <b>사이드바와 헤더 축이 여기서 선다.</b> 2026-08-22 실측으로 「목업 629장에 사이드바
 * 마크업 0건」이었는데, 추출기가 2026-08-24 판에 이 파일을 보냈다 — 운영 소스의 셸 조각을
 * <b>그대로</b> 옮긴 것이다.
 *
 * <p>⚠ <b>{@code th:*} 를 걷어내지 않는다.</b> 추출기가 안 걷은 이유를 그대로 받는다 —
 * 「무엇을 걷어냈나」가 판단이 되고 그 판단이 굳는다. 브라우저에서 {@code th:*} 는 그냥
 * 무시되는 속성이라 <b>그리는 데 문제가 없다.</b>
 *
 * <p>⛔ <b>빈 자리를 채우지 마라.</b> {@code th:each} 로 도는 메뉴는 정적으로 그리면 라벨이
 * 빈다. 그 자리에 「관리」·「통계」 같은 예시를 넣으면 <b>소스에 없는 화면</b>을 만드는 것이다.
 * 비어 있는 것을 「데이터에서 오는 자리」로 <b>표시</b>하는 것까지가 우리 몫이다.
 *
 * <p>⛔ <b>조각 이름을 코드가 판단할 때 g2c 를 알지 않게 한다.</b> 파일 이름
 * ({@code left}·{@code header})으로만 가른다 — 그건 소스가 스스로 적어 둔 이름이고
 * 우리가 「어느 기관이 어느 폴더」 같은 사업 지식을 쥐는 것과는 다르다.
 */
@Component
public class ShellFragmentReader {

    private static final Logger log = LoggerFactory.getLogger(ShellFragmentReader.class);

    /** {@code ## <원본 경로>} 로 시작하는 절 하나. 다음 {@code ##} 이나 문서 끝까지다. */
    private static final Pattern SECTION =
            Pattern.compile("(?m)^## (\\S+\\.html)\\s*$(.*?)(?=^## |\\z)", Pattern.DOTALL);

    /** 그 절의 「소스 그대로」 블록. */
    private static final Pattern HTML_BLOCK =
            Pattern.compile("```html\\s*\\n(.*?)```", Pattern.DOTALL);

    private final PlanningManifestReader manifests;

    public ShellFragmentReader(PlanningManifestReader manifests) {
        this.manifests = manifests;
    }

    /**
     * 그 시스템의 조각 전수. 선언이나 파일이 없으면 <b>빈 목록</b>이다.
     *
     * <p>⚠ 순서는 파일에 적힌 순서를 그대로 둔다 — 추출기가 정렬해 굽는다.
     */
    public List<ShellFragment> read(String projectId, String system) {
        Optional<Path> file = manifests.shellFile(projectId, system);
        if (file.isEmpty() || !Files.isRegularFile(file.get())) {
            log.info("shell 계약서가 없다 projectId={} system={}", projectId, system);
            return List.of();
        }
        String body;
        try {
            body = Files.readString(file.get());
        } catch (IOException | RuntimeException unreadable) {
            log.info("shell 계약서를 읽지 못했다 projectId={} system={}", projectId, system);
            return List.of();
        }
        List<ShellFragment> found = new ArrayList<>();
        Matcher section = SECTION.matcher(body);
        while (section.find()) {
            String sourcePath = section.group(1);
            Matcher html = HTML_BLOCK.matcher(section.group(2));
            if (!html.find()) {
                // 「소스 그대로」 블록이 없는 절은 조각이 아니다 — 지어내지 않는다.
                continue;
            }
            found.add(new ShellFragment(sourcePath, html.group(1).strip()));
        }
        return List.copyOf(found);
    }

    /** 그 시스템에서 이 갈래에 해당하는 조각들. */
    public List<ShellFragment> of(String projectId, String system, Kind kind) {
        return read(projectId, system).stream().filter(fragment -> fragment.kind() == kind).toList();
    }

    /**
     * 조각의 갈래.
     *
     * <p>⚠ <b>파일 이름으로만 가른다.</b> 소스가 스스로 붙인 이름이고, 모르는 이름은
     * {@link #OTHER} 로 남긴다 — 버리지 않는다.
     */
    public enum Kind {
        SIDEBAR, HEADER, FOOTER, LAYOUT, OTHER;

        static Kind of(String fileName) {
            String name = fileName.toLowerCase();
            if (name.startsWith("left")) return SIDEBAR;
            if (name.startsWith("header") || name.startsWith("top") || name.startsWith("head")) return HEADER;
            if (name.startsWith("footer")) return FOOTER;
            if (name.startsWith("layout")) return LAYOUT;
            return OTHER;
        }
    }

    /**
     * 조각 하나.
     *
     * @param sourcePath 운영 소스에서의 자리. <b>화면에 그대로 보여준다</b> — 개발자가 그것으로
     *                   실물을 찾는다
     * @param html       소스 그대로의 마크업. {@code th:*} 가 남아 있다
     */
    public record ShellFragment(String sourcePath, String html) {

        public String fileName() {
            int cut = sourcePath.lastIndexOf('/');
            return cut < 0 ? sourcePath : sourcePath.substring(cut + 1);
        }

        public Kind kind() {
            return Kind.of(fileName());
        }

        /**
         * 같은 갈래가 여럿일 때 사람이 가릴 이름 — 경로의 <b>끝 두세 마디</b>다.
         *
         * <p>⚠ 웹뷰는 헤더가 <b>넷</b>이다({@code inc/iks/header} · {@code inc/iks/store/header} ·
         * {@code inc/tnj/…}). 파일 이름만 보이면 넷이 구별되지 않는다 — 그래서 앞 마디를 같이 낸다.
         * ⛔ 그 마디에서 기관을 <b>추측하지 않는다</b>. 우리가 {@code iks} 를 익산으로 읽으면
         * 그 순간 빌더가 g2c 를 아는 것이 된다.
         */
        public String label() {
            String[] parts = sourcePath.split("/");
            int from = Math.max(0, parts.length - 3);
            return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
        }

        /** {@code <body>} 안쪽만. 조각을 우리 틀에 끼워 그릴 때 쓴다. */
        public String bodyInner() {
            String html = this.html;
            int bodyOpen = html.indexOf("<body");
            if (bodyOpen >= 0) {
                int after = html.indexOf('>', bodyOpen);
                int bodyClose = html.lastIndexOf("</body>");
                if (after > 0 && bodyClose > after) {
                    return html.substring(after + 1, bodyClose).strip();
                }
            }
            // body 가 없는 조각도 있다(head 조각 하나). 그때는 통째로 낸다.
            return html;
        }
    }
}
