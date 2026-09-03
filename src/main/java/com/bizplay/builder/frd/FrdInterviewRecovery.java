package com.bizplay.builder.frd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** 서버 재시작으로 실행 프로세스만 사라진 FRD 분석을 다시 제출한다. */
@Component
public class FrdInterviewRecovery {

    private static final Logger log = LoggerFactory.getLogger(FrdInterviewRecovery.class);

    private final FrdMapper frds;
    private final FrdService service;
    private final ScreenPickWorker worker;

    public FrdInterviewRecovery(FrdMapper frds, FrdService service, ScreenPickWorker worker) {
        this.frds = frds;
        this.service = service;
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedAnalysis() {
        for (Frd frd : frds.selectByState(Frd.State.ANALYZING)) {
            try {
                worker.pick(frd.id());
                log.info("서버 재시작 뒤 FRD 요구사항 분석을 다시 제출했다 frdId={}", frd.id());
            } catch (TaskRejectedException full) {
                service.rejectDispatch(frd.id(),
                        "서버 재시작 뒤 분석을 다시 시작하지 못했습니다. 잠시 뒤 다시 분석해 주세요.");
            }
        }
    }
}
