package com.bizplay.builder.srt;

import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.config.BuilderProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SrtAiAnalyzerTest {

    @TempDir Path temp;

    @Test
    void SRT_원문을_Claude에_보내고_구조화된_결과를_읽는다() throws Exception {
        ClaudeCredentialRunner runner = mock(ClaudeCredentialRunner.class);
        when(runner.run(eq("account-1"), any(Path.class), any(Path.class), any(Duration.class),
                anyList(), anyString(), any()))
                .thenReturn(Optional.of(new ClaudeResult(0, false, "end_turn", null, """
                        {"eligible":true,"analysisComment":"버튼 명칭을 명확하게 바꾸는 요청입니다.",
                         "requirements":["버튼 명칭을 변경한다."],
                         "acceptanceCriteria":["변경된 명칭이 표시된다."]}
                        """)));
        BuilderProperties properties = new BuilderProperties("admin", "password",
                Base64.getEncoder().encodeToString(new byte[32]), temp,
                Duration.ofSeconds(10), 1, 1, Duration.ofSeconds(10));
        SrtAiAnalyzer analyzer = new SrtAiAnalyzer(runner, properties,
                new SrtAiAnalysisReader(new ObjectMapper()));
        Srt srt = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "버튼명 변경", "확인을 등록으로 바꾼다.", null,
                "account-1", "frd-1", null, Srt.AnalysisState.READY, null, null, null);

        SrtAiAnalysis result = analyzer.analyze(srt);

        assertThat(result.eligible()).isTrue();
        assertThat(result.analysisComment()).isEqualTo("버튼 명칭을 명확하게 바꾸는 요청입니다.");
        assertThat(result.requirements()).containsExactly("버튼 명칭을 변경한다.");
        verify(runner).run(eq("account-1"), any(Path.class), any(Path.class),
                eq(Duration.ofSeconds(10)), anyList(),
                org.mockito.ArgumentMatchers.contains("FRD로 보낼지 판단하지도 않는다"), any());
        assertThat(temp.resolve("srt-analysis-runs")).isDirectory();
        try (var children = java.nio.file.Files.list(temp.resolve("srt-analysis-runs"))) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void Claude_API_오류_원문은_SRT_화면용_예외에_노출하지_않는다() throws Exception {
        ClaudeCredentialRunner runner = mock(ClaudeCredentialRunner.class);
        when(runner.run(eq("account-1"), any(Path.class), any(Path.class), any(Duration.class),
                anyList(), anyString(), any()))
                .thenReturn(Optional.of(new ClaudeResult(1, true, "api_error", 529,
                        "API Error: 529 overloaded")));
        BuilderProperties properties = new BuilderProperties("admin", "password",
                Base64.getEncoder().encodeToString(new byte[32]), temp,
                Duration.ofSeconds(10), 1, 1, Duration.ofSeconds(10));
        SrtAiAnalyzer analyzer = new SrtAiAnalyzer(runner, properties,
                new SrtAiAnalysisReader(new ObjectMapper()));
        Srt srt = new Srt("srt-1", "project-1", 1, Srt.SourceKind.DIRECT,
                null, "버튼명 변경", "확인을 등록으로 바꾼다.", null,
                "account-1", "frd-1", null, Srt.AnalysisState.READY, null, null, null);

        assertThatThrownBy(() -> analyzer.analyze(srt))
                .isInstanceOf(SrtAiAnalyzer.AnalysisException.class)
                .hasMessageContaining("AI 서버가 혼잡")
                .hasMessageNotContaining("529")
                .hasMessageNotContaining("API Error");
    }
}
