package com.bizplay.builder.devrequest;

import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.secret.Sealed;
import com.bizplay.builder.secret.SecretSealer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/**
 * 나가는 창구 — <b>개발요청 꾸러미를 GitLab 이슈로 연다.</b>
 *
 * <p>정본: 계획 9 Task 10. 계약은 병주가 준 참조 스크립트
 * ({@code builder/create-issue.mjs})가 실물로 확인해 준 것이다.
 *
 * <p>절차 둘이다.
 * <ol>
 *   <li>{@code POST {base}/api/v4/projects/{id}/uploads} — zip 을 multipart 로. 응답의
 *       {@code markdown} 이 본문에 붙일 링크다</li>
 *   <li>{@code POST .../issues} — 제목 · 본문 · 라벨 {@code intake}</li>
 * </ol>
 *
 * <p>⭐ <b>「되울림이 없어 2xx 를 못 믿는다」가 여기서 사라진다.</b> GitLab 은 만든 이슈를 그대로
 * 돌려주고 우리는 <b>본문에 전송 키를 심어 보냈다</b> — 돌아온 본문에 그 키가 있으면 받았다는 증거다.
 * 사내 프록시나 로그인 페이지는 그 값을 만들어 낼 수 없다.
 *
 * <p>⭐ <b>두 번 가는 것을 실제로 막는다.</b> 보내기 전에 전송 키로 이슈를 찾는다 — 있으면
 * 그 이슈가 답이다. 전송 설계가 「우리 벽 밖이라 0 은 아니다」로 남겨 둔 자리가 <b>이 창구에서는</b> 닫힌다.
 * <b>그래서 「전송중」에서 다시 누르는 것이 열렸다</b> — 설계의 ⛔ 은 「받았나」를 물어볼 길이 없는
 * API 를 전제로 한 것이고 GitLab 에는 그 길이 있다.
 *
 * <p>⚠ <b>0 은 아니다.</b> GitLab 이 고급 검색(Elasticsearch)을 쓰면 <b>방금 만든 이슈가 색인에
 * 늦게 올라</b> 못 찾을 수 있다 — 그때는 두 번 열린다. 기본 검색은 DB 조회라 즉시다.
 *
 * <p>⛔ <b>리다이렉트를 따라가지 않는다.</b> POST 에 302 가 오면 클라이언트가 GET 으로 바꿔 따라가
 * <b>몸을 통째로 버리고</b> 최종 2xx 를 받는다 — 아무것도 안 보내 놓고 「전송완료」가 된다.
 * ⛔ <b>몰래 재시도하지 않는다.</b> 자바 {@code HttpClient} 는 POST 를 자동 재시도하지 않는다
 * ({@code jdk.httpclient.enableAllMethodRetry} 가 기본 꺼짐) — <b>그 값을 켜지 마라.</b>
 *
 * <p>⚠ <b>설정이 없으면 옛 행동으로 돌아간다</b>({@link LoggingDevHandoffGateway}) — 꾸러미만 남기고
 * 「전송중」이다. 되돌릴 길을 스위치로 남겨 둔 자리다.
 */
@Component
public class DevIssueGateway implements DevHandoffGateway, DevProgressGateway {

    private static final Logger log = LoggerFactory.getLogger(DevIssueGateway.class);

    /** 개발이 인수 전 이슈를 찾는 이름. ⚠ 참조 스크립트의 {@code list-issues} 가 이 라벨을 본다. */
    private static final String INTAKE_LABEL = "intake";

    /** 철회 표시. ⭐ {@code intake} 를 빼는 것이 본체이고 이것은 <b>왜 빠졌나</b>를 남긴다. */
    private static final String WITHDRAWN_LABEL = "withdrawn";
    private static final String PROGRESS_LABEL = "progress";
    private static final String DONE_LABEL = "done";

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final DevIssueTargetMapper targets;
    private final SecretSealer sealer;
    private final DevRequestPackageZipper zipper;
    private final LoggingDevHandoffGateway fallback;
    private final ObjectMapper json;
    private final HttpClient http;

    public DevIssueGateway(DevIssueTargetMapper targets, SecretSealer sealer,
                           DevRequestPackageZipper zipper, LoggingDevHandoffGateway fallback,
                           ObjectMapper json) {
        this.targets = targets;
        this.sealer = sealer;
        this.zipper = zipper;
        this.fallback = fallback;
        this.json = json;
        // ⛔ NEVER 다. ALWAYS·NORMAL 로 바꾸면 「안 보내고 전송완료」가 난다.
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public Receipt send(DevRequestPackage built, String deliveryKey) {
        String projectId = projectIdOf(built);
        DevIssueTarget target = projectId == null ? null : targets.selectByProjectId(projectId);
        if (target == null) {
            return fallback.send(built, deliveryKey);
        }
        String token = sealer.unseal(new Sealed(target.tokenCipher(), target.tokenNonce()));
        String base = base(target);
        try {
            // ⭐ 먼저 찾는다 — 이미 열려 있으면 그 이슈가 답이다. 두 번 열지 않는다.
            Receipt existing = findExisting(base, token, deliveryKey);
            if (existing != null) {
                return existing;
            }
            String markdown;
            try {
                markdown = upload(base, token, built, deliveryKey);
            } catch (IOException | UncheckedIOException uploadFailed) {
                /*
                 * ⭐ [2026-08-24 병주 교정] 올리기 단계는 실패가 어떤 모양이든 「대기」다.
                 *   「모르면 전송중」의 전제는 「상대가 이미 받았으면 중복 작업이 생긴다」인데,
                 *   개발이 보는 것은 이슈다 — 올리기만 되고 이슈가 없으면 개발 손에는 아무것도
                 *   없다. 다시 눌러 남는 것은 중복 작업이 아니라 고아 zip 하나이고, 그건
                 *   감수하면 된다. ⛔ 갇히는 것은 감수의 문제가 아니다.
                 */
                return new Receipt(DeliveryOutcome.NOT_SENT, null, null,
                        "꾸러미를 GitLab 에 올리지 못했습니다: "
                                + GitCommand.mask(String.valueOf(uploadFailed.getMessage())));
            }
            if (markdown == null) {
                return new Receipt(DeliveryOutcome.NOT_SENT, null, null,
                        "꾸러미를 GitLab 에 올리지 못했습니다. 주소·저장소·토큰을 확인해 주세요.");
            }
            return createIssue(base, token, built, deliveryKey, markdown);
        } catch (IOException | UncheckedIOException network) {
            // ⚠ 여기까지 왔다는 것은 <b>이슈 등록</b>에서 답을 못 받은 것이다 — 이슈가 생겼을 수 있다.
            //    ⛔ 「대기」로 뭉치지 마라. 다시 누를 때 전송 키 검색이 이것을 갈라 준다.
            return Receipt.sending("이슈 등록 응답을 받지 못했습니다: "
                    + GitCommand.mask(String.valueOf(network.getMessage())));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Receipt.sending("전송이 중단되었습니다.");
        }
    }

    /**
     * GitLab 라벨을 개발 상태 세 값으로 읽는다. 라벨이 없거나 둘 이상이면 기존 상태를 움직이지 않는다.
     */
    @Override
    public Inspection inspect(String projectId, String issueUrl, String deliveryKey) {
        DevIssueTarget target = projectId == null ? null : targets.selectByProjectId(projectId);
        if (target == null) {
            return Inspection.failed("GitLab 이슈 자리 설정이 없습니다.");
        }
        String token = sealer.unseal(new Sealed(target.tokenCipher(), target.tokenNonce()));
        try {
            JsonNode issue = findProgressIssue(base(target), token, issueUrl, deliveryKey);
            if (issue == null) {
                return Inspection.failed("전송 키에 해당하는 GitLab 이슈를 찾지 못했습니다.");
            }
            int matched = 0;
            DevelopmentState state = null;
            if (hasLabel(issue, INTAKE_LABEL)) {
                matched++;
                state = DevelopmentState.INTAKE;
            }
            if (hasLabel(issue, PROGRESS_LABEL)) {
                matched++;
                state = DevelopmentState.PROGRESS;
            }
            if (hasLabel(issue, DONE_LABEL)) {
                matched++;
                state = DevelopmentState.DONE;
            }
            if (matched != 1) {
                return Inspection.failed("GitLab 이슈의 개발 상태 라벨을 하나로 확인할 수 없습니다.");
            }
            return Inspection.found(state);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Inspection.failed("GitLab 개발 상태 확인이 중단되었습니다.");
        } catch (IOException | RuntimeException failure) {
            return Inspection.failed("GitLab 개발 상태를 확인하지 못했습니다: "
                    + GitCommand.mask(String.valueOf(failure.getMessage())));
        }
    }

    /** 개발 완료 이슈는 닫힐 수 있으므로 열린 이슈만 보는 전송 중복 검사와 조회 조건을 분리한다. */
    private JsonNode findProgressIssue(String base, String token, String issueUrl, String deliveryKey)
            throws IOException, InterruptedException {
        String issueIid = issueIid(issueUrl);
        if (issueIid != null) {
            HttpResponse<String> direct = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/issues/" + issueIid)).timeout(TIMEOUT)
                            .header("PRIVATE-TOKEN", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (direct.statusCode() != 200) {
                throw new IOException("GitLab 이슈 조회 응답 " + direct.statusCode());
            }
            JsonNode issue = json.readTree(direct.body());
            return issue.path("description").asText("").contains(deliveryKey) ? issue : null;
        }

        String url = base + "/issues?scope=all&state=all&order_by=created_at&sort=desc&per_page=100&in=description&search="
                + java.net.URLEncoder.encode(deliveryKey, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                        .header("PRIVATE-TOKEN", token).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GitLab 이슈 조회 응답 " + response.statusCode());
        }
        for (JsonNode issue : json.readTree(response.body())) {
            if (issue.path("description").asText("").contains(deliveryKey)) {
                return issue;
            }
        }
        return null;
    }

    private String issueIid(String issueUrl) {
        if (issueUrl == null || issueUrl.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matched = java.util.regex.Pattern.compile("/issues/(\\d+)/?$")
                .matcher(issueUrl.strip());
        return matched.find() ? matched.group(1) : null;
    }

    /**
     * 같은 전송 키로 이미 열린 이슈가 있나.
     *
     * <p>⚠ <b>못 찾는 것과 없는 것을 뭉치지 않는다.</b> 검색이 실패하면 널을 내고 그냥 보낸다 —
     * 검색 실패로 전송을 막으면 그 개발요청서가 영영 못 나간다.
     */
    private Receipt findExisting(String base, String token, String deliveryKey)
            throws IOException, InterruptedException {
        JsonNode issue = findIssue(base, token, deliveryKey, false);
        if (issue == null) {
            return null;
        }
        log.info("같은 전송 키의 이슈가 이미 있다 — 두 번 열지 않는다 iid={} url={}",
                issue.path("iid").asText(), issue.path("web_url").asText());
        return new Receipt(DeliveryOutcome.SENT, 200,
                issue.path("web_url").asText(null), "이미 열려 있던 이슈를 그대로 씁니다.");
    }

    /**
     * 전송 키로 이슈 하나를 찾는다 — <b>보내기 전 확인과 철회가 같은 검색을 쓴다.</b>
     *
     * <p>⛔ 두 자리에 따로 적지 마라. 검색 조건이 갈리면 <b>보낼 때는 찾고 철회할 때는 못 찾는</b>
     * (또는 그 반대) 상태가 된다.
     *
     * <p>⚠ 검색이 실패하면 <b>널이다</b> — 부르는 쪽이 뜻을 정한다. 보낼 때는 「그냥 보낸다」이고
     * 철회할 때는 「철회하지 않는다」다. <b>같은 널이 반대 뜻인 자리라 부르는 쪽에 적어 뒀다.</b>
     */
    private JsonNode findIssue(String base, String token, String deliveryKey, boolean includeWithdrawn)
            throws IOException, InterruptedException {
        String url = base + "/issues?in=description&search="
                + java.net.URLEncoder.encode(deliveryKey, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                        .header("PRIVATE-TOKEN", token).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            log.warn("이슈를 찾지 못했다 status={}", response.statusCode());
            return null;
        }
        for (JsonNode issue : json.readTree(response.body())) {
            if (!issue.path("description").asText("").contains(deliveryKey)) {
                continue;
            }
            // ⭐ 닫힌 이슈는 못 본 것으로 한다 (2026-08-25). 전송 키는 결정적이라
            //    (DRK-<요청ID>) 철회한 뒤 다시 보내면 같은 키로 찾게 되는데, 그때 방금 닫은
            //    이슈를 재활용해 버리면 ⛔ 새 이슈가 영영 안 열린다 — 철회의 뜻이 사라진다.
            // ⚠ 「다시 보내면 같은 키」 계약은 그대로 산다 — 열려 있는 동안은 여전히 두 번 안 연다.
            if ("closed".equals(issue.path("state").asText(null))) {
                if (includeWithdrawn && hasLabel(issue, WITHDRAWN_LABEL)) {
                    return issue;
                }
                log.info("같은 키의 이슈가 있지만 닫혀 있다 — 새로 연다 iid={}", issue.path("iid").asText());
                continue;
            }
            return issue;
        }
        return null;
    }

    /** zip 을 올리고 본문에 붙일 markdown 링크를 받는다. */
    private String upload(String base, String token, DevRequestPackage built, String deliveryKey)
            throws IOException, InterruptedException {
        // 정상 전송 흐름은 Builder에 먼저 저장한 ZIP 원본을 그대로 올린다.
        // archive가 없는 경우는 창구 단위 테스트와 옛 직접 호출 계약을 위한 호환 경로다.
        byte[] zip = built.archive() == null
                ? zipper.zip(built)
                : Files.readAllBytes(built.archive());
        String boundary = "builder" + deliveryKey.replaceAll("[^A-Za-z0-9]", "");
        String fileName = deliveryKey + ".zip";
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(zip);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/uploads")).timeout(TIMEOUT)
                        .header("PRIVATE-TOKEN", token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            log.warn("꾸러미 올리기가 실패했다 status={} {}자", response.statusCode(), zip.length);
            return null;
        }
        String markdown = json.readTree(response.body()).path("markdown").asText(null);
        log.info("꾸러미를 올렸다 {}바이트 링크={}", zip.length, markdown);
        return markdown;
    }

    private Receipt createIssue(String base, String token, DevRequestPackage built,
                                String deliveryKey, String markdown)
            throws IOException, InterruptedException {
        var payload = json.createObjectNode();
        payload.put("title", title(built));
        payload.put("description", description(built, deliveryKey, markdown));
        payload.put("labels", INTAKE_LABEL);

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/issues")).timeout(TIMEOUT)
                        .header("PRIVATE-TOKEN", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(),
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status != 201 && status != 200) {
            DeliveryOutcome outcome = DeliveryOutcome.of(status, false);
            return new Receipt(outcome, status, null,
                    "이슈를 열지 못했습니다: " + GitCommand.mask(cut(response.body())));
        }
        JsonNode issue = json.readTree(response.body());
        // ⭐ 되울림 검사다 — 우리가 심은 전송 키가 만들어진 이슈 본문에 있어야 「받았다」다.
        boolean echoed = issue.path("description").asText("").contains(deliveryKey);
        return new Receipt(DeliveryOutcome.of(status, echoed), status,
                issue.path("web_url").asText(null),
                echoed ? null : "이슈는 열렸지만 본문에서 전송 키를 확인하지 못했습니다.");
    }

    /**
     * 철회 — <b>라벨이 {@code intake} 일 때만</b> (2026-08-25 병주 지시).
     *
     * <p>⭐ <b>이 판정에 개발과 합의할 것이 없다.</b> {@code intake} 는 우리가 붙인 「인수 전」
     * 표시이고, 개발이 집어가면 <b>그쪽 워크플로가 라벨을 바꾼다</b> — 라벨이 그대로인지 보는 것만으로
     * 「아직 아무도 손 안 댔다」가 판정된다.
     *
     * <p>⛔ <b>못 찾으면 철회하지 않는다.</b> 「없으니 무른 셈 치자」로 가면 실은 열려 있는데
     * 우리만 철회로 알고, 개발이 그걸 집어간다.
     */
    @Override
    public Receipt withdraw(String projectId, String deliveryKey, String reason) {
        DevIssueTarget target = projectId == null ? null : targets.selectByProjectId(projectId);
        if (target == null) {
            return new Receipt(DeliveryOutcome.SENT, null, null,
                    "이슈 자리 설정이 없어 개발요청을 취소할 수 없습니다.");
        }
        String token = sealer.unseal(new Sealed(target.tokenCipher(), target.tokenNonce()));
        String base = base(target);
        try {
            JsonNode issue = findIssue(base, token, deliveryKey, true);
            if (issue == null) {
                return new Receipt(DeliveryOutcome.SENT, null, null,
                        "그 전송 키로 열린 이슈를 찾지 못했습니다. 개발요청을 취소하지 않았습니다.");
            }
            String iid = issue.path("iid").asText(null);
            if ("closed".equals(issue.path("state").asText(null))
                    && hasLabel(issue, WITHDRAWN_LABEL)) {
                log.info("이미 철회된 이슈를 확인했다 — 빌더 상태를 복구한다 iid={} key={}", iid, deliveryKey);
                return new Receipt(DeliveryOutcome.WITHDRAWN, 200,
                        issue.path("web_url").asText(null), null);
            }
            if (!hasIntakeLabel(issue)) {
                return new Receipt(DeliveryOutcome.SENT, null, issue.path("web_url").asText(null),
                        "개발이 이미 가져갔습니다(라벨이 " + INTAKE_LABEL + " 이 아닙니다). 사람이 개발에 말할 일입니다.");
            }
            var payload = json.createObjectNode();
            // ⛔ 라벨을 통째로 비우지 마라 — 개발이 자기 라벨을 붙여 뒀을 수 있다.
            //    intake 하나만 빼고 withdrawn 을 더한다.
            payload.put("remove_labels", INTAKE_LABEL);
            payload.put("add_labels", WITHDRAWN_LABEL);
            payload.put("state_event", "close");
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/issues/" + iid)).timeout(TIMEOUT)
                            .header("PRIVATE-TOKEN", token)
                            .header("Content-Type", "application/json")
                            .method("PUT", HttpRequest.BodyPublishers.ofString(payload.toString(),
                                    StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return new Receipt(DeliveryOutcome.SENT, response.statusCode(),
                        issue.path("web_url").asText(null),
                        "이슈를 닫지 못했습니다: " + GitCommand.mask(cut(response.body())));
            }
            comment(base, token, iid, reason);
            log.info("개발요청을 취소했다 iid={} key={}", iid, deliveryKey);
            return new Receipt(DeliveryOutcome.WITHDRAWN, response.statusCode(),
                    issue.path("web_url").asText(null), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Receipt(DeliveryOutcome.SENT, null, null, "개발요청 취소 처리가 중단됐습니다.");
        } catch (IOException | RuntimeException failed) {
            log.warn("철회하지 못했다 key={}", deliveryKey, failed);
            return new Receipt(DeliveryOutcome.SENT, null, null,
                    "개발요청을 취소하지 못했습니다: " + GitCommand.mask(String.valueOf(failed.getMessage())));
        }
    }

    /** ⚠ 라벨은 배열로 온다 — 문자열 포함으로 재면 {@code intake-done} 같은 것에 걸린다. */
    private boolean hasIntakeLabel(JsonNode issue) {
        return hasLabel(issue, INTAKE_LABEL);
    }

    private boolean hasLabel(JsonNode issue, String expected) {
        for (JsonNode label : issue.path("labels")) {
            if (expected.equals(label.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 철회 까닭을 이슈에 남긴다. ⚠ 실패해도 철회 자체는 이미 섰다 — 로그만 남긴다. */
    private void comment(String base, String token, String iid, String reason) {
        try {
            var payload = json.createObjectNode();
            payload.put("body", "빌더에서 이 개발요청을 취소했습니다."
                    + (reason == null || reason.isBlank() ? "" : "\n\n까닭: " + reason));
            http.send(HttpRequest.newBuilder(URI.create(base + "/issues/" + iid + "/notes"))
                            .timeout(TIMEOUT).header("PRIVATE-TOKEN", token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(),
                                    StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            log.warn("철회 댓글을 남기지 못했다 iid={}", iid);
        }
    }

    private String title(DevRequestPackage built) {
        String label = readFirstHeading(built);
        return label == null ? "개발요청" : label;
    }

    /** ⚠ 제목은 {@code dev-request.md} 의 첫 줄을 쓴다 — 거기 이미 「DR-003 업무명」이 적혀 있다. */
    private String readFirstHeading(DevRequestPackage built) {
        try {
            var lines = Files.readAllLines(built.root().resolve("dev-request.md"),
                    StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("# ")) {
                    return line.substring(2).strip();
                }
            }
        } catch (IOException unreadable) {
            log.warn("dev-request.md 를 읽지 못해 제목을 기본값으로 둔다", unreadable);
        }
        return null;
    }

    /**
     * 이슈 본문 — {@code dev-request.md} 전문 + 꾸러미 링크 + 전송 키.
     *
     * <p>⛔ <b>전송 키를 빼지 마라.</b> 되울림 검사와 두 번 열기 막기가 둘 다 이 글자에 기댄다.
     */
    private String description(DevRequestPackage built, String deliveryKey, String markdown) {
        StringBuilder out = new StringBuilder();
        try {
            out.append(Files.readString(built.root().resolve("dev-request.md"), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            out.append("개발요청서 본문을 읽지 못했습니다. 첨부 꾸러미를 확인해 주세요.");
        }
        out.append("\n\n---\n\n## 꾸러미\n\n").append(markdown).append('\n');
        out.append("\n풀면 `screens/<시스템>/<화면ID>/` 아래 화면별 파일이 있고, 목업은 그 자리에서 ")
                .append("`../assets/…` 로 바로 열립니다.\n");
        out.append("\n<!-- 전송 키: ").append(deliveryKey)
                .append(" — 같은 키가 두 번 오면 재시도이지 새 요청이 아닙니다. -->\n");
        return out.toString();
    }

    /** ⚠ {@code /api/v4} 는 여기서 붙인다 — 설정에는 GitLab 주소만 넣는다. */
    private String base(DevIssueTarget target) {
        String root = target.baseUrl().strip().replaceAll("/+$", "");
        String project = java.net.URLEncoder.encode(target.projectPath().strip(),
                StandardCharsets.UTF_8);
        return root + "/api/v4/projects/" + project;
    }

    /** ⚠ 꾸러미 자리에서 프로젝트를 되읽는다 — {@code .../projects/<프로젝트>/dev-request-packages/<DR>}. */
    private String projectIdOf(DevRequestPackage built) {
        var root = built.root().toAbsolutePath().normalize();
        var parent = root.getParent();
        if (parent == null || parent.getParent() == null) {
            return null;
        }
        return parent.getParent().getFileName().toString();
    }

    private static String cut(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500);
    }
}
