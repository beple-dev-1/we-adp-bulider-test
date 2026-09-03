package com.bizplay.builder.intake;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>V7 을 「자료가 있는 DB」에 대고 돌린다.</b>
 *
 * <p>⛔ <b>2026-08-16 에 여기서 데었다.</b> 다른 모든 테스트는 <b>빈 DB</b> 에 Flyway 를 처음부터
 * 돌리므로 <b>자료를 옮기는 SQL 이 한 줄도 실행되지 않는다</b> — V7 이 옛 값을 옮기기 전에 새 CHECK 를
 * 걸어 두었는데도 <b>240건이 전부 초록</b>이었고, 병주가 서버를 띄우자마자
 * {@code 23514 "violated by some row"} 로 죽었다.
 *
 * <p>그래서 이 테스트만 <b>스프링을 안 띄우고</b> 임베디드 PostgreSQL 을 직접 잡는다.
 * <b>V6 까지 올려 옛 모양으로 줄을 앉힌 뒤 V7 을 돌린다</b> — 그것이 실물이 겪는 길이다.
 *
 * <p>⛔ <b>이 테스트를 「느리다」고 지우지 마라.</b> 자료를 옮기는 마이그레이션이 또 나오면
 * 여기에 줄을 하나 더 앉혀라. 빈 DB 의 초록은 이 부류의 버그를 <b>한 건도</b> 못 잡는다.
 */
class V7LegacyDataMigrationTest {

    /** ⚠ 이 클래스 하나에 한 번만 띄운다 — PostgreSQL 을 잡는 값이 싸지 않다. */
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    /**
     * 옛 모양의 DB 를 세우고 V7 을 돌린다.
     * ⚠ 이름이 영문인 것은 규칙이다 — 한글로 적는 것은 {@code @Test} 메서드뿐이다
     * (→ {@code docs/coding-conventions.md}).
     */
    @BeforeAll
    static void migrateLegacyDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);

        migrateTo(dataSource, "6");
        seedLegacyRows();
        migrateTo(dataSource, "7");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    /**
     * ⛔ <b>이것이 병주의 서버를 죽인 자리다.</b> 옛 {@code NORMALIZE} 줄을 옮기기 전에
     * 새 {@code CHECK} 를 걸면 여기서 {@code 23514} 로 통째로 실패한다.
     */
    @Test
    void 옛_NORMALIZE_이력이_UNDERSTAND_로_옮겨지고_지워지지_않는다() {
        List<Map<String, Object>> runs = jdbc.queryForList(
                "select run_kind, state from builder.adk_builder_document_processing_run order by id");

        assertThat(runs).hasSize(2);
        assertThat(runs).extracting(row -> row.get("run_kind"))
                .as("⛔ 시도 이력은 지우지 않는다 — 무엇을 왜 했는지가 이 표의 존재 이유다")
                .containsExactly("EXTRACT", "UNDERSTAND");
    }

    /**
     * ⛔ <b>둘째로 터질 자리였다.</b> 굳은 시도를 닫기 전에 유일 인덱스를 걸면
     * 한 문서에 살아 있는 시도가 둘일 때 인덱스가 못 선다 — 이것도 빈 DB 에서는 안 걸린다.
     */
    @Test
    void 굳은_시도는_인덱스를_걸기_전에_닫힌다() {
        Integer live = jdbc.queryForObject("""
                select count(*) from builder.adk_builder_document_processing_run
                 where state in ('WAITING', 'RUNNING')""", Integer.class);

        assertThat(live).isZero();
        assertThat(jdbc.queryForObject("""
                select error_message from builder.adk_builder_document_processing_run
                 where id = '0000002'""", String.class))
                .contains("서버가 다시 뜨면서 닫았다");
    }

    /**
     * ⚠ 옛 상태 이름만 보고 갈면 안 된다 — <b>파일이 있느냐</b>가 새 규칙의 갈림길이다.
     * 직접 입력만 있는 문서는 새 규칙에서 <b>확인할 것이 없다.</b>
     */
    @Test
    void 확인_대기였던_직접_입력_문서는_등록_완료로_앉고_정리본이_문서_내용이_된다() {
        Map<String, Object> document = jdbc.queryForMap("""
                select content_state, document_content, typed_content, extracted_content
                  from builder.adk_builder_received_document where id = '0000001'""");

        assertThat(document.get("content_state")).isEqualTo("READY");
        assertThat(document.get("document_content"))
                .as("⛔ 사람이 고쳐 둔 정리본을 버리지 않는다").isEqualTo("AI 가 정리해 둔 본문");
        assertThat(document.get("typed_content"))
                .as("⛔ 원문은 그대로다").isEqualTo("상신할 때 임시저장이 됐으면 좋겠다");
    }

    /** 파일이 있는 확인 대기는 그대로 남고, <b>확인 화면이 보여줄 글</b>이 채워져 있어야 한다. */
    @Test
    void 파일이_있는_확인_대기는_남고_확인_화면에_보여줄_글이_채워진다() {
        Map<String, Object> document = jdbc.queryForMap("""
                select content_state, extracted_content, document_content
                  from builder.adk_builder_received_document where id = '0000002'""");

        assertThat(document.get("content_state")).isEqualTo("REVIEW_REQUIRED");
        assertThat(document.get("extracted_content"))
                .as("⛔ 비어 있으면 확인 화면이 민무늬로 뜨고 확인 완료가 막힌다")
                .isEqualTo("스캔에서 읽어 낸 본문");
    }

    /** 글이 아직 없는 첨부는 멀티모달이 읽도록 줄에 세운다. ⚠ 부팅 청소가 이것을 데려간다. */
    @Test
    void 글이_없는_첨부는_줄에_선다() {
        assertThat(jdbc.queryForObject("""
                select content_state from builder.adk_builder_received_document where id = '0000003'""",
                String.class)).isEqualTo("QUEUED");
    }

    /** ⛔ 처리 방향은 열째 폐기다 — 열이 남아 있으면 화면이 그것을 다시 집어 든다. */
    @Test
    void 폐기한_열이_실제로_사라진다() {
        assertThat(columnsOf("adk_builder_intake"))
                .doesNotContain("process_type").contains("requirement_state");
        assertThat(columnsOf("adk_builder_received_document"))
                .doesNotContain("preparation_state", "normalized_content", "normalized_confirmed_at")
                .contains("content_state", "document_content", "content_confirmed_at");
    }

    /** 요구사항 채번 카운터가 프로젝트마다 0 에서 선다. */
    @Test
    void 요구사항_채번_카운터가_선다() {
        assertThat(jdbc.queryForObject(
                "select requirement_seq from builder.adk_builder_project where id = '0000001'",
                Integer.class)).isZero();
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private static void migrateTo(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("builder")
                .defaultSchema("builder")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    /**
     * <b>V6 까지만 올라간 DB 에 옛 모양 그대로 앉힌다.</b>
     * ⚠ 여기 적힌 열 이름들은 <b>V7 이 지우는 것들</b>이다 — 그래서 이 SQL 은 여기서만 쓸 수 있다.
     */
    private static void seedLegacyRows() {
        jdbc.update("""
                insert into builder.adk_builder_account (id, login_id, name, email, password_hash)
                values ('0000001', 'planner', '기획자', 'planner@example.com', '해시')""");
        jdbc.update("""
                insert into builder.adk_builder_project
                       (id, name, repo_url, default_branch, sealed_token, token_nonce, state)
                values ('0000001', '탐나는전', 'https://gitlab.example.com/x.git', 'main',
                        '\\x00'::bytea, '\\x00'::bytea, 'READY')""");

        // ① 직접 입력 + 정리본 확인 대기 — 병주 DB 에 실제로 있던 모양이다.
        seedIntake("0000001", "8/13 운영회의 회의록", "UNDECIDED");
        jdbc.update("""
                insert into builder.adk_builder_received_document
                       (id, intake_id, document_type, typed_content,
                        preparation_state, normalized_content)
                values ('0000001', '0000001', 'MEETING_MINUTES', '상신할 때 임시저장이 됐으면 좋겠다',
                        'REVIEW_REQUIRED', 'AI 가 정리해 둔 본문')""");

        // ② 파일이 있는 확인 대기 — 확인 화면이 보여줄 글이 정리본에만 있다.
        seedIntake("0000002", "8/5 회의록 스캔", "REQUIREMENTS");
        jdbc.update("""
                insert into builder.adk_builder_received_document
                       (id, intake_id, document_type, original_name, server_path, byte_size,
                        preparation_state, normalized_content)
                values ('0000002', '0000002', 'MEETING_MINUTES', '회의록.pdf', '/tmp/회의록.pdf', 1024,
                        'REVIEW_REQUIRED', '스캔에서 읽어 낸 본문')""");

        // ③ 파일은 있는데 아직 글이 없다 — 멀티모달이 읽어야 한다.
        seedIntake("0000003", "제안서 v3", "REFERENCE");
        jdbc.update("""
                insert into builder.adk_builder_received_document
                       (id, intake_id, document_type, original_name, server_path, byte_size,
                        preparation_state)
                values ('0000003', '0000003', 'PROPOSAL', '제안서.pdf', '/tmp/제안서.pdf', 2048, 'PENDING')""");

        // 시도 둘 — 옛 갈래(NORMALIZE)와 굳은 채로 남은 것.
        jdbc.update("""
                insert into builder.adk_builder_document_processing_run (id, document_id, run_kind, state)
                values ('0000001', '0000001', 'EXTRACT', 'SUCCEEDED')""");
        jdbc.update("""
                insert into builder.adk_builder_document_processing_run (id, document_id, run_kind, state)
                values ('0000002', '0000001', 'NORMALIZE', 'RUNNING')""");
    }

    private static void seedIntake(String id, String title, String processType) {
        jdbc.update("""
                insert into builder.adk_builder_intake (id, project_id, title, uploaded_by, process_type)
                values (?, '0000001', ?, '0000001', ?)""", id, title, processType);
    }

    private static List<String> columnsOf(String table) {
        return jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = 'builder' and table_name = ?""", String.class, table);
    }
}
