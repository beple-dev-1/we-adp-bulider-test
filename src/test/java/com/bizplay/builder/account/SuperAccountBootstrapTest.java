package com.bizplay.builder.account;

import com.bizplay.builder.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class SuperAccountBootstrapTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SuperAccountBootstrap bootstrap;

    @Test
    void 부팅하면_슈퍼계정이_서_있다() {
        Account superUser = accounts.selectByLoginId("admin").orElseThrow();
        assertThat(superUser.isSuperAccount()).isTrue();
        assertThat(superUser.isMustChangePassword()).isTrue();
        assertThat(encoder.matches("firstpass", superUser.getPasswordHash())).isTrue();
    }

    @Test
    void 다시_돌려도_바꾼_비밀번호를_안_되돌린다() {
        Account superUser = accounts.selectByLoginId("admin").orElseThrow();
        // ⚠ 「사람이 이미 비밀번호를 바꿨다」는 이 시험의 전제다. 2026-08-15 까지는
        //    superUser.changePassword(...) + save 였는데, 엔티티의 상태 변경 메서드를 걷어내며
        //    매퍼의 update 로 옮겼다. ⛔ 이 줄을 지우지 마라 — 지우면 부팅이 되돌리는지를 재는
        //    시험이 「원래 안 바뀐 것」을 재는 시험으로 조용히 바뀐다.
        accounts.updatePassword(superUser.getId(), encoder.encode("사람이바꾼비번"));

        bootstrap.run(null);

        Account reloaded = accounts.selectByLoginId("admin").orElseThrow();
        assertThat(encoder.matches("사람이바꾼비번", reloaded.getPasswordHash())).isTrue();
        assertThat(reloaded.isMustChangePassword()).isFalse();
    }
}
