package com.bizplay.builder.ai;

import com.bizplay.builder.ai.AiRun.CheckerResult;
import com.bizplay.builder.id.IdSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 실행의 <b>시작 · 그만두기 · 끝처리</b> 한 자리.
 *
 * <p><b>끝처리를 부르는 놈은 둘뿐이다 — 일꾼과 재기동 청소.</b> ⛔ <b>취소는 안 부른다.</b>
 * 그래도 둘이 겹칠 수 있어서 <b>DB 가 심판한다</b>({@link #finish}).
 *
 * <p><b>2026-08-15 에 데이터 접근이 JPA 에서 MyBatis 로 옮겨졌다.</b> 그전에는 이 파일 안에
 * {@code JdbcTemplate} 네이티브 SQL 셋과 {@code EntityManager.flush()/clear()} 가 섞여 있었다 —
 * 조건부 UPDATE 를 JPA 로는 못 써서다. 지금은 <b>SQL 이 전부 {@code AiRunMapper.xml} 한 자리에 있고</b>
 * 1차 캐시를 비울 일도 없어졌다. ⛔ 여기에 SQL 을 다시 들여오지 마라.
 */
@Service
public class AiRunService {

    private static final Logger log = LoggerFactory.getLogger(AiRunService.class);

    private final AiRunMapper runs;
    private final IdSequence ids;
    private final ApplicationEventPublisher events;

    /**
     * ⚠ <b>일꾼을 생성자로 받으면 순환이 된다</b>(일꾼이 이 서비스를 든다). 늦게 찾아오면 안 엉킨다.
     * 그리고 여기서 꺼내는 것은 <b>프록시</b>라 {@code @Async} 가 제대로 발동한다 —
     * 실물을 들고 있으면 뒤에서 안 돌고 <b>그 자리에서 몇 분을 막는다.</b>
     */
    private final ObjectProvider<AiRunWorker> workers;

    /**
     * 지금 돌고 있는 프로세스. <b>그만두기가 닿는 유일한 손잡이다.</b>
     *
     * <p>⛔ <b>취소 요청을 여기에만 두지 마라</b> — 실행을 넣은 직후·프로세스가 뜨기 전에 누르면
     * 지도에 아무것도 없어서 <b>아무 일도 안 일어나고 그 뒤에 일꾼이 태연히 프로세스를 띄운다.</b>
     * 그래서 {@code cancel_requested_at} 을 DB 에 남기고, 일꾼이 <b>띄우기 직전과 띄운 직후</b>에 두 번 본다.
     */
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    public AiRunService(AiRunMapper runs, IdSequence ids,
                        ApplicationEventPublisher events, ObjectProvider<AiRunWorker> workers) {
        this.runs = runs;
        this.ids = ids;
        this.events = events;
        this.workers = workers;
    }

    /**
     * 실행을 넣고 <b>넣자마자 돌아온다.</b> 일은 커밋 뒤에 다른 스레드가 한다.
     *
     * <p>⛔ <b>{@link AiRun} 을 돌려주지 마라.</b> 돌려준 것은 <b>그 순간의 사진</b>이라
     * 밖에서 보면 영원히 {@link AiRunState#RUNNING} 이다.
     *
     * @throws AlreadyRunningException 같은 일에 이미 실행이 돌고 있을 때.
     *         ⚠ 판정은 자바가 아니라 <b>부분 유일 인덱스</b>가 한다 — 두 탭 경합은 자바 검사로 못 막는다
     */
    @Transactional
    public String start(AiRunRequest request) {
        String runId = ids.next(IdSequence.Kind.AI_RUN);
        try {
            // ⚠ MyBatis 는 JDBC 로 곧장 쏜다 — JPA 의 write-behind 가 없으니 이 줄은 여기서 바로 DB 에 든다.
            //    그래서 유일 인덱스 위반도 커밋 때가 아니라 이 자리에서 난다.
            //    ⚠ 2026-08-15 에 계정·프로젝트까지 전부 MyBatis 로 넘어왔다 — 앞선 줄도 곧장 들어가므로
            //      「먼저 만든 것이 아직 DB 에 없어 FK 가 위배된다」는 갈래가 통째로 사라졌다.
            //      (그것 때문에 테스트 픽스처가 saveAndFlush 였다. 이제 하나도 안 남았다.)
            runs.insert(AiRun.start(runId, request));
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyRunningException(
                    "이 일에는 이미 AI 실행이 돌고 있다: " + request.work().text(), e);
        }
        // ⛔ 여기서 곧바로 일꾼을 부르지 마라 — 아직 커밋 전이라 일꾼이 그 줄을 못 찾는다.
        //    「가끔 나는」 것이 아니라 빠르면 늘 난다.
        events.publishEvent(new Started(runId));
        return runId;
    }

    /**
     * <b>죽이라는 신호까지만 한다. 끝처리를 하지 않는다.</b>
     *
     * <p>⛔ <b>여기서 되돌리지 마라.</b> 되돌리기와 「죽은 것 확인」은 <b>일꾼 스레드가</b> 한다 —
     * 취소가 직접 되돌리면 확인하는 자리가 비어 <b>죽는 중인 프로세스가 되돌린 뒤에 파일을 한 번 더 쓴다.</b>
     * <b>한 실행의 파일 일을 만지는 스레드는 언제나 하나뿐이어야 한다.</b>
     *
     * <p>⛔ <b>여기서 {@link #finish} 를 부르지 마라.</b> 끝처리를 부르는 놈은 둘뿐이다 —
     * <b>일꾼과 재기동 청소.</b> 취소는 표시만 찍고, 상태를 {@link AiRunState#CANCELLED} 로 만드는 것은
     * {@link #finish} 안의 {@code CASE} 다.
     *
     * <p>⚠ 그래서 취소를 눌러도 화면이 바로 안 바뀐다 — 프로세스가 죽어야 {@link AiRunState#CANCELLED} 가 뜬다.
     * 그 사이는 <b>「그만두는 중」</b>으로 보여준다({@code RUNNING} + 취소 요청 있음).
     */
    @Transactional
    public void cancel(String runId) {
        runs.updateCancelRequested(runId);

        Process process = running.get(runId);
        if (process == null) {
            return;                       // 아직 안 떴거나 이미 끝났다 — DB 표시를 일꾼이 본다
        }
        killTree(process);
    }

    /**
     * <b>끝처리 한 자리.</b> 돌려주는 {@code boolean} 은 <b>「내가 이겼나」</b>다.
     *
     * <p>1행이 바뀐 호출자만 되돌리고 · 알리고 · 「끝났다」 이벤트를 낸다.
     * <b>0행이면 남이 이미 닫은 것이라 아무 것도 안 한다.</b>
     *
     * <p>⛔ <b>취소 여부를 먼저 읽어 보고 그 결과로 값을 골라 넘기지 마라.</b> 읽기와 쓰기 사이에 취소가
     * 들어오면 <b>성공이 이겨서 사람이 그만두라 한 실행이 「성공」으로 뜬다.</b> {@code CASE} 를 UPDATE 안에
     * 넣어야 읽는 시점과 쓰는 시점이 같아진다 — 자바에서 {@code if} 로 가르면 그 틈이 그대로 남는다.
     *
     * <p>⛔ <b>무조건 덮어쓰는 {@code finish} 를 만들지 마라.</b> 자연 종료한 일꾼이 성공을 덮으면
     * <b>파일은 되돌아갔는데 화면은 「결과가 떴다」</b>가 된다. 그 보장은 매퍼의
     * {@code where state = 'RUNNING'} 한 줄에 걸려 있다 — {@link AiRunMapper#updateToFinished} 하나뿐이고
     * 조건 없는 짝이 없다.
     *
     * <p>⛔ <b>자바 {@code synchronized} 나 {@code @Version} 으로 하지 마라.</b> 앞엣것은 재기동 청소라는
     * 셋째 쓰기를 못 막고, 뒤엣것은 이 저장소에 낙관적 잠금 선례가 0이라 새 개념이 는다.
     */
    @Transactional
    public boolean finish(String runId, AiRunState state, String developerLog, CheckerResult checkerResult) {
        if (!state.isFinished()) {
            throw new IllegalArgumentException("끝이 아닌 상태로 닫을 수 없다: " + state);
        }
        Optional<AiRunState> decided = runs.updateToFinished(runId, state, developerLog, checkerResult);
        if (decided.isEmpty()) {
            return false;
        }
        // ⚠ 무엇을 사람에게 말할지는 내가 넘긴 값이 아니라 **DB 가 정한 값**으로 정한다.
        events.publishEvent(new Finished(runId, decided.get()));
        return true;
    }

    /** 일꾼이 프로세스를 띄우는 데 드는 재료. <b>짧은 읽기 트랜잭션이다.</b> */
    @Transactional(readOnly = true)
    public RunMaterials materials(String runId) {
        AiRun run = runs.selectById(runId)
                .orElseThrow(() -> new IllegalStateException("그런 실행이 없다: " + runId));
        return new RunMaterials(runId, run.getAccountId(), Path.of(run.getWorkDir()), run.getInstruction());
    }

    /**
     * 일꾼이 <b>프로세스가 뜨는 순간</b> 부른다.
     *
     * <p>⚠ <b>넣자마자 취소 표시를 한 번 더 본다</b> — 「띄우기 직전」과 「띄운 직후」 사이에 들어온 취소는
     * 지도가 비어 있어서 놓쳤을 것이다. 그 틈을 여기서 닫는다.
     */
    public void register(String runId, Process process) {
        running.put(runId, process);
        if (isCancelRequested(runId)) {
            killTree(process);
        }
    }

    public void unregister(String runId) {
        running.remove(runId);
    }

    /** ⚠ <b>DB 를 직접</b> 본다 — 취소는 다른 트랜잭션이 찍는다(매퍼 쪽에 캐시를 꺼 둔 까닭이다). */
    @Transactional(readOnly = true)
    public boolean isCancelRequested(String runId) {
        return Boolean.TRUE.equals(runs.selectCancelRequested(runId));
    }

    /**
     * 서버가 죽었다 살면 <b>안 끝난 실행만</b> 닫는다({@code decided-facts} 8번 그대로 셋이다).
     *
     * <p>① 안 끝난 실행을 실패로 닫고 「돌고 있다」를 비운다 ② <b>잠금과 워크트리는 손대지 않는다</b>
     * ③ <b>「끝났다」 이벤트를 대신 발행한다</b>(안 그러면 앞단이 영영 멈춘다 — {@link #finish} 가 낸다).
     *
     * <p>⛔ <b>닫을 때도 조건부 UPDATE 를 쓴다.</b> 청소기는 <b>세 번째 쓰는 놈</b>이라
     * 무조건 덮으면 마침 그때 끝난 일꾼의 결과를 지운다. 그래서 여기도 제 SQL 을 따로 쓰지 않고
     * {@link #finish} 를 지난다.
     *
     * <p>⚠ <b>JVM 이 죽어도 {@code claude} 프로세스는 안 죽는다 — 고아가 남는다.</b> 새 서버는 지도가
     * 비어 있어 그것을 못 찾는다. 「잠금과 워크트리는 손대지 않는다」 덕에 최악은 피하지만,
     * <b>고아가 자기 워크트리에 계속 쓰는 것</b>은 이 회차에서 안 닫는다(리스크로 남긴다).
     * ⛔ 그 셋을 넓혀서 여기에 「되돌리기」를 더하지 마라 — 그 자리가 비어 있는 것은 우연이 아니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void closeStuckRuns() {
        List<AiRun> stuck = runs.selectByState(AiRunState.RUNNING);
        for (AiRun run : stuck) {
            finish(run.getId(), AiRunState.FAILED,
                    "서버가 다시 뜨면서 닫았다 — 이 실행이 실제로 끝났는지는 알 수 없다",
                    CheckerResult.NOT_RUN);
        }
        if (!stuck.isEmpty()) {
            log.warn("재기동 청소: 안 끝난 AI 실행 {}건을 닫았다", stuck.size());
        }
    }

    /**
     * 커밋된 <b>뒤에</b> 일꾼을 띄운다.
     *
     * <p>⚠ <b>대기줄이 차서 제출이 거절되면 그 실행을 실패로 닫는다.</b> ⛔ {@code RUNNING} 인 채로
     * 버려두지 마라 — 그러면 그 일은 <b>부분 유일 인덱스에 막혀 영영 다시 못 돈다.</b>
     * <b>비동기로 넘긴다고 자원 상한이 없어지지 않는다.</b>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void submitWorker(Started event) {
        try {
            workers.getObject().run(event.runId());
        } catch (TaskRejectedException e) {
            log.warn("AI 실행을 제출하지 못했다 runId={}", event.runId(), e);
            finish(event.runId(), AiRunState.FAILED, "대기줄이 차서 제출이 거절됐다", CheckerResult.NOT_RUN);
        }
    }

    /**
     * ⛔ <b>{@code destroyForcibly()} 하나로 끝내지 마라 — 프로세스 트리를 죽인다는 보장이 없다.</b>
     * {@code claude} CLI 가 자식을 띄우면 <b>부모만 죽고 과금되는 일은 계속 돈다.</b>
     *
     * <p>⚠ {@code GitCommand} 가 아직 이 함정에 걸려 있다(승격후보에 있다) — <b>여기서는 되풀이하지 않는다.</b>
     */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** 일꾼이 다른 스레드에서 읽어 갈 재료. ⚠ 실행 줄이 아니라 값이다 — 낡을 것이 없다. */
    public record RunMaterials(String runId, String accountId, Path workDir, String instruction) {
    }

    /** 실행이 들어갔다. ⚠ 커밋 뒤에만 쓸모가 있다. */
    public record Started(String runId) {
    }

    /** 실행이 끝났다. <b>앞단이 이것을 기다린다</b> — 재기동 청소도 이것을 낸다. */
    public record Finished(String runId, AiRunState state) {
    }
}
