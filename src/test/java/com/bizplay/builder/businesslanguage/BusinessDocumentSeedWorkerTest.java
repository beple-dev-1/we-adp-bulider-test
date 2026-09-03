package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.ai.BusinessLanguageAiGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessDocumentSeedWorkerTest {

    @Test
    void 초안_검증_실패_코드를_생성_상태에_기록한다() throws Exception {
        BusinessLanguageAiGateway ai = mock(BusinessLanguageAiGateway.class);
        BusinessDocumentSeedService seeds = mock(BusinessDocumentSeedService.class);
        var draft = new BusinessLanguageAiGateway.DraftResult(
                true, null, "## 1. 목적\n", "# 표준용어\n", List.of("domains/payment/approval.md"));
        when(ai.create("0000001", "0000002")).thenReturn(draft);
        doThrow(new BusinessDocumentSeedException(
                "INVALID_STANDARD_TERMS", "표준용어 항목이 없습니다."))
                .when(seeds).complete("0000001", "0000002", draft);

        new BusinessDocumentSeedWorker(ai, seeds).create("0000001", "0000002");

        verify(seeds).fail("0000001", "INVALID_STANDARD_TERMS");
    }
}
