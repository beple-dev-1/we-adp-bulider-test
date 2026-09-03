package com.bizplay.builder.frd;

import com.bizplay.builder.devrequest.DevelopmentRequest;
import com.bizplay.builder.devrequest.DevelopmentRequestService;
import com.bizplay.builder.project.PlanningRepositoryUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** FRD 변경을 커밋하고 검토 단계로 넘기는 작업 완료 절차다. */
@Service
public class FrdCompletionService {

    private static final Logger log = LoggerFactory.getLogger(FrdCompletionService.class);

    private final FrdService frds;
    private final FrdScreenMapper screens;
    private final FrdScreenChatService screenChats;
    private final FrdAnalysisNoteMapper notes;
    private final FrdScreenIaMaterializer iaMaterializer;
    private final FrdWorkspace workspaces;
    private final DevelopmentRequestService developmentRequests;
    private final PlanningRepositoryUpdater repositoryUpdater;

    public FrdCompletionService(FrdService frds, FrdScreenMapper screens,
                                FrdScreenChatService screenChats, FrdAnalysisNoteMapper notes,
                                FrdScreenIaMaterializer iaMaterializer,
                                FrdWorkspace workspaces,
                                DevelopmentRequestService developmentRequests,
                                PlanningRepositoryUpdater repositoryUpdater) {
        this.frds = frds;
        this.screens = screens;
        this.screenChats = screenChats;
        this.notes = notes;
        this.iaMaterializer = iaMaterializer;
        this.workspaces = workspaces;
        this.developmentRequests = developmentRequests;
        this.repositoryUpdater = repositoryUpdater;
    }

    public synchronized String complete(String projectId, String frdId) {
        return complete(projectId, frdId, null);
    }

    public synchronized String complete(String projectId, String frdId, String confirmedCloneHead) {
        Frd frd = frds.of(projectId, frdId);
        DevelopmentRequest previousRequest = developmentRequests.findNotSentByFrd(projectId, frdId);
        if (frd.state() == Frd.State.REVIEW && previousRequest != null) {
            developmentRequests.returnToFrd(projectId, previousRequest.id());
            frd = frds.of(projectId, frdId);
        }
        if (frd.state() != Frd.State.DRAFTING) {
            throw new IllegalStateException("수정 중인 FRD만 작업을 완료할 수 있습니다.");
        }
        List<FrdScreen> mine = screens.selectByFrdId(frdId);
        if (mine.stream().anyMatch(screen -> screen.state() == FrdScreen.State.GENERATING)
                || screenChats.running(frdId) != null) {
            throw new IllegalStateException("AI가 화면을 수정하고 있어 FRD 작업을 완료할 수 없습니다.");
        }

        // 개발요청서에서 돌아오는 중 DB 상태만 DRAFTING으로 바뀌고 완료 커밋이 남은 경우를 복구한다.
        // 기존 완료 커밋을 먼저 풀어야 다시 완료해도 「완료 = 커밋 하나」 구조가 유지된다.
        String completionMessage = FrdWorkspace.completionMessage(frd.label());
        if (workspaces.hasCompletionToReopen(projectId, frdId, completionMessage)) {
            if (!workspaces.uncommitCompletion(projectId, frdId, completionMessage)) {
                throw new IllegalStateException("이전 FRD 작업 완료를 다시 열지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
            }
        }

        // 신규 화면의 개발용 화면 ID를 확보하고 HTML·기능정의서의 내부 식별자만 정규화한다.
        // 정식 IA 위치가 미정이어도 FRD 완료와 개발요청서 생성을 막지 않는다.
        iaMaterializer.materialize(projectId, frdId);

        // 커밋 앞에 원격 기본 브랜치를 받고 워크트리에 반영한다.
        // 충돌은 작업물을 보존한 채 완료를 멈추고, 화면 영향이 있으면 기획자 확인을 먼저 받는다.
        return repositoryUpdater.withLatest(projectId, () -> completeWithLatestClone(
                projectId, frdId, completionMessage, mine, confirmedCloneHead));
    }

    private String completeWithLatestClone(String projectId, String frdId, String completionMessage,
                                           List<FrdScreen> mine, String confirmedCloneHead) {
        FrdWorkspace.SyncResult sync = workspaces.syncWithCloneDetails(projectId, frdId);
        if (sync.state() == FrdWorkspace.Sync.CONFLICT) {
            throw new IllegalStateException("최신 기획 저장소 변경과 현재 FRD 작업이 겹칩니다. "
                    + "작업물은 그대로 보존했습니다. 충돌 내용을 확인한 뒤 다시 완료해 주세요.");
        }
        if (sync.state() == FrdWorkspace.Sync.MERGED) {
            workspaces.refreshDerivedFiles(projectId, frdId);
            FrdScreen affected = visuallyAffectedScreen(mine, sync.changedPaths());
            if (affected != null) {
                workspaces.requireLatestReview(projectId, frdId, sync.cloneHead(), affected.id());
            }
        }

        FrdWorkspace.PendingReview pending = workspaces.pendingLatestReview(projectId, frdId);
        if (pending != null) {
            if (!workspaces.confirmLatestReview(projectId, frdId, confirmedCloneHead)) {
                throw new LatestReviewRequired(pending.cloneHead(), pending.screenRowId());
            }
        }

        FrdWorkspace.Commit commit = workspaces.commitChanges(projectId, frdId, completionMessage);
        try {
            DevelopmentRequest request = developmentRequests.createFromCompletedFrd(
                    projectId, frdId, commit.before(), commit.after());
            prepareDevelopmentRequest(projectId, request.id());
            return request.id();
        } catch (RuntimeException transitionFailure) {
            try {
                workspaces.rollbackCommit(commit);
            } catch (RuntimeException rollbackFailure) {
                transitionFailure.addSuppressed(rollbackFailure);
            }
            throw transitionFailure;
        }
    }

    private FrdScreen visuallyAffectedScreen(List<FrdScreen> screens, List<String> changedPaths) {
        for (FrdScreen screen : screens) {
            if (screen.systemCode() == null || screen.systemCode().isBlank()) continue;
            String system = screen.systemCode().strip();
            String systemRoot = "core/" + system + "/";
            String scopedStyle = "design-guide/styles/" + system;
            boolean systemVisualChange = changedPaths.stream().anyMatch(path ->
                    path.startsWith(systemRoot) && (path.endsWith(".css") || path.endsWith(".js"))
                            || path.startsWith(scopedStyle) && path.endsWith(".css"));
            if (systemVisualChange) return screen;
            if (screen.isNewScreen() || screen.screenId() == null) continue;
            String pages = "core/" + screen.systemCode() + "/pages/" + screen.screenId() + ".html";
            String variants = "core/" + screen.systemCode() + "/variants-";
            if (changedPaths.stream().anyMatch(path -> path.equals(pages)
                    || path.startsWith(variants) && path.endsWith("/" + screen.screenId() + ".html"))) {
                return screen;
            }
        }
        return null;
    }

    /** 최신 공통 변경이 화면에 영향을 주어 기획자 확인이 먼저 필요한 경우다. */
    public static final class LatestReviewRequired extends IllegalStateException {
        private final String cloneHead;
        private final String screenRowId;

        LatestReviewRequired(String cloneHead, String screenRowId) {
            super("최신 기획 저장소 내용이 화면에 반영되었습니다. 변경된 화면을 확인해 주세요.");
            this.cloneHead = cloneHead;
            this.screenRowId = screenRowId;
        }

        public String cloneHead() { return cloneHead; }
        public String screenRowId() { return screenRowId; }
    }

    /** 미전송 개발요청서가 남은 검토 단계 FRD를 작업 완료 한 번으로 다시 반영할 수 있는지 확인한다. */
    public boolean canCompleteFromReview(String projectId, Frd frd) {
        return frd.state() == Frd.State.REVIEW
                && developmentRequests.findNotSentByFrd(projectId, frd.id()) != null;
    }

    /** 화면 작업이 없는 FRD는 작업 파일 커밋 없이 분석 결과만 개발요청서로 고정한다. */
    public synchronized String completeWithoutScreenWork(String projectId, String frdId) {
        Frd frd = frds.of(projectId, frdId);
        if (frd.state() != Frd.State.DRAFTING) {
            throw new IllegalStateException("수정 중인 FRD만 작업을 완료할 수 있습니다.");
        }
        if (!screens.selectByFrdId(frdId).isEmpty()) {
            throw new IllegalStateException("화면 작업이 있는 FRD는 작업대에서 완료해 주세요.");
        }
        String requestId = developmentRequests.createFromCompletedFrd(projectId, frdId).id();
        prepareDevelopmentRequest(projectId, requestId);
        return requestId;
    }

    /**
     * FRD와 SRT가 개발요청서를 만든 뒤 함께 타는 AI 준비 단계다.
     * 화면이 없으면 기능정의서는 0건이고, 나머지 준비 작업은 같은 규칙으로 실행한다.
     */
    public void prepareDevelopmentRequest(String projectId, String requestId) {
        requestTobeDocuments(projectId, requestId);
        requestTestScenarios(projectId, requestId);
    }

    /**
     * 「변경 예정 기능정의서」 만들기를 완료 시점에 <b>스스로 건다</b> — 병주 지시 2026-08-25.
     *
     * <p>⭐ 여기가 맞는 자리다. 목업이 커밋되어 더 안 바뀌는 순간이라 재료 셋(as-is · 목업 · 변경 목록)이
     * 확정된다. 설계가 막은 것은 「목업을 고칠 때마다」 만드는 것이고, 완료 시점은 그 금지에 걸리지 않는다.
     * 상세 화면에 수동 생성 버튼을 두지 않는다. 실패하면 전송 전 확인에서 부족한 화면을 안내한다.
     *
     * <p>⛔ <b>실패가 완료를 뒤집지 않는다.</b> 개발요청서는 이미 만들어졌고 커밋도 섰다 — 여기서 던지면
     * {@code complete} 가 커밋을 되돌려 <b>성립한 완료를 무너뜨린다.</b> 못 걸면 전송 전 확인이 차단으로 잡는다.
     */
    private void requestTobeDocuments(String projectId, String requestId) {
        try {
            developmentRequests.requestTobeDocuments(projectId, requestId);
        } catch (RuntimeException failure) {
            log.warn("변경 예정 기능정의서 자동 생성을 시작하지 못했다 — 전송 전 확인에서 안내한다 requestId={}",
                    requestId, failure);
        }
    }

    /**
     * 개발이 받을 <b>테스트 시나리오</b> 만들기를 완료 시점에 건다 — 병주 지시 2026-08-27.
     *
     * <p>⭐ <b>여기가 맞는 자리다.</b> 재료 셋(완료 조건 · 화면 외 구현 · 판정 방법)이 개발요청서로
     * 고정되는 순간이라 더 안 바뀐다. 위 기능정의서와 같은 자리이고 같은 규칙이다.
     *
     * <p>⛔ <b>종전 자리(개발요청서 상세 렌더)로 되돌리지 마라.</b> 거기는
     * {@code DevelopmentRequestService#precheck} 의 {@code readOnly = true} 트랜잭션 안이라,
     * 만들기가 스냅샷을 저장하는 순간 PostgreSQL 이 그 트랜잭션을 통째로 중단시킨다 — 그러면
     * <b>완료의 도착 화면이 500 으로 죽어</b> 성립한 완료가 실패로 보인다(2026-08-27 실물).
     *
     * <p>⛔ <b>실패가 완료를 뒤집지 않는다</b> — 위와 같다. 못 걸면 전송 전 확인이 경고로 알려 준다.
     */
    private void requestTestScenarios(String projectId, String requestId) {
        try {
            developmentRequests.requestTestScenarios(projectId, requestId);
        } catch (RuntimeException failure) {
            log.warn("테스트 시나리오 자동 생성을 시작하지 못했다 — 전송 전 확인에서 안내한다 requestId={}",
                    requestId, failure);
        }
    }

    /**
     * 화면 작업이 없는 범위를 작업대와 워크트리 없이 개발요청서로 만든다.
     *
     * <p>⭐ <b>화면이 하나라도 있으면 닫는다 (2026-09-02 병주 확정).</b> 종전에는 AI 가
     * 간단 변경으로 권장한 기존 화면 한 장을 통과시켰다 — 그 몫은 이제 FRD 밖의
     * <b>SRT(빠른 개발요청)</b> 메뉴가 받는다. ⛔ <b>권장 여부로 화면을 다시 열지 마라</b> —
     * 인터뷰 지시문({@code ScreenPickWorker} 의 인터뷰 절)과 같은 기준이어야
     * 프롬프트가 흔들려도 여기서 잡힌다.
     */
    public synchronized String completeFastTrack(String projectId, String frdId) {
        Frd frd = frds.of(projectId, frdId);
        if (frd.state() != Frd.State.SCOPE_REVIEW) {
            throw new IllegalStateException("개발 범위를 확인하는 중에만 개발요청서를 바로 만들 수 있습니다.");
        }
        var analysisNotes = notes.selectByFrdId(frdId);
        if (analysisNotes.stream().anyMatch(note -> note.kind() == FrdAnalysisNote.Kind.OPEN_ISSUE)) {
            throw new IllegalStateException("확인할 내용을 먼저 정리한 뒤 진행 방식을 선택해 주세요.");
        }
        if (!screens.selectByFrdId(frdId).isEmpty()) {
            throw new IllegalStateException("이 작업은 FRD 작업에서 변경 내용을 구체화해야 합니다.");
        }
        String requestId = developmentRequests.createFromConfirmedScope(projectId, frdId).id();
        prepareDevelopmentRequest(projectId, requestId);
        return requestId;
    }
}
