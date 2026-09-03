package com.bizplay.builder.solution;

import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.PlanningManifestReader.ManifestSystem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 미리보기를 내보내기 직전에 <b>기관 스킨 폴더를 갈아끼운다.</b>
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-preview-skin-design.md}.
 * 계약은 추출기 회신 #5(추출기 {@code 80530f9} · 기획 레포 {@code 568586b})가 준 것이고,
 * 경계는 그쪽 실측이 그었다 — <b>마크업이 갈리면 추출기(갈래 목업), 스타일만 갈리면 빌더(여기).</b>
 * g2c 웹뷰 252장은 공통 목업 한 장뿐이라 <b>렌더 시점에 갈지 않으면 제주로 볼 길이 없다.</b>
 *
 * <p>절차 셋. 이대로 하면 결정론이다.
 * <ol>
 *   <li>{@code href}·{@code src} 를 <b>목업이 있는 폴더 기준으로 풀어</b> 저장소 뿌리 기준 경로로 만든다</li>
 *   <li>그 경로가 어느 기관의 스킨 폴더 아래면(경계는 {@code /}) 그 접두를 <b>고른 기관의 폴더</b>로 바꾼다</li>
 *   <li>목업 폴더 기준으로 <b>다시 상대화</b>해 링크에 쓴다</li>
 * </ol>
 *
 * <p>⭐ <b>색인의 {@code screens[].skin} 을 읽지 않는다.</b> 「지금 어느 기관인가」는 링크 실물이
 * 이미 말한다 — 한 목업이 부르는 기관 스킨은 많아야 하나임을 그쪽 {@code SKIN-1} red 가 보증한다.
 * 그래서 <b>「칸이 없으면 전환 대상이 아니다」가 저절로 지켜진다</b>: 걸릴 링크가 없으면 손댈 것도 없다.
 *
 * <p>⛔ <b>보증하지 않는 것을 보증하는 척하지 마라.</b> 인라인 {@code <style>} 의 {@code url()} 과
 * {@code srcset} 은 <b>안 본다</b>(추출기 검사기와 같은 눈이다). 스킨 폴더 <b>밖</b>에서 기관 그림을
 * 직접 부르는 목업 둘({@code wv-modal-history-card}·{@code wv-modal-store-pay-info})은
 * css 를 갈아도 안 갈린다 — 그쪽 {@code SKIN-3} review 가 그 이름을 부른다.
 *
 * <p>⛔ <b>저장본을 고치지 않는다.</b> 치환은 응답에서만 한다. 클론의 목업 파일과 DB 의 초안 html 은
 * 한 글자도 안 바뀐다 — 기획 레포로 밀 때 우리 손자국이 섞이면 안 된다.
 */
@Component
public class SkinRewriter {

    /**
     * 요소의 {@code href}·{@code src} 만 본다.
     *
     * <p>⚠ 따옴표 두 가지를 다 받는다 — 추출 목업은 큰따옴표를 쓰지만 AI 초안이 작은따옴표를
     * 낼 수 있고, 그때 조용히 안 갈리면 <b>「제주로 봤는데 익산이 나오는」</b> 자리가 된다.
     */
    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b(href|src)\\s*=\\s*([\"'])([^\"']*)\\2");

    /** 이 레포의 사실이 아닌 주소들. 건드리면 남의 사이트를 가리키게 된다. */
    private static final List<String> OUTSIDE = List.of(
            "http://", "https://", "//", "/", "#", "data:", "javascript:", "mailto:", "tel:");

    private final PlanningManifestReader manifests;

    public SkinRewriter(PlanningManifestReader manifests) {
        this.manifests = manifests;
    }

    /**
     * 목업 한 장을 고른 기관으로 그린다.
     *
     * @param pageDirInRepo 그 html 이 사는 폴더. <b>저장소 뿌리 기준</b>이고 {@code /} 로 잇는다
     *                      (예: {@code core/webview/pages})
     * @param facet         고른 기관. <b>{@code null}·빈값이면 아무것도 안 한다</b> — 기본 기관을
     *                      지어내면 방향이 반대인 시스템이 통째로 틀린다(g2c {@code online-pg})
     */
    public String draw(String projectId, String pageDirInRepo, String facet, String html) {
        return rewrite(manifests.systems(projectId), pageDirInRepo, facet, html);
    }

    // ── 기계 ──────────────────────────────────────────────────────────────

    static String rewrite(List<ManifestSystem> systems, String pageDirInRepo, String facet, String html) {
        if (html == null || html.isBlank() || facet == null || facet.isBlank()) {
            return html;
        }
        List<Swap> swaps = swaps(systems, facet.strip());
        if (swaps.isEmpty()) {
            return html;
        }
        String from = normalizeDir(pageDirInRepo);

        Matcher link = LINK.matcher(html);
        StringBuilder drawn = new StringBuilder();
        while (link.find()) {
            String replaced = swap(link.group(3), from, swaps);
            link.appendReplacement(drawn, Matcher.quoteReplacement(
                    "%s=%s%s%s".formatted(link.group(1), link.group(2), replaced, link.group(2))));
        }
        link.appendTail(drawn);
        return drawn.toString();
    }

    /**
     * 갈아끼울 짝들 — {@code 어느 기관의 폴더 → 고른 기관의 폴더}.
     *
     * <p>⚠ <b>시스템을 가려내지 않고 전부 모은다.</b> 각 시스템의 스킨 폴더는 자기 {@code assets}
     * 아래에 있어 뿌리 기준 경로로는 서로 안 부딪힌다 — 그래서 「이 화면이 어느 시스템인가」를
     * 따로 알 필요가 없다. <b>아는 것을 줄이면 틀릴 자리도 준다.</b>
     */
    private static List<Swap> swaps(List<ManifestSystem> systems, String facet) {
        List<Swap> found = new ArrayList<>();
        for (ManifestSystem system : systems) {
            Map<String, String> skins = system.skins();
            String target = skins.get(facet);
            if (target == null) {
                // ⛔ 없는 스킨을 지어내지 않는다 — portal 은 제주만 실재한다.
                continue;
            }
            skins.forEach((otherFacet, folder) -> {
                if (!otherFacet.equals(facet) && !folder.equals(target)) {
                    found.add(new Swap(folder, target));
                }
            });
        }
        return found;
    }

    private static String swap(String raw, String pageDirInRepo, List<Swap> swaps) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return raw;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String outside : OUTSIDE) {
            if (lower.startsWith(outside)) {
                return raw;
            }
        }
        // 질의문자열과 조각은 판단에서 빼고 도로 붙인다 — 실물 목업이 ?ver=3.4 를 달고 부른다.
        int tailAt = indexOfAny(value, '?', '#');
        String path = tailAt < 0 ? value : value.substring(0, tailAt);
        String tail = tailAt < 0 ? "" : value.substring(tailAt);
        if (path.isEmpty()) {
            return raw;
        }

        String resolved = resolve(pageDirInRepo, path);
        for (Swap swap : swaps) {
            if (!under(resolved, swap.from())) {
                continue;
            }
            String swapped = swap.to() + resolved.substring(swap.from().length());
            return relativize(pageDirInRepo, swapped) + tail;
        }
        return raw;
    }

    /** {@code core/webview/pages} + {@code ../assets/css/iks/x.css} → {@code core/webview/assets/css/iks/x.css} */
    private static String resolve(String dir, String path) {
        List<String> parts = new ArrayList<>(List.of(dir.isEmpty() ? new String[0] : dir.split("/")));
        for (String piece : path.split("/")) {
            if (piece.isEmpty() || piece.equals(".")) {
                continue;
            }
            if (piece.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(piece);
        }
        return String.join("/", parts);
    }

    /** 목업 폴더 기준으로 되돌린다. 폴더가 그대로이므로 모양은 원문과 같고 스킨 마디만 갈린다. */
    private static String relativize(String dir, String target) {
        String[] from = dir.isEmpty() ? new String[0] : dir.split("/");
        String[] to = target.split("/");
        int same = 0;
        while (same < from.length && same < to.length && from[same].equals(to[same])) {
            same++;
        }
        StringBuilder path = new StringBuilder();
        for (int up = same; up < from.length; up++) {
            path.append("../");
        }
        for (int down = same; down < to.length; down++) {
            path.append(to[down]);
            if (down < to.length - 1) {
                path.append('/');
            }
        }
        return path.isEmpty() ? "." : path.toString();
    }

    /** 경계는 {@code /} 다 — {@code css/iks2} 가 {@code css/iks} 로 잘못 걸리면 안 된다. */
    private static boolean under(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder + "/");
    }

    private static int indexOfAny(String value, char one, char other) {
        int at = value.indexOf(one);
        int too = value.indexOf(other);
        if (at < 0) {
            return too;
        }
        return too < 0 ? at : Math.min(at, too);
    }

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return "";
        }
        return dir.strip().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private record Swap(String from, String to) {
    }
}
