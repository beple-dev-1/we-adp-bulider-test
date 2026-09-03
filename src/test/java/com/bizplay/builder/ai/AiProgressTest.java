package com.bizplay.builder.ai;

import com.bizplay.builder.ai.ClaudeRunner.Progress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도는 중인 AI 실행의 <b>진행 표시</b>. ⭐ <b>AI 자리 넷이 같이 쓴다</b> —
 * 화면 짚기·요구사항 분석·목업 만들기가 저마다 보관함을 만들지 않는다.
 *
 * <p>⚠ <b>메모리에 둔다 — DB 에 두지 않는다.</b> 진행은 상태가 아니라 곁가지다. 상태의 정본은
 * 각 표의 {@code state} 열이고, 서버가 재기동하면 <b>실행 자체가 죽으므로</b> 진행이 같이
 * 사라지는 것이 옳다. ⛔ 설계서의 「기다림을 메모리로 붙잡지 마라」는 <b>마법사 상태</b>를 두고
 * 한 말이다 — 이것과 섞지 마라.
 */
class AiProgressTest {

    private final AiProgress progress = new AiProgress();

    @Test
    void 아무것도_없으면_빈_목록이다() {
        assertThat(progress.of("frd:0000001")).isEmpty();
    }

    /** ⚠ <b>최근 것이 위</b>다 — 사람이 「지금 무엇을 하는 중」을 맨 먼저 읽는다. */
    @Test
    void 최근_것이_위로_온다() {
        progress.add("frd:0000001", new Progress(Progress.Kind.TOOL, "Read index.json"));
        progress.add("frd:0000001", new Progress(Progress.Kind.SAY, "후보를 넷으로 좁혔다"));

        assertThat(progress.of("frd:0000001")).extracting(Progress::text)
                .containsExactly("후보를 넷으로 좁혔다", "Read index.json");
    }

    /** ⛔ 끝없이 쌓지 마라 — 한 판이 수십 걸음이고 서버가 오래 산다. */
    @Test
    void 최근_스무_걸음만_남긴다() {
        for (int i = 1; i <= 30; i++) {
            progress.add("frd:0000001", new Progress(Progress.Kind.TOOL, "걸음 " + i));
        }

        assertThat(progress.of("frd:0000001")).hasSize(20);
        assertThat(progress.of("frd:0000001")).first()
                .extracting(Progress::text).isEqualTo("걸음 30");
    }

    /** ⛔ 끝나면 지운다 — 안 지우면 실행마다 한 칸씩 영원히 남는다. */
    @Test
    void 끝나면_지운다() {
        progress.add("frd:0000001", new Progress(Progress.Kind.TOOL, "Read index.json"));
        progress.clear("frd:0000001");

        assertThat(progress.of("frd:0000001")).isEmpty();
    }

    /** ⚠ 실행마다 따로다 — 여러 사람이 동시에 돌린다. */
    @Test
    void 실행마다_따로_쌓인다() {
        progress.add("frd:0000001", new Progress(Progress.Kind.TOOL, "이쪽"));
        progress.add("intake:0000002", new Progress(Progress.Kind.TOOL, "저쪽"));

        assertThat(progress.of("frd:0000001")).extracting(Progress::text).containsExactly("이쪽");
        assertThat(progress.of("intake:0000002")).extracting(Progress::text).containsExactly("저쪽");
    }

    /** ⚠ 같은 말이 잇달아 오면 한 줄로 둔다 — 모델이 같은 파일을 연달아 읽는 일이 있다. */
    @Test
    void 같은_말이_잇달아_오면_한_번만_남는다() {
        progress.add("frd:0000001", new Progress(Progress.Kind.TOOL, "Read index.json"));
        progress.add("frd:0000001", new Progress(Progress.Kind.TOOL, "Read index.json"));

        assertThat(progress.of("frd:0000001")).hasSize(1);
    }
}
