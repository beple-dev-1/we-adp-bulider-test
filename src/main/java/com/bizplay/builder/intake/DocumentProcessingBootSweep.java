package com.bizplay.builder.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 재기동할 때 <b>줄에 선 채 잊힌 문서를 다시 데려간다.</b>
 *
 * <p>⛔ <b>이 자리를 지우지 마라.</b> 줄에 세우는 것과 일꾼을 깨우는 것은 <b>다른 걸음</b>이다 —
 * 앞엣것은 등록 트랜잭션 안이고 뒤엣것은 커밋 뒤다(안에서 깨우면 아직 안 보이는 접수를 찾다가 죽는다).
 * 그 사이에 서버가 죽거나 대기줄이 꽉 차면 <b>깨울 사람이 사라지고 문서는 영영 「내용 분석 대기」</b>다.
 * 화면에도 그 문서를 미는 버튼이 없다 — 「다시 시도」는 오류일 때만 열린다.
 *
 * <p>⚠ <b>V7 이 옛 자료를 옮기며 만든 대기 줄도 이 문이 데려간다.</b>
 *
 * <p>★ <b>별도 빈이다.</b> ⛔ {@link DocumentProcessingWorker} 안에 두지 마라 —
 * 자기 자신의 {@code process} 를 부르는 꼴이라 프록시를 안 타서 {@code @Async} 가 발동하지 않고,
 * <b>부팅 스레드에서 문서를 하나씩 통째로 읽고 앉아 있게 된다.</b>
 *
 * <p>⚠ {@code @Order} 로 {@link DocumentProcessingService#closeStuckRuns} <b>뒤에</b> 선다.
 * 먼저 돌면 굳은 시도가 아직 살아 있어 <b>부분 유일 인덱스에 막혀</b> 시작도 못 하고 조용히 돌아간다.
 */
@Component
public class DocumentProcessingBootSweep {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingBootSweep.class);

    private final DocumentProcessingService processing;
    private final DocumentProcessingWorker worker;

    public DocumentProcessingBootSweep(DocumentProcessingService processing,
                                       DocumentProcessingWorker worker) {
        this.processing = processing;
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void resumeQueued() {
        List<String> waiting = processing.queuedIntakeIds();
        if (waiting.isEmpty()) {
            return;
        }
        log.info("재기동: 줄에 선 문서 {}건을 다시 데려간다", waiting.size());
        for (String intakeId : waiting) {
            try {
                worker.process(intakeId);
            } catch (TaskRejectedException full) {
                // ⛔ 여기서 멈추지 마라 — 대기줄이 찼을 뿐이고 남은 것들은 다음 재기동이 데려간다.
                //   ⚠ 상태는 QUEUED 그대로라 잃는 것이 없다.
                log.warn("대기줄이 차서 지금은 못 데려간다 intakeId={}", intakeId);
                return;
            }
        }
    }
}
