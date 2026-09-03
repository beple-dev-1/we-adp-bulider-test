package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdItem;
import com.bizplay.builder.frd.FrdItemMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 나가는 창구와 전송 이력 — 계획 9 Task 8.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-handoff-to-dev-design.md}.
 *
 * <p>⭐ <b>여기서 재는 것은 「두 번 가지 않는 것」이다.</b> 우리 벽 안에서 증명되는 것 —
 * 한 사람이 두 번 누르거나 두 탭이 거의 같은 때에 눌러도 발송은 한 번이다.
 *
 * <p>⛔ 「같은 개발요청서가 개발에 두 번 도착하지 않는다」는 <b>보장이 아니다</b> — 우리 벽 밖이
 * 걸려 있다. 그래서 그 문장을 시험 이름에 쓰지 않는다.
 */
class DevRequestDeliveryTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdItemMapper items;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired DevRequestDeliveryMapper attempts;
    @Autowired DevelopmentRequestService service;
    @Autowired ProjectPaths paths;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionTemplate sqlSession;

    // ── 상태 셋 ───────────────────────────────────────────────────────────

    @Test
    void 창구가_아직_없으면_전송중에_머문다() {
        String requestId = deliverable("전송-창구없음");

        service.requestDelivery(project(requestId), requestId, null, null, null, null, null);

        // ⭐ 「대기」로 두면 다시 누를 때마다 처음부터 보내고, 「전송완료」로 두면
        //    안 나갔는데 나갔다고 거짓말한다.
        assertThat(requests.selectById(requestId).deliveryState())
                .isEqualTo(DevelopmentRequest.DeliveryState.SENDING);
    }

    @Test
    void 전송할_ZIP_원본을_Builder_데이터_영역에_저장한다() throws Exception {
        String requestId = deliverable("전송-ZIP저장");
        String projectId = project(requestId);

        service.requestDelivery(projectId, requestId, null, null, null, null, null);

        DevelopmentRequest request = requests.selectById(requestId);
        Path archive = paths.devRequestPackageArchive(projectId, requestId, request.number());
        assertThat(archive).isRegularFile();
        assertThat(archive.getFileName().toString()).isEqualTo(request.label() + ".zip");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.getEntry("dev-request.md")).isNotNull();
            assertThat(zip.getEntry("manifest.json")).isNotNull();
        }

        attempts.moveFromSending(requestId, DeliveryOutcome.SENT);
        sqlSession.clearCache();
        assertThat(service.hasStoredPackage(requests.selectById(requestId))).isTrue();
    }

    @Test
    void 성공한_최신_전송_시도를_개발_상태_동기화_대상으로_읽는다() {
        String requestId = deliverable("개발상태-대상");
        service.requestDelivery(project(requestId), requestId, null, null, null, null, null);
        DevRequestDeliveryAttempt attempt = attempts.selectByRequestId(requestId).get(0);
        attempts.finish(attempt.id(), DeliveryOutcome.SENT, 201,
                "http://gitlab.test/dev/x/-/issues/17", null);
        attempts.moveFromSending(requestId, DeliveryOutcome.SENT);

        DevelopmentStatusCandidate candidate = requests.selectDevelopmentStatusCandidates().stream()
                .filter(one -> one.requestId().equals(requestId))
                .findFirst().orElseThrow();

        assertThat(candidate.deliveryAttemptId()).isEqualTo(attempt.id());
        assertThat(candidate.issueUrl()).endsWith("/issues/17");
        assertThat(candidate.developmentState()).isEqualTo(DevelopmentState.INTAKE);
        assertThat(requests.isCurrentDevelopmentStatusCandidate(candidate)).isEqualTo(1);
        assertThat(requests.advanceDevelopmentState(candidate, DevelopmentState.PROGRESS)).isEqualTo(1);
        assertThat(requests.selectById(requestId).developmentState()).isEqualTo(DevelopmentState.PROGRESS);
    }

    @Test
    void 전송중에서_다시_확인하고_보낼_수_있다() {
        String requestId = deliverable("전송-다시확인");
        String projectId = project(requestId);
        service.requestDelivery(projectId, requestId, null, null, null, null, null);
        assertThat(requests.selectById(requestId).deliveryState())
                .isEqualTo(DevelopmentRequest.DeliveryState.SENDING);

        /*
         * ⭐ [2026-08-24 병주 교정] 설계는 「전송중이면 못 누른다」로 막았지만 그것은 「받았나」를
         *   물어볼 길이 없는 API 를 전제로 한 것이다. GitLab 창구는 보내기 전에 전송 키로 이슈를
         *   찾으므로 두 번 열리지 않는다 — 막으면 그 개발요청서가 영영 못 나가는 쪽이 더 나쁘다.
         */
        service.requestDelivery(projectId, requestId, null, null, null, null, null);

        assertThat(attempts.selectByRequestId(requestId)).hasSize(2);
    }

    @Test
    void 끝나지_않은_시도가_있으면_잠긴다() {
        String requestId = deliverable("전송-잠금");
        String projectId = project(requestId);
        service.requestDelivery(projectId, requestId, null, null, null, null, null);
        // 앞선 시도가 아직 도는 중인 모양을 만든다 (닫히지 않은 줄 하나).
        attempts.insert(new DevRequestDeliveryAttempt(ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY),
                requestId, "DRK-" + requestId, "0".repeat(64), DeliveryOutcome.SENDING,
                null, null, null, null, null, null));

        // ⛔ 이 잠금이 없으면 두 탭이 둘 다 「없다」를 읽고 둘 다 이슈를 연다.
        assertThatThrownBy(() -> service.requestDelivery(projectId, requestId, null,
                null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("아직 끝나지 않았습니다");
    }

    // ── 시도 이력 ─────────────────────────────────────────────────────────

    @Test
    void 시도마다_한_줄이_남고_지문과_전송키를_안고_간다() {
        String requestId = deliverable("전송-이력");
        service.requestDelivery(project(requestId), requestId, null, null, null, null, null);

        var log = attempts.selectByRequestId(requestId);
        assertThat(log).hasSize(1);
        var attempt = log.get(0);
        assertThat(attempt.deliveryKey()).isEqualTo("DRK-" + requestId);
        // ⭐ 「받았다」가 어느 판을 받은 것인지 묶는 값이다.
        assertThat(attempt.bodyFingerprint()).matches("^[0-9a-f]{64}$");
        assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.SENDING);
        assertThat(attempt.startedAt()).isNotNull();
        assertThat(attempt.finishedAt()).isNotNull();
        assertThat(attempt.failure()).contains("개발 창구 주소");
    }

    // ── 철회 (2026-08-25) ─────────────────────────────────────────────────

    /**
     * ⛔ <b>이 시험이 없어서 실물에서 터졌다 (2026-08-25).</b> 창구(`DevIssueGateway`)만 재고
     * <b>서비스 경로를 안 쟀더니</b> 철회 시도를 이력에 넣는 자리에서
     * {@code body_fingerprint} not null 위반이 났다.
     *
     * <p>⭐ 철회는 <b>몸을 안 보내므로 지문이 없다</b> — 널이 참인 값이다.
     */
    @Test
    void 철회_시도는_지문_없이_이력에_남는다() {
        String requestId = deliverable("철회-이력");
        String projectId = project(requestId);
        service.requestDelivery(projectId, requestId, null, null, null, null, null);

        // ⚠ 창구 설정이 없어 「전송중」이다 — 철회는 「전송완료」에서만 되므로 손으로 옮긴다.
        attempts.moveFromSending(requestId, DeliveryOutcome.SENT);

        assertThatThrownBy(() -> service.withdraw(projectId, requestId, "요구가 바뀌었다", null))
                // ⭐ 창구가 철회를 못 하는 것은 정상이다 — 여기서 재는 것은 「이력 줄이 남나」다.
                .isInstanceOf(IllegalStateException.class);

        var log = attempts.selectByRequestId(requestId);
        assertThat(log).as("철회 시도도 한 줄 남는다").hasSize(2);
        var withdrawal = log.stream().filter(one -> one.bodyFingerprint() == null).findFirst();
        assertThat(withdrawal).as("지문 없는 줄이 철회 시도다").isPresent();
        assertThat(withdrawal.get().deliveryKey()).isEqualTo("DRK-" + requestId);
    }

    /** ⛔ 창구가 철회를 못 했으면 DB 를 안 옮긴다 — 이슈는 열려 있는데 우리만 철회로 알면 안 된다. */
    @Test
    void 창구가_철회를_못_하면_상태가_안_바뀐다() {
        String requestId = deliverable("철회-실패");
        String projectId = project(requestId);
        service.requestDelivery(projectId, requestId, null, null, null, null, null);
        attempts.moveFromSending(requestId, DeliveryOutcome.SENT);

        assertThatThrownBy(() -> service.withdraw(projectId, requestId, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(requests.selectById(requestId).deliveryState())
                .isEqualTo(DevelopmentRequest.DeliveryState.SENT);
    }

    /** 전송완료가 아니면 철회 자체가 막힌다. */
    @Test
    void 전송완료가_아니면_철회할_수_없다() {
        String requestId = deliverable("철회-대기");
        String projectId = project(requestId);

        assertThatThrownBy(() -> service.withdraw(projectId, requestId, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전송완료인 개발요청서만");
    }

    @Test
    void 철회_성공_결과를_전송_이력에_저장할_수_있다() {
        String requestId = deliverable("철회-성공결과");
        String attemptId = ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY);
        attempts.insert(new DevRequestDeliveryAttempt(attemptId, requestId,
                "DRK-" + requestId, null, DeliveryOutcome.SENDING,
                null, null, null, null, null, null));

        assertThat(attempts.finish(attemptId, DeliveryOutcome.WITHDRAWN,
                200, "http://gitlab.test/issues/9", null)).isEqualTo(1);
        assertThat(attempts.selectByRequestId(requestId)).singleElement()
                .extracting(DevRequestDeliveryAttempt::outcome)
                .isEqualTo(DeliveryOutcome.WITHDRAWN);
    }

    @Test
    void 다시_보내도_전송키는_같다() {
        String requestId = deliverable("전송-같은키");

        // ⚠ 시도마다 달라지는 값을 쓰면 재시도가 새 요청이 된다.
        assertThat(DevelopmentRequestService.deliveryKey(requestId))
                .isEqualTo(DevelopmentRequestService.deliveryKey(requestId));
    }

    // ── 답을 상태로 옮기는 표 ──────────────────────────────────────────────

    @Test
    void 되울림이_있는_이백번대만_전송완료다() {
        assertThat(DeliveryOutcome.of(200, true)).isEqualTo(DeliveryOutcome.SENT);
        assertThat(DeliveryOutcome.of(201, true)).isEqualTo(DeliveryOutcome.SENT);
        // ⚠ 사내 프록시와 로그인 페이지도 200 을 준다 — 되울림이 없으면 확인할 길이 없다.
        assertThat(DeliveryOutcome.of(200, false)).isEqualTo(DeliveryOutcome.SENDING);
    }

    @Test
    void 이백이번은_접수이지_처리가_아니다() {
        assertThat(DeliveryOutcome.of(202, true)).isEqualTo(DeliveryOutcome.SENDING);
    }

    @Test
    void 요청_자체를_안_받은_것만_대기다() {
        for (int status : new int[] {400, 401, 403, 404, 413, 415, 422}) {
            assertThat(DeliveryOutcome.of(status, false))
                    .as("%d 는 몸이 상대 처리에 닿지 않았다", status)
                    .isEqualTo(DeliveryOutcome.NOT_SENT);
        }
    }

    @Test
    void 사백번대를_통째로_대기로_뭉치지_않는다() {
        // ⛔ 408·429 는 「지금 말고 나중에」이고 409 는 오히려 「이미 받았다」일 수 있다.
        assertThat(DeliveryOutcome.of(408, false)).isEqualTo(DeliveryOutcome.SENDING);
        assertThat(DeliveryOutcome.of(429, false)).isEqualTo(DeliveryOutcome.SENDING);
        assertThat(DeliveryOutcome.of(409, false)).isEqualTo(DeliveryOutcome.SENDING);
    }

    @Test
    void 오백번대와_삼백번대는_모르는_것이라_전송중이다() {
        for (int status : new int[] {301, 302, 307, 500, 502, 503, 504}) {
            assertThat(DeliveryOutcome.of(status, false))
                    .as("%d 는 처리를 안 했다는 증거가 아니다", status)
                    .isEqualTo(DeliveryOutcome.SENDING);
        }
    }

    @Test
    void 몸이_나가기_전에_끝난_것은_대기다() {
        // DNS 실패·연결 거부·TLS 실패. ⛔ 시간 상한과 응답 전 끊김은 여기가 아니다.
        assertThat(DeliveryOutcome.beforeBody()).isEqualTo(DeliveryOutcome.NOT_SENT);
    }

    // ── 전송 전 확인 얼리기 ─────────────────────────────────────────────

    /**
     * ⭐ 실물에서 발견 (2026-08-25 병주) — 전송완료된 DR-011 의 「전송 전 확인」이 상세를 열 때마다 달라졌다.
     * 매번 지금 클론·워크트리를 다시 재고 있었고, 검사기 UNKNOWN 이 10분마다 만료돼 「점검 중 ↔ 돌리지 못했다」를
     * 왕복했다. 「전송 <b>전</b> 확인」은 보낸 시점의 기록이어야 한다.
     */
    @Test
    void 전송한_뒤에는_전송_전_확인이_전송_시점_결과로_얼어붙는다() {
        String requestId = deliverable("전송-확인얼림");
        String projectId = project(requestId);

        service.requestDelivery(projectId, requestId, null, null, null, null, null);

        // 보낸 순간의 결과가 그대로 저장된다.
        assertThat(requests.selectById(requestId).precheckJson()).isNotBlank();

        // 저장된 것을 읽는다는 증명 — 저장값을 표식으로 갈아끼우면 화면이 그 표식을 보여 준다.
        jdbc.update("update builder.adk_builder_dev_request set precheck_json = ? where id = ?",
                "{\"blocking\":[],\"warnings\":[{\"subject\":\"전체\",\"message\":\"얼어붙은 표식\",\"fix\":null}],"
                        + "\"checking\":false,\"notes\":[]}", requestId);
        // ⚠ 시험이 MyBatis 를 우회해 고쳤으니 세션 캐시를 비운다 — 안 비우면 selectById 가 묵은 줄을 준다.
        sqlSession.clearCache();
        var frozen = service.precheck(projectId, requestId);
        assertThat(frozen.warnings()).extracting(DevRequestPrecheck.Item::message).containsExactly("얼어붙은 표식");
        assertThat(frozen.checking()).isFalse();
    }

    /** 아직 한 번도 전송을 안 눌렀으면 굳은 기록이 없다 — 그때는 DB 로 아는 것만 잰다(검사기는 안 부른다). */
    @Test
    void 전송을_한_번도_안_눌렀으면_굳은_기록이_없다() {
        String requestId = deliverable("전송-확인생생");
        String projectId = project(requestId);

        assertThat(requests.selectById(requestId).precheckJson()).isNull();
        assertThat(service.precheck(projectId, requestId).checking()).isFalse();
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    private String project(String requestId) {
        return requests.selectById(requestId).projectId();
    }

    /** 게이트를 지나는 개발요청서 하나 — 화면 0장이라 화면 쪽 차단이 없다. */
    private String deliverable(String name) {
        Project project = readyProject(name);
        String frdId = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(frdId, project.getId(), frds.allocateNumber(project.getId()),
                "결재 배치 시간 변경", "정산 배치 실행 시간을 바꿔야 한다.", planner().getId()));
        frds.updateAfterPick(frdId, "결재 배치 시간 변경", "webview", null, Frd.State.PICKED, null);
        frds.updateState(frdId, Frd.State.DRAFTING);
        items.insert(new FrdItem(ids.next(IdSequence.Kind.FRD_ITEM), frdId, 1,
                "정산 배치 시간을 바꾼다", FrdItem.Nature.DEVELOP, FrdItem.Verdict.NO_SCREEN,
                null, null, null));
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), frdId, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "배치가 바뀐 시간에 돈다", null));
        return service.createFromCompletedFrd(project.getId(), frdId).id();
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account planner() {
        return accounts.selectByLoginId("sendplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "sendplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
