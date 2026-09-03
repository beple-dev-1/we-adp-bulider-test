package com.bizplay.builder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

class BuilderApplicationTest extends AbstractDbTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MultipartProperties multipart;

    @Test
    void 받은_문서는_파일당_20MB까지_올릴_수_있다() {
        assertThat(multipart.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(multipart.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(25));
    }

    /**
     * ⚠ 표 이름을 여기 박아 둔 것은 일부러다. {@code ddl-auto: validate} 는 엔티티가 가리키는 표만
     * 보므로 <b>엔티티와 마이그레이션을 같이 틀리면 둘 다 초록</b>이 된다. 여기가 그 짝을 밖에서 잰다.
     */
    @Test
    void 마이그레이션이_돌아_표_셋이_새_이름으로_생긴다() {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from information_schema.tables
                 where table_schema = 'builder'
                   and table_name in ('adk_builder_account',
                                      'adk_builder_claude_credential',
                                      'adk_builder_project')
                """,
                Integer.class);
        assertThat(count).isEqualTo(3);
    }

    /** 옛 이름이 남아 있으면 소급이 반만 된 것이다 — 그 상태로는 FK 가 엉뚱한 표를 건다. */
    @Test
    void 옛_이름의_표는_남아_있지_않다() {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from information_schema.tables
                 where table_schema = 'builder'
                   and table_name in ('account', 'claude_credential', 'project')
                """,
                Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void 메뉴구조도_정본과_행과_확정판_표가_생긴다() {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from information_schema.tables
                 where table_schema = 'builder'
                   and table_name in ('adk_builder_ia_structure',
                                      'adk_builder_ia_row',
                                      'adk_builder_ia_revision')
                """, Integer.class);
        assertThat(count).isEqualTo(3);
        Integer depth7 = jdbc.queryForObject(
                """
                select count(*) from information_schema.columns
                 where table_schema = 'builder'
                   and table_name = 'adk_builder_ia_row'
                   and column_name = 'depth7'
                """, Integer.class);
        assertThat(depth7).isOne();
    }
}
