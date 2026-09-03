package com.bizplay.builder.screendesign;

import com.bizplay.builder.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenDesignStorageTest extends AbstractDbTest {

    @Autowired ScreenDesignMapper designs;
    @Autowired ScreenDesignStorage storage;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 같은_생성_ID만_새_화면설계서_개정판으로_승격한다() {
        Instant now = Instant.now();
        assertThat(designs.beginGeneration("P1", "bo", "detail", "run-1", "a".repeat(64),
                ScreenDesignWorker.GENERATOR_VERSION, ScreenDesignWorker.SCHEMA_VERSION,
                now.minusSeconds(60), now)).isOne();

        assertThat(storage.save("P1", "bo", "detail", "다른-ID", "revision-wrong",
                "a".repeat(64), ScreenDesignWorker.GENERATOR_VERSION, ScreenDesignWorker.SCHEMA_VERSION,
                "{}", "<article>문서</article>", "P1/revision-wrong", "[]")).isFalse();
        assertThat(storage.save("P1", "bo", "detail", "run-1", "revision-1",
                "a".repeat(64), ScreenDesignWorker.GENERATOR_VERSION, ScreenDesignWorker.SCHEMA_VERSION,
                "{}", "<article>문서</article>", "P1/revision-1", "[]")).isTrue();

        ScreenDesignCurrent current = designs.selectCurrent("P1", "bo", "detail").orElseThrow();
        assertThat(current.state()).isEqualTo(ScreenDesignState.DONE);
        assertThat(current.currentRevisionNo()).isEqualTo(1);
        assertThat(designs.selectRevision(current.currentRevisionId()).orElseThrow().bundlePath())
                .isEqualTo("P1/revision-1");
    }

    @Test
    void 진행_중인_요청은_중복으로_선점하지_않고_오래된_요청만_회수한다() {
        Instant now = Instant.now();
        assertThat(designs.beginGeneration("P2", "bo", "detail", "run-1", "a".repeat(64),
                "g", "s", now.minusSeconds(60), now)).isOne();
        assertThat(designs.beginGeneration("P2", "bo", "detail", "run-2", "b".repeat(64),
                "g", "s", now.minusSeconds(60), now)).isZero();

        jdbc.update("update builder.adk_builder_screen_design set generation_started_at = now() - interval '2 hours' "
                + "where project_id = 'P2'");
        assertThat(designs.beginGeneration("P2", "bo", "detail", "run-3", "c".repeat(64),
                "g", "s", now.minusSeconds(3600), now)).isOne();
        assertThat(designs.selectCurrent("P2", "bo", "detail").orElseThrow().generationId())
                .isEqualTo("run-3");
    }

    @Test
    void 프로젝트_현재_개정판은_목록_조회_한_번으로_읽는다() {
        Instant now = Instant.now();
        designs.beginGeneration("P3", "bo", "one", "run-1", "a".repeat(64), "g", "s",
                now.minusSeconds(60), now);
        storage.save("P3", "bo", "one", "run-1", "revision-1", "a".repeat(64), "g", "s",
                "{}", "<article>하나</article>", "P3/revision-1", "[]");

        assertThat(designs.selectCurrentRevisionsByProject("P3"))
                .extracting(ScreenDesignRevision::screenId).containsExactly("one");
    }
}
