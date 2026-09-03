package com.bizplay.builder.businesslanguage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BusinessDocumentSeedServiceTest {

    @Test
    void 표준용어_항목이_없는_AI_초안은_구체적인_실패_코드로_거절한다() {
        BusinessDocumentMapper documents = mock(BusinessDocumentMapper.class);
        BusinessDocumentSeedMapper seeds = mock(BusinessDocumentSeedMapper.class);
        BusinessDocumentService documentService = mock(BusinessDocumentService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        var service = new BusinessDocumentSeedService(documents, seeds, documentService, events,
                new ObjectMapper(), new BusinessLanguageMarkdown());
        var draft = new com.bizplay.builder.ai.BusinessLanguageAiGateway.DraftResult(
                true, null, "## 1. 목적\n\n내용\n", "# 표준용어\n", List.of("domains/payment/approval.md"));

        assertThatThrownBy(() -> service.complete("0000001", "0000002", draft))
                .isInstanceOfSatisfying(BusinessDocumentSeedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo("INVALID_STANDARD_TERMS"));
        verifyNoInteractions(documents, seeds);
    }

    @Test
    void 이미_초안을_만드는_프로젝트는_두_번_시작하지_않는다() {
        BusinessDocumentMapper documents = mock(BusinessDocumentMapper.class);
        BusinessDocumentSeedMapper seeds = mock(BusinessDocumentSeedMapper.class);
        BusinessDocumentService documentService = mock(BusinessDocumentService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(documents.selectByProjectId("0000001")).thenReturn(List.of());
        when(documentService.hasDomainDocuments("0000001")).thenReturn(true);
        when(seeds.begin("0000001", "0000002")).thenReturn(0);
        var service = new BusinessDocumentSeedService(documents, seeds, documentService, events,
                new ObjectMapper(), new BusinessLanguageMarkdown());

        assertThatThrownBy(() -> service.start("0000001", "0000002"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("초안을 이미 만들고 있습니다.");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void domains_문서가_없으면_AI_작업을_시작하지_않는다() {
        BusinessDocumentMapper documents = mock(BusinessDocumentMapper.class);
        BusinessDocumentSeedMapper seeds = mock(BusinessDocumentSeedMapper.class);
        BusinessDocumentService documentService = mock(BusinessDocumentService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(documents.selectByProjectId("0000001")).thenReturn(List.of());
        when(documentService.hasDomainDocuments("0000001")).thenReturn(false);
        var service = new BusinessDocumentSeedService(documents, seeds, documentService, events,
                new ObjectMapper(), new BusinessLanguageMarkdown());

        assertThatThrownBy(() -> service.start("0000001", "0000002"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domains");
        verifyNoInteractions(seeds, events);
    }

    @Test
    void AI_초안을_저장하면_두_문서의_최초_개정본도_남긴다() {
        BusinessDocumentMapper documents = mock(BusinessDocumentMapper.class);
        BusinessDocumentSeedMapper seeds = mock(BusinessDocumentSeedMapper.class);
        BusinessDocumentService documentService = mock(BusinessDocumentService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(seeds.finish("0000001")).thenReturn(1);
        var service = new BusinessDocumentSeedService(documents, seeds, documentService, events,
                new ObjectMapper(), new BusinessLanguageMarkdown());
        String policy = "## 1. 가입\n\n만 14세 이상 가입할 수 있다.\n";
        String terms = "# 표준용어\n\n| 표준용어 | 용어 정의 | 동의어·유사어 |\n"
                + "| --- | --- | --- |\n| 회원 | 서비스 이용자 | 사용자 |\n";
        var draft = new com.bizplay.builder.ai.BusinessLanguageAiGateway.DraftResult(
                true, null, policy, terms, List.of("domains/account/member.md"));

        service.complete("0000001", "0000002", draft);

        verify(documentService).recordInitialRevision(eq("0000001"), eq(BusinessDocumentKind.POLICY),
                eq(policy), anyString(), eq("0000002"));
        verify(documentService).recordInitialRevision(eq("0000001"), eq(BusinessDocumentKind.STANDARD_TERMS),
                eq(terms), anyString(), eq("0000002"));
    }
}
