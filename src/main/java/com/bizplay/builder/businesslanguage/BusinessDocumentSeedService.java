package com.bizplay.builder.businesslanguage;

import com.bizplay.builder.ai.BusinessLanguageAiGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessDocumentSeedService {

    private final BusinessDocumentMapper documents;
    private final BusinessDocumentSeedMapper seeds;
    private final BusinessDocumentService service;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final BusinessLanguageMarkdown markdown;

    public BusinessDocumentSeedService(BusinessDocumentMapper documents, BusinessDocumentSeedMapper seeds,
                                       BusinessDocumentService service, ApplicationEventPublisher events,
                                       ObjectMapper objectMapper, BusinessLanguageMarkdown markdown) {
        this.documents = documents;
        this.seeds = seeds;
        this.service = service;
        this.events = events;
        this.objectMapper = objectMapper;
        this.markdown = markdown;
    }

    @Transactional
    public void start(String projectId, String accountId) {
        if (!documents.selectByProjectId(projectId).isEmpty()) {
            throw new IllegalStateException("이미 초안이 만들어져 있습니다.");
        }
        if (!service.hasDomainDocuments(projectId)) {
            throw new IllegalStateException("저장소의 domains 문서를 찾지 못했습니다. 추출 결과를 먼저 저장소에 반영해 주세요.");
        }
        if (seeds.begin(projectId, accountId) != 1) {
            throw new IllegalStateException("초안을 이미 만들고 있습니다.");
        }
        events.publishEvent(new BusinessDocumentSeedRequested(projectId, accountId));
    }

    @Transactional
    public void complete(String projectId, String accountId, BusinessLanguageAiGateway.DraftResult draft) {
        if (draft.sourceRefs().isEmpty()) {
            throw new BusinessDocumentSeedException(
                    "INVALID_SOURCE_REFERENCES", "초안의 근거 문서를 확인하지 못했습니다.");
        }
        if (markdown.policyHeadings(draft.policyMarkdown()).isEmpty()) {
            throw new BusinessDocumentSeedException(
                    "INVALID_POLICY", "정책서 초안에서 항목을 확인하지 못했습니다.");
        }
        if (markdown.terms(draft.standardTermsMarkdown()).isEmpty()) {
            throw new BusinessDocumentSeedException(
                    "INVALID_STANDARD_TERMS", "표준용어 초안에서 용어 항목을 확인하지 못했습니다.");
        }
        String refs;
        try {
            refs = objectMapper.writeValueAsString(draft.sourceRefs());
        } catch (JsonProcessingException impossible) {
            throw new BusinessDocumentSeedException(
                    "REFERENCE_SERIALIZATION_FAILED", "초안 근거를 저장할 형식으로 바꾸지 못했습니다.", impossible);
        }
        documents.upsert(projectId, BusinessDocumentKind.POLICY, draft.policyMarkdown(), refs, accountId);
        service.recordInitialRevision(projectId, BusinessDocumentKind.POLICY,
                draft.policyMarkdown(), refs, accountId);
        documents.upsert(projectId, BusinessDocumentKind.STANDARD_TERMS,
                draft.standardTermsMarkdown(), refs, accountId);
        service.recordInitialRevision(projectId, BusinessDocumentKind.STANDARD_TERMS,
                draft.standardTermsMarkdown(), refs, accountId);
        if (seeds.finish(projectId) != 1) {
            throw new BusinessDocumentSeedException(
                    "STATE_CHANGED", "초안 생성 상태가 작업 도중 바뀌었습니다.");
        }
    }

    @Transactional
    public void fail(String projectId, String reason) {
        seeds.fail(projectId, reason);
    }
}
