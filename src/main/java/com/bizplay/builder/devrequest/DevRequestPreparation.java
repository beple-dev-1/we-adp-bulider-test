package com.bizplay.builder.devrequest;

import com.bizplay.builder.frd.FrdScreenHistory;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FRD·SRT 완료가 만든 개발요청서의 <b>준비</b> — 변경 예정 기능정의서와 테스트 시나리오.
 *
 * <p>⭐ <b>완료는 준비까지 기다린다</b> (2026-09-24 사용자 확정). 기획자는 FRD·SRT 화면에서 기다리고,
 * 준비가 끝나야 개발요청서가 목록에 보인다. 개발요청서에는 늘 TC 가 있다. 하나라도 못 만들면
 * <b>요청서를 거두고 FRD·SRT 를 완료 전으로 되돌린다</b> — 그 자리에서 까닭을 보고 다시 완료한다.
 *
 * <p>⭐ <b>지켜보는 스레드를 두지 않는다.</b> 누가 상태를 물을 때({@link #settle}) 잰다 — 대기 화면의 폴링,
 * SRT 상태 조회, 개발요청서 목록. 서버가 다시 떠 진행 기록이 사라지면 「만드는 중이 아닌데 결과가 없다」로
 * 잡혀 저절로 거둬진다. 만들기 자체는 AI 실행기의 일꾼들이 한다 — 여기서는 AI 를 부르지 않는다.
 */
@Service
public class DevRequestPreparation {

    private static final Logger log = LoggerFactory.getLogger(DevRequestPreparation.class);

    static final String TEST_SCENARIO_FAILED = "테스트 시나리오를 만들지 못했습니다. 다시 완료해 주세요.";
    static final String TOBE_FAILED = "변경 예정 기능정의서를 만들지 못했습니다 — %s. 다시 완료해 주세요.";

    public enum State { NONE, PREPARING, READY, FAILED }

    /** @param requestId 준비된(READY) 요청서. 거뒀으면(FAILED) 없다 */
    public record Status(State state, String message, String requestId) {
        static final Status NONE = new Status(State.NONE, null, null);
    }

    private final DevelopmentRequestMapper requests;
    private final DevelopmentRequestService service;
    private final ScreenTobeDocumentWorker tobeDocuments;
    private final FrdScreenHistoryMapper histories;
    private final DevRequestTestScenarioWorker testScenarios;
    private final List<DevRequestLink> links;
    /** 거둔 까닭 — 요청서가 지워지므로 FRD 로 찾는다. 다시 완료하면({@link #start}) 지운다. */
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    public DevRequestPreparation(DevelopmentRequestMapper requests, DevelopmentRequestService service,
                                 ScreenTobeDocumentWorker tobeDocuments, FrdScreenHistoryMapper histories,
                                 DevRequestTestScenarioWorker testScenarios, List<DevRequestLink> links) {
        this.requests = requests;
        this.service = service;
        this.tobeDocuments = tobeDocuments;
        this.histories = histories;
        this.testScenarios = testScenarios;
        this.links = links == null ? List.of() : List.copyOf(links);
    }

    /**
     * 완료가 요청서를 만든 직후에 부른다 — 목록에서 감추고 기능정의서·TC 만들기를 건다.
     *
     * <p>⛔ <b>실패가 완료를 뒤집지 않는다</b> — 여기서 던지면 완료가 커밋을 되돌린다. 못 걸면 다음 {@link #settle}
     * 이 「만드는 중이 아닌데 결과가 없다」로 잡아 거둔다.
     */
    public void start(String projectId, String requestId) {
        requests.markPreparing(requestId);
        failures.remove(frdIdOf(requestId));
        try {
            service.requestTobeDocuments(projectId, requestId);
        } catch (RuntimeException failure) {
            log.warn("변경 예정 기능정의서 만들기를 걸지 못했다 requestId={}", requestId, failure);
        }
        try {
            service.requestTestScenarios(projectId, requestId);
        } catch (RuntimeException failure) {
            log.warn("테스트 시나리오 만들기를 걸지 못했다 requestId={}", requestId, failure);
        }
    }

    /**
     * FRD 의 준비를 잰다 — 끝났으면 목록에 올리고, 실패했으면 거둔다.
     *
     * @return FRD 에 준비 중인 요청서가 없고 거둔 까닭도 없으면 {@link State#NONE}
     */
    public Status settleFrd(String projectId, String frdId) {
        DevelopmentRequest request = requests.selectByFrdId(frdId);
        if (request == null || !request.projectId().equals(projectId)) {
            String failure = failures.get(frdId);
            return failure == null ? Status.NONE : new Status(State.FAILED, failure, null);
        }
        return settle(projectId, request.id());
    }

    /** 요청서 하나의 준비를 잰다. */
    public Status settle(String projectId, String requestId) {
        if (!requests.isPreparing(requestId)) {
            return new Status(State.READY, null, requestId);
        }
        DevelopmentRequestService.View view = service.read(projectId, requestId);
        String failure = failureOf(view);
        if (failure == null && pending(view)) {
            return new Status(State.PREPARING, null, requestId);
        }
        if (failure == null) {
            requests.markPrepared(requestId);
            log.info("개발요청서 준비가 끝났다 requestId={}", requestId);
            return new Status(State.READY, null, requestId);
        }
        withdraw(projectId, view, failure);
        return new Status(State.FAILED, failure, null);
    }

    /** 이 프로젝트에서 준비 중인 요청서를 모두 잰다 — 목록을 그리기 전에. */
    public void settleProject(String projectId) {
        for (String requestId : requests.selectPreparingIds()) {
            DevelopmentRequest request = requests.selectById(requestId);
            if (request != null && request.projectId().equals(projectId)) {
                try {
                    settle(projectId, requestId);
                } catch (RuntimeException failure) {
                    log.warn("개발요청서 준비를 재지 못했다 requestId={}", requestId, failure);
                }
            }
        }
    }

    private boolean pending(DevelopmentRequestService.View view) {
        String requestId = view.request().id();
        if (testScenarios.isGenerating(requestId)) return true;
        if (view.asIsOnly()) return false;
        return view.content().screens().stream()
                .anyMatch(screen -> tobeDocuments.isGenerating(screen.frdScreenId()));
    }

    /** @return 거둘 까닭. 없으면 {@code null} — 끝났거나 아직 만드는 중이다 */
    private String failureOf(DevelopmentRequestService.View view) {
        DevelopmentRequestContent content = view.content();
        String requestId = view.request().id();
        boolean needsScenarios = !content.acceptanceCriteria().isEmpty() || !content.requiredChanges().isEmpty();
        if (needsScenarios && !content.hasTestScenarios() && !testScenarios.isGenerating(requestId)) {
            return TEST_SCENARIO_FAILED;
        }
        if (view.asIsOnly()) return null;
        for (var screen : content.screens()) {
            if (tobeDocuments.isGenerating(screen.frdScreenId())) continue;
            FrdScreenHistory latest = histories.selectLatestByScreenId(screen.frdScreenId());
            if (latest == null || latest.md() == null || latest.md().isBlank()) {
                return TOBE_FAILED.formatted(screen.displayName());
            }
        }
        return null;
    }

    private void withdraw(String projectId, DevelopmentRequestService.View view, String failure) {
        String requestId = view.request().id();
        String frdId = view.request().frdId();
        log.warn("개발요청서 준비를 못 마쳐 거둔다 requestId={} frdId={} 까닭={}", requestId, frdId, failure);
        links.forEach(link -> link.release(requestId));
        service.returnToFrd(projectId, requestId);
        failures.put(frdId, failure);
    }

    private String frdIdOf(String requestId) {
        DevelopmentRequest request = requests.selectById(requestId);
        return request == null ? "" : request.frdId();
    }
}
