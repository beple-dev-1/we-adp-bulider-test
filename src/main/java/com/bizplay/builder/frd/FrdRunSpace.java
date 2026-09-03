package com.bizplay.builder.frd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FRD 한 건이 쓰는 <b>실행 자리</b>.
 *
 * <p>⭐ <b>자리가 FRD 단위인 것이 요점이다 (2026-08-19).</b> 종전에는 <b>실행마다</b> UUID 폴더였고
 * 판이 끝나면 통째로 지웠다. 그래서 다음 판이 앞판의 대화를 이어 붙일 수 없었고, 답변 한 줄마다
 * 저장소를 처음부터 다시 뒤졌다 — 실측에서 한 판 350초 중 <b>220초가 그 재탐색</b>이었다.
 *
 * <p>⚠ <b>실행마다 갈랐던 까닭은 여전히 옳다</b> — 같은 사람이 두 일을 동시에 돌릴 수 있어서
 * 사람 단위로 잡으면 먼저 끝난 판이 아직 도는 판의 파일을 지운다. <b>FRD 단위는 그 함정이 없다</b>:
 * 한 FRD 는 상태({@code ANALYZING})가 한 판만 돌게 막는다.
 *
 * <p>⛔ <b>{@link #wipeCredential()} 을 빼먹지 마라.</b> 자리를 남기게 된 뒤로 「남의 자격을 서버
 * 디스크에 남기지 않는다」를 지키는 곳은 여기 하나다 — 종전에는 폴더를 통째로 지우는 것이
 * 그 몫까지 했다.
 */
final class FrdRunSpace {

    private static final Logger log = LoggerFactory.getLogger(FrdRunSpace.class);

    /** ⚠ {@code ClaudeCredentialRunner} 가 쓰는 이름이다 — 저쪽과 같이 고쳐라. */
    private static final String CREDENTIAL_FILE = ".credentials.json";

    /** 이어붙일 세션ID 가 앉는 이름. ⚠ 숨김 파일이 아니다 — 사람이 자리를 열어 보고 알 수 있어야 한다. */
    private static final String SESSION_FILE = "session.id";

    private final Path root;

    FrdRunSpace(Path runsRoot, String frdId) {
        this.root = runsRoot.resolve(frdId);
    }

    /**
     * {@code CLAUDE_CONFIG_DIR} 로 넘길 자리.
     *
     * <p>⭐ <b>세션 기록이 이 밑에 앉는다</b> — 그래서 판이 끝나도 이 폴더를 남긴다.
     * 안에 무엇이 어떻게 앉는지는 {@code claude} 의 규칙이고 <b>우리가 그 규칙에 기대지 않는다.</b>
     */
    Path credentialDir() {
        return root.resolve("credentials");
    }

    /**
     * 요구사항·화면목록·인터뷰 사본이 앉는 자리.
     *
     * <p>⭐ <b>경로가 판마다 안 바뀌는 것이 중요하다.</b> 이어붙인 판은 앞판이 본 절대경로를
     * 대화에 들고 있다 — 자리가 옮겨 다니면 그 경로가 <b>없는 자리를 가리킨다.</b>
     */
    Path workDir() {
        return root.resolve("work");
    }

    void prepare() throws IOException {
        Files.createDirectories(credentialDir());
        Files.createDirectories(workDir());
    }

    /**
     * 이어붙일 세션ID. 없으면 널이다.
     *
     * <p>⛔ <b>실패한 판의 세션을 기억하지 마라</b> — 부르는 쪽이 {@link #remember(String)} 를
     * 성공한 판에서만 부르는 것으로 그 약속을 지킨다.
     */
    String resumableSession() {
        Path file = root.resolve(SESSION_FILE);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String remembered = Files.readString(file, StandardCharsets.UTF_8).strip();
            return remembered.isEmpty() ? null : remembered;
        } catch (IOException unreadable) {
            // 못 읽으면 못 이어붙이는 것뿐이다 — 처음부터 도는 판은 여전히 옳은 답을 낸다.
            log.warn("이어붙일 세션을 못 읽었다 {}", root.getFileName(), unreadable);
            return null;
        }
    }

    /** ⚠ 못 적어도 판을 실패로 만들지 않는다 — 다음 판이 처음부터 돌 뿐이다. */
    void remember(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(SESSION_FILE), sessionId, StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            log.warn("이어붙일 세션을 못 적었다 {}", root.getFileName(), unwritable);
        }
    }

    /**
     * 이어붙일 기억을 <b>버린다</b> — 이어붙이기가 죽었을 때다.
     *
     * <p>⛔ <b>이것을 빼면 그 FRD 가 영영 못 돈다.</b> 세션이 사라졌거나 깨졌는데 기억이 남아 있으면
     * 사람이 다시 눌러도 <b>같은 이유로 또 죽는다.</b> 잊으면 다음 판이 처음부터 도는 것뿐이고,
     * <b>느릴 뿐 답은 나온다.</b>
     *
     * <p>⚠ 자리를 지우는 것이 아니다 — 요구사항 사본은 그대로 둔다.
     */
    void forget() {
        try {
            Files.deleteIfExists(root.resolve(SESSION_FILE));
        } catch (IOException stubborn) {
            log.warn("이어붙일 세션을 못 지웠다 {}", root.getFileName(), stubborn);
        }
    }

    /**
     * 판이 끝날 때마다 <b>남의 자격만</b> 지운다. 세션 기록은 남는다.
     *
     * <p>⛔ <b>이것을 빼면 자격이 서버 디스크에 눌러앉는다.</b>
     */
    void wipeCredential() {
        try {
            Files.deleteIfExists(credentialDir().resolve(CREDENTIAL_FILE));
        } catch (IOException stubborn) {
            log.warn("실행 자리의 자격 파일을 못 지웠다 {}", root.getFileName(), stubborn);
        }
    }

    /**
     * 자리를 통째로 지운다 — <b>FRD 의 인터뷰가 끝났을 때</b>다.
     *
     * <p>⚠ 요구사항 사본도 대화도 남길 까닭이 없다. 정본은 DB 의 {@code source_text} 와
     * {@code adk_builder_frd_interview_message} 다.
     */
    void wipe() {
        FileSystemUtils.deleteRecursively(root.toFile());
    }
}
