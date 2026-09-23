package com.bizplay.builder.devrequest;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdWorkspace;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.PlanningRepositoryUpdater;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectRepositoryLocks;
import com.bizplay.builder.project.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「개발에 넘기기」에서 「개발 결과 받기」까지 <b>한 바퀴</b>를 진짜 git 으로 돈다.
 *
 * <p>⭐ <b>부품이 아니라 잇는 자리를 본다.</b> 2026-09-23 에 찾은 결함 둘 — 넘기기가 스스로
 * 기본 브랜치를 밀어 꾸러미가 알린 기준을 낡게 만든 것, 받기 쪽이 알려 주지 않은 경로를 기다린 것 —
 * 은 부품별 시험이 다 초록인 채로 났다. 틀린 것은 <b>부품 사이로 넘어가는 값</b>이었다.
 *
 * <p>⭐ <b>개발 흉내는 코드를 모른다.</b> 원격에 올라간 전달 목록({@code dev-requests}) 과
 * {@code expected-back.md} 만 읽고 움직인다 — 그래야 「문서대로 하면 받아진다」가 증명된다.
 * ⛔ 여기서 {@link ReturnBatch} 의 상수를 개발 쪽에 쓰지 마라. 쓰면 문서가 틀려도 초록이 된다.
 *
 * <p>⚠ DB 자리만 가짜다 — 매퍼와 조회는 흉내 내고, git·꾸러미·판정은 전부 진짜다.
 */
class DevRequestRoundTripTest {

    private static final String PROJECT = "0000001";
    private static final String REQUEST = "0000009";
    private static final String FRD = "0000002";
    private static final String SCREEN = "EXW-UWV-70-30-10-C";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir Path dataRoot;

    private final GitCommand git = new GitCommand();
    private ProjectPaths paths;
    private Path remote;

    private final DevelopmentRequestMapper requests = mock(DevelopmentRequestMapper.class);
    private final DevelopmentRequestService requestService = mock(DevelopmentRequestService.class);
    private final DevRequestTestResultMapper testResults = mock(DevRequestTestResultMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final DevelopmentRequest request = mock(DevelopmentRequest.class);
    private final DevRequestReceiptMapper receipts = mock(DevRequestReceiptMapper.class);

    private DevRequestDeliveryService deliveries;
    private DevRequestReceiveService receives;

    @BeforeEach
    void setUp() throws IOException {
        BuilderProperties properties = new BuilderProperties("admin", "pw", "A".repeat(42) + "g=",
                dataRoot, Duration.ofMinutes(10), 4, 50, Duration.ofMinutes(2));
        paths = new ProjectPaths(properties);

        // 기획 저장소 — 원격과 빌더의 클론
        remote = dataRoot.resolve("remote.git");
        run(dataRoot, "init", "--bare", "-q", "-b", "main", remote.toString());
        Path clone = paths.cloneDir(PROJECT);
        Files.createDirectories(clone);
        run(clone, "init", "-q", "-b", "main");
        run(clone, "config", "user.email", "builder@example.com");
        run(clone, "config", "user.name", "빌더");
        write(clone, "core/EXW/assets/webview_api/css/style.css", "body{color:#111}");
        write(clone, "core/EXW/pages/" + SCREEN + ".html", "<main>현재 회원가입</main>");
        write(clone, "core/EXW/pages/" + SCREEN + ".md", "# 회원가입\n현재 정의서\n");
        write(clone, "core/EXW/ia.md", "# 메뉴구조도\n");
        write(clone, "index.json", "{\"screens\": {}}\n");
        run(clone, "add", ".");
        run(clone, "commit", "-q", "-m", "as-is");
        run(clone, "push", "-q", remote.toUri().toString(), "main");
        String asIs = run(clone, "rev-parse", "HEAD").stdout().strip();

        // FRD 워크트리 — 기획이 그린 to-be
        Path frd = paths.frdWorktree(PROJECT, FRD);
        run(clone, "worktree", "add", "-q", "-b", "frd/" + FRD, frd.toString(), "main");
        write(frd, "core/EXW/pages/" + SCREEN + ".html", "<main>프리필된 회원가입</main>");
        write(frd, "core/EXW/pages/" + SCREEN + ".md", "# 회원가입\n프리필 정의서\n");
        run(frd, "add", ".");
        run(frd, "commit", "-q", "-m", "to-be");
        String toBe = run(frd, "rev-parse", "HEAD").stdout().strip();

        // DB 자리 — 개발요청서 한 건
        DevelopmentRequestContent content = new DevelopmentRequestContent(
                "에이블리 회원가입에서 회원정보를 미리 채운다.", null,
                List.of(new DevelopmentRequestContent.Requirement(1, "프리필한다", "DEVELOP", "개발", null)),
                List.of(new DevelopmentRequestContent.Screen(FRD, SCREEN, "에이블리 회원가입", "EXW",
                        null, List.of("회원정보를 미리 채운다"), List.of(), List.of())),
                List.of(),
                List.of(new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION",
                        "프리필된 값으로 가입이 끝난다")))
                .withTestScenarios(List.of(
                        new DevelopmentRequestContent.TestScenario("UNIT", 1, "TC-001",
                                "생년월일이 없으면 빈 값으로 둔다", "없음", "생년월일 없음(mock)",
                                "웹뷰를 연다", "빈 칸이다")));
        given(request.id()).willReturn(REQUEST);
        given(request.projectId()).willReturn(PROJECT);
        given(request.label()).willReturn("DR-009");
        given(request.frdId()).willReturn(FRD);
        given(request.systemCode()).willReturn("EXW");
        given(request.title()).willReturn("에이블리 회원가입 프리필");
        given(request.workspaceBaseSha()).willReturn(asIs);
        given(request.workspaceHeadSha()).willReturn(toBe);
        // ⚠ 받기는 보낸 뒤에만 된다 — 넘기기는 이 값을 보지 않으므로 처음부터 SENT 로 둔다.
        given(request.deliveryState()).willReturn(DevelopmentRequest.DeliveryState.SENT);
        given(requests.selectById(REQUEST)).willReturn(request);
        given(requestService.precheck(PROJECT, REQUEST))
                .willReturn(new DevRequestPrecheck.Result(List.of(), List.of(), false, List.of()));
        given(requestService.read(PROJECT, REQUEST)).willReturn(new DevelopmentRequestService.View(
                request, content, "김기획", Map.of(), Map.of(), Map.of(), false, null));
        given(projects.cloneMaterials(PROJECT))
                .willReturn(new ProjectService.CloneMaterials("main", remote.toUri().toString()));
        IdSequence ids = mock(IdSequence.class);
        given(ids.next(any())).willReturn("0000001");

        DevRequestDeliveryWorkspace workspace = new DevRequestDeliveryWorkspace(paths, git, TIMEOUT);
        deliveries = new DevRequestDeliveryService(requests, requestService, mock(FrdMapper.class),
                mock(FrdScreenMapper.class), mock(FrdScreenHistoryMapper.class), mock(FrdWorkspace.class),
                workspace, new DevRequestPackage(git, TIMEOUT), new DevRequestDocument(),
                new ExpectedBackDocument(), projects, paths, ids);
        receives = new DevRequestReceiveService(requests, requestService, workspace, testResults, projects,
                new PlanningRepositoryUpdater(projects, paths, git, new ProjectRepositoryLocks()), receipts);
    }

    /**
     * ⭐ <b>문서대로 한 개발은 받아진다 — 그 사이 기본 브랜치가 움직였어도.</b>
     * 넘기기는 기본 브랜치를 밀지 않고, 개발은 며칠 뒤에 돌려주며, 그 사이 기획이 다른 파일을 고친다.
     */
    @Test
    void 문서대로_한_개발은_그_사이_기본_브랜치가_움직였어도_받아진다() throws IOException {
        String mainBeforeSend = remoteMain();
        deliveries.deliver(PROJECT, REQUEST, "account-1");

        // ⭐ 넘기기가 기본 브랜치를 안 민다 — 꾸러미가 알린 기준이 지금 머리와 같다.
        assertThat(remoteMain()).isEqualTo(mainBeforeSend);

        Path dev = developerFollowsTheDocuments("<main>개발이 만든 프리필</main>");
        plannerCommits("core/EXW/ia.md", "# 메뉴구조도\n그 사이 기획이 고쳤다\n");

        DevRequestReceiveService.Result result = receives.receive(PROJECT, REQUEST, "account-1");

        assertThat(result.rejections()).isEmpty();
        assertThat(result.accepted()).isTrue();
        String head = remoteMain();
        assertThat(show(head, "core/EXW/pages/" + SCREEN + ".html")).contains("개발이 만든 프리필");
        assertThat(show(head, "core/EXW/ia.md")).contains("그 사이 기획이 고쳤다");
        // ⭐ 받은 뒤 클론도 원격과 같다 — 그린존 문서가 여기서 재료를 읽는다.
        assertThat(run(paths.cloneDir(PROJECT), "rev-parse", "main").stdout().strip()).isEqualTo(head);
        assertThat(Files.readString(paths.cloneDir(PROJECT).resolve("core/EXW/pages/" + SCREEN + ".html")))
                .contains("개발이 만든 프리필");
        assertThat(run(remote, "log", "-1", "--format=%an", head).stdout()).contains("수신");
        // ⛔ 회신서와 테스트 결과는 기본 브랜치에 안 들어간다.
        assertThat(run(remote, "ls-tree", "-r", "--name-only", head).stdout())
                .doesNotContain("return.json").doesNotContain("unit-tests.md");

        ArgumentCaptor<TestResultReader.Result> rows = ArgumentCaptor.forClass(TestResultReader.Result.class);
        verify(testResults).upsert(eq(REQUEST), rows.capture());
        assertThat(rows.getValue().tcId()).isEqualTo("TC-001");
        assertThat(rows.getValue().verdict()).isEqualTo(TestResultReader.PASS);
        assertThat(dev).exists();

        // ⭐ 같은 회신을 다시 받으면 「이미 받았다」 — 빌더 자신의 받기 커밋과 겹쳐 거절되지 않는다.
        DevRequestReceiveService.Result again = receives.receive(PROJECT, REQUEST, "account-1");
        assertThat(again.rejections()).isEmpty();
        assertThat(again.alreadyReceived()).isTrue();
        assertThat(remoteMain()).isEqualTo(head);
    }

    /**
     * ⛔ <b>그 사이 같은 화면 파일이 바뀌었으면 거절하고 아무것도 안 놓는다</b> — 개발이
     * 화면 md 를 {@code unchanged} 로 적었어도. html 만 받으면 옛 기준 html 과 새 md 가 섞인다.
     */
    @Test
    void 그_사이_같은_화면의_md_가_바뀌었으면_거절하고_아무것도_안_놓는다() throws IOException {
        deliveries.deliver(PROJECT, REQUEST, "account-1");
        developerFollowsTheDocuments("<main>개발이 만든 프리필</main>");
        plannerCommits("core/EXW/pages/" + SCREEN + ".md", "# 회원가입\n그 사이 다른 DR 이 고친 정의서\n");
        String before = remoteMain();

        DevRequestReceiveService.Result result = receives.receive(PROJECT, REQUEST, "account-1");

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejections()).anyMatch(reason -> reason.contains(SCREEN + ".md"));
        assertThat(remoteMain()).isEqualTo(before);
        verify(testResults, never()).upsert(anyString(), any());
    }

    /**
     * ⭐ <b>SRT 로 만든 요청서도 넘기고 받는다</b> — 화면 0장 · 시스템 없음 · FRD 워크트리 없음.
     * 이름은 {@code dr/SRT/DR-012} 이고(2026-09-23 사용자 확정), 돌려받을 화면이 없으니 기본 브랜치에는
     * 아무것도 안 놓이고 테스트 결과만 담긴다.
     */
    @Test
    void SRT_요청서도_넘기고_테스트_결과를_받는다() throws IOException {
        DevelopmentRequestContent srt = new DevelopmentRequestContent("정산 배치 주기를 바꾼다.", null,
                List.of(new DevelopmentRequestContent.Requirement(1, "배치 주기를 바꾼다", "DEVELOP", "개발", null)),
                List.of(), List.of(),
                List.of(new DevelopmentRequestContent.Note("ACCEPTANCE_CRITERION", "새 주기로 배치가 돈다")))
                .withTestScenarios(List.of(new DevelopmentRequestContent.TestScenario("INTEGRATION", 1,
                        "TC-001", "새 주기로 배치가 돈다", "없음", "운영 설정", "하루를 기다린다", "두 번 돈다")));
        given(request.label()).willReturn("DR-012");
        given(request.systemCode()).willReturn(null);
        given(request.workspaceBaseSha()).willReturn(null);
        given(request.workspaceHeadSha()).willReturn(null);
        given(requestService.read(PROJECT, REQUEST)).willReturn(new DevelopmentRequestService.View(
                request, srt, "김기획", Map.of(), Map.of(), Map.of(), false, null));
        String mainBefore = remoteMain();

        deliveries.deliver(PROJECT, REQUEST, "account-1");

        assertThat(run(remote, "ls-tree", "-r", "--name-only", "refs/heads/dr/SRT/DR-012").stdout())
                .contains("DR-012/dev-request.md", "DR-012/expected-back.md", "DR-012/manifest.json");
        assertThat(remoteMain()).isEqualTo(mainBefore);

        // 개발 — 문서의 머리 표와 견본만 보고 돌려보낸다
        Path dev = dataRoot.resolve("dev-srt");
        run(dataRoot, "clone", "-q", "-b", "main", remote.toUri().toString(), dev.toString());
        run(dev, "config", "user.email", "dev@example.com");
        run(dev, "config", "user.name", "개발");
        run(dev, "fetch", "-q", "origin", "dr/SRT/DR-012");
        String contract = run(dev, "show", "FETCH_HEAD:DR-012/expected-back.md").stdout();
        Map<String, String> places = places(contract);
        assertThat(places).doesNotContainKey(SCREEN + " 화면(html)");
        run(dev, "checkout", "-q", "-b", returnBranch(contract), mainBefore);
        write(dev, places.get("회신서"), template(contract));
        write(dev, places.get("통합테스트 결과"), filledTable(contract, "## 4. 통합테스트"));
        run(dev, "add", ".");
        run(dev, "commit", "-q", "-m", "DR-012 결과");
        run(dev, "push", "-q", "origin", returnBranch(contract));

        DevRequestReceiveService.Result result = receives.receive(PROJECT, REQUEST, "account-1");

        assertThat(returnBranch(contract)).isEqualTo("feedback/SRT/DR-012");
        assertThat(result.rejections()).isEmpty();
        assertThat(result.commit()).as("돌려받을 화면이 없으니 기본 브랜치에 놓을 것도 없다").isNull();
        assertThat(result.testRows()).isEqualTo(1);
        assertThat(remoteMain()).isEqualTo(mainBefore);
    }

    /**
     * 개발 흉내 — <b>원격에 올라간 것만 읽고</b> 문서대로 돌려보낸다.
     *
     * <ol>
     *   <li>전달 목록({@code dev-requests} 의 {@code deliveries.json})에서 브랜치와 기준을 찾는다</li>
     *   <li>{@code expected-back.md} 「돌려보내는 법」 표에서 파일 자리를 읽는다</li>
     *   <li>회신서 견본의 칸을 고르고, 3절 표의 빈 칸을 채운다</li>
     * </ol>
     */
    private Path developerFollowsTheDocuments(String builtHtml) throws IOException {
        Path dev = dataRoot.resolve("dev-" + System.nanoTime());
        run(dataRoot, "clone", "-q", "-b", "main", remote.toUri().toString(), dev.toString());
        run(dev, "config", "user.email", "dev@example.com");
        run(dev, "config", "user.name", "개발");

        run(dev, "fetch", "-q", "origin", "dev-requests");
        JsonNode delivery = null;
        for (JsonNode row : json(run(dev, "show", "FETCH_HEAD:deliveries.json").stdout()).path("deliveries")) {
            if ("DR-009".equals(row.path("dr").asText())) {
                delivery = row;
            }
        }
        assertThat(delivery).as("전달 목록에 DR-009 가 있어야 개발이 찾는다").isNotNull();
        String branch = delivery.path("branch").asText();
        String base = delivery.path("base").asText();
        // ⭐ 브랜치 이름은 IA 의 시스템으로 가른다 — dr/EXW/DR-009.
        assertThat(branch).isEqualTo("dr/EXW/DR-009");

        run(dev, "fetch", "-q", "origin", branch);
        String contract = run(dev, "show", "FETCH_HEAD:DR-009/expected-back.md").stdout();
        Map<String, String> places = places(contract);

        String returnBranch = returnBranch(contract);
        run(dev, "checkout", "-q", "-b", returnBranch, base);
        write(dev, places.get(SCREEN + " 화면(html)"), builtHtml);

        ObjectNode reply = (ObjectNode) json(template(contract));
        for (JsonNode screen : reply.path("screens")) {
            ((ObjectNode) screen).put("pages", "changed");
            if (screen.has("screen-md")) {
                ((ObjectNode) screen).put("screen-md", "unchanged");
            }
            ((ObjectNode) screen).put("index", "unchanged");
        }
        write(dev, places.get("회신서"), reply.toPrettyString());
        write(dev, places.get("단위테스트 결과"), filledTable(contract, "## 3. 단위테스트"));

        run(dev, "add", ".");
        run(dev, "commit", "-q", "-m", "DR-009 개발 결과");
        run(dev, "push", "-q", "origin", returnBranch);
        return dev;
    }

    /** 머리 표의 「돌려보낼 브랜치」. */
    private static String returnBranch(String contract) {
        String line = contract.lines().filter(row -> row.startsWith("| 돌려보낼 브랜치 |")).findFirst().orElseThrow();
        return line.split("\\|")[2].strip().replace("`", "");
    }

    /** 「돌려보내는 법」 표 — 첫 칸의 이름 → 둘째 칸의 자리. */
    private static Map<String, String> places(String contract) {
        String section = contract.substring(contract.indexOf("## 돌려보내는 법"), contract.indexOf("```json"));
        Map<String, String> places = new LinkedHashMap<>();
        for (String line : section.lines().toList()) {
            if (!line.startsWith("| ") || line.startsWith("| 무엇") || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.split("\\|");
            String name = cells[1].strip().replace("`", "");
            String place = cells[2].strip().replace("`", "");
            places.put(name, place);
        }
        return places;
    }

    /** 회신서 견본 — {@code ```json} 칸. */
    private static String template(String contract) {
        int start = contract.indexOf("```json") + "```json".length();
        return contract.substring(start, contract.indexOf("```", start)).strip();
    }

    /** 절의 TC 표를 그대로 옮기고 빈 칸 셋(실제 결과 · 판정 · 근거)만 채운다. */
    private static String filledTable(String contract, String heading) {
        String section = contract.substring(contract.indexOf(heading));
        StringBuilder md = new StringBuilder();
        boolean inTable = false;
        for (String line : section.lines().toList()) {
            // ⚠ TC 표 머리부터 뜬다 — 통합테스트 절은 그 앞에 완료 조건 대응표가 있다.
            if (line.startsWith("| TC ")) {
                inTable = true;
                md.append(line).append('\n');
            } else if (inTable && line.startsWith("|---")) {
                md.append(line).append('\n');
            } else if (inTable && line.startsWith("| TC-")) {
                md.append(line, 0, line.lastIndexOf("|  |  |  |"))
                        .append("| 빈 칸이었다 | 통과 | 로그 12행 |").append('\n');
            } else if (inTable && !line.startsWith("|")) {
                break;
            }
        }
        return md.toString();
    }

    /** 개발이 작업하는 며칠 사이 기획이 기본 브랜치에 커밋 하나를 더한다. */
    private void plannerCommits(String relative, String content) throws IOException {
        Path work = dataRoot.resolve("planner-" + System.nanoTime());
        run(dataRoot, "clone", "-q", "-b", "main", remote.toUri().toString(), work.toString());
        run(work, "config", "user.email", "p@example.com");
        run(work, "config", "user.name", "기획");
        write(work, relative, content);
        run(work, "add", ".");
        run(work, "commit", "-q", "-m", "그 사이 기획 변경");
        run(work, "push", "-q", "origin", "HEAD:refs/heads/main");
    }

    private String remoteMain() {
        return run(remote, "rev-parse", "refs/heads/main").stdout().strip();
    }

    private String show(String commit, String path) {
        return run(remote, "show", commit + ":" + path).stdout();
    }

    private static JsonNode json(String text) throws IOException {
        return new ObjectMapper().readTree(text);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private GitResult run(Path directory, String... args) {
        GitResult result = git.run(directory, TIMEOUT, args);
        assertThat(result.succeeded()).as(String.join(" ", args) + " — " + result.stderr()).isTrue();
        return result;
    }
}
