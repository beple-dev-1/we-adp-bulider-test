package com.bizplay.builder;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * 테스트는 zonky 가 띄우는 빈 PostgreSQL 을 쓴다. Docker 가 필요 없다.
 * 스키마는 Flyway 가 만든다 — 빈 DB 라서 테스트에서 강제로 켠다.
 *
 * <p>⚠ {@code @Transactional} 을 지우지 마라. zonky 는 DB 를 <b>컨텍스트마다 한 번</b> 띄우고
 * 테스트 사이에 되돌려주지 않는다. 이게 없으면 한 테스트가 바꾼 계정·프로젝트가
 * 다음 테스트로 새어 <b>실행 순서에 따라 통과와 실패가 갈린다</b>(찾기 아주 어렵다).
 * 이웃 저장소 we-adk-admin 도 DB 테스트 전부에 같은 것을 달아 뒀다.
 *
 * <p>부팅에서 서는 슈퍼계정(Task 5)은 컨텍스트 시작 때 커밋되므로 롤백에 안 지워진다 — 그게 맞다.
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Transactional
@AutoConfigureEmbeddedDatabase(
        provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY,
        type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
public abstract class AbstractDbTest {

    /**
     * 엔티티를 손으로 만드는 테스트가 기본키를 받아 갈 자리.
     *
     * <p>⚠ 번호를 글자로 박지 마라({@code "0000001"}). 그 표를 쓰는 다른 테스트와 <b>부딪힌다</b> —
     * 부팅 슈퍼계정은 롤백에 안 지워져 계정 {@code '0000001'} 이 이미 나가 있다.
     */
    @org.springframework.beans.factory.annotation.Autowired
    protected com.bizplay.builder.id.IdSequence ids;

    /** 뒤의 모든 통합 테스트가 이 설치 설정을 쓴다. */
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("builder.super-account-login-id", () -> "admin");
        registry.add("builder.super-account-password", () -> "firstpass");
        registry.add("builder.secret-key-base64", () -> "A".repeat(42) + "g=");
        // Global Constraints: 경로는 Path 로만 만든다. "/" 를 문자열로 이어붙이지 않는다.
        registry.add("builder.data-root",
                () -> Path.of(System.getProperty("java.io.tmpdir"), "builder-test").toString());
        registry.add("builder.ai-run-timeout", () -> "10m");
    }
}
