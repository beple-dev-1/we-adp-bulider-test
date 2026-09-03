package com.bizplay.builder.featurespec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 md 블록 파서 — <b>실물이 던지는 모양</b>에서 안 깨지나.
 *
 * <p>⭐ <b>여기 있는 시험 넷은 2026-08-27 코덱스 적대 검증이 짚은 자리다.</b> 전부
 * 「예외를 안 던지고 조용히 틀린 값을 내는」 부류라, 시험이 없으면 아무도 모른 채 지나간다.
 *
 * <p>⚠ DB 도 스프링도 안 쓴다 — 파서는 순수 함수다.
 */
class FeatureSpecDocumentTest {

    private static final String MD = """
            --- 꼬리표 ---
            id: bo-x-detail / system: backoffice / 기능: 가 > 나 > 상세 / 과업: []

            --- 화면명세 ---
            화면명: 상세 화면
            목적: 무엇을 한다

            --- IA ---
            - 종류: 화면 / 상위화면: bo-x-list

            --- 정의 ---
            - 구분: 이동 / 좌표: id=userNm / 앵커: bo-x-detail-e01 / 이동: bo-y-detail / 해설: 사용자명 클릭 → 이동 (userForm POST /customer/user/detailPage). 배선 완료.

            --- 원본 글 ---
            > 역추출 소스: 어딘가
            """;

    @Test
    void 해설에_슬래시가_들어_있어도_통째로_읽는다() {
        var document = FeatureSpecDocument.parse(MD);

        var function = document.functions().get(0);
        assertThat(function.no()).isEqualTo("e01");
        assertThat(function.kind()).isEqualTo("이동");
        assertThat(function.locator()).isEqualTo("id=userNm");
        assertThat(function.moveTo()).isEqualTo("bo-y-detail");
        // ⛔ 해설은 마지막 칸이고 값 안에 / 가 있다 — " / " 로 자르면 뒤가 통째로 잘린다.
        assertThat(function.detail())
                .isEqualTo("사용자명 클릭 → 이동 (userForm POST /customer/user/detailPage). 배선 완료.");
    }

    /**
     * ⛔ <b>BOM 이 붙으면 블록을 하나도 못 찾는데 {@code raw} 는 안 비어 있다.</b>
     * 그러면 「명세 있음」인데 제목·목적·기능이 전부 빈 화면이 뜬다 — 아무도 왜인지 모른다.
     */
    @Test
    void 파일_앞에_BOM_이나_공백이_있어도_블록을_찾는다() {
        var withBom = FeatureSpecDocument.parse("﻿" + MD);
        var withIndent = FeatureSpecDocument.parse(MD.replace("--- 화면명세 ---", "  --- 화면명세 ---"));

        assertThat(withBom.screenName()).isEqualTo("상세 화면");
        assertThat(withBom.hasFunctions()).isTrue();
        assertThat(withIndent.screenName()).isEqualTo("상세 화면");
    }

    /**
     * ⛔ <b>IA 항목이 여럿이면 첫 줄로 찍으면 안 된다.</b> 앞에 설명 줄이 하나 붙는 순간
     * 종류와 상위화면이 빈 값이 되고, 그러면 <b>화면 가족이 조용히 사라진다.</b>
     */
    @Test
    void IA_에_다른_항목이_먼저_있어도_종류와_상위화면을_읽는다() {
        var document = FeatureSpecDocument.parse(MD.replace(
                "- 종류: 화면 / 상위화면: bo-x-list",
                "- 설명: 참고용으로 남긴 줄\n- 종류: 화면 / 상위화면: bo-x-list"));

        assertThat(document.iaKind()).isEqualTo("화면");
        assertThat(document.parent()).isEqualTo("bo-x-list");
    }

    @Test
    void 빈_명세와_모르는_블록에도_안_깨진다() {
        assertThat(FeatureSpecDocument.parse(null).isEmpty()).isTrue();
        assertThat(FeatureSpecDocument.parse("   ").isEmpty()).isTrue();
        assertThat(FeatureSpecDocument.empty().screenName()).isEmpty();
        assertThat(FeatureSpecDocument.empty().hasFunctions()).isFalse();

        // ⚠ 규격은 기획 저장소가 정한다 — 새 블록이 생겼다고 화면이 깨지면 고칠 사람이 여기 없다.
        var extra = FeatureSpecDocument.parse(MD + "\n--- 새로운 블록 ---\n- 무엇이든\n");
        assertThat(extra.screenName()).isEqualTo("상세 화면");
        assertThat(extra.functions()).hasSize(1);
    }

    /**
     * ⛔ <b>해설 안의 「/ 이동: …」 이 진짜 이동 칸으로 둔갑하면 안 된다</b>(2026-08-27 코덱스 2회차).
     * 해설은 마지막 칸이므로 거기서 멈추고 줄 끝까지가 해설이다.
     */
    @Test
    void 해설_안의_칸_모양_글자를_진짜_칸으로_읽지_않는다() {
        var document = FeatureSpecDocument.parse(MD.replace(
                "해설: 사용자명 클릭 → 이동 (userForm POST /customer/user/detailPage). 배선 완료.",
                "해설: 저장 후 / 이동: 없음으로 표시"));

        var function = document.functions().get(0);
        assertThat(function.moveTo()).isEqualTo("bo-y-detail");
        assertThat(function.detail()).isEqualTo("저장 후 / 이동: 없음으로 표시");
    }

    /** ⛔ 좌표 값 안에 칸 이름 모양이 들어와도 해설을 거기서부터 자르면 안 된다. */
    @Test
    void 좌표_값에_칸_이름_모양이_있어도_해설을_제자리에서_뗀다() {
        var document = FeatureSpecDocument.parse(MD.replace(
                "좌표: id=userNm", "좌표: id=해설:fake"));

        var function = document.functions().get(0);
        assertThat(function.locator()).isEqualTo("id=해설:fake");
        assertThat(function.detail()).startsWith("사용자명 클릭");
    }

    /** ⚠ 슬래시 둘레의 공백이 실물과 달라도 칸이 통째로 합쳐지면 안 된다. */
    @Test
    void 슬래시_둘레_공백이_달라도_칸을_가른다() {
        var document = FeatureSpecDocument.parse(MD.replace(
                "- 구분: 이동 / 좌표: id=userNm / 앵커: bo-x-detail-e01 / 이동: bo-y-detail / 해설:",
                "- 구분: 이동/좌표: id=userNm/앵커: bo-x-detail-e01/이동: bo-y-detail/해설:"));

        var function = document.functions().get(0);
        assertThat(function.kind()).isEqualTo("이동");
        assertThat(function.locator()).isEqualTo("id=userNm");
        assertThat(function.anchor()).isEqualTo("bo-x-detail-e01");
        assertThat(function.moveTo()).isEqualTo("bo-y-detail");
    }

    /** ⛔ 연관은 쉼표와 가운뎃점으로만 가른다 — 슬래시로 가르면 경로 한 개가 여럿으로 갈린다. */
    @Test
    void 연관을_슬래시로_가르지_않는다() {
        var document = FeatureSpecDocument.parse(MD.replace(
                "목적: 무엇을 한다", "목적: 무엇을 한다\n연관: customer/user/detail, bo-x-list"));

        assertThat(document.related()).containsExactly("customer/user/detail", "bo-x-list");
    }

    /** ⚠ 앵커가 규격을 벗어나도 줄을 버리지 않는다 — 번호만 비고 해설은 남는다. */
    @Test
    void 앵커_번호가_없어도_줄을_버리지_않는다() {
        var document = FeatureSpecDocument.parse(MD.replace("앵커: bo-x-detail-e01", "앵커: 없음"));

        assertThat(document.functions()).hasSize(1);
        assertThat(document.functions().get(0).no()).isEmpty();
        assertThat(document.functions().get(0).detail()).startsWith("사용자명 클릭");
    }

    /** 기획자용 이름과 이동 종류는 이미 md 에 있으므로 버리지 않고 구조로 읽는다. */
    @Test
    void 라벨과_여러_이동_종류를_읽는다() {
        String md = MD.replace(
                "좌표: id=userNm / 앵커: bo-x-detail-e01 / 이동: bo-y-detail",
                "좌표: id=userNm / 라벨: 선택 완료 / 이동modal: pt-modal-confirm / 앵커: bo-x-detail-e01");

        var function = FeatureSpecDocument.parse(md).functions().get(0);

        assertThat(function.label()).isEqualTo("선택 완료");
        assertThat(function.moveType()).isEqualTo(FeatureSpecDocument.MoveType.MODAL);
        assertThat(function.moveTo()).isEqualTo("pt-modal-confirm");
    }
}
