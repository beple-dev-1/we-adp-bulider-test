package com.bizplay.builder.ia;

import com.bizplay.builder.solution.SolutionScreen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 색인에서 메뉴구조도 트리를 조립한다. <b>순수 함수라 시험이 여기를 직접 잰다.</b>
 *
 * <p>⭐ <b>재료가 셋이다 (2026-08-21 병주 확정)</b> —
 * ① {@code 경로}(색인의 {@code ia.경로}, 실측 2마디) ② {@code 상위화면} 사슬(목록 → 상세)
 * ③ {@code 여는화면} 사슬(상세 → 팝업·모달). 여기에 현재 화면을 마지막 마디로 붙인다.
 * 현재 화면을 빼면 상세 화면 ID가 부모 목록 이름에 연결되어 한 단계 밀린다.
 *
 * <p>⛔ <b>{@code ia.md} 의 {@code --- 배치 ---} 를 뎁스 재료로 쓰지 않는다.</b> 그것은 색인보다
 * 좁다 — 백오피스 실측에서 <b>배치 82줄 대 색인 240장</b>이었고, 그래서 종전 메뉴구조도에는
 * 화면 셋 중 하나만 들어와 있었다. {@code ia.md} 는 <b>한글 이름표의 정본으로만</b> 남는다
 * (그 파일은 사람이 고치는 문서라 색인이 대신할 수 없다).
 *
 * <p>⚠ <b>「솔직히 IA 느낌은 아니다」</b> — 팝업이 메뉴 마디가 되는 것을 알고 고른 것이다.
 * 사람이 화면을 찾는 길이 실제로 그러하기 때문이다(상세를 열고 그 안에서 팝업을 띄운다).
 *
 * <p>⛔ <b>지어내 붙이지 않는다.</b> 자리를 못 얻은 화면과 일곱 마디를 넘는 화면은
 * <b>빼고 까닭을 남긴다</b> — 억지 자리를 주면 「모른다」가 아니라 <b>틀린 정보</b>가 되고,
 * 나중에 진짜 경로가 와도 아무도 안 고친다.
 */
public final class IaTreeBuilder {

    /** DB 가 담는 뎁스 칸 수({@code depth1}~{@code depth7}). ⛔ 늘리려면 마이그레이션이 먼저다. */
    public static final int MAX_DEPTH = 7;

    private IaTreeBuilder() {
    }

    /**
     * @param screens 한 시스템의 화면 전부
     * @param labels  {@code ia.md} 의 {@code ## 이름표} — 열쇠는 <b>경로 접두사</b>
     *                ({@code bizcard} · {@code bizcard/delivery})
     */
    public static Tree of(Collection<SolutionScreen> screens, Map<String, String> labels) {
        Map<String, SolutionScreen> byId = new LinkedHashMap<>();
        for (SolutionScreen screen : screens) byId.put(screen.screenId(), screen);

        List<IaDocumentCodec.Placement> placements = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<Seat> seats = new ArrayList<>();

        for (SolutionScreen screen : byId.values()) {
            if (screen.shared()) {
                // ⛔ 공용 화면을 한 자리에 못 박지 않는다. 여러 자리에서 열리므로 한 곳을 고르면
                //    「모른다」가 아니라 틀린 정보가 된다 — 화면이 「공용 화면」 목록으로 따로 보여 준다.
                skipped.put(screen.screenId(), "여러 자리에서 열리는 공용 화면이라 트리에 앉히지 않았다");
                continue;
            }
            List<SolutionScreen> chain = chainOf(screen, byId);
            if (chain == null) {
                skipped.put(screen.screenId(), "상위 사슬이 자기 자신으로 돈다");
                continue;
            }
            Seat seat = seatOf(chain, labels);
            if (seat == null) {
                skipped.put(screen.screenId(), "색인에 경로가 없고 조상에서도 물려받지 못했다");
                continue;
            }
            if (seat.depths().size() > MAX_DEPTH) {
                skipped.put(screen.screenId(),
                        "뎁스가 %d 로 담을 수 있는 %d 를 넘었다".formatted(seat.depths().size(), MAX_DEPTH));
                continue;
            }
            seats.add(seat);
        }

        // ⚠ 순서를 경로 → 화면ID 로 못 박는다. 같은 클론을 두 번 가져오면 같은 순번이 나와야
        //   사람이 두 판을 대조할 수 있다(채번의 정렬과 같은 규칙이다).
        seats.sort(Comparator.comparing(Seat::pathKey).thenComparing(Seat::screenId));
        int order = 1;
        for (Seat seat : seats) {
            placements.add(new IaDocumentCodec.Placement(order++, seat.pathKey(), seat.screenId(), seat.depths()));
        }
        return new Tree(List.copyOf(placements), Map.copyOf(skipped));
    }

    /**
     * 자기부터 조상 순으로 사슬을 만든다. <b>돌면 널이다.</b>
     *
     * <p>⚠ {@code 상위화면} 이 먼저다 — 팝업·모달은 그것이 없고 {@code 여는화면} 을 갖는다.
     * 여는화면이 여럿이면 <b>첫째만 쓴다</b>: 트리는 자리가 하나여야 하고, 색인이 사전순으로
     * 이미 정렬해 내주므로 같은 클론에서 같은 답이 나온다.
     */
    private static List<SolutionScreen> chainOf(SolutionScreen screen, Map<String, SolutionScreen> byId) {
        List<SolutionScreen> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        SolutionScreen current = screen;
        while (current != null) {
            if (!seen.add(current.screenId())) return null;
            chain.add(current);
            current = byId.get(parentIdOf(current));
        }
        return chain;
    }

    private static String parentIdOf(SolutionScreen screen) {
        if (screen.parentScreenId() != null && !screen.parentScreenId().isBlank()) {
            return screen.parentScreenId();
        }
        List<String> openedBy = screen.openingScreenIds();
        return openedBy == null || openedBy.isEmpty() ? null : openedBy.get(0);
    }

    /**
     * 사슬에서 자리를 뽑는다. 경로를 가진 <b>가장 가까운 조상</b>이 뿌리이고,
     * 그 조상부터 바로 위 부모까지가 마디로 선다.
     *
     * <p>⚠ 경로를 준 조상 <b>자신도 마디가 된다</b>. 목록 화면이 자기 자리에 앉으면서
     * 그 아래 상세·팝업의 부모 노릇도 하기 때문이다.
     */
    private static Seat seatOf(List<SolutionScreen> chain, Map<String, String> labels) {
        // ⛔ 자기 경로를 먼저 집으면 안 된다 — 상세도 목록과 같은 경로를 갖고 있어서
        //    그러면 부모 사슬이 통째로 사라진다(2026-08-21 시험이 잡았다).
        //    ⚠ 뿌리는 「경로를 가진 가장 위 조상」이다. 그것이 메뉴에 걸린 자리다.
        int root = -1;
        for (int index = chain.size() - 1; index >= 0; index--) {
            String path = chain.get(index).iaPath();
            if (path != null && !path.isBlank()) {
                root = index;
                break;
            }
        }
        if (root < 0) return null;

        String basePath = chain.get(root).iaPath().strip();
        List<String> depths = new ArrayList<>(labelsOf(basePath, labels));
        List<String> keys = new ArrayList<>(Arrays.asList(basePath.split("/")));
        // 조상은 「위에서 아래로」 붙는다 — 사슬은 자기부터라 거꾸로 돈다.
        for (int index = root; index >= 1; index--) {
            SolutionScreen ancestor = chain.get(index);
            keys.add(ancestor.screenId());
            depths.add(nameOf(ancestor));
        }
        SolutionScreen current = chain.get(0);
        keys.add(current.screenId());
        depths.add(nameOf(current));
        return new Seat(String.join("/", keys), chain.get(0).screenId(), List.copyOf(depths));
    }

    /** ⛔ 이름표가 없으면 slug 를 그대로 쓴다 — 사람이 {@code ia.md} 에서 채울 자리가 보여야 한다. */
    private static List<String> labelsOf(String path, Map<String, String> labels) {
        String[] keys = path.split("/");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) {
            String prefix = String.join("/", Arrays.copyOfRange(keys, 0, index + 1));
            values.add(labels.getOrDefault(prefix, keys[index]));
        }
        return values;
    }

    private static String nameOf(SolutionScreen screen) {
        return screen.screenName() == null || screen.screenName().isBlank()
                ? screen.screenId() : screen.screenName();
    }

    private record Seat(String pathKey, String screenId, List<String> depths) {
    }

    /**
     * @param placements DB 에 넣을 행. 순번이 1부터 매겨져 있다
     * @param skipped    빠진 화면 → 까닭. <b>사람이 볼 자리다</b> — 조용히 버리지 않는다
     */
    public record Tree(List<IaDocumentCodec.Placement> placements, Map<String, String> skipped) {
    }
}
