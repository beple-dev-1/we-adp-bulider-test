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

    /** ⭐ AI 가 고칠 화면과 정한 것(권장안)까지 채운다 — 사람은 생성 전에 확인만 한다 (2026-09-24). */
    @Test
    void 고칠_화면과_권장안으로_정한_것을_읽는다() throws Exception {
        SrtAiAnalysis result = reader.read("""
                {"eligible":true,"analysisComment":"이름 입력 검증을 더합니다.",
                 "requirements":["이름 칸에 한글만 받는다."],"acceptanceCriteria":["영문을 넣으면 안내가 뜬다."],
                 "screenChange":true,
                 "screens":[{"screenId":"EXW-UWV-70-30-10-C","reason":"이름 칸에 검증을 더한다"}],
                 "decisions":[{"question":"띄어쓰기를 허용할지","answer":"허용하지 않는다"}]}
                """);

        assertThat(result.screenChange()).isTrue();
        assertThat(result.screens()).extracting(SrtAiAnalysis.Target::screenId).containsExactly("EXW-UWV-70-30-10-C");
        assertThat(result.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.question()).isEqualTo("띄어쓰기를 허용할지");
            assertThat(decision.answer()).isEqualTo("허용하지 않는다");
            assertThat(decision.recommended()).isTrue();
        });
    }

    /** ⛔ 권장안이 빈 것은 확인 필요를 남긴 것이다 — 받지 않는다. */
    @Test
    void 권장안이_빈_정한_것은_거절한다() {
        assertThatThrownBy(() -> reader.read("""
                {"eligible":true,"analysisComment":"요청입니다.","requirements":["바꾼다."],
                 "acceptanceCriteria":["바뀐다."],"screenChange":false,"screens":[],
                 "decisions":[{"question":"언제 바꿀지","answer":""}]}
                """))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("권장안");
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
