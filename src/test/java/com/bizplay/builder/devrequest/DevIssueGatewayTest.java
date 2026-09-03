package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.secret.SecretSealer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 나가는 창구 — <b>GitLab 이슈로 실제로 여나</b>. 계획 9 Task 10.
 *
 * <p>가짜 GitLab 을 띄워 참조 스크립트({@code builder/create-issue.mjs})와 <b>같은 두 호출</b>이
 * 오는지 잰다 — {@code POST /uploads} 그리고 {@code POST /issues}.
 *
 * <p>⭐ 여기서 증명하는 것 셋 — ① <b>되울림 검사</b>(본문에 심은 전송 키가 돌아와야 「전송완료」)
 * ② <b>두 번 열지 않는 것</b>(같은 키의 이슈가 있으면 그것을 쓴다) ③ <b>설정이 없으면 옛 행동</b>.
 */
class DevIssueGatewayTest extends AbstractDbTest {

    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;
    @Autowired DevIssueTargetService devIssueTargets;
    @Autowired DevIssueGateway gateway;

    private HttpServer gitlab;
    private final List<String> calls = new ArrayList<>();
    private final AtomicReference<String> issueBody = new AtomicReference<>();
    private final AtomicReference<byte[]> uploadBody = new AtomicReference<>();
    /** 검색이 이미 열린 이슈를 내놓나. */
    private final AtomicReference<String> searchResult = new AtomicReference<>("[]");
    private int issueStatus = 201;
    /** 철회가 보낸 몸 — 라벨을 어떻게 바꿨나. */
    private final AtomicReference<String> withdrawBody = new AtomicReference<>();
    private int withdrawStatus = 200;

    @BeforeEach
    void startFakeGitlab() throws IOException {
        gitlab = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gitlab.createContext("/api/v4/projects/43/issues", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/notes")) {
                calls.add("POST notes");
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 201, "{}");
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                calls.add("PUT issues");
                withdrawBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                respond(exchange, withdrawStatus, """
                        {"iid":7,"state":"closed",
                         "web_url":"http://gitlab.test/dev/x/-/issues/7"}""");
                return;
            }
            calls.add(exchange.getRequestMethod() + " issues");
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, searchResult.get());
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            issueBody.set(body);
            // ⭐ GitLab 은 만든 이슈를 그대로 돌려준다 — 본문에 우리 전송 키가 실려 돌아온다.
            String description = body.contains("DRK-") ? "\\u0000" : "";
            respond(exchange, issueStatus, """
                    {"iid":7,"web_url":"http://gitlab.test/dev/x/-/issues/7",
                     "description":%s}""".formatted(quoteDescription(body)));
        });
        gitlab.createContext("/api/v4/projects/43/uploads", exchange -> {
            calls.add("POST uploads");
            uploadBody.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 201, "{\"markdown\":\"[꾸러미](/uploads/abc/pkg.zip)\"}");
        });
        gitlab.start();
    }

    @AfterEach
    void stopFakeGitlab() {
        if (gitlab != null) {
            gitlab.stop(0);
        }
    }

    @Test
    void 꾸러미를_올리고_이슈를_연다() {
        Project project = configured();
        var built = fakePackage(project, "DRK-0000001");

        var receipt = gateway.send(built, "DRK-0000001");

        // 참조 스크립트와 같은 순서다 — 찾고 · 올리고 · 연다.
        assertThat(calls).containsExactly("GET issues", "POST uploads", "POST issues");
        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENT);
        assertThat(receipt.httpStatus()).isEqualTo(201);
        assertThat(receipt.responseId()).isEqualTo("http://gitlab.test/dev/x/-/issues/7");
    }

    @Test
    void Builder에_저장한_ZIP_원본을_그대로_올린다() throws Exception {
        Project project = configured();
        DevRequestPackage built = fakePackage(project, "DRK-0000015");
        byte[] stored = "stored-zip-marker".getBytes(StandardCharsets.UTF_8);
        Path archive = built.root().resolve("DR-001.zip");
        Files.write(archive, stored);

        gateway.send(built.withArchive(archive), "DRK-0000015");

        assertThat(uploadBody.get()).containsSubsequence(stored);
    }

    @Test
    void 이슈_본문에_개발요청서와_꾸러미_링크와_전송키가_실린다() {
        Project project = configured();
        gateway.send(fakePackage(project, "DRK-0000002"), "DRK-0000002");

        String body = issueBody.get();
        assertThat(body).contains("DR-001");                       // dev-request.md 본문
        assertThat(body).contains("/uploads/abc/pkg.zip");         // 올린 꾸러미 링크
        assertThat(body).contains("DRK-0000002");                  // 전송 키
        assertThat(body).contains("intake");                       // 개발이 찾는 라벨
    }

    @Test
    void 같은_전송키의_이슈가_이미_있으면_두_번_열지_않는다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":3,"web_url":"http://gitlab.test/dev/x/-/issues/3",
                  "description":"앞서 열린 이슈 DRK-0000003 입니다"}]""");

        var receipt = gateway.send(fakePackage(project, "DRK-0000003"), "DRK-0000003");

        // ⭐ 전송 설계가 「우리 벽 밖이라 0 은 아니다」로 남긴 자리가 이 창구에서는 닫힌다.
        assertThat(calls).containsExactly("GET issues");
        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENT);
        assertThat(receipt.responseId()).isEqualTo("http://gitlab.test/dev/x/-/issues/3");
    }

    @Test
    void 설정이_없으면_보내지_않고_전송중에_둔다() {
        Project project = readyProject("이슈-설정없음");
        var built = fakePackage(project, "DRK-0000004");

        var receipt = gateway.send(built, "DRK-0000004");

        // ⚠ 되돌릴 길이다 — 설정을 비우면 옛 행동으로 돌아간다.
        assertThat(calls).isEmpty();
        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENDING);
        assertThat(receipt.failure()).contains("개발 창구 주소");
    }

    @Test
    void 이슈를_못_열면_상태코드를_그대로_옮긴다() {
        Project project = configured();
        issueStatus = 403;

        var receipt = gateway.send(fakePackage(project, "DRK-0000005"), "DRK-0000005");

        // 403 은 「요청 자체를 안 받아들였다」 — 대기다.
        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.NOT_SENT);
        assertThat(receipt.httpStatus()).isEqualTo(403);
    }

    @Test
    void 주소가_죽어_있으면_전송중이다() {
        Project project = readyProject("이슈-죽은주소");
        devIssueTargets.save(project.getId(), "http://127.0.0.1:1", "43", "glpat-x", null);

        var receipt = gateway.send(fakePackage(project, "DRK-0000006"), "DRK-0000006");

        // ⛔ 「대기」로 뭉치지 마라 — 답을 못 받은 것은 상대가 이미 받았을 수 있다.
        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENDING);
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    // ── 철회 (2026-08-25 병주 지시) ───────────────────────────────────────────────

    @Test
    void 라벨이_intake_면_철회한다_라벨을_바꾸고_이슈를_닫는다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"opened","web_url":"http://gitlab.test/dev/x/-/issues/7",
                  "description":"DRK-0000010 입니다","labels":["intake"]}]""");

        var receipt = gateway.withdraw(project.getId(), "DRK-0000010", "요구가 바뀌었습니다");

        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.WITHDRAWN);
        assertThat(calls).containsExactly("GET issues", "PUT issues", "POST notes");
        // ⛔ 라벨을 통째로 비우지 않는다 — 개발이 붙인 라벨이 있을 수 있다.
        assertThat(withdrawBody.get()).contains("\"remove_labels\":\"intake\"");
        assertThat(withdrawBody.get()).contains("\"add_labels\":\"withdrawn\"");
        assertThat(withdrawBody.get()).contains("\"state_event\":\"close\"");
    }

    @Test
    void 개발이_가져갔으면_철회하지_않는다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"opened","web_url":"http://gitlab.test/dev/x/-/issues/7",
                  "description":"DRK-0000011 입니다","labels":["doing"]}]""");

        var receipt = gateway.withdraw(project.getId(), "DRK-0000011", null);

        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENT);
        assertThat(receipt.failure()).contains("개발이 이미 가져갔습니다");
        // ⛔ 찾기만 하고 아무것도 안 바꿨다.
        assertThat(calls).containsExactly("GET issues");
    }

    /** ⛔ 「없으니 무른 셈 치자」가 가장 나쁜 결말이다 — 실은 열려 있는데 개발이 그걸 집어간다. */
    @Test
    void 이슈를_못_찾으면_철회하지_않는다() {
        Project project = configured();
        searchResult.set("[]");

        var receipt = gateway.withdraw(project.getId(), "DRK-0000012", null);

        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENT);
        assertThat(receipt.failure()).contains("찾지 못했습니다");
    }

    /**
     * ⭐ 전송 키가 결정적({@code DRK-<요청ID>})이라, 닫힌 이슈를 재활용하면
     * <b>철회 뒤 다시 보낼 때 새 이슈가 영영 안 열린다.</b>
     */
    @Test
    void 철회로_닫힌_이슈는_다시_보낼_때_재활용하지_않는다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"closed","web_url":"http://gitlab.test/dev/x/-/issues/7",
                  "description":"철회한 DRK-0000013 입니다","labels":["withdrawn"]}]""");

        var receipt = gateway.send(fakePackage(project, "DRK-0000013"), "DRK-0000013");

        assertThat(calls).containsExactly("GET issues", "POST uploads", "POST issues");
        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.SENT);
    }

    @Test
    void 이미_철회된_이슈는_철회_재시도에서_빌더_복구_근거가_된다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"closed","web_url":"http://gitlab.test/dev/x/-/issues/7",
                  "description":"철회한 DRK-0000014 입니다","labels":["withdrawn"]}]""");

        var receipt = gateway.withdraw(project.getId(), "DRK-0000014", null);

        assertThat(receipt.outcome()).isEqualTo(DeliveryOutcome.WITHDRAWN);
        assertThat(calls).containsExactly("GET issues");
    }

    @Test
    void progress_라벨을_개발_진행_중으로_읽는다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"opened","description":"DRK-0000015 입니다",
                  "labels":["progress"]}]""");

        var inspected = gateway.inspect(project.getId(), null, "DRK-0000015");

        assertThat(inspected.state()).isEqualTo(DevelopmentState.PROGRESS);
        assertThat(inspected.failure()).isNull();
    }

    @Test
    void 저장한_이슈_URL의_iid로_현재_전송_이슈를_직접_읽는다() {
        Project project = configured();
        searchResult.set("""
                {"iid":7,"state":"opened","description":"DRK-0000018 입니다",
                 "labels":["progress"]}""");

        var inspected = gateway.inspect(project.getId(),
                "http://gitlab.test/dev/x/-/issues/7", "DRK-0000018");

        assertThat(inspected.state()).isEqualTo(DevelopmentState.PROGRESS);
        assertThat(calls).containsExactly("GET issues");
    }

    @Test
    void 닫힌_이슈라도_done_라벨이면_개발_완료로_읽는다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"closed","description":"DRK-0000016 입니다",
                  "labels":["done"]}]""");

        var inspected = gateway.inspect(project.getId(), null, "DRK-0000016");

        assertThat(inspected.state()).isEqualTo(DevelopmentState.DONE);
    }

    @Test
    void 개발_상태_라벨이_둘이면_기존_상태를_바꾸지_않도록_실패를_돌려준다() {
        Project project = configured();
        searchResult.set("""
                [{"iid":7,"state":"opened","description":"DRK-0000017 입니다",
                  "labels":["progress","done"]}]""");

        var inspected = gateway.inspect(project.getId(), null, "DRK-0000017");

        assertThat(inspected.succeeded()).isFalse();
        assertThat(inspected.failure()).contains("하나로 확인할 수 없습니다");
    }

    private Project configured() {
        Project project = readyProject("이슈-" + ids.next(IdSequence.Kind.PROJECT));
        devIssueTargets.save(project.getId(),
                "http://127.0.0.1:" + gitlab.getAddress().getPort(), "43", "glpat-testtoken", null);
        return project;
    }

    /** 꾸러미 한 채를 자리에 만든다 — 게이트웨이는 자리에서 프로젝트를 되읽는다. */
    private DevRequestPackage fakePackage(Project project, String key) {
        Path root = paths.devRequestPackageDir(project.getId(), ids.next(IdSequence.Kind.DEV_REQUEST));
        try {
            Files.createDirectories(root.resolve("screens/webview/wv-appr-write"));
            Files.writeString(root.resolve("dev-request.md"),
                    "# DR-001 전자결재 상신 임시저장 지원\n\n## 1. 요청 내용 (원문)\n\n임시저장이 필요하다\n",
                    StandardCharsets.UTF_8);
            Files.writeString(root.resolve("screens/webview/wv-appr-write/to-be.html"),
                    "<html><body>바뀐 화면</body></html>", StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return new DevRequestPackage(root, List.of(
                new DevRequestPackage.Entry("dev-request.md", "본문", 10, "0".repeat(64)),
                new DevRequestPackage.Entry("screens/webview/wv-appr-write/to-be.html",
                        "바뀐 화면", 10, "1".repeat(64))));
    }

    private static String quoteDescription(String requestBody) {
        int at = requestBody.indexOf("\"description\":");
        if (at < 0) {
            return "\"\"";
        }
        // 보낸 본문을 그대로 되울려 준다 — GitLab 이 하는 일과 같다.
        int start = requestBody.indexOf('"', at + 14);
        int end = start + 1;
        while (end < requestBody.length()) {
            if (requestBody.charAt(end) == '"' && requestBody.charAt(end - 1) != '\\') {
                break;
            }
            end++;
        }
        return requestBody.substring(start, Math.min(end + 1, requestBody.length()));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }
}
