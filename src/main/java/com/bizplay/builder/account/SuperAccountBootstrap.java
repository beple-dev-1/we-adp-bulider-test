package com.bizplay.builder.account;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.id.IdSequence;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SuperAccountBootstrap implements ApplicationRunner {

    private final AccountMapper accounts;
    private final PasswordEncoder encoder;
    private final BuilderProperties properties;
    private final IdSequence ids;

    public SuperAccountBootstrap(AccountMapper accounts, PasswordEncoder encoder,
                                 BuilderProperties properties, IdSequence ids) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.properties = properties;
        this.ids = ids;
    }

    @Override
    public void run(ApplicationArguments args) {
        String loginId = properties.superAccountLoginId();
        // ⛔ 이미 있는 계정을 되살리지 않는다. 사람이 바꾼 비밀번호를 부팅 때마다 설정값으로
        //    덮으면, 설정 파일을 읽을 수 있는 사람이 언제든 슈퍼계정으로 들어올 수 있게 된다.
        if (accounts.selectByLoginId(loginId).isPresent()) {
            return;
        }
        accounts.insert(Account.create(
                ids.next(IdSequence.Kind.ACCOUNT),
                loginId,
                "설치 관리자",
                loginId + "@localhost",
                encoder.encode(properties.superAccountPassword()),
                true));
    }
}
