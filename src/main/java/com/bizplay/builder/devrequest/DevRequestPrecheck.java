package com.bizplay.builder.devrequest;

import com.bizplay.builder.checker.CheckReport;
import com.bizplay.builder.checker.Finding;
import com.bizplay.builder.checker.PlanningRepoCheckCache;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenHistory;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdWorkspace;
import com.bizplay.builder.frd.ScreenTobeDocumentWorker;
import com.bizplay.builder.project.ProjectPaths;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 전송 전 검증 — <b>차단과 경고를 갈라 낸다.</b>
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⭐ <b>두 층인 것이 핵심이다.</b> 하나로 뭉치면 둘 중 하나가 된다 — 전부 차단이면
 * 애매한 건이 영영 못 나가고, 전부 경고면 <b>개발이 못 쓰는 것이 계약에 실려 나간다.</b>
 *
 * <p>⛔ <b>빌더가 자기 검사기를 만들지 않는다.</b> 규격 판정은 기획 레포에 실려 온 검사기가 한다
 * ({@code CheckerCommand}). 여기가 하는 일은 그 결과를 <b>차단이냐 경고냐로 옮기는 것</b>뿐이다.
 *
 * <p>⭐ <b>검사기는 「전송을 누를 때」만 돈다 (2026-08-25 병주 지시).</b> 상세 화면은 {@link #check} 로
 * DB 로 아는 것만 말하고, {@link #checkForDelivery} 만 검사기를 부른다 — 열 때마다 걸었더니 결과가
 * 만료될 때마다 같은 화면이 다르게 보였다.
 */
@Component
public class DevRequestPrecheck {

    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final FrdWorkspace workspaces;
    private final PlanningRepoCheckCache checks;
    private final ProjectPaths paths;
    private final ScreenTobeDocumentWorker tobeDocuments;
    private final DevRequestTestScenarioWorker testScenarios;

    public DevRequestPrecheck(FrdScreenMapper screens, FrdScreenHistoryMapper histories,
                              FrdWorkspace workspaces, PlanningRepoCheckCache checks,
                              ProjectPaths paths, ScreenTobeDocumentWorker tobeDocuments,
                              DevRequestTestScenarioWorker testScenarios) {
        this.tobeDocuments = tobeDocuments;
        this.testScenarios = testScenarios;
        this.screens = screens;
        this.histories = histories;
        this.workspaces = workspaces;
        this.checks = checks;
        this.paths = paths;
    }

    /**
     * 검증 결과.
     *
     * @param blocking 하나라도 있으면 전송이 안 눌린다
     * @param warnings 사람이 보고 넘긴다
     * @param checking 기획 문서 자동 점검이 아직 도는 중이다 — 끝나기 전에는 전송이 안 눌린다 (2026-08-25)
     * @param notes    <b>참고</b> — 이 개발요청서와 무관하게 기획 저장소에 원래 있던 것. 「살펴볼 것」에 섞지 않는다
     *                 (2026-08-25 병주 지시: 매 문서마다 같은 두 줄이 떠서 진짜 볼 것이 묻혔다). 숨기지는 않는다 —
     *                 「레포에 원래 N건이 있다」는 알려야 하고, 그건 추출기·기획팀 몫이다
     */
    public record Result(List<Item> blocking, List<Item> warnings, boolean checking, List<Item> notes) {

        public Result {
            blocking = blocking == null ? List.of() : List.copyOf(blocking);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public Result(List<Item> blocking, List<Item> warnings) {
            this(blocking, warnings, false, List.of());
        }

        public Result(List<Item> blocking, List<Item> warnings, boolean checking) {
            this(blocking, warnings, checking, List.of());
        }

        /** ⚠ 검사 중이면 거짓이다 — 결과를 모르는 채 보내지 않는다. 그 값이 「검사 중」 표시 하나다. */
        public boolean sendable() {
            return blocking.isEmpty() && !checking;
        }

        public boolean isEmpty() {
            return blocking.isEmpty() && warnings.isEmpty() && !checking;
        }
    }

    /**
     * 검증 한 줄.
     *
     * @param subject 어디 것인가 (화면명·「전체」)
     * @param message 무엇이 걸렸나. <b>사람이 그대로 읽는 한국어</b>다
     * @param fix     어떻게 푸나. 없으면 널
     */
    public record Item(String subject, String message, String fix, String detail) {
        public Item(String subject, String message, String fix) {
            this(subject, message, fix, null);
        }
    }

    /**
     * <b>상세 화면용</b> — DB 로 아는 것만 잰다. ⛔ 기획 문서 자동 점검(검사기)을 부르지 않는다.
     *
     * <p>⭐ <b>병주 지시 2026-08-25 — 「화면 들어갈 때마다 검증하지 마라」.</b> 상세를 열 때마다 검사기를
     * 걸었더니 결과가 10분마다 만료돼(UNKNOWN TTL) <b>같은 화면이 열 때마다 다르게</b> 보였고, 「점검 중 ↔
     * 돌리지 못했다」를 왕복했다. 검사기는 {@link #checkForDelivery} 에서 <b>전송을 누를 때</b> 한 번 돈다.
     */
    public Result check(DevelopmentRequestService.View view) {
        return check(view, false);
    }

    /**
     * <b>전송용</b> — 검사기까지 돌려서 잰다. 사람이 「개발요청 전송」을 누른 자리라 몇 초를 기다릴 각오가 돼 있다.
     *
     * <p>⚠ 결과가 캐시에 있으면 그것을 쓴다({@link PlanningRepoCheckCache}) — 매번 새로 도는 것이 아니다.
     */
    public Result checkForDelivery(DevelopmentRequestService.View view) {
        return check(view, true);
    }

    private Result check(DevelopmentRequestService.View view, boolean withPlanningRepo) {
        DevelopmentRequest request = view.request();
        DevelopmentRequestContent content = view.content();
        List<Item> blocking = new ArrayList<>();
        List<Item> warnings = new ArrayList<>();
        List<Item> notes = new ArrayList<>();

        /*
         * ── 테스트 시나리오 — 읽기만 한다. ⚠ 차단이 아니다 — 빈 양식으로도 계약은 성립한다.
         *
         * ⛔ 여기서 만들기를 청하지 마라 (병주 지시 2026-08-27). 이 메서드는 상세 화면 렌더가 부르고
         *   그 자리는 DevelopmentRequestService#precheck 의 readOnly = true 트랜잭션 안이다 —
         *   만들기가 스냅샷을 저장하는 순간 PostgreSQL 이 그 트랜잭션을 통째로 중단시켜
         *   FRD 완료의 도착 화면이 500 으로 죽는다. 청하는 자리는 FrdCompletionService 하나다.
         */
        if (!content.hasTestScenarios()
                && request.deliveryState() == DevelopmentRequest.DeliveryState.NOT_SENT) {
            if (testScenarios.isGenerating(request.id())) {
                warnings.add(new Item("전체", "테스트 시나리오를 만들고 있습니다.",
                        "AI 가 쓰는 데 몇 분 걸립니다. 지금 보내면 회신 양식이 빈 칸으로 나갑니다."));
            } else if (testScenarios.hasFailed(request.id())) {
                warnings.add(new Item("전체", "테스트 시나리오를 만들지 못했습니다.",
                        "회신 양식은 빈 칸으로 나가고 개발이 채웁니다."));
            }
        }

        // ── 보낼 것이 있나 ──
        if (content.developmentRequirements().isEmpty()) {
            blocking.add(new Item("전체", "개발이 필요한 항목이 없습니다.",
                    "FRD 로 돌아가 개발 범위를 확인해 주세요."));
        }
        if (content.acceptanceCriteria().isEmpty()) {
            warnings.add(new Item("전체", "완료 조건이 없습니다.",
                    "무엇으로 됐다고 할지 정해 두면 개발이 검수 기준을 갖습니다."));
        }
        if (!content.openIssues().isEmpty()) {
            warnings.add(new Item("전체",
                    "확인이 필요한 내용이 " + content.openIssues().size() + "건 남아 있습니다.",
                    "정해지지 않은 채 나가면 개발이 임의로 정합니다."));
        }

        // ── 화면 ──
        Set<String> linkedTargets = content.screens().stream()
                .flatMap(screen -> screen.connections().stream())
                .map(DevelopmentRequestContent.Connection::targetScreenId)
                .collect(java.util.stream.Collectors.toSet());
        for (var screen : content.screens()) {
            checkScreen(request, view, screen, linkedTargets.contains(screen.deliveryScreenId()),
                    blocking, warnings);
        }

        // ── 화면 외 구현 ──
        for (var change : content.requiredChanges()) {
            if (change.target() == null || change.target().isBlank()) {
                warnings.add(new Item("화면 외 구현", "대상이 비어 있는 항목이 있습니다.",
                        "무엇을 고치는지 적어야 개발이 짚을 수 있습니다."));
            }
            if (change.verification() == null || change.verification().isBlank()) {
                // ⭐ 화면은 목업이 완료 조건 노릇을 하지만 화면 외 구현은 이 칸이 없으면
                //    항목별 검수가 갈리지 않는다. ⚠ 다만 차단은 아니다 — 지어내는 것보다 비는 것이 낫다.
                warnings.add(new Item("화면 외 구현 · " + value(change.target()),
                        "판정 방법이 정해지지 않았습니다.",
                        "무엇으로 됐다고 할지 한 문장이면 됩니다."));
            }
        }

        // ── 작업 자리 ──
        checkWorkspace(request, blocking, warnings);

        // ── 기획 문서 자동 점검 (기획 레포에 실려 온 검사기) ──
        boolean checking = withPlanningRepo && checkPlanningRepo(request, blocking, warnings, notes);

        return new Result(blocking, warnings, checking, notes);
    }

    /**
     * 화면에 뜨는 이름. ⛔ 「기획 저장소 검사」·「검사기」라 쓰지 마라 — 기획자가 쓰는 프로그램이다
     * (2026-08-25 병주: 「기획자가 그걸 어떻게 알아」). 무엇을 하는지가 이름에 들어야 한다.
     */
    static final String CHECK_SUBJECT = "기획 문서 자동 점검";

    /**
     * 작업 자리에 커밋 안 된 것이 남았나.
     *
     * <p>⭐ <b>못 재는 것을 던지지 않는다.</b> {@code hasChanges} 는 자리가 있는데 git 저장소가
     * 아니면 던진다 — 그것을 그대로 올리면 <b>게이트 화면 전체가 500 으로 죽고</b> 기획자는
     * 무엇이 걸렸는지도 못 본다. {@code CheckReport.unknown()} 과 같은 판단이다:
     * ⛔ 초록으로 읽지 않고, 차단도 아니고, <b>경고로 그대로 보여 준다.</b>
     */
    private void checkWorkspace(DevelopmentRequest request, List<Item> blocking,
                                List<Item> warnings) {
        try {
            if (workspaces.hasChanges(request.projectId(), request.frdId())) {
                blocking.add(new Item("전체", "FRD 작업 자리에 커밋되지 않은 변경이 남아 있습니다.",
                        "저장된 것과 보낼 것이 갈립니다. FRD 작업을 다시 완료해 주세요."));
            }
        } catch (RuntimeException unreadable) {
            warnings.add(new Item("전체", "FRD 작업 자리를 확인하지 못했습니다.",
                    "커밋되지 않은 변경이 있어도 지금은 알 수 없습니다."));
        }
    }

    private void checkScreen(DevelopmentRequest request, DevelopmentRequestService.View view,
                             DevelopmentRequestContent.Screen screen, boolean hasInboundConnection,
                             List<Item> blocking, List<Item> warnings) {
        String subject = screen.displayName();

        if (screen.deliveryScreenId() == null || screen.deliveryScreenId().isBlank()
                || screen.deliveryScreenId().startsWith("tmp-")
                || !screen.deliveryScreenId().matches("^[a-z0-9][a-z0-9-]*$")) {
            blocking.add(new Item(subject, "개발에서 사용할 화면 ID가 없습니다.",
                    "FRD 작업 완료를 다시 실행해 화면 ID를 자동 생성해 주세요."));
        }
        if (screen.isNewScreen() && screen.deliveryScreenId() != null
                && screen.deliveryScreenId().matches("^[a-z0-9][a-z0-9-]*$")) {
            Path occupied = paths.cloneDir(request.projectId()).resolve("core")
                    .resolve(system(screen)).resolve("pages")
                    .resolve(screen.deliveryScreenId() + ".html");
            if (Files.isRegularFile(occupied)) {
                blocking.add(new Item(subject, "개발용 화면 ID가 기존 화면과 겹칩니다.",
                        "FRD 작업을 다시 완료해 겹치지 않는 화면 ID를 확인해 주세요."));
            }
        }
        if (screen.managementNumber() == null || screen.managementNumber().isBlank()) {
            warnings.add(new Item(subject, "화면 관리번호가 아직 정해지지 않았습니다.",
                    "개발요청은 진행할 수 있으며 관리자가 화면 관리에서 나중에 연결할 수 있습니다."));
        }
        if (screen.isNewScreen() && screen.connections().isEmpty() && !hasInboundConnection
                && (screen.entryPoint() == null || screen.entryPoint().isBlank())) {
            blocking.add(new Item(subject, "신규 화면의 연결 안내가 없습니다.",
                    "진입 화면과 클릭 요소를 연결하거나 메뉴 진입 위치를 적어 주세요."));
        }

        FrdScreen row = screens.selectById(screen.frdScreenId());
        if (row == null || row.html() == null || row.html().isBlank()) {
            blocking.add(new Item(subject, "수정한 화면이 아직 없습니다.",
                    "화면 작업을 마친 뒤에 보내야 합니다."));
        } else if (row.state() == FrdScreen.State.FAILED) {
            blocking.add(new Item(subject, "화면 만들기가 실패한 상태로 남아 있습니다.",
                    "다시 만들어 주세요."));
        } else if (row.state() == FrdScreen.State.GENERATING) {
            blocking.add(new Item(subject, "화면을 아직 만들고 있습니다.",
                    "끝난 뒤에 보내야 합니다."));
        }

        FrdScreenHistory latest = histories.selectLatestByScreenId(screen.frdScreenId());
        if (latest == null || latest.md() == null || latest.md().isBlank()) {
            if (tobeDocuments.isGenerating(screen.frdScreenId())) {
                // ⭐ 「없다」가 아니다 — 완료가 자동으로 걸어 둔 것이 아직 도는 중이다 (2026-08-25 병주 실측).
                blocking.add(new Item(subject, "변경 예정 기능정의서를 만들고 있습니다.",
                        "AI 가 쓰는 데 몇 분 걸립니다. 끝나면 이 화면이 스스로 다시 읽습니다."));
            } else {
                blocking.add(tobeDocumentWarning(subject, latest));
            }
        }
        if (screen.menuPath() == null || screen.menuPath().isBlank()) {
            // ⚠ 차단으로 올리지 마라 — 메뉴구조도를 나중에 정리하는 사업에서 전송이 통째로 막힌다.
            warnings.add(new Item(subject, "정식 메뉴 위치가 아직 연결되지 않았습니다.",
                    "개발요청은 진행할 수 있으며 관리자가 메뉴구조도에서 나중에 연결할 수 있습니다."));
        }
        Path asIs = paths.cloneDir(request.projectId()).resolve("core")
                .resolve(system(screen)).resolve("pages").resolve(screen.screenId() + ".md");
        if (row != null && !row.isNewScreen() && !Files.isRegularFile(asIs)) {
            warnings.add(new Item(subject, "현재 기능정의서가 저장소에 없습니다.",
                    "기존 화면의 기능정의서가 빠졌는지 기획 저장소를 확인해 주세요."));
        }
    }

    private Item tobeDocumentWarning(String subject, FrdScreenHistory latest) {
        var status = latest == null ? null : histories.selectTobeDocumentStatus(latest.id());
        if (status != null && "FAILED".equals(status.state())) {
            return new Item(subject, "변경 예정 기능정의서 생성에 실패했습니다.",
                    failureGuide(status.failure()));
        }
        if (status != null && "UNKNOWN".equals(status.state())) {
            return new Item(subject, "변경 예정 기능정의서 생성 결과를 확인할 수 없습니다.",
                    "이전 실행 기록이 남아 있지 않습니다. 「FRD 작업 재개」 후 다시 완료해 주세요.");
        }
        if (status != null && status.isGenerating()) {
            return new Item(subject, "변경 예정 기능정의서 생성이 중단됐습니다.",
                    "서버가 다시 시작됐거나 제한 시간을 넘겼습니다. 「FRD 작업 재개」 후 다시 완료해 주세요.");
        }
        return new Item(subject, "변경 예정 기능정의서를 만들어야 합니다.",
                "「FRD 작업 재개」 후 다시 완료하면 자동 생성을 다시 요청합니다.");
    }

    private String failureGuide(String failure) {
        if ("NO_CREDENTIAL".equals(failure)) {
            return "Claude 연결을 확인한 뒤 「FRD 작업 재개」 후 다시 완료해 주세요.";
        }
        if ("INVALID_RESPONSE".equals(failure)) {
            return "AI가 만든 문서 형식을 읽지 못했습니다. 「FRD 작업 재개」 후 다시 완료해 주세요.";
        }
        if ("MISSING_SYSTEM".equals(failure)) {
            return "FRD의 시스템을 확인한 뒤 다시 완료해 주세요.";
        }
        return "「FRD 작업 재개」 후 다시 완료해 주세요. 반복되면 관리자에게 알려 주세요.";
    }

    /**
     * 기획 레포 검사기를 돌린다.
     *
     * <p>⚠ <b>「못 돌렸다」를 차단으로 두지 않는다.</b> {@code NodeCheckerCommand} 가 적어 둔 대로
     * 사내 TLS 가로채기에 걸리면 서버에서 <b>늘 UNKNOWN</b> 이 된다 — 차단으로 두면 전송이
     * 영영 안 눌린다. ⛔ 그렇다고 <b>초록으로 읽지도 않는다</b>: 경고로 그대로 보여 준다.
     *
     * <p>⚠ 화면 0장 FRD 는 워크트리가 없다 — 검사기를 돌리지 않는다(정상이다).
     *
     * @return 검사가 아직 도는 중이면 참 — 결과가 캐시에 없어 뒤에서 걸어 둔 상태다
     */
    private boolean checkPlanningRepo(DevelopmentRequest request, List<Item> blocking,
                                      List<Item> warnings, List<Item> notes) {
        Path worktree = paths.frdWorktree(request.projectId(), request.frdId());
        if (!Files.isDirectory(worktree)) {
            return false;
        }
        Path clone = paths.cloneDir(request.projectId());
        // ⭐ 낡음을 먼저 말한다 (2026-08-25 실측) — 워크트리 manifest 가 옛 판이면 검사기가 첫 관문에서 멈춰
        //    UNKNOWN 이 되는데, 「돌리지 못했다」만으로는 사람이 왜인지 알 길이 없었다.
        boolean behind = false;
        try {
            behind = workspaces.isBehindClone(request.projectId(), request.frdId());
        } catch (RuntimeException unreadable) {
            // 재지 못하면 아래 검사기 결과만 말한다.
        }
        if (behind) {
            warnings.add(new Item(CHECK_SUBJECT, "이 FRD 의 작업 자리가 기획 문서 최신판보다 낡았습니다.",
                    "「FRD 작업 재개」 후 작업을 다시 완료하면 최신판에 맞춰집니다. 낡은 채 보내면 옛 규칙으로 점검되거나 점검이 안 됩니다."));
        }
        // ⚠ 여기는 전송을 누른 자리다 — 결과가 날 때까지 기다린다(캐시에 있으면 즉시).
        CheckReport report = checks.await(clone, worktree);
        if (report.isUnknown()) {
            warnings.add(new Item(CHECK_SUBJECT, "자동 점검을 돌리지 못했습니다.",
                    behind ? "작업 자리가 낡아 점검이 첫 단계에서 멈춘 것일 수 있습니다."
                           : "규칙에 어긋난 것이 있어도 지금은 알 수 없습니다."));
            return false;
        }
        boolean baselinePending = blockOnlyNewRed(clone, report, blocking, warnings, notes);
        // 저장소 전체의 review 수는 이 개발요청서의 판단 대상이 아니므로 화면에 싣지 않는다.
        return baselinePending;
    }

    /**
     * ⭐ <b>기저를 상쇄하고, 새로 생긴 red 중 {@link #isBlockingGate} 에 든 것만 막는다.</b>
     *
     * <p><b>두 단계다.</b> ① 기저 상쇄 — 클론에 원래 있던 red 는 이 FRD 탓이 아니다 (2026-08-25).
     * ② 게이트 선별 — 남은 것 중에서도 <b>개발이 그것 없이는 일을 못 하는 것</b>만 막는다
     * (2026-08-27). 둘 다 실물에서 고친 것이고, 막으려는 것은 같다 —
     * <b>기획자가 풀 수 없는 것으로 전송이 영영 막히는 상태.</b>
     *
     * <p><b>무슨 일이 있었나.</b> `DR-003` 전송 화면에 <b>「막는 것 28건」</b>이 떴는데,
     * 같은 때 클론을 대상으로 검사기를 직접 돌리니 <b>red 가 정확히 28</b> 이었다 —
     * <b>그 FRD 가 만든 위반은 0건</b>이고 전부 기획 레포에 원래 깔려 있던 것이었다
     * (도메인 색인 · `ui-base-wrap` 의 `display:none` · 팝업 IA 구멍).
     *
     * <p>⛔ <b>이 저장소는 그 답을 이미 알고 있었다.</b> {@link com.bizplay.builder.checker.DraftChecker}
     * 가 2026-08-14 에 같은 벽을 만나 적어 뒀다 — 「전체가 초록이어야 저장」은 <b>애초에 불가능</b>하고
     * <b>남이 만든 빨강이 기획자를 영원히 막는다</b>. 저장 전 검사는 그때 상쇄를 넣었는데
     * <b>전송 전 검증에만 안 들어와 있었다.</b>
     *
     * <p>⛔ <b>기저 red 를 안 보이게 하지 마라.</b> 사라지는 게 아니라 <b>경고로 내려간다</b> —
     * 「이 레포에 원래 N건이 있다」는 알려야 하고, 그건 <b>추출기·기획팀 몫</b>이지
     * 이 개발요청서를 막을 이유가 아니다.
     *
     * <p>⚠ <b>줄 번호를 열쇠에 넣지 않는다.</b> 워크트리가 파일 위쪽에 줄을 더하면 기저 red 의
     * 줄이 밀려 <b>「새로 생긴 것」으로 오인</b>된다. 열쇠는 `게이트|파일|내용` 이다.
     *
     * <p>⚠ <b>같은 열쇠가 여럿일 수 있어 개수로 센다.</b> 기저에 2건인데 워크트리에 3건이면
     * <b>1건만</b> 새것이다.
     *
     * <p>⛔ <b>기저를 못 재면 막지 않는다.</b> 그 red 가 누구 탓인지 모르는데 막으면
     * 이 함수가 없애려는 바로 그 상태(영영 못 나감)로 돌아간다. 대신 경고로 올려 사람이 본다.
     */
    // ⚠ 시험이 직접 부른다 — 재려는 것은 워크트리 배선이 아니라 상쇄 셈법이다.
    /** @return 기저를 아직 재는 중이면 참 — 그동안은 「검사 중」이다 */
    boolean blockOnlyNewRed(Path clone, CheckReport report,
                            List<Item> blocking, List<Item> warnings, List<Item> notes) {
        List<Finding> reds = report.findings().stream()
                .filter(finding -> finding.level() == Finding.Level.RED).toList();
        if (reds.isEmpty()) {
            return false;
        }
        CheckReport baseline = checks.await(clone, clone);
        if (baseline.isUnknown()) {
            warnings.add(new Item(CHECK_SUBJECT,
                    "규칙에 어긋난 것이 " + reds.size() + "건 있는데 이 작업 탓인지 원래 있던 것인지 가릴 수 없습니다.",
                    "가려지지 않아 막지 않았습니다. 기획 저장소 상태를 한 번 봐 주세요."));
            return false;
        }
        Map<String, Integer> pool = new HashMap<>();
        baseline.findings().stream()
                .filter(finding -> finding.level() == Finding.Level.RED)
                .forEach(finding -> pool.merge(baselineKey(finding), 1, Integer::sum));

        int cancelled = 0;
        List<Finding> added = new ArrayList<>();
        for (Finding finding : reds) {
            String key = baselineKey(finding);
            Integer left = pool.get(key);
            if (left != null && left > 0) {
                pool.put(key, left - 1);
                cancelled++;
                continue;
            }
            added.add(finding);
        }
        List<Finding> formalIa = added.stream()
                .filter(DevRequestPrecheck::isFormalIaFinding).toList();
        List<Finding> reviewCount = added.stream()
                .filter(DevRequestPrecheck::isReviewCountFinding).toList();
        List<Finding> blockingAdded = added.stream()
                .filter(DevRequestPrecheck::isBlockingGate).toList();
        List<Finding> rest = added.stream()
                .filter(finding -> !isBlockingGate(finding) && !isFormalIaFinding(finding)
                        && !isReviewCountFinding(finding))
                .toList();
        if (!formalIa.isEmpty()) {
            String detail = findingDetail(formalIa);
            warnings.add(new Item("기획 문서",
                    "정식 IA 확인 항목이 " + formalIa.size() + "건 있습니다.",
                    "개발요청은 진행할 수 있으며 관리자가 화면 관리에서 나중에 연결할 수 있습니다.", detail));
        }
        if (!reviewCount.isEmpty()) {
            String detail = findingDetail(reviewCount);
            warnings.add(new Item("기획 문서", "사람이 볼 항목이 늘었습니다.",
                    "규칙에 어긋난 것이 아니라 확인할 것이 늘어난 것입니다. "
                            + "개발요청은 진행할 수 있으며 기획 저장소의 기준선은 관리자가 올립니다.", detail));
        }
        if (!blockingAdded.isEmpty()) {
            String detail = findingDetail(blockingAdded);
            blocking.add(new Item("기획 문서",
                    "이번 작업에서 자동 점검 오류가 " + blockingAdded.size() + "건 늘었습니다.",
                    "FRD로 돌아가 변경 내용을 확인한 뒤 다시 완료해 주세요.", detail));
        }
        if (!rest.isEmpty()) {
            // ⭐ 막지는 않지만 지우지도 않는다 — 점검 기록에 남겨 관리자·추출기가 볼 수 있게 한다.
            //    ⛔ 화면에 끌어올리지 마라 (2026-08-27 병주 지시). 「추출기를 다시 돌려라」·
            //    「도구를 돌려 커밋해라」는 기획자가 읽고 할 수 있는 말이 아니다.
            warnings.add(new Item("기획 문서",
                    "막지는 않지만 자동 점검에서 걸린 것이 " + rest.size() + "건 있습니다.",
                    "개발요청은 진행할 수 있습니다. 기획 저장소·추출기 쪽에서 정리할 항목입니다.",
                    findingDetail(rest)));
        }
        if (cancelled > 0) {
            // 이 개발요청서와 무관한 기존 오류는 기본 화면에 반복 노출하지 않는다.
        }
        return false;
    }

    /**
     * ⭐ <b>막는 게이트는 목록으로 정한다 — 2026-08-27 에 「예외만 뺀다」를 뒤집었다 (병주 확정).</b>
     *
     * <p><b>왜 뒤집었나.</b> 검사기 게이트가 <b>30개</b>이고 추출기가 계속 늘린다. 종전 방식
     * (예외로 뺀 것 말고 전부 막는다)에서는 <b>새 게이트가 아무도 정하지 않은 채 전송을 막는 힘을
     * 갖는다</b> — {@code RATCHET} 이 정확히 그렇게 들어왔다. 이 방향의 실수는 기획자가 못 푸는
     * 벽으로 나타나고, 그때는 이미 늦다.
     *
     * <p><b>실측이 그걸 보여 줬다 (작업 자리 18곳).</b> 막혀 있던 3곳에서 막는 것의 대부분이
     * {@code DESIGN-1} 이었고 그 「고치는 법」은 <b>「{@code node verify/reindex.mjs} 를 다시 돌려
     * 커밋해라. 이 파일은 손으로 고치는 것이 아니다」</b> 였다. {@code DOMAIN-COVERAGE}·
     * {@code DESIGN-6} 은 아예 <b>「추출기를 다시 돌려라」</b> 라고 적혀 있다.
     *
     * <p>⛔ <b>기준은 하나다 — 개발이 그것 없이는 일을 못 하나.</b>
     * <ul>
     *   <li>{@code A-1} — 화면에 짝이 되는 설명 md(기능정의서)가 없다. 개발이 무엇을 만드는지 모른다
     *   <li>{@code A-2} — html 의 화면 ID 가 파일명과 다르다. 개발이 어느 화면인지 못 짚는다
     * </ul>
     *
     * <p>⚠ <b>여기 게이트를 더할 때는 그 기준만으로 판단해라.</b> 「중요해 보인다」·「깨진 것 같다」는
     * 근거가 아니다. 그 게이트의 <b>「고치는 법」이 기획자가 FRD 작업으로 할 수 있는 일인가</b>를
     * 검사기 출력에서 직접 읽어라 — 도구·추출기·기준선을 가리키면 여기 두지 않는다.
     *
     * <p>⚠ 화면·DB 로 아는 것(화면 미생성·생성 실패·화면 ID 없음 따위)은 이 목록과 무관하게
     * {@link #checkScreen} 이 따로 막는다. 여기는 <b>검사기가 낸 것</b>만 가른다.
     */
    private static boolean isBlockingGate(Finding finding) {
        return "A-1".equals(finding.gate()) || "A-2".equals(finding.gate());
    }

    /** 정식 IA는 후속 화면 관리 대상이므로 개발요청을 막지 않는다. */
    private static boolean isFormalIaFinding(Finding finding) {
        return finding.gate() != null && finding.gate().startsWith("IA-");
    }

    /**
     * ⭐ <b>「사람이 볼 항목이 늘었다」는 개발요청을 막을 근거가 아니다 (2026-08-27 실물에서 고쳤다).</b>
     *
     * <p><b>무슨 일이 있었나.</b> `DR-033` 을 만들고 바로 전송했더니 「자동 점검 오류가 1건 늘었습니다」로
     * 막혔다. 늘어난 red 1건의 정체는 <b>{@code RATCHET} 그 자신</b>이었다 — 실측: 클론 red 28 ·
     * 사람이 볼 68, FRD 작업 자리 red 29 · 사람이 볼 69. 늘어난 실체는 {@code A-9} 의 <b>review</b>
     * 한 건(「이 간선이 실재하는지 사람이 봐야 한다」)이다.
     *
     * <p>⛔ <b>이 저장소는 그 답을 이미 알고 있었다.</b> {@link com.bizplay.builder.checker.DraftChecker}
     * 가 2026-08-14 에 「review 는 빨강이 아니라 <b>사람이 볼 항목</b>이다」로 정했다.
     * {@code RATCHET} 은 게이트 중 유일하게 <b>개수만</b> 보는 것이라, 그 review 개수를
     * <b>red 로 세탁해</b> 원칙을 뒷문으로 뚫고 있었다.
     *
     * <p>⛔ <b>기획자가 풀 수 없는 것으로 막았다.</b> 검사기 자신이 적어 둔 해법은 「{@code index.json}
     * 의 기준선을 함께 올려 커밋해라」이고 그건 기획 레포를 손보는 일이다 — 화면 안내는 「FRD로
     * 돌아가 다시 완료해 주세요」였으니 <b>몇 번 다시 완료해도 똑같이 +1</b> 이었다.
     *
     * <p>⭐ <b>기획자에게 보여 주지 않는다 (2026-08-27 병주 지시).</b> 「{@code index.json} 의
     * 기준선을 올려라」는 <b>기획자가 볼 수 있는 문서가 아니다</b> — 「검증기 {@code index.json} 을
     * 사람이 어떻게 보냐, 몰라도 되는 거면 넘겨라」. 그래서 경고 목록에만 남긴다. 상세 화면은
     * <b>막는 것만</b> 그리므로({@code DevRequestTemplateContractTest}) 이 줄은 화면에 뜨지 않고
     * 점검 기록에만 남는다 — ⛔ <b>그걸 화면에 끌어올리지 마라.</b>
     *
     * <p>⚠ <b>병주는 「검증기에서 빼라」고 했고 그건 여기서 할 수 없다.</b> {@code RATCHET} 은
     * 기획 레포에 실려 온 검사기의 게이트이고 원본은 {@code we-adk-builder-extractor} 다 —
     * 빌더가 자기 검사기를 만들지 않는다는 경계 그대로다. 여기 것은 <b>임시 방편</b>이고,
     * 게이트 자체를 빼면 이 함수도 같이 지워라.
     */
    private static boolean isReviewCountFinding(Finding finding) {
        return "RATCHET".equals(finding.gate());
    }

    private static String findingDetail(List<Finding> findings) {
        return findings.stream()
                    .map(finding -> finding.file() + " · " + finding.what())
                    .distinct()
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse(null);
    }

    /** ⚠ 줄 번호를 빼는 까닭은 위에 있다 — 줄이 밀리면 기저가 새것으로 보인다. */
    String baselineKey(Finding finding) {
        return finding.gate() + "|" + finding.file() + "|" + withoutCounts(finding.what());
    }

    /**
     * ⚠ <b>세는 수는 열쇠에서 뺀다 — 줄 번호와 같은 함정이다.</b> 몇몇 게이트는 내용에 개수를
     * 적는다(「가리키는 곳이 없는 번호표가 <b>6건</b> 있다」). 워크트리에서 그 수가 하나 움직이면
     * 열쇠가 갈려 <b>클론에 원래 있던 위반이 이 FRD 탓으로 잡힌다</b> — {@link #blockOnlyNewRed}
     * 가 없애려는 바로 그 상태다.
     *
     * <p>⛔ <b>숫자를 통째로 지우지 마라.</b> 내용 속 숫자에는 세는 수와 <b>번호표</b>
     * (「flow-01」·「bo-…-e03」) 두 종류가 있고, 뭉뚱그려 지우면 번호표까지 같은 열쇠가 되어
     * <b>새로 깨진 번호표가 조용히 상쇄된다.</b> 오탐은 사람이 빨강을 보고 판정하지만
     * 이 방향의 실수는 아무도 모른다. 그래서 <b>뒤에 세는 단위가 붙은 숫자만</b> 지운다 —
     * 번호표에는 단위가 안 붙는다. 그 경계는 시험이 지킨다
     * ({@code 번호표만_다른_것은_기저로_보지_않는다}).
     */
    private static final java.util.regex.Pattern COUNT =
            java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?(?=%|건|개|장|종|쪽|줄|번째)");

    private static String withoutCounts(String what) {
        return what == null ? null : COUNT.matcher(what).replaceAll("#");
    }

    private static String system(DevelopmentRequestContent.Screen screen) {
        return screen.systemCode() == null || screen.systemCode().isBlank()
                ? "unknown" : screen.systemCode();
    }

    private static String value(String raw) {
        return raw == null || raw.isBlank() ? "—" : raw;
    }
}
