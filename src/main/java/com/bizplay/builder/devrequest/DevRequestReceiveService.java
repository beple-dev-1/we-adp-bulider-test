package com.bizplay.builder.devrequest;

import com.bizplay.builder.project.PlanningRepositoryUpdater;
import com.bizplay.builder.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「개발 결과 받기」 — 역류의 <b>부르는 자리</b>.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md}
 * 「받으면 바로 넣는다 — 사람이 끼어들지 않는다」.
 *
 * <pre>
 * feedback/<프로젝트>/DR-nnn 을 받는다 → 회신서로 판정 → 통과하면 골라 담아 기본 브랜치에 커밋 하나
 *                          → 테스트 결과 md 둘을 TC 번호별로 DB 에 담는다
 * </pre>
 *
 * <p>⭐ <b>역류는 두 갈래이고 앉는 자리가 다르다.</b> as-is 재동기는 <b>원자리</b>(기획 저장소)로,
 * 산출물 역류(테스트 결과 둘)는 <b>개발요청서 자리</b>(빌더 DB)로 간다. 갈라 두지 않으면
 * 개수와 경로가 서로를 오염시킨다.
 *
 * <p>⛔ <b>거절이면 아무것도 안 놓는다</b> — 기획 저장소도 DB 도. 반쪽 상태를 다음 FRD 가
 * as-is 로 읽는 것이 가장 나쁘다.
 *
 * <p>⚠ <b>판정에 쓰는 「돌려받을 것」은 스냅샷에서 다시 계산한다.</b> 스냅샷은 다시 만들지 않는
 * 값이라 보낼 때와 같은 결과가 나온다. ⛔ 기준 커밋은 여기서 안 넘긴다 — 받는 자리가
 * <b>지금 기본 브랜치의 판</b>을 재서 판정기에 준다(설계: 받을 때 HEAD 와 다르면 거절).
 */
@Service
public class DevRequestReceiveService {

    private static final Logger log = LoggerFactory.getLogger(DevRequestReceiveService.class);

    private final DevelopmentRequestMapper requests;
    private final DevelopmentRequestService requestService;
    private final DevRequestDeliveryWorkspace workspaces;
    private final DevRequestTestResultMapper testResults;
    private final ProjectService projects;
    private final PlanningRepositoryUpdater repositoryUpdater;
    private final DevRequestReceiptMapper receipts;

    public DevRequestReceiveService(DevelopmentRequestMapper requests,
                                    DevelopmentRequestService requestService,
                                    DevRequestDeliveryWorkspace workspaces,
                                    DevRequestTestResultMapper testResults,
                                    ProjectService projects,
                                    PlanningRepositoryUpdater repositoryUpdater,
                                    DevRequestReceiptMapper receipts) {
        this.requests = requests;
        this.requestService = requestService;
        this.workspaces = workspaces;
        this.testResults = testResults;
        this.projects = projects;
        this.repositoryUpdater = repositoryUpdater;
        this.receipts = receipts;
    }

    /** 받은 결과 — 화면이 이것을 그대로 보여 준다. */
    /** @param alreadyReceived 이미 받은 판을 다시 누름 — git 은 건너뛰고 테스트 결과만 다시 담았다 */
    public record Result(boolean accepted, List<String> rejections, String commit, int testRows,
                         boolean alreadyReceived) {
    }

    /**
     * @param accountId 누른 계정 — 수신 이력에 남는다. 모르면 {@code null}
     */
    public synchronized Result receive(String projectId, String requestId, String accountId) {
        DevelopmentRequest request = requests.selectById(requestId);
        if (request == null || !request.projectId().equals(projectId)) {
            throw new IllegalArgumentException("개발요청서를 찾을 수 없습니다.");
        }
        if (request.deliveryState() != DevelopmentRequest.DeliveryState.SENT) {
            // ⚠ 안 보낸 것을 받을 수는 없다 — 무엇을 기준으로 판정할지가 없다.
            throw new IllegalStateException("아직 개발에 넘기지 않은 개발요청서입니다.");
        }

        DevelopmentRequestService.View view = requestService.read(projectId, requestId);
        String feedbackBranch = DeliveryIndex.returnBranch(projectId, request.label());
        /*
         * ⚠ 보호 화면 목록은 오늘 늘 비어 있다 — 그 표시를 담는 자리가 빌더에 아직 없다.
         *   표시가 생기면 여기에 넘겨야 그 화면의 화면 md 를 안 받는 규율이 실제로 선다.
         */
        ExpectedBack expected = ExpectedBack.of(feedbackBranch, null, view.content(), List.of());

        ProjectService.CloneMaterials materials = projects.cloneMaterials(projectId);
        DevRequestDeliveryWorkspace.Received received = workspaces.receive(
                projectId, requestId, materials.defaultBranch(), materials.authenticatedUrl(),
                feedbackBranch, request.label() + "/" + ReturnBatch.FILE,
                (returnJson, main, returnedFile) -> ReturnBatch
                        .judge(expected, returnJson, main)
                        // ⛔ 테스트 결과도 커밋 전에 따진다 — 뒤에서 거르면 화면만 들어가는 반쪽이 된다.
                        .rejectAlso(ReturnBatch.judgeTests(expected,
                                returnedFile.apply(request.label() + "/" + ReturnBatch.UNIT_TESTS),
                                returnedFile.apply(request.label() + "/" + ReturnBatch.INTEGRATION_TESTS))),
                "chore: " + request.label() + " 개발 결과 반영");

        if (!received.accepted()) {
            log.info("개발 결과를 거절했다 projectId={} 개발요청서={} 사유={}",
                    projectId, requestId, received.rejections());
            // ⭐ 거절도 남긴다 — 무엇 때문에 몇 번 떨어졌는지가 개발과 말을 맞출 근거다.
            receipts.insert(DevRequestReceipt.rejected(requestId, received.returnedHead(),
                    received.rejections(), accountId));
            return new Result(false, received.rejections(), null, 0, false);
        }

        /*
         * ⭐ 이미 받은 판이어도 테스트 결과는 다시 담는다 — 밀고 나서 담기가 실패했을 때 다시 누르는
         *   길이 이것이다. 같은 TC 는 갈아 끼우므로 두 번 담아도 한 벌이다.
         */
        int rows = storeTestResults(projectId, requestId, received.returnedHead(), request.label());
        if (received.commit() != null) {
            // ⚠ 이미 받은 판이어도 맞춘다 — 앞 받기에서 맞추기가 실패했을 수 있다.
            refreshClone(projectId, requestId);
        }
        receipts.insert(received.alreadyReceived()
                ? DevRequestReceipt.already(requestId, received.returnedHead(), received.commit(), rows, accountId)
                : DevRequestReceipt.accepted(requestId, received.returnedHead(), received.commit(), rows, accountId));
        log.info("개발 결과를 받았다 projectId={} 개발요청서={} 커밋={} 테스트={}줄 이미받음={}",
                projectId, requestId, received.commit(), rows, received.alreadyReceived());
        return new Result(true, List.of(), received.commit(), rows, received.alreadyReceived());
    }

    /**
     * 받아 놓은 뒤 클론을 원격에 맞춘다.
     *
     * <p>⭐ <b>클론을 뒤처지게 만드는 것이 이 받기다</b> — 원격 {@code main} 에 직접 커밋하고 클론은 안
     * 건드린다. 그린존 문서(기능명세서·화면설계서·사용자 매뉴얼)는 클론의 {@code core/} 를 재료로 읽고
     * 「최신인가」도 그 파일 지문으로 잰다 — 맞추지 않으면 받은 개발 결과가 문서에 안 담기고, 옛 문서가
     * 최신으로 보인다(2026-09-23 실측: DR-010 을 받은 뒤 클론 {@code 896b7e6} · 원격 {@code 02924c1}).
     *
     * <p>⚠ <b>못 맞춰도 받기는 성공이다</b> — 원격에는 이미 들어갔다. 실패로 알리면 사람이 다시 누르고
     * 두 번째는 「바뀐 것 없음」이 된다. 클론은 다음 FRD 작업하기·IA 게시가 맞춘다.
     */
    private void refreshClone(String projectId, String requestId) {
        try {
            repositoryUpdater.refresh(projectId);
        } catch (RuntimeException failure) {
            log.warn("받은 뒤 클론을 원격에 맞추지 못했다 projectId={} 개발요청서={}", projectId, requestId, failure);
        }
    }

    /**
     * 테스트 결과 md 둘을 <b>TC 번호별 한 줄</b>로 담는다.
     *
     * <p>⚠ <b>md 가 없어도 받기는 성립한다.</b> 설계가 「시나리오 없이도 계약은 성립한다」고 했고,
     * 화면 변경만 있는 개발요청서도 있다. 없으면 0줄이다.
     */
    private int storeTestResults(String projectId, String requestId, String returnedHead,
                                 String label) {
        if (returnedHead == null) {
            return 0;
        }
        int rows = 0;
        rows += store(projectId, requestId, returnedHead, label + "/" + ReturnBatch.UNIT_TESTS, "UNIT");
        rows += store(projectId, requestId, returnedHead, label + "/" + ReturnBatch.INTEGRATION_TESTS,
                "INTEGRATION");
        return rows;
    }

    private int store(String projectId, String requestId, String commit, String path, String kind) {
        String markdown = workspaces.fileAt(projectId, commit, path);
        List<TestResultReader.Result> results = TestResultReader.read(markdown, kind);
        // ⭐ 다시 받으면 그 줄을 갈아 낀다 — 같은 TC 가 두 판 남으면 어느 것이 마지막인지 모른다.
        results.forEach(result -> testResults.upsert(requestId, result));
        return results.size();
    }
}
