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

    @Test
    void 화면명이_화면ID와_같으면_자기_경로의_이름표를_쓴다() {
        // ⛔ 옛 조건은 screenName == null || isBlank() 다. SolutionScreenReader:171 이 이미
        //    화면명을 화면ID 로 채워 두므로 그 조건은 production 에서 영원히 거짓이었다.
        //    조건을 screenId.equals(screenName) 으로 바꿔야 생코드가 안 뜬다.
        // ⚠ 픽스처 이름은 합성이다 — 실측 화면키(PS-BO-APR-010-L01-S 꼴)와 안 이어져 있는데
        //    "EXW-UWV-50-50-S" 는 그 꼴을 흉내 내 실재하는 화면키처럼 오해를 부른다
        //    (코드리뷰 지적, 2026-09-04). 눈에 합성임이 드러나는 이름으로 바꿨다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("wv-lspn-main", "소비쿠폰 홈", "lspn/main", null),
                popup("zz-synthetic-noname", "zz-synthetic-noname", "card/issue", "wv-lspn-main")),
                labels());

        assertThat(placement(tree, "zz-synthetic-noname")).satisfies(row -> {
            assertThat(row.pathKey()).isEqualTo("lspn/main/wv-lspn-main/zz-synthetic-noname");
            assertThat(row.depths()).containsExactly("소비쿠폰", "메인", "소비쿠폰 홈", "카드 발급 내역");
        });
    }

    @Test
    void 이름표가_비어도_뎁스에_빈_칸이나_널이_생기지_않는다() {
        // 🔴 CRITICAL(코드리뷰, 2026-09-04) — 「- notice/board:」처럼 콜론 뒤가 빈 줄이면
        //    IaDocumentCodec.labels() 가 값 "" 을 담는다. labelsOf·nameOf 가 그것을 그대로
        //    내보내면 IaRow.depths() 는 빈 칸을 걸러 내는데 여기서 만드는 Placement.depths() 는
        //    안 걸러서 저장·재계산 두 목록의 길이가 갈리고 재작성 판정이 영원히 참이 된다.
        Map<String, String> labels = new java.util.LinkedHashMap<>(labels());
        labels.put("notice/board", "");   // labelsOf 갈래 — 이름표 값이 빈 문자열
        labels.put("notice/board2", "");  // nameOf 갈래 — 화면명이 화면ID 와 같을 때 참조하는 이름표도 빈 문자열

        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                // labelsOf 갈래: 경로 마디 이름표가 비어 있으면 slug 로 되돌아간다.
                screen("bo-notice-list", "게시판 목록", "notice/board", null),
                // nameOf 갈래: 화면명이 화면ID 와 같아 이름표를 참조하는데 그 이름표도 비어 있다 →
                // 화면ID 로 되돌아간다.
                screen("bo-notice-list-2", "bo-notice-list-2", "notice/board2", null),
                // nameOf 갈래(널 방어): 조상의 화면명이 널이면 옛 조건은 그 널을 그대로 돌려줬다.
                screen("bo-bizcard-req-list", null, "bizcard/req", null),
                screen("bo-bizcard-req-detail", "선불카드 신청/승인 상세", "bizcard/req", "bo-bizcard-req-list")),
                labels);

        assertThat(placement(tree, "bo-notice-list").depths())
                .containsExactly("공지사항", "board", "게시판 목록");
        assertThat(placement(tree, "bo-notice-list-2").depths())
                .containsExactly("공지사항", "board2", "bo-notice-list-2");
        assertThat(placement(tree, "bo-bizcard-req-detail").depths())
                .containsExactly("선불카드 관리", "선불카드 신청/승인 관리", "선불카드 신청/승인 관리",
                        "선불카드 신청/승인 상세");

        // ⛔ 단정으로 못 박는다 — Tree.placements() 의 어떤 뎁스도 널이거나 공백이 아니다.
        assertThat(tree.placements())
                .flatMap(IaDocumentCodec.Placement::depths)
                .noneMatch(value -> value == null || value.isBlank());
    }

    @Test
    void 경로에_빈_마디가_있으면_트리에_안_앉고_까닭을_남긴다() {
        // 🔴 CRITICAL(코드리뷰 2차, 2026-09-04) — "notice//board" 처럼 마디 사이가 비면
        // split("/") 이 빈 마디를 낸다. labelsOf 의 keys[index] 폴백과 nameOf 의 screenId
        // 폴백은 그것을 검사 없이 그대로 내보낸다 — 1차 CRITICAL(이름표가 비었을 때)과 같은
        // 실패 모드다. 지어내 붙이지 않고 자리를 못 얻은 것으로 본다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(screen("bo-notice-list", "게시판 목록", "notice//board", null)),
                labels());

        assertThat(tree.placements()).isEmpty();
        assertThat(tree.skipped()).containsEntry("bo-notice-list", "색인의 경로에 빈 마디가 있다");

        // ⛔ 이 케이스에도 단정으로 못 박는다 — 어떤 뎁스도 널이거나 공백이 아니다.
        assertThat(tree.placements())
                .flatMap(IaDocumentCodec.Placement::depths)
                .noneMatch(value -> value == null || value.isBlank());
    }

    @Test
    void 화면_id가_비어_있으면_트리에_안_앉고_까닭을_남긴다() {
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(screen("", "이름 없는 화면", "notice/board", null)), labels());

        assertThat(tree.placements()).isEmpty();
        assertThat(tree.skipped()).containsEntry("", "화면 ID 가 비어 있다");
    }

    @Test
    void 자손을_가진_자리는_안_접히고_행_없는_빈_마디가_생기지_않는다() {
        // 🟡 접기가 자손의 경로키를 끊는다 (코드리뷰 2차, 2026-09-04) — L(목록, notice/board)이
        // 접히면 그 자식 D 의 경로키는 여전히 "notice/board/L/D" 를 가리켜 행 없는 마디 L 이
        // 다시 생긴다. 자손이 있는 자리는 접지 않는 것으로 막는다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                // "게시판" 이름이 라벨과 같아 접힐 수 있는 자리(마지막_마디가_앞_마디와_같으면...
                // 시험과 같은 모양) — 여기는 자손(상세)이 있어 접히면 안 된다.
                screen("bo-notice-board-list", "게시판", "notice/board", null),
                screen("bo-notice-board-detail", "게시판 상세", "notice/board", "bo-notice-board-list")),
                labels());

        assertThat(placement(tree, "bo-notice-board-list")).satisfies(row -> {
            // ⛔ 접혔다면 "notice/board" 였을 자리 — 자손이 있어 안 접혀 화면 마디가 남는다.
            assertThat(row.pathKey()).isEqualTo("notice/board/bo-notice-board-list");
            assertThat(row.depths()).containsExactly("공지사항", "게시판", "게시판");
        });
        assertThat(placement(tree, "bo-notice-board-detail")).satisfies(row -> {
            // ⚠ 자손의 경로키는 조상 마디(bo-notice-board-list)를 그대로 가리킨다 — 그 마디가
            //    행으로 존재하므로 IaService.treeOf 가 행 없는 빈 마디를 새로 만들지 않는다.
            assertThat(row.pathKey()).isEqualTo("notice/board/bo-notice-board-list/bo-notice-board-detail");
        });
        assertThat(tree.kept())
                .containsEntry("bo-notice-board-list", "자손이 이 자리를 조상 마디로 삼고 있어 화면 마디를 남겼다");

        // ⛔ 행 없는 빈 마디가 안 생긴다 — 자손이 조상 마디로 가리키는 문자열
        //    "notice/board/bo-notice-board-list" 이 bo-notice-board-list 자신의 pathKey 와
        //    정확히 같다(위 두 satisfies). 그래서 IaService.treeOf 가 그 마디를 만들 때 반드시
        //    이 행을 만나고, 행 없이 만드는 computeIfAbsent 갈래를 안 지난다.
        assertThat(placement(tree, "bo-notice-board-detail").pathKey())
                .startsWith(placement(tree, "bo-notice-board-list").pathKey() + "/");
    }

    @Test
    void 접기_후보끼리_부딪히면_어느_쪽도_안_접고_둘_다_까닭을_남긴다() {
        // 🟡 ③의 「접기 후보끼리 부딪히는」 갈래(collidesWithOtherCandidate) — 지금까지 시험
        //    셋은 전부 collidesWithUncollapsed 쪽만 지났다(코드리뷰 지적, 2026-09-04).
        //    공유 화면(자기 자리는 안 서고 연결만 되는)을 같은 이름으로 여는 팝업 둘을 만들면,
        //    둘 다 같은 접은 경로키를 얻으면서도 안 접은 값(공유 화면은 자기 seat 이 없다)과는
        //    안 부딪힌다 — collidesWithOtherCandidate 갈래만 단독으로 잰다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("bo-notice-list", "게시판", "notice/board", null),
                shared("bo-notice-mid-shared", "상세보기", "bo-notice-list"),
                popup("bo-notice-child-a", "상세보기", null, "bo-notice-mid-shared"),
                popup("bo-notice-child-b", "상세보기", null, "bo-notice-mid-shared")),
                labels());

        assertThat(placement(tree, "bo-notice-child-a").pathKey())
                .isEqualTo("notice/board/bo-notice-list/bo-notice-mid-shared/bo-notice-child-a");
        assertThat(placement(tree, "bo-notice-child-b").pathKey())
                .isEqualTo("notice/board/bo-notice-list/bo-notice-mid-shared/bo-notice-child-b");
        assertThat(tree.kept())
                .containsEntry("bo-notice-child-a", "경로 식별자가 다른 행과 부딪혀 화면 마디를 남겼다")
                .containsEntry("bo-notice-child-b", "경로 식별자가 다른 행과 부딪혀 화면 마디를 남겼다");
    }

    @Test
    void 마지막_마디가_앞_마디와_같으면_겹쳐_그리지_않는다() {
        // ⛔ 한 칸만 본다 — 붙일 마지막 마디(현재 화면)의 이름이 바로 앞 마디와 같을 때만 접는다.
        //    화면명이 자기 경로의 이름표("게시판")와 그대로 같아서 labelsOf 가 낸 마지막 마디와 겹친다.
        //    (`경로만_있는_화면도_마지막에_현재_화면명이_붙는다` 와 같은 모양이지만 거기는 화면명이
        //    이름표와 달라 안 겹친다 — 여기는 일부러 겹치게 골랐다.)
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(screen("bo-notice-board-list", "게시판", "notice/board", null)),
                labels());

        assertThat(placement(tree, "bo-notice-board-list")).satisfies(row -> {
            assertThat(row.pathKey()).isEqualTo("notice/board");
            assertThat(row.depths()).containsExactly("공지사항", "게시판");
            // ⚠ 접은 뒤에도 screenId 는 그대로 화면ID 다 — 경로키만 짧아진다.
            assertThat(row.screenId()).isEqualTo("bo-notice-board-list");
        });
    }

    @Test
    void 경로키가_부딪히면_그_행만_겹침을_되돌리고_까닭을_남긴다() {
        // ⛔ 되돌리는 방식으로 짜지 않는다 — 접기 전에 정한다(브리프 §3-1 「③의 차례」).
        //    상세(D)는 이름이 따로 있어 안 접히고 경로키 notice/board/bo-notice-detail 로 선다.
        //    그 아래 팝업(X)은 이름이 D 와 같아 접히면 바로 그 경로키와 부딪힌다.
        //    부딪힌 자리만 되돌리고 까닭을 남긴다 — D 는 건드리지 않는다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(List.of(
                screen("bo-notice-detail", "게시판 상세", "notice/board", null),
                popup("bo-notice-detail-pop", "게시판 상세", null, "bo-notice-detail")),
                labels());

        assertThat(placement(tree, "bo-notice-detail").pathKey()).isEqualTo("notice/board/bo-notice-detail");
        assertThat(placement(tree, "bo-notice-detail-pop")).satisfies(row -> {
            assertThat(row.pathKey()).isEqualTo("notice/board/bo-notice-detail/bo-notice-detail-pop");
            assertThat(row.depths()).containsExactly("공지사항", "게시판", "게시판 상세", "게시판 상세");
        });
        assertThat(tree.kept())
                .containsEntry("bo-notice-detail-pop", "경로 식별자가 다른 행과 부딪혀 화면 마디를 남겼다");
        assertThat(tree.kept()).doesNotContainKey("bo-notice-detail");
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
                "lspn/main", "메인",
                "notice", "공지사항",
                "notice/board", "게시판");
    }

    @Test
    void 경로_앞뒤에_공백이_있어도_이름표를_찾아_메뉴명으로_쓴다() {
        // ⛔ seatOf 는 basePath 를 .strip() 해서 쓰고 IaDocumentCodec.labels 도 열쇠를 .strip() 해
        //    담는다. nameOf 만 안 하면 이름표를 못 찾아 화면ID 가 메뉴명으로 나가고, 마지막 마디가
        //    앞 마디와 달라져 접기 판정까지 뒤집힌다(코드리뷰 2차 지적, 2026-09-04).
        //    strip 을 지우면 이 시험이 깨져야 한다 — 그것이 이 시험의 존재 이유다.
        IaTreeBuilder.Tree tree = IaTreeBuilder.of(
                List.of(screen("bo-notice-board", "bo-notice-board", "  notice/board  ", null)),
                labels());

        assertThat(tree.placements()).singleElement().satisfies(row -> {
            assertThat(row.depths()).containsExactly("공지사항", "게시판");
            assertThat(row.pathKey()).isEqualTo("notice/board");
        });
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
