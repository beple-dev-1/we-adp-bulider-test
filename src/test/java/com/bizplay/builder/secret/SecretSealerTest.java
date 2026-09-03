package com.bizplay.builder.secret;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretSealerTest {

    private final SecretSealer sealer = new SecretSealer("A".repeat(42) + "g=");

    @Test
    void 봉인한_것을_다시_푼다() {
        Sealed sealed = sealer.seal("glpat-비밀토큰");
        assertThat(sealer.unseal(sealed)).isEqualTo("glpat-비밀토큰");
    }

    @Test
    void 같은_값도_봉인할_때마다_다르게_보인다() {
        Sealed a = sealer.seal("같은값");
        Sealed b = sealer.seal("같은값");
        assertThat(a.cipher()).isNotEqualTo(b.cipher());
        assertThat(a.nonce()).isNotEqualTo(b.nonce());
    }

    @Test
    void 다른_열쇠로는_안_풀린다() {
        Sealed sealed = sealer.seal("비밀");
        SecretSealer otherKey = new SecretSealer("B".repeat(42) + "g=");
        assertThatThrownBy(() -> otherKey.unseal(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("봉인을 풀 수 없다");
    }
}
