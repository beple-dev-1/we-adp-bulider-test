package com.bizplay.builder.featurespec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureSpecContentReaderTest {

    private final FeatureSpecContentReader reader = new FeatureSpecContentReader(new ObjectMapper());

    @Test
    void 구조화_응답의_모든_근거와_화면_이동을_검증한다() throws Exception {
        FeatureSpecContent content = reader.read(valid("IA:list"),
                Set.of("MD:line:1", "HTML:screen.html#save", "IA:list"), Set.of("detail", "list"));

        assertThat(content.functions()).hasSize(1);
        assertThat(content.transitions().get(0).targetScreenId()).isEqualTo("list");
        assertThat(reader.evidenceIds(content)).containsExactly(
                "HTML:screen.html#save", "IA:list", "MD:line:1");
    }

    @Test
    void 근거_목록에_없는_ID는_거부한다() {
        assertThatThrownBy(() -> reader.read(valid("MD:없는줄"),
                Set.of("MD:line:1", "HTML:screen.html#save"), Set.of("detail", "list")))
                .isInstanceOf(IOException.class).hasMessageContaining("근거");
    }

    @Test
    void 존재하지_않는_이동_화면은_거부한다() {
        assertThatThrownBy(() -> reader.read(valid("IA:list").replace("\"targetScreenId\":\"list\"",
                        "\"targetScreenId\":\"ghost\""),
                Set.of("MD:line:1", "HTML:screen.html#save", "IA:list"), Set.of("detail", "list")))
                .isInstanceOf(IOException.class).hasMessageContaining("이동 대상");
    }

    private String valid(String transitionEvidence) {
        return """
                {"title":"배송 상세","overview":{"purpose":"배송 건을 확인한다.","scope":"배송 상세 화면",
                "evidenceIds":["MD:line:1"]},"preconditions":[],
                "functions":[{"name":"저장","trigger":"저장 선택","precondition":"입력값 정상",
                "processing":"입력 내용을 저장한다.","result":"저장 완료를 표시한다.",
                "evidenceIds":["HTML:screen.html#save"]}],"fields":[],"businessRules":[],
                "permissionRules":[],"messages":[],
                "transitions":[{"action":"목록 선택","targetScreenId":"list","result":"목록으로 이동한다.",
                "evidenceIds":["%s"]}],"integrations":[]}
                """.formatted(transitionEvidence);
    }
}
