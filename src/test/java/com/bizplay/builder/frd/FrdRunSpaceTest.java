package com.bizplay.builder.frd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FRD 한 건이 쓰는 <b>실행 자리</b>의 계약.
 *
 * <p>⭐ <b>여기가 「자격은 지우고 세션은 남긴다」가 사는 자리다 (2026-08-19).</b> 종전에는 판이
 * 끝날 때 자리를 <b>통째로</b> 지워서 다음 판이 앞판의 대화를 이어 붙일 수 없었다 —
 * 그것이 답변 한 줄마다 저장소를 처음부터 다시 뒤진 까닭이다.
 */
class FrdRunSpaceTest {

    @Test
    void 성공한_판의_세션을_기억했다가_다음_판에_내준다(@TempDir Path root) throws IOException {
        FrdRunSpace space = new FrdRunSpace(root, "0000015");
        space.prepare();

        assertThat(space.resumableSession()).as("첫 판에는 이어붙일 것이 없다").isNull();

        space.remember("79a07238-1ceb-4b01-bfdd-241183d0686b");

        assertThat(new FrdRunSpace(root, "0000015").resumableSession())
                .as("자리를 새로 열어도 기억이 남아 있다 — 서버가 재기동해도 그렇다")
                .isEqualTo("79a07238-1ceb-4b01-bfdd-241183d0686b");
    }

    /**
     * ⛔ <b>자격을 디스크에 남기지 마라.</b> 자리를 남기게 된 뒤로 이 약속을 지키는 곳은
     * 여기 하나다 — 종전에는 폴더를 통째로 지우는 것이 이 몫까지 했다.
     */
    @Test
    void 자격만_지우고_세션_기록은_남긴다(@TempDir Path root) throws IOException {
        FrdRunSpace space = new FrdRunSpace(root, "0000015");
        space.prepare();
        Path credential = space.credentialDir().resolve(".credentials.json");
        Files.writeString(credential, "{\"claudeAiOauth\":{}}", StandardCharsets.UTF_8);
        Path sessionLog = space.credentialDir().resolve("projects").resolve("어떤클론");
        Files.createDirectories(sessionLog);
        Files.writeString(sessionLog.resolve("대화.jsonl"), "앞판의 대화", StandardCharsets.UTF_8);

        space.wipeCredential();

        assertThat(credential).as("남의 자격은 판이 끝나면 사라진다").doesNotExist();
        assertThat(sessionLog.resolve("대화.jsonl")).as("이어붙일 대화는 남는다").exists();
    }

    /** FRD 가 끝나면 요구사항 사본도 대화도 남길 까닭이 없다. 정본은 DB 다. */
    @Test
    void 통째로_지우면_아무것도_안_남는다(@TempDir Path root) throws IOException {
        FrdRunSpace space = new FrdRunSpace(root, "0000015");
        space.prepare();
        Files.writeString(space.workDir().resolve("요구사항.md"), "사업 내용", StandardCharsets.UTF_8);
        space.remember("79a07238-1ceb-4b01-bfdd-241183d0686b");

        space.wipe();

        assertThat(space.workDir()).doesNotExist();
        assertThat(space.resumableSession()).isNull();
    }

    /** ⚠ FRD 마다 자리가 갈린다 — 섞이면 남의 요구사항이 남의 대화에 얹힌다. */
    @Test
    void FRD_마다_자리가_갈린다(@TempDir Path root) {
        assertThat(new FrdRunSpace(root, "0000015").workDir())
                .isNotEqualTo(new FrdRunSpace(root, "0000016").workDir());
    }

    /**
     * ⛔ <b>이어붙이기가 죽으면 그 기억을 버려라 (2026-08-19).</b> 세션이 사라졌거나 깨졌는데
     * 기억이 남아 있으면 <b>다시 눌러도 같은 이유로 또 죽는다</b> — 그 FRD 가 영영 못 돈다.
     * 잊으면 다음 판이 처음부터 도는 것뿐이고, <b>느릴 뿐 답은 나온다.</b>
     */
    @Test
    void 이어붙이기를_잊으면_다음_판이_처음부터_돈다(@TempDir Path root) throws IOException {
        FrdRunSpace space = new FrdRunSpace(root, "0000015");
        space.prepare();
        space.remember("79a07238-1ceb-4b01-bfdd-241183d0686b");

        space.forget();

        assertThat(space.resumableSession()).isNull();
        assertThat(space.workDir()).as("잊는 것은 자리를 지우는 것이 아니다").exists();
    }
}
