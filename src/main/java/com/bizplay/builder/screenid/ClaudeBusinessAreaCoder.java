package com.bizplay.builder.screenid;

import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.project.ProjectPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code claude} 를 한 번 돌려 업무영역 3글자를 받는다.
 *
 * <p>업무영역 코드는 Claude Haiku 한 판으로 짓는다. 저장소를 읽을 필요가 없는 작은 판단이므로
 * 도구는 전부 끄고 JSON 스키마로 답의 모양을 고정한다.
 *
 * <p>⚠ <b>실패해도 던지지 않는다.</b> 전부 {@code XXX} 를 내고 채번은 계속 돈다 —
 * AI 가 안 되는 날 화면이 통째로 번호를 못 갖는 것보다 낫고, 사람이 코드표를 고치면 된다.
 *
 * <p>⛔ <b>자격이 없으면(빈 {@code Optional}) 그냥 {@code XXX} 다.</b> 클론은 관리자가 돌리는데
 * 그 계정에 Claude 연결이 없을 수 있다 — 그때 던지면 <b>클론 흐름이 죽는다.</b>
 */
@Component
public class ClaudeBusinessAreaCoder implements BusinessAreaCoder {

    private static final Logger log = LoggerFactory.getLogger(ClaudeBusinessAreaCoder.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OUTPUT_SCHEMA = """
            {"type":"object","properties":{"codes":{"type":"object","additionalProperties":{"type":"string","pattern":"^[A-Z]{3}$"}}},"required":["codes"],"additionalProperties":false}
            """.strip();
    private static final List<String> CLAUDE_ARGS = List.of(
            "--model", "haiku",
            "--tools", "",
            "--json-schema", OUTPUT_SCHEMA);

    private final ClaudeCredentialRunner credentials;
    private final ProjectPaths paths;

    public ClaudeBusinessAreaCoder(ClaudeCredentialRunner credentials, ProjectPaths paths) {
        this.credentials = credentials;
        this.paths = paths;
    }

    @Override
    public Map<String, String> codesOf(String projectId, String accountId, Map<String, String> areas) {
        if (areas.isEmpty()) return new LinkedHashMap<>();
        return BusinessAreaCodes.of(ask(projectId, accountId, BusinessAreaCodes.instructionFor(areas)), areas);
    }

    /** ⚠ 어떤 실패든 널을 낸다 — 부르는 쪽이 그것을 「전부 XXX」로 읽는다. */
    private JsonNode ask(String projectId, String accountId, String instruction) {
        if (credentials == null || paths == null || accountId == null || accountId.isBlank()) {
            log.info("Claude 자격을 쓸 수 없어 업무영역 코드를 전부 XXX 로 둔다 projectId={}", projectId);
            return null;
        }
        Path credentialDir = null;
        try {
            credentialDir = Files.createTempDirectory("builder-screen-id-cred-");
            Optional<ClaudeResult> executed = credentials.run(accountId, credentialDir,
                    paths.cloneDir(projectId), TIMEOUT, CLAUDE_ARGS, instruction, process -> { });
            if (executed.isEmpty() || executed.get().isError()) {
                log.info("업무영역 코드 생성이 실패해 전부 XXX 로 둔다 projectId={}", projectId);
                return null;
            }
            return JSON.readTree(executed.get().body()).path("codes");
        } catch (com.fasterxml.jackson.core.JacksonException malformed) {
            // ⛔ 예외를 그대로 로그에 넘기지 마라. Jackson 의 파싱 오류 메시지는 입력 일부를 그대로
            //    실어 나른다 — 그 입력이 AI 응답 본문이고, 거기에 사업 내용이 섞인다.
            //    ClaudeRunner javadoc 의 「사업 내용을 서버 로그에 붓지 않는다」와 같은 자리다.
            log.info("업무영역 코드 답이 JSON 이 아니어서 전부 XXX 로 둔다 projectId={} 예외={}",
                    projectId, malformed.getClass().getSimpleName());
            return null;
        } catch (Exception failure) {
            log.info("업무영역 코드 답을 읽지 못해 전부 XXX 로 둔다 projectId={}", projectId, failure);
            return null;
        } finally {
            if (credentialDir != null) FileSystemUtils.deleteRecursively(credentialDir.toFile());
        }
    }
}
