package com.bizplay.builder.ia;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IaDocumentCodecTest {

    private final IaDocumentCodec codec = new IaDocumentCodec();

    @Test
    void 최초_ia_md의_경로와_이름표를_depth_칸으로_가져온다() {
        IaDocumentCodec.Parsed parsed = codec.parse("""
                # backoffice IA 이름표

                ## 이름표
                - approval: 전자결재
                - approval/document: 결재 문서

                --- 배치 ---
                - 순서: 010 / 경로: approval/document / 화면: bo-appr-list
                - 순서: 020 / 경로: approval / 화면:
                """);

        assertThat(parsed.placements()).containsExactly(
                new IaDocumentCodec.Placement(1, "approval/document", "bo-appr-list",
                        List.of("전자결재", "결재 문서")),
                new IaDocumentCodec.Placement(2, "approval", null, List.of("전자결재")));
        assertThat(parsed.hash()).hasSize(64);
    }

    @Test
    void 배치_구간의_산문_줄은_배치_행으로_세지_않는다() {
        // lspnoffice 실측 (2026-08-21) — 배치 표 아래에 설명 문단이 온다. 이 줄이 배치 행으로
        // 오인되면 validatePath("") 가 던져 그 시스템 전체가 재료에서 빠진다.
        IaDocumentCodec.Parsed parsed = codec.parse("""
                ## 이름표
                - approval: 전자결재

                --- 배치 ---
                - 순서: 010 / 경로: approval / 화면: bo-appr-list
                - **상위화면 사슬이 나르는 다섯**: 목록이 상세를 열고, 상세가 팝업을 연다.
                - 콜론조차 없는 산문 줄도 있다
                """);

        assertThat(parsed.placements()).containsExactly(
                new IaDocumentCodec.Placement(1, "approval", "bo-appr-list", List.of("전자결재")));
    }

    @Test
    void 경로_칸이_있는데_꼴이_틀린_배치_줄은_여전히_던진다() {
        assertThatThrownBy(() -> codec.parse("""
                --- 배치 ---
                - 순서: 010 / 경로: 한글경로 / 화면: bo-appr-list
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경로");
    }

    @Test
    void 화면_칸만_있고_경로가_없는_배치_줄은_여전히_던진다() {
        assertThatThrownBy(() -> codec.parse("""
                --- 배치 ---
                - 화면: bo-appr-list
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void depth_다섯_칸을_결정적인_ia_md로_직렬화한다() {
        List<IaRow> rows = List.of(
                row("0000002", 20, "approval/document/detail", "전자결재", "결재 문서", "상세", "bo-detail"),
                row("0000001", 10, "approval/document", "전자결재", "결재 문서", null, null));

        String published = codec.serialize("backoffice", rows);

        assertThat(published).contains("- approval: 전자결재")
                .contains("- approval/document: 결재 문서")
                .contains("- approval/document/detail: 상세")
                .containsSubsequence(
                        "- 순서: 010 / 경로: approval/document / 화면: ",
                        "- 순서: 020 / 경로: approval/document/detail / 화면: bo-detail");
        assertThat(codec.serialize("backoffice", rows)).isEqualTo(published);
    }

    @Test
    void depth_중간이_비거나_경로_깊이와_다르면_거절한다() {
        IaRow gap = new IaRow("0000001", "0000001", 10, "approval/detail", "전자결재",
                null, "상세", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> codec.validateRows(List.of(gap)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Depth");
    }

    @Test
    void 같은_경로키가_두_행에_있으면_거절한다() {
        // ⑥ 마지막 그물(브리프 §3-1) — path_key 에는 UNIQUE 도 인덱스도 없다(V19:43-44 실측).
        // 화면ID 는 서로 다르게 둬서, 경로키 중복만으로 걸리는지 본다(화면ID 중복 검사와 헷갈리지 않게).
        IaRow first = new IaRow("0000001", "0000001", 10, "approval/document", "전자결재",
                "결재 문서", null, null, null, null, null, null, null, null, "bo-appr-list", null, null);
        IaRow second = new IaRow("0000002", "0000001", 20, "approval/document", "전자결재",
                "결재 문서", null, null, null, null, null, null, null, null, "bo-appr-detail", null, null);

        // ⚠ "경로" 만으로 좁히면 validatePath 의 다른 두 오류도 걸린다 — "두 번" 으로 좁힌다
        //    (코드리뷰 지적, 2026-09-04).
        assertThatThrownBy(() -> codec.validateRows(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("두 번");
    }

    private IaRow row(String id, int order, String key, String d1, String d2, String d3, String screen) {
        return new IaRow(id, "0000001", order, key, d1, d2, d3, null, null, null, null,
                null, null, null, screen, null, null);
    }
}
