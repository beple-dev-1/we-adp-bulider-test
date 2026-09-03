package com.bizplay.builder.srt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrtAiAnalysisReaderTest {

    private final SrtAiAnalysisReader reader = new SrtAiAnalysisReader(new ObjectMapper());

    @Test
    void 개발_요청이면_정리된_요구사항과_완료_조건을_읽는다() throws Exception {
        SrtAiAnalysis result = reader.read("""
                {"eligible":true,"analysisComment":"버튼 명칭을 명확하게 바꾸는 단순 화면 변경입니다.",
                 "requirements":["저장 버튼의 명칭을 등록으로 변경한다."],
                 "acceptanceCriteria":["화면에 등록 버튼이 표시된다."]}
                """);

        assertThat(result.eligible()).isTrue();
        assertThat(result.analysisComment()).isEqualTo("버튼 명칭을 명확하게 바꾸는 단순 화면 변경입니다.");
        assertThat(result.requirements()).containsExactly("저장 버튼의 명칭을 등록으로 변경한다.");
        assertThat(result.acceptanceCriteria()).containsExactly("화면에 등록 버튼이 표시된다.");
    }

    @Test
    void 개발과_무관하면_거절_사유를_읽고_정의는_비운다() throws Exception {
        SrtAiAnalysis result = reader.read("""
                {"eligible":false,"rejectionReason":"개발 변경 내용을 확인할 수 없습니다.",
                 "requirements":[],"acceptanceCriteria":[]}
                """);

        assertThat(result.eligible()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("개발 변경 내용을 확인할 수 없습니다.");
        assertThat(result.requirements()).isEmpty();
    }

    @Test
    void 유효하다고_하면서_완료_조건을_비우면_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"eligible":true,"analysisComment":"버튼 변경 요청입니다.",
                 "requirements":["버튼을 변경한다."],"acceptanceCriteria":[]}
                """))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("요구사항과 완료 조건");
    }
}
