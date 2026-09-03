package com.bizplay.builder.screenid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandardScreenIdFormatTest {

    @Test
    void 종류가_팝업이면_화면유형보다_먼저다() {
        assertThat(StandardScreenIdFormat.letterOf("팝업", "목록")).isEqualTo("P");
        assertThat(StandardScreenIdFormat.letterOf("모달", "상세")).isEqualTo("M");
    }

    @Test
    void 종류가_화면이면_화면유형이_글자를_정한다() {
        assertThat(StandardScreenIdFormat.letterOf("화면", "목록")).isEqualTo("L");
        assertThat(StandardScreenIdFormat.letterOf("화면", "상세")).isEqualTo("D");
        assertThat(StandardScreenIdFormat.letterOf("화면", "등록")).isEqualTo("R");
        assertThat(StandardScreenIdFormat.letterOf("화면", "수정")).isEqualTo("U");
        assertThat(StandardScreenIdFormat.letterOf("화면", "안내")).isEqualTo("G");
    }

    @Test
    void 모르는_유형은_미분류_X_다() {
        assertThat(StandardScreenIdFormat.letterOf("화면", "미분류")).isEqualTo("X");
        assertThat(StandardScreenIdFormat.letterOf("화면", null)).isEqualTo("X");
        assertThat(StandardScreenIdFormat.letterOf(null, "목록")).isEqualTo("L");
    }

    @Test
    void 등록과_수정은_C_와_E_를_쓰지_않는다() {
        // 여섯째 마디가 S·N·C 라서 C 가 두 자리에서 다른 뜻이 되면 사람이 헷갈린다.
        assertThat(StandardScreenIdFormat.letterOf("화면", "등록")).isNotEqualTo("C");
        assertThat(StandardScreenIdFormat.letterOf("화면", "수정")).isNotEqualTo("E");
    }

    @Test
    void 다섯마디는_기능그룹_세자리와_일련번호_두자리를_영으로_채운다() {
        assertThat(StandardScreenIdFormat.core("PS", "WB", "MRC", 10, "L", 1))
                .isEqualTo("PS-WB-MRC-010-L01");
    }

    @Test
    void 일련번호가_아흔아홉을_넘으면_세자리로_늘어난다() {
        assertThat(StandardScreenIdFormat.core("PS", "WB", "MRC", 10, "L", 100))
                .isEqualTo("PS-WB-MRC-010-L100");
    }

    @Test
    void 상태마디는_저장값이_아니라_조립할_때_붙는다() {
        String core = "PS-WB-MRC-010-L01";
        assertThat(StandardScreenIdFormat.display(core, ScreenStandardId.Origin.S))
                .isEqualTo("PS-WB-MRC-010-L01-S");
        assertThat(StandardScreenIdFormat.display(core, ScreenStandardId.Origin.N))
                .isEqualTo("PS-WB-MRC-010-L01-N");
    }
}
