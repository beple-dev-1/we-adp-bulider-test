package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenDefinitionDocumentTest {

    @Test
    void 정의_블록이_없는_AI_응답에도_필수_구조를_보완한다() {
        String normalized = ScreenDefinitionDocument.normalizeStructure("# 화면 설명\n내용");

        assertThat(normalized).contains("--- 정의 ---", "--- 원본 글 ---");
    }

    @Test
    void 신규_화면_정의서를_검사기_계약에_맞게_보정한다() {
        String malformed = """
                --- 꼬리표 ---
                id: tmp-0000067 / system: bo / 기능: 폐업가맹점 목록 조회 / 작업: []

                --- 화면명세 ---
                화면명: 폐업가맹점 목록 조회

                --- 정의 ---
                - 구분: 이동 / 앵커: tmp-0000067-e01 / 이동: tmp-0000068
                """;

        String normalized = ScreenDefinitionDocument.normalizeStructure(malformed);

        assertThat(normalized)
                .contains("/ 과업: []")
                .doesNotContain("/ 작업: []")
                .contains("--- 정의 ---", "--- 원본 글 ---");
        assertThat(ScreenDefinitionDocument.normalizeStructure(normalized)).isEqualTo(normalized);
    }

    @Test
    void 전달본은_파일명과_본문의_임시_ID를_모두_개발_화면_ID로_바꾼다() {
        String document = """
                --- 꼬리표 ---
                id: tmp-0000067 / system: bo / 기능: 폐업가맹점 목록 조회 / 과업: []

                --- 정의 ---
                - 구분: 이동 / 앵커: tmp-0000067-e01 / 이동: tmp-0000068

                --- 원본 글 ---
                tmp-0000067에서 tmp-0000068로 이동한다.
                """;
        Map<String, String> deliveryIds = new LinkedHashMap<>();
        deliveryIds.put("tmp-0000067", "bo-merc-closed-list");
        deliveryIds.put("tmp-0000068", "bo-merc-closed-detail");

        String delivered = ScreenDefinitionDocument.forDelivery(document, deliveryIds);

        assertThat(delivered)
                .contains("id: bo-merc-closed-list")
                .contains("앵커: bo-merc-closed-list-e01")
                .contains("이동: bo-merc-closed-detail")
                .doesNotContain("tmp-0000067", "tmp-0000068");
    }
}
