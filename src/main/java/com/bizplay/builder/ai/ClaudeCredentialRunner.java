package com.bizplay.builder.ai;

import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.claude.ClaudeAccountLocks;
import com.bizplay.builder.claude.ClaudeCredentialFile;
import com.bizplay.builder.claude.ClaudeCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 계정 자격을 실행 전 임시 디렉터리에 놓고, 실행 후 갱신된 OAuth 자격을 DB에 되돌린다.
 */
@Component
public class ClaudeCredentialRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCredentialRunner.class);
    private static final String CREDENTIAL_FILE = ".credentials.json";

    private final ClaudeRunner runner;
    private final ClaudeCredentialService credentials;
    private final ClaudeCredentialFile credentialFile;
    private final ClaudeAccountLocks accountLocks;

    public ClaudeCredentialRunner(ClaudeRunner runner, ClaudeCredentialService credentials,
                                  ClaudeCredentialFile credentialFile,
                                  ClaudeAccountLocks accountLocks) {
        this.runner = runner;
        this.credentials = credentials;
        this.credentialFile = credentialFile;
        this.accountLocks = accountLocks;
    }

    public Optional<ClaudeResult> run(String accountId, Path credentialDir, Path workDir,
                                      Duration timeout, String instruction,
                                      Consumer<Process> onStarted) throws IOException {
        return run(accountId, credentialDir, workDir, timeout, List.of(), instruction, onStarted);
    }

    /**
     * 같은 계정의 실행과 재연결을 직렬화한다. 잠금을 잡은 뒤 DB 자격을 다시 읽으므로,
     * 기다리는 동안 앞 실행이 갱신한 토큰을 곧바로 이어받는다.
     */
    public Optional<ClaudeResult> run(String accountId, Path credentialDir, Path workDir,
                                      Duration timeout, List<String> extraArgs, String instruction,
                                      Consumer<Process> onStarted) throws IOException {
        return run(accountId, credentialDir, workDir, timeout, extraArgs, instruction, onStarted, null);
    }

    /**
     * 진행을 <b>돌는 중에</b> 받으면서 돌린다. ⚠ {@code onProgress} 가 널이면 종전 그대로다 —
     * 자세한 것은 {@link ClaudeRunner#run(Path, Path, Duration, List, String, Consumer, Consumer)}.
     */
    public Optional<ClaudeResult> run(String accountId, Path credentialDir, Path workDir,
                                      Duration timeout, List<String> extraArgs, String instruction,
                                      Consumer<Process> onStarted, Consumer<ClaudeRunner.Progress> onProgress)
            throws IOException {
        try (ClaudeAccountLocks.Guard ignored = accountLocks.acquire(accountId)) {
            Optional<String> stored = credentials.tokenOf(accountId);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            ClaudeResult result = runOnce(accountId, credentialDir, workDir, timeout, extraArgs, instruction,
                    onStarted, onProgress, stored.get());
            /*
             * ⭐ 겹친 OAuth 갱신의 뒷수습 (2026-08-27 병주 확정 ②).
             *   같은 계정의 실행이 동시에 돌 수 있게 되자(ClaudeAccountLocks 세마포어), 토큰 만료 순간에 둘이
             *   같은 갱신 토큰으로 갱신을 시도하면 한쪽은 이미 쓰인 토큰이라 「Not logged in」으로 죽는다.
             *   그때 **DB 자격이 내가 시작할 때와 달라졌으면** 다른 실행이 새 자격을 저장한 것이다 —
             *   그 자격으로 **한 번만** 다시 돈다. DB 가 그대로면 진짜 로그아웃이라 종전처럼 자격끊김으로 낸다.
             *   ⛔ 자격 오류가 아닌 실패는 다시 돌지 않는다 — 판정은 ClaudeResult.credentialLost() 하나다.
             */
            if (result.credentialLost()) {
                Optional<String> now = credentials.tokenOf(accountId);
                if (now.isPresent() && !sameOAuth(now.get(), stored.get())) {
                    log.info("Claude 자격 오류인데 그 사이 DB 자격이 갱신됐다 — 새 자격으로 한 번 다시 돈다 accountId={}",
                            accountId);
                    result = runOnce(accountId, credentialDir, workDir, timeout, extraArgs, instruction,
                            onStarted, onProgress, now.get());
                }
            }
            return Optional.of(result);
        }
    }

    private ClaudeResult runOnce(String accountId, Path credentialDir, Path workDir, Duration timeout,
                                 List<String> extraArgs, String instruction, Consumer<Process> onStarted,
                                 Consumer<ClaudeRunner.Progress> onProgress, String credential) throws IOException {
        Files.createDirectories(credentialDir);
        Path credentialPath = credentialDir.resolve(CREDENTIAL_FILE);
        Files.writeString(credentialPath, credential, StandardCharsets.UTF_8);
        try {
            return runner.run(credentialDir, workDir, timeout, extraArgs, instruction, onStarted, onProgress);
        } finally {
            persistRefreshedCredential(accountId, credentialPath, credential);
        }
    }

    private boolean sameOAuth(String left, String right) {
        return credentialFile.extractOAuthBlock(left).equals(credentialFile.extractOAuthBlock(right));
    }

    /**
     * Claude CLI가 access/refresh token을 바꿨을 때만 다시 봉인한다.
     * 파일이 없거나 깨졌다면 기존 DB 자격을 덮지 않고 운영 로그로 알린다.
     *
     * <p>⛔ <b>덮기 전에 DB 를 다시 읽는다 (2026-08-17).</b> 이 실행이 도는 몇 분 사이에
     * <b>사람이 브라우저에서 계정을 다시 연결했을 수 있다.</b> 그때 이 실행이 들고 나갔던 자격을
     * 그대로 덮으면 <b>방금 손으로 살려 놓은 연결이 조용히 죽는다</b> — 그리고 다음 실행부터
     * 다시 만료로 죽는다. DB 가 그 사이에 바뀌었으면 <b>사람 쪽이 이긴다.</b>
     * ⚠ 재연결 쪽이 계정 잠금을 안 잡는 것도 이 때문이다
     * ({@code ClaudeCredentialService#store} 에 사유를 적어 뒀다).
     */
    private void persistRefreshedCredential(String accountId, Path credentialPath, String before) {
        try {
            if (!Files.isRegularFile(credentialPath) || Files.size(credentialPath) == 0) {
                log.warn("Claude 실행 뒤 자격 파일이 없어 갱신 자격을 보존하지 못했다 accountId={}", accountId);
                return;
            }
            String refreshed = credentialFile.extractOAuthBlock(
                    Files.readString(credentialPath, StandardCharsets.UTF_8));
            String normalizedBefore = credentialFile.extractOAuthBlock(before);
            if (refreshed.equals(normalizedBefore)) {
                return;
            }
            /*
             * ⛔ 「DB 다시 읽기 → 비교 → 저장」은 한 덩어리여야 한다 (2026-08-26 코덱스 적대 검증).
             *   같은 계정의 실행이 이제 동시에 돌므로(ClaudeAccountLocks 세마포어), 둘이 같은 원본을 들고
             *   끝나면 둘 다 「아직 원본이다」를 읽고 둘 다 저장할 수 있다 — 잠금 없이는 뒤에 쓴 낡은 토큰이
             *   앞의 것을 덮는다. 이 잠금은 밀리초짜리다 — 실행 내내 잡는 것이 아니다.
             */
            try (ClaudeAccountLocks.Guard ignored = accountLocks.persistGuard(accountId)) {
                if (!stillHolds(accountId, normalizedBefore)) {
                    log.info("Claude 자격이 실행 중에 바뀌었다 — 갱신 자격으로 덮지 않는다 accountId={}", accountId);
                    return;
                }
                credentials.store(accountId, refreshed);
            }
            log.info("Claude OAuth 갱신 자격을 보존했다 accountId={}", accountId);
        } catch (IOException | RuntimeException failure) {
            // 자격 내용은 절대 로그에 싣지 않는다. 기존 DB 자격도 덮지 않는다.
            log.warn("Claude 실행 뒤 갱신 자격을 보존하지 못했다 accountId={}", accountId, failure);
        }
    }

    /** DB 가 아직 이 실행이 들고 나갔던 자격을 쥐고 있나. 없어졌으면(연결 끊김) 되살리지 않는다. */
    private boolean stillHolds(String accountId, String normalizedBefore) {
        return credentials.tokenOf(accountId)
                .map(credentialFile::extractOAuthBlock)
                .filter(normalizedBefore::equals)
                .isPresent();
    }
}
