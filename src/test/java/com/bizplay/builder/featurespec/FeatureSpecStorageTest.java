package com.bizplay.builder.featurespec;

import com.bizplay.builder.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureSpecStorageTest extends AbstractDbTest {

    @Autowired FeatureSpecMapper specs;
    @Autowired FeatureSpecStorage storage;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 같은_생성_ID만_새_개정판으로_승격한다() {
        Instant now = Instant.now();
        assertThat(specs.beginGeneration("P1", "bo", "detail", "run-1", "a".repeat(64),
                FeatureSpecWorker.GENERATOR_VERSION, FeatureSpecWorker.SCHEMA_VERSION,
                now.minusSeconds(60), now)).isOne();

        assertThat(storage.save("P1", "bo", "detail", "다른-ID", "a".repeat(64),
                FeatureSpecWorker.GENERATOR_VERSION, FeatureSpecWorker.SCHEMA_VERSION,
                "{}", "[]", "<article>문서</article>")).isFalse();
        assertThat(storage.save("P1", "bo", "detail", "run-1", "a".repeat(64),
                FeatureSpecWorker.GENERATOR_VERSION, FeatureSpecWorker.SCHEMA_VERSION,
                "{}", "[]", "<article>문서</article>")).isTrue();

        FeatureSpecCurrent current = specs.selectCurrent("P1", "bo", "detail").orElseThrow();
        assertThat(current.state()).isEqualTo(FeatureSpecState.DONE);
        assertThat(current.currentRevisionNo()).isEqualTo(1);
        assertThat(specs.selectRevision(current.currentRevisionId()).orElseThrow().documentHtml())
                .contains("문서");
    }

    @Test
    void 진행_중인_요청은_중복으로_선점하지_않는다() {
        Instant now = Instant.now();
        assertThat(specs.beginGeneration("P2", "bo", "detail", "run-1", "a".repeat(64), "g", "s",
                now.minusSeconds(60), now)).isOne();
        assertThat(specs.beginGeneration("P2", "bo", "detail", "run-2", "a".repeat(64), "g", "s",
                now.minusSeconds(60), now)).isZero();
        assertThat(specs.selectCurrent("P2", "bo", "detail").orElseThrow().generationId()).isEqualTo("run-1");
    }

    @Test
    void 오래_멈춘_생성은_새_요청이_회수한다() {
        Instant now = Instant.now();
        specs.beginGeneration("P3", "bo", "detail", "run-old", "a".repeat(64), "g", "s",
                now.minusSeconds(60), now);
        jdbc.update("update builder.adk_builder_feature_spec set generation_started_at = now() - interval '2 hours' "
                + "where project_id = 'P3'");

        assertThat(specs.beginGeneration("P3", "bo", "detail", "run-new", "b".repeat(64), "g", "s",
                now.minusSeconds(3600), now)).isOne();
        assertThat(specs.selectCurrent("P3", "bo", "detail").orElseThrow().generationId()).isEqualTo("run-new");
    }
}
