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
            SeatResult result = seatOf(chain, labels);
            if (result.seat() == null) {
                skipped.put(screen.screenId(), result.skipReason());
                continue;
            }
            Seat seat = result.seat();
            if (seat.depths().size() > MAX_DEPTH) {
                skipped.put(screen.screenId(),
                        "뎁스가 %d 로 담을 수 있는 %d 를 넘었다".formatted(seat.depths().size(), MAX_DEPTH));
                continue;
            }
            seats.add(seat);
        }

        Map<String, String> kept = new LinkedHashMap<>();
        List<Decided> decided = decideCollapsing(seats, kept);

        // ⚠ 순서를 경로 → 화면ID 로 못 박는다. 같은 클론을 두 번 가져오면 같은 순번이 나와야
        //   사람이 두 판을 대조할 수 있다(채번의 정렬과 같은 규칙이다). ③ 되돌리기가 pathKey 를
        //   바꾸므로 되돌리기가 정렬보다 먼저 와야 이 재현성이 산다.
        decided.sort(Comparator.comparing(Decided::pathKey).thenComparing(Decided::screenId));
        List<IaDocumentCodec.Placement> placements = new ArrayList<>();
        int order = 1;
        for (Decided seat : decided) {
            placements.add(new IaDocumentCodec.Placement(order++, seat.pathKey(), seat.screenId(), seat.depths()));
        }
        return new Tree(List.copyOf(placements), Map.copyOf(skipped), Map.copyOf(kept));
    }

    /**
     * ③ 경로키 충돌 (브리프 §3-1 「③의 차례」 원문이 정본이다) — <b>접기 전에</b> 부딪힘을 가른다.
     *
     * <p>⛔ <b>접었다가 부딪히면 되돌리는 꼴로 짜지 않는다.</b> 되돌린 값이 또 부딪히는 반례가
     * 있다 — 안 접은 상세 D(경로키 {@code …/L/D}) 밑의 팝업 X 가 접혀 D 와 부딪혀 되돌리면
     * {@code …/L/D/X} 가 되는데, 그 밑에 안 접힌 Y 가 있으면 Y 의 경로키가 바로 그것이다.
     *
     * <p>대신 접기 전에 한 번에 정한다 — 안 접은 경로키 전부를 집합으로 모으고(각자 자기
     * 화면ID로 끝나므로 서로 다르다), 접을 수 있는 자리마다 접은 경로키를 구해 그 집합과
     * 부딪히거나 다른 접기 후보와 부딪히면 <b>그 후보를 전부</b> 안 접는다. 「먼저 앉은 자리가
     * 이긴다」로 하지 않는다 — 그러면 안 접을 대상이 {@code index.json} 의 기재 순서를 타서,
     * 같은 화면 집합인데 순서만 바뀌면 다른 판이 나온다.
     *
     * <p>⭐ <b>자손을 가진 자리는 접지 않는다 (코드리뷰 2차 CRITICAL, 2026-09-04)</b> — 접으면
     * 자기 마디가 없어지는데, 자손의 경로키는 여전히 그 마디(자기 화면ID)를 가리킨다. 그러면
     * {@code IaService.treeOf} 가 <b>행 없는 빈 마디</b>를 그 자리에 새로 만든다 — 없애려던
     * 겹침이 한 칸 아래로 옮겨갈 뿐이다. 그래서 접기 후보의 <b>안 접은</b> 경로키가 다른 자리의
     * <b>안 접은</b> 경로키의 앞머리이면(그 자리가 자손이면) 접지 않는다. 앞의 둘(부딪힘)과
     * 같은 꼴이다 — {@code full} 하나로 한 번에 정해지고 입력 순서를 안 탄다.
     */
    private static List<Decided> decideCollapsing(List<Seat> seats, Map<String, String> kept) {
        Set<String> full = new LinkedHashSet<>();
        for (Seat seat : seats) full.add(seat.fullPathKey());

        Map<String, List<Seat>> candidatesByCollapsedKey = new LinkedHashMap<>();
        for (Seat seat : seats) {
            if (seat.collapsible()) {
                candidatesByCollapsedKey.computeIfAbsent(seat.collapsedPathKey(), key -> new ArrayList<>())
                        .add(seat);
            }
        }

        List<Decided> decided = new ArrayList<>();
        for (Seat seat : seats) {
            if (seat.collapsible()) {
                String collapsedKey = seat.collapsedPathKey();
                boolean collidesWithUncollapsed = full.contains(collapsedKey);
                boolean collidesWithOtherCandidate = candidatesByCollapsedKey.get(collapsedKey).size() > 1;
                boolean hasDescendant = hasDescendant(seat, full);
                if (!collidesWithUncollapsed && !collidesWithOtherCandidate && !hasDescendant) {
                    decided.add(new Decided(collapsedKey, seat.screenId(), seat.collapsedDepths()));
                    continue;
                }
                kept.put(seat.screenId(), hasDescendant
                        ? "자손이 이 자리를 조상 마디로 삼고 있어 화면 마디를 남겼다"
                        : "경로 식별자가 다른 행과 부딪혀 화면 마디를 남겼다");
            }
            decided.add(new Decided(seat.fullPathKey(), seat.screenId(), seat.depths()));
        }
        return decided;
    }

    /** {@code seat} 의 안 접은 경로키를 조상 마디로 삼는 다른 안 접은 경로키가 있는지. */
    private static boolean hasDescendant(Seat seat, Set<String> full) {
        String prefix = seat.fullPathKey() + "/";
        for (String other : full) {
            if (other.startsWith(prefix)) return true;
        }
        return false;
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
     *
     * <p>⛔ <b>여기서는 접지 않는다.</b> 늘 안 접은(full) 자리를 낸다. ③(경로키 충돌)이
     * 접기 전 상태끼리 견줘야 하므로, 접을 수 있는지(마지막 마디가 바로 앞 마디와 같은지)만
     * {@link Seat#collapsible()} 로 같이 낸다 — 접기 결정은 {@code of} 가 모아서 한다.
     *
     * <p>⭐ <b>빈 마디를 아무도 검사하지 않으면 뎁스로 그대로 나간다 (코드리뷰 2차 CRITICAL,
     * 2026-09-04)</b> — {@code iaPath} 가 {@code "/BPY/MYAF"} 나 {@code "BPY//10"} 이면
     * {@code split("/")} 이 빈 마디를 낸다. {@code labelsOf} 의 {@code keys[index]} 폴백과
     * {@code nameOf} 의 {@code screen.screenId()} 폴백은 그 빈 문자열을 검사 없이 그대로
     * 뎁스로 내보낸다 — 「이름표가 비었을 때」의 실패 모드와 같다(1차 CRITICAL). 그래서 뿌리를
     * 찾은 뒤 곧바로 <b>basePath 의 마디</b>와 <b>현재 화면의 화면ID</b>를 검사해, 하나라도
     * 비어 있으면 자리를 못 얻은 것으로 본다 — 「(이름 없음)」 같은 대체 이름을 지어내 붙이지
     * 않는다. 트리에 안 앉히고 {@code skipped} 에 까닭을 남긴다.
     */
    private static SeatResult seatOf(List<SolutionScreen> chain, Map<String, String> labels) {
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
        if (root < 0) return SeatResult.skip("색인에 경로가 없고 조상에서도 물려받지 못했다");

        String basePath = chain.get(root).iaPath().strip();
        List<String> baseKeys = Arrays.asList(basePath.split("/"));
        if (baseKeys.stream().anyMatch(String::isBlank)) {
            return SeatResult.skip("색인의 경로에 빈 마디가 있다");
        }
        SolutionScreen current = chain.get(0);
        // ⛔ 현재 화면만 보지 않는다 — 조상의 화면ID 도 경로 마디가 된다(아래 조상 루프).
        //    조상 쪽이 비면 경로키에 빈 마디(`a/b//c`)가 생겨 V41 의 path_key 체크를 어기고,
        //    nameOf 가 그 빈 화면ID 를 뎁스로 그대로 내보낸다 — 위 「빈 마디」와 같은 실패 모드다
        //    (코드리뷰 3차 지적, 2026-09-04. 현재 화면만 막아 조상 갈래가 남아 있었다).
        for (int index = root; index >= 0; index--) {
            String screenId = chain.get(index).screenId();
            if (screenId == null || screenId.isBlank()) {
                return SeatResult.skip("화면 ID 가 비어 있다");
            }
        }

        List<String> depths = new ArrayList<>(labelsOf(basePath, labels));
        List<String> keys = new ArrayList<>(baseKeys);
        // 조상은 「위에서 아래로」 붙는다 — 사슬은 자기부터라 거꾸로 돈다.
        for (int index = root; index >= 1; index--) {
            SolutionScreen ancestor = chain.get(index);
            keys.add(ancestor.screenId());
            depths.add(nameOf(ancestor, labels));
        }
        String currentName = nameOf(current, labels);
        // ⛔ 한 칸만 본다 — 붙일 마지막 마디(현재 화면)가 바로 앞 마디와 같은지만 본다.
        //    앞쪽 연쇄(labelsOf 가 낸 마디끼리 같은 경우)는 안 건드린다(브리프 §5-2).
        boolean collapsible = !depths.isEmpty() && depths.get(depths.size() - 1).equals(currentName);
        keys.add(current.screenId());
        depths.add(currentName);
        return SeatResult.of(new Seat(List.copyOf(keys), current.screenId(), List.copyOf(depths), collapsible));
    }

    /**
     * ⛔ 이름표가 없거나 <b>비어 있으면</b> slug 를 그대로 쓴다 — 사람이 {@code ia.md} 에서 채울
     * 자리가 보여야 한다.
     *
     * <p>⭐ <b>빈 문자열을 그대로 내보내지 않는다 (코드리뷰 CRITICAL, 2026-09-04)</b> —
     * {@code IaDocumentCodec.labels}(콜론 뒤가 빈 줄이면 이름표 값이 {@code ""})가 그런 항목을
     * 낼 수 있다. {@code labels.getOrDefault(prefix, keys[index])} 는 <b>키가 있고 값이 빈
     * 경우</b>를 못 막는다 — 그 값이 뎁스로 그대로 나가면 {@link IaRow#depths()} 는 빈 칸을
     * 걸러 내는데 여기서 만든 {@link IaDocumentCodec.Placement#depths()} 는 안 걸러서, 저장·
     * 재계산 두 목록의 길이가 갈리고 {@code IaService.sameShape} 가 영원히 거짓이 되어
     * 작업대를 열 때마다 재작성이 돈다(수렴하지 않는다).
     */
    private static List<String> labelsOf(String path, Map<String, String> labels) {
        String[] keys = path.split("/");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) {
            String prefix = String.join("/", Arrays.copyOfRange(keys, 0, index + 1));
            String label = labels.get(prefix);
            values.add(label == null || label.isBlank() ? keys[index] : label);
        }
        return values;
    }

    /**
     * ⛔ <b>옛 조건({@code screenName == null || isBlank()})은 production 에서 영원히 거짓이다</b> —
     * {@code SolutionScreenReader:171} 이 가져오기 시점에 이미 빈 화면명을 화면ID 로 메꾼다.
     * 그래서 조건을 <b>화면명이 화면ID 와 같은가</b>로 바꾼다. 그때는 자기 경로의 이름표
     * ({@code labels.get(screen.iaPath())})를 쓰고, 그것도 없으면 화면ID 를 그대로 둔다.
     *
     * <p>⚠ 조상 마디도 같은 규칙을 탄다. 조상의 {@code iaPath} 가 널일 수 있다(경로를 물려받은
     * 화면) — {@code labels.get(null)} 을 부르지 않고 <b>경로가 없으면 이름표 조회를 건너뛴다</b>.
     *
     * <p>⭐ <b>널 방어와 빈 이름표 방어를 둘 다 여기서 한다 (코드리뷰 CRITICAL, 2026-09-04)</b> —
     * ① {@code screenName} 이 널이면 {@code !screenId.equals(screenName)} 이 <b>참</b>이 되어
     * 널을 그대로 돌려주던 자리였다. 그래서 널·공백을 먼저 걸러 그 갈래로 안 들어가게 한다.
     * ② {@code labels.getOrDefault(path, screenId)} 도 이름표가 {@code ""} 면 {@code ""} 를
     * 그대로 돌려준다 — {@link #labelsOf} 와 같은 까닭(재작성이 수렴하지 않음)으로 막는다.
     */
    private static String nameOf(SolutionScreen screen, Map<String, String> labels) {
        String screenName = screen.screenName();
        if (screenName != null && !screenName.isBlank() && !screen.screenId().equals(screenName)) {
            return screenName;
        }
        String path = screen.iaPath();
        if (path == null || path.isBlank()) {
            return screen.screenId();
        }
        // ⛔ seatOf 의 basePath 는 .strip() 해서 열쇠로 쓴다(IaDocumentCodec.labels 도 열쇠를
        //    .strip() 해 담는다) — 여기만 안 하면 경로 앞뒤 공백이 있을 때 이름표를 못 찾아
        //    화면ID 가 메뉴명으로 나가고 collapsible 판정까지 뒤집힌다(코드리뷰 지적, 2026-09-04).
        String label = labels.get(path.strip());
        return label == null || label.isBlank() ? screen.screenId() : label;
    }

    /**
     * @param keys        안 접은(full) 경로 마디. {@link #fullPathKey()} 가 이것을 잇는다
     * @param collapsible 마지막 마디(현재 화면)가 바로 앞 마디와 같아 <b>접을 수 있는지</b>.
     *                    접을지 말지는 여기서 정하지 않는다 — {@code of} 가 충돌을 본 뒤 정한다
     */
    private record Seat(List<String> keys, String screenId, List<String> depths, boolean collapsible) {

        String fullPathKey() {
            return String.join("/", keys);
        }

        /** ⛔ {@link #collapsible()} 이 거짓일 때는 부르지 않는다 — 마지막 마디가 없어진다. */
        String collapsedPathKey() {
            return String.join("/", keys.subList(0, keys.size() - 1));
        }

        List<String> collapsedDepths() {
            return depths.subList(0, depths.size() - 1);
        }
    }

    /** ③ 충돌 판정까지 끝나 확정된 자리 — {@code pathKey} 가 접힌 값일 수도 안 접힌 값일 수도 있다. */
    private record Decided(String pathKey, String screenId, List<String> depths) {
    }

    /**
     * {@link #seatOf} 의 결과 — 자리를 얻었으면 {@code seat} 가, 못 얻었으면 {@code skipReason} 이
     * 채워진다. 못 얻은 까닭이 여럿이라(경로 없음·빈 마디·화면ID 없음) 단순 {@code null} 반환으로는
     * {@code of} 가 어느 까닭인지 알 수 없어 이 결과 타입으로 실어 나른다.
     */
    private record SeatResult(Seat seat, String skipReason) {
        static SeatResult of(Seat seat) {
            return new SeatResult(seat, null);
        }

        static SeatResult skip(String reason) {
            return new SeatResult(null, reason);
        }
    }

    /**
     * @param placements DB 에 넣을 행. 순번이 1부터 매겨져 있다
     * @param skipped    빠진 화면 → 까닭. <b>사람이 볼 자리다</b> — 조용히 버리지 않는다
     * @param kept       <b>트리에 남은</b> 화면 → 안 접은 까닭. {@code skipped} 와 섞지 않는다 —
     *                   {@code skipped} 는 「트리에서 빠진 화면」이고 여기 담긴 화면은 행으로 남는다
     *                   (③ 경로키 충돌로 접기를 포기한 자리)
     */
    public record Tree(List<IaDocumentCodec.Placement> placements, Map<String, String> skipped,
                        Map<String, String> kept) {
    }
}
