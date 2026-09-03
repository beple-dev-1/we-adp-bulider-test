package com.bizplay.builder.ai;

import com.bizplay.builder.ai.AiRun.CheckerResult;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.project.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 실행 하나를 실제로 돌리는 일꾼. ★ <b>별도 빈이다.</b>
 *
 * <p>⛔ <b>{@link AiRunService} 안에 두지 마라</b> — 자기 자신을 부르는 꼴이라 프록시를 안 타서
 * {@code @Async} 가 <b>아예 발동하지 않는다.</b> 몇 분짜리 일이 요청 스레드를 그대로 막는다.
 */
@Component
public class AiRunWorker {

    private static final Logger log = LoggerFactory.getLogger(AiRunWorker.class);

    private final AiRunService runs;
    private final ClaudeCredentialRunner credentialRunner;
    private final BuilderProperties properties;
    private final ProjectPaths paths;

    public AiRunWorker(AiRunService runs, ClaudeCredentialRunner credentialRunner,
                       BuilderProperties properties, ProjectPaths paths) {
        this.runs = runs;
        this.credentialRunner = credentialRunner;
        this.properties = properties;
        this.paths = paths;
    }

    /**
     * ⛔ <b>트랜잭션을 걸지 마라</b> — {@code CloneWorker} 주석이 그 이유 둘을 이미 적어 뒀다
     * (rollback-only 오염 · 커넥션 물기). DB 는 <b>짧은 세 토막</b>으로만 만진다:
     * 재료 읽기 → (프로세스·파일 일) → 결과 쓰기.
     *
     * <p>⛔ <b>최상위를 {@code try/catch} 로 감싸라.</b> {@code void @Async} 의 예외는 호출자에게 안 가고
     * 로그만 남는다 — 빠뜨리면 실행이 <b>영원히 {@code RUNNING}</b> 으로 굳는다.
     */
    @Async("aiExecutor")
    public void run(String runId) {
        try {
            execute(runId);
        } catch (RuntimeException e) {
            log.warn("AI 실행이 예상 못 한 이유로 끝났다 runId={}", runId, e);
            runs.finish(runId, AiRunState.FAILED,
                    GitCommand.mask(String.valueOf(e.getMessage())), CheckerResult.NOT_RUN);
        }
    }

    private void execute(String runId) {
        AiRunService.RunMaterials materials = runs.materials(runId);

        // ① 띄우기 **직전**에 본다. 넣은 직후·프로세스가 뜨기 전에 누른 취소가 여기서 잡힌다.
        //    ⚠ 값은 CANCELLED 를 넘기지만 판정은 어차피 UPDATE 안의 CASE 가 한다.
        if (runs.isCancelRequested(runId)) {
            runs.finish(runId, AiRunState.CANCELLED, null, CheckerResult.NOT_RUN);
            return;
        }

        Path credentialDir = paths.runCredentialDir(runId);
        try {
            // ② 띄운 **직후**의 눈은 register 안에 있다 — 그 사이에 들어온 취소를 거기서 죽인다.
            var executed = credentialRunner.run(materials.accountId(), credentialDir,
                    materials.workDir(), properties.aiRunTimeout(), materials.instruction(),
                    process -> runs.register(runId, process));
            if (executed.isEmpty()) {
                // 자격 자체가 없다. 다시 해봐야 똑같다 — 실패가 아니라 자격끊김이다.
                runs.finish(runId, AiRunState.CREDENTIAL_LOST, "이 사람의 Claude 자격이 없다",
                        CheckerResult.NOT_RUN);
                return;
            }
            ClaudeResult result = executed.get();

            runs.finish(runId, judge(result), developerLog(result), CheckerResult.NOT_RUN);
        } catch (IOException e) {
            log.warn("AI 실행이 파일 일에서 실패했다 runId={}", runId, e);
            runs.finish(runId, AiRunState.FAILED, GitCommand.mask(String.valueOf(e.getMessage())),
                    CheckerResult.NOT_RUN);
        } finally {
            runs.unregister(runId);
            // ⛔ 끝나면 지운다. **실패로 끝나도 지운다** — 남의 자격이 서버 디스크에 남으면 안 된다.
            FileSystemUtils.deleteRecursively(credentialDir.toFile());
        }
    }

    /**
     * <b>구조화된 필드로만</b> 판정한다.
     *
     * <p>⛔ 사람이 읽는 문구를 파싱하지 않는다 — 버전마다 바뀐다. 그리고 <b>모르면 실패</b>다.
     * 갈래를 지어내지 않는다.
     *
     * <p>⛔ <b>성공 판정에 종료코드를 반드시 넣어라.</b> {@code isError()} 하나만 보면
     * <b>종료코드가 0 이 아닌데 성공</b>이 된다.
     *
     * <p>⛔ <b>{@code subtype} 으로 성패를 가르지 마라</b>(2026-08-14 스파이크) — 자격이 없을 때도
     * {@code "success"} 로 와서 <b>전부 성공으로 읽힌다.</b> 그래서 이 판정에 {@code subtype} 이 없다.
     */
    private static AiRunState judge(ClaudeResult result) {
        if (result.isTimedOut()) {
            return AiRunState.TIMED_OUT;
        }
        if (result.exitCode() == 0 && !result.isError()) {   // 둘 다여야 성공이다
            return AiRunState.SUCCEEDED;
        }
        if (credentialLost(result)) {
            return AiRunState.CREDENTIAL_LOST;
        }
        return AiRunState.FAILED;
    }

    /** 판정 조건의 정본은 {@link ClaudeResult#credentialLost()} 다 (2026-08-27 에 옮겼다). */
    private static boolean credentialLost(ClaudeResult result) {
        return result.credentialLost();
    }

    /**
     * 개발자가 보는 원문. ⛔ <b>화면에 그대로 내지 않는다</b>(Global Constraints) —
     * 사람에게 하는 말은 {@link AiRun#userMessage()} 가 상태에서 따로 만든다.
     */
    private static String developerLog(ClaudeResult result) {
        if (result.exitCode() == 0 && !result.isError()) {
            return null;
        }
        // ⛔ 비밀·토큰이 섞여 나올 수 있다 — 이미 있는 가리개를 지난다.
        return GitCommand.mask("exitCode=%d isError=%s terminalReason=%s apiStatus=%s body=%s"
                .formatted(result.exitCode(), result.isError(), result.terminalReason(),
                        result.apiStatus(), result.body()));
    }
}
