package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.ai.BusinessLanguageAiGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class BusinessDocumentSeedWorker {

    private static final Logger log = LoggerFactory.getLogger(BusinessDocumentSeedWorker.class);
    private final BusinessLanguageAiGateway ai;
    private final BusinessDocumentSeedService seeds;

    public BusinessDocumentSeedWorker(BusinessLanguageAiGateway ai, BusinessDocumentSeedService seeds) {
        this.ai = ai;
        this.seeds = seeds;
    }

    @Async("aiExecutor")
    public void create(String projectId, String accountId) {
        try {
            var draft = ai.create(projectId, accountId);
            if (!draft.succeeded()) {
                seeds.fail(projectId, draft.reason());
                return;
            }
            seeds.complete(projectId, accountId, draft);
        } catch (BusinessDocumentSeedException invalid) {
            log.warn("정책서·표준용어 초안 검증이 실패했다 projectId={} reason={}",
                    projectId, invalid.reason(), invalid);
            seeds.fail(projectId, invalid.reason());
        } catch (IOException trouble) {
            log.warn("정책서·표준용어 초안 생성 중 파일 처리가 실패했다 projectId={}", projectId, trouble);
            seeds.fail(projectId, "INPUT_OUTPUT_FAILED");
        } catch (DataAccessException database) {
            log.warn("정책서·표준용어 초안을 DB에 저장하지 못했다 projectId={}", projectId, database);
            seeds.fail(projectId, "SAVE_FAILED");
        } catch (RuntimeException unexpected) {
            log.warn("정책서·표준용어 초안 생성이 예상 못 한 이유로 끝났다 projectId={}", projectId, unexpected);
            seeds.fail(projectId, "UNEXPECTED");
        }
    }
}
