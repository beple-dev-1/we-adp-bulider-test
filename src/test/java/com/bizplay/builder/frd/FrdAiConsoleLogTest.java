package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrdAiConsoleLogTest {

    @Test
    void 실행_인자에서_모델과_추론레벨을_같은_규칙으로_읽는다() {
        List<String> arguments = List.of("--allowed-tools", "Read", "--model", "opus",
                "--effort", "high");

        assertThat(FrdAiConsoleLog.modelOf(arguments)).isEqualTo("opus");
        assertThat(FrdAiConsoleLog.effortOf(arguments)).isEqualTo("high");
    }

    @Test
    void 지정하지_않은_실행값은_누락하지_않고_기본값으로_표시한다() {
        assertThat(FrdAiConsoleLog.modelOf(List.of())).isEqualTo("기본값(미지정)");
        assertThat(FrdAiConsoleLog.effortOf(List.of())).isEqualTo("기본값(미지정)");
    }
}
