package com.bizplay.builder.ia;

import com.bizplay.builder.solution.SolutionScreen;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⛔ <b>메뉴구조도의 뎁스는 셋을 이어 붙여 만든다</b>(2026-08-21 병주 확정) —
 * 색인의 {@code 경로}(2마디) · {@code 상위화면} 사슬 · {@code 여는화면} 사슬.
 *
 * <p>⚠ 「솔직히 IA 느낌은 아니다」 — 팝업이 메뉴 마디가 되는 것을 알고 고른 것이다.
 * 사람이 화면을 찾는 길이 실제로 그러하기 때문이다(상세에서 팝업을 띄운다).
 */
class IaTreeBuilderTest {

    @Test
    void 경로만_있는_화면도_마지막에_현재_화면명이_붙는다() {
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(screen("bo-bizcard-list", "선불카드 관리 (목록)", "bizcard/bizcard", null)),
                labels());

        assertThat(tree.placements()).singleElement().satisfies(row -> {
            assertThat(row.pathKey()).isEqualTo("bizcard/bizcard/bo-bizcard-list");
            assertThat(row.depths()).containsExactly("선불카드 관리", "선불카드 관리", "선불카드 관리 (목록)");
            assertThat(row.screenId()).isEqualTo("bo-bizcard-list");
        });
    }

    @Test
    void 상위화면이_있으면_부모_화면명이_한_마디_된다() {
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("bo-bizcard-req-list", "선불카드 신청/승인 목록", "bizcard/req", null),
                screen("bo-bizcard-req-detail", "선불카드 신청/승인 상세", "bizcard/req", "bo-bizcard-req-list")),
                labels());

        assertThat(placement(tree, "bo-bizcard-req-detail")).satisfies(row -> {
            assertThat(row.pathKey()).isEqualTo("bizcard/req/bo-bizcard-req-list/bo-bizcard-req-detail");
            assertThat(row.depths()).containsExactly(
                    "선불카드 관리", "선불카드 신청/승인 관리", "선불카드 신청/승인 목록",
                    "선불카드 신청/승인 상세");
        });
    }

    @Test
    void 팝업은_여는화면_아래로_들어간다() {
        // ⭐ 팝업·모달은 상위화면이 없고 「여는화면」을 갖는다 — 2026-08-15 에 추출기가 뒤집은 규격이다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("bo-bizcard-detail", "선불카드 관리 상세", "bizcard/bizcard", null),
                popup("bo-bizcard-detail-pop", "카드상태 변경 확인 팝업", "bizcard/bizcard", "bo-bizcard-detail")),
                labels());

        assertThat(placement(tree, "bo-bizcard-detail-pop").depths())
                .containsExactly("선불카드 관리", "선불카드 관리", "선불카드 관리 상세",
                        "카드상태 변경 확인 팝업");
    }

    @Test
    void 경로가_없으면_사슬을_올라가_조상의_경로를_쓴다() {
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("wv-lspn-main", "소비쿠폰 홈", "lspn/main", null),
                popup("wv-modal-coupon-agree", "약관 동의", null, "wv-lspn-main")),
                labels());

        assertThat(placement(tree, "wv-modal-coupon-agree")).satisfies(row -> {
            assertThat(row.pathKey()).isEqualTo("lspn/main/wv-lspn-main/wv-modal-coupon-agree");
            assertThat(row.depths()).containsExactly("소비쿠폰", "메인", "소비쿠폰 홈", "약관 동의");
        });
    }

    @Test
    void 모달_사슬이_깊으면_현재_화면까지_일곱_뎁스로_그린다() {
        // 실측(planning-g2c): 소비쿠폰 홈 → 약관 동의 → 신청서 → 신청 전 확인 → 신청 완료.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("wv-lspn-main", "소비쿠폰 홈", "lspn/main", null),
                popup("m1", "약관 동의", null, "wv-lspn-main"),
                popup("m2", "신청서", null, "m1"),
                popup("m3", "신청 전 확인", null, "m2"),
                popup("m4", "신청 완료", null, "m3")),
                labels());

        assertThat(placement(tree, "m4").depths()).containsExactly(
                "소비쿠폰", "메인", "소비쿠폰 홈", "약관 동의", "신청서", "신청 전 확인", "신청 완료");
        assertThat(tree.skipped()).isEmpty();
    }

    @Test
    void 일곱_마디를_넘으면_빼고_남긴_까닭을_적는다() {
        // ⛔ 지어내 붙이지 않는다 — DB 는 depth7 까지다. 넘는 것은 사람이 볼 목록으로 남는다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("wv-lspn-main", "소비쿠폰 홈", "lspn/main", null),
                popup("m1", "1", null, "wv-lspn-main"),
                popup("m2", "2", null, "m1"),
                popup("m3", "3", null, "m2"),
                popup("m4", "4", null, "m3"),
                popup("m5", "5", null, "m4")),
                labels());

        assertThat(tree.placements()).extracting(IaDocumentCodec.Placement::screenId)
                .doesNotContain("m5");
        assertThat(tree.skipped()).containsKey("m5");
    }

    @Test
    void 공용_화면은_트리에_앉히지_않는다() {
        // ⛔ 여러 자리에서 열리는 화면을 한 자리에 못 박으면 「모른다」가 아니라 틀린 정보가 된다.
        //    화면은 그것을 「공용 화면」 목록으로 따로 보여 준다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("bo-appr-list", "결재 목록", "approval/document", null),
                shared("bo-shared-pop", "공용 검색", "bo-appr-list")),
                labels());

        assertThat(tree.placements()).extracting(IaDocumentCodec.Placement::screenId)
                .containsExactly("bo-appr-list");
        assertThat(tree.skipped()).containsKey("bo-shared-pop");
    }

    @Test
    void 자리를_못_얻은_화면은_빠진다() {
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(popup("bo-front-lnkgpop", "공용 팝업", null, null)), labels());

        assertThat(tree.placements()).isEmpty();
        assertThat(tree.skipped()).containsKey("bo-front-lnkgpop");
    }

    @Test
    void 사슬이_돌면_끊고_그_화면을_뺀다() {
        // 색인이 잘못 나온 날 무한 재귀로 서버가 죽는 것을 막는다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                popup("a", "가", null, "b"),
                popup("b", "나", null, "a")),
                labels());

        assertThat(tree.placements()).isEmpty();
        assertThat(tree.skipped()).containsKeys("a", "b");
    }

    @Test
    void 순서는_경로와_화면ID_사전순이고_1부터_센다() {
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("bo-card-issue-list", "카드 발급 내역", "card/issue", null),
                screen("bo-bizcard-list", "선불카드 관리", "bizcard/bizcard", null)),
                labels());

        assertThat(tree.placements()).extracting(IaDocumentCodec.Placement::order)
                .containsExactly(1, 2);
        assertThat(tree.placements()).extracting(IaDocumentCodec.Placement::screenId)
                .containsExactly("bo-bizcard-list", "bo-card-issue-list");
    }

    @Test
    void 이름표가_없는_마디는_slug_를_그대로_쓴다() {
        // ⛔ 지어내지 않는다 — 사람이 ia.md 에서 채울 자리가 그대로 보여야 한다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(screen("x-list", "목록", "unknown/branch", null)), Map.of());

        assertThat(placement(tree, "x-list").depths()).containsExactly("unknown", "branch", "목록");
    }

    private static IaDocumentCodec.Placement placement(IaTreeBuilder.Tree tree, String screenId) {
        return tree.placements().stream().filter(row -> screenId.equals(row.screenId())).findFirst()
                .orElseThrow(() -> new AssertionError("그 화면의 행이 없다: " + screenId));
    }

    private static Map<String, String> labels() {
        return Map.of(
                "bizcard", "선불카드 관리",
                "bizcard/bizcard", "선불카드 관리",
                "bizcard/req", "선불카드 신청/승인 관리",
                "card", "카드 관리",
                "card/issue", "카드 발급 내역",
                "lspn", "소비쿠폰",
                "lspn/main", "메인");
    }

    private static SolutionScreen screen(String id, String name, String iaPath, String parent) {
        return solution(id, name, "화면", iaPath, parent, List.of());
    }

    private static SolutionScreen popup(String id, String name, String iaPath, String opener) {
        return solution(id, name, "팝업", iaPath, null, opener == null ? List.of() : List.of(opener));
    }

    private static SolutionScreen shared(String id, String name, String opener) {
        return new SolutionScreen(id, name, "backoffice", "팝업", null, null, null, null,
                null, null, List.of(), List.of(), null, List.of(opener), true, null);
    }

    private static SolutionScreen solution(String id, String name, String kind, String iaPath,
                                           String parent, List<String> openedBy) {
        return new SolutionScreen(id, name, "backoffice", kind, null, null, null, iaPath,
                null, null, List.of(), List.of(), parent, openedBy, false, null);
    }
}
