package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 신규 화면의 임시 화면ID. 정본: {@code docs/superpowers/specs/2026-08-22-new-screen-id-design.md}. */
class TemporaryScreenIdTest {

    @Test
    void 기본키_앞에_tmp_를_붙인다() {
        assertThat(TemporaryScreenId.of("0000042")).isEqualTo("tmp-0000042");
    }

    @Test
    void 빌더가_지은_이름과_기획_저장소_이름을_가려낸다() {
        assertThat(TemporaryScreenId.isTemporary("tmp-0000042")).isTrue();
        assertThat(TemporaryScreenId.isTemporary("wv-appr-write")).isFalse();
        assertThat(TemporaryScreenId.isTemporary(null)).isFalse();
    }

    /** ⚠ 기본키가 아닌 값이 새면 그것이 그대로 클론 안의 파일 이름이 된다 — 여기서 막는다. */
    @Test
    void 일곱자리_숫자가_아니면_거절한다() {
        assertThatThrownBy(() -> TemporaryScreenId.of("42"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemporaryScreenId.of("../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemporaryScreenId.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
