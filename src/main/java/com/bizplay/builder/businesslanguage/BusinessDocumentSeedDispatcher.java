package com.bizplay.builder.businesslanguage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BusinessDocumentSeedDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BusinessDocumentSeedDispatcher.class);
    private final BusinessDocumentSeedWorker worker;
    private final BusinessDocumentSeedService service;
    private final BusinessDocumentSeedMapper seeds;

    public BusinessDocumentSeedDispatcher(BusinessDocumentSeedWorker worker,
                                          BusinessDocumentSeedService service,
                                          BusinessDocumentSeedMapper seeds) {
        this.worker = worker;
        this.service = service;
        this.seeds = seeds;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(BusinessDocumentSeedRequested requested) {
        try {
            worker.create(requested.projectId(), requested.accountId());
        } catch (TaskRejectedException rejected) {
            log.warn("정책서·표준용어 초안 생성 대기열이 가득 찼다 projectId={}", requested.projectId());
            service.fail(requested.projectId(), "QUEUE_REJECTED");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void closeInterruptedRuns() {
        seeds.selectRunning().forEach(seed -> service.fail(seed.projectId(), "SERVER_RESTARTED"));
    }
}
