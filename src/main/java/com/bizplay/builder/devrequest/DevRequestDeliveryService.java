package com.bizplay.builder.devrequest;

import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenHistory;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdWorkspace;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「개발에 넘기기」 — 설계의 <b>칸 3·4</b> 를 잇는 자리.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-handoff-to-dev-design.md} 「줄기 — 칸 넷」.
 *
 * <pre>
 * 전송중 남기기 → 설계서를 워크트리에 커밋 → 꾸러미 쓰기 → dr/DR-nnn 로 push → 전송완료
 * </pre>
 *
 * <p>⭐ <b>먼저 남기고 보낸다.</b> 보내기 전에 「전송중」 한 줄을 확정해 둔다 — 그래야 보내는
 * 도중에 죽어도 「갔는지 안 갔는지 아무도 모른다」가 안 된다.
 * ⭐ <b>시도마다 한 줄이다.</b> 상태 한 줄만 남기면 두 번째 시도가 첫 번째를 지운다.
 * ⭐ <b>다시 보내면 같은 전송 키다.</b> 번호는 프로젝트마다 1번부터라 개발 쪽에서 보면 서로 다른
 * 사업의 {@code DR-001} 이 여럿이 된다 — 키가 그것을 가른다. ⛔ 다시 보낼 때 새 번호를 뽑지 마라.
 *
 * <p>⛔ <b>사람이 누른다.</b> 앞의 자동들은 전부 안쪽 일이라 잘못돼도 안에서 고치면 되는데,
 * 이것은 바깥으로 나가 되돌리기 어렵다. 그리고 「FRD 가 닫혔다」와 「이걸로 넘겨도 된다」는
 * 다른 판단이라 <b>기계가 아니라 사람만 안다.</b>
 *
 * <p>⛔ <b>FRD 워크트리를 지우지 않는다</b>(2026-09-22 사용자 확정). 설계 칸 4 는 「워크트리를
 * 지운다」이지만, 지우면 재전송과 역류 대조의 재료가 사라진다 — 꾸러미 설계도 ⛔ 로 막았다.
 */
@Service
public class DevRequestDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DevRequestDeliveryService.class);

    private final DevelopmentRequestMapper requests;
    private final DevelopmentRequestService requestService;
    private final FrdMapper frds;
    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final FrdWorkspace workspaces;
    private final DevRequestDeliveryWorkspace deliveries;
    private final DevRequestPackage packages;
    private final DevRequestDocument documents;
    private final ProjectService projects;
    private final ProjectPaths paths;
    private final IdSequence ids;

    public DevRequestDeliveryService(DevelopmentRequestMapper requests,
                                     DevelopmentRequestService requestService,
                                     FrdMapper frds, FrdScreenMapper screens,
                                     FrdScreenHistoryMapper histories, FrdWorkspace workspaces,
                                     DevRequestDeliveryWorkspace deliveries,
                                     DevRequestPackage packages, DevRequestDocument documents,
                                     ProjectService projects, ProjectPaths paths, IdSequence ids) {
        this.requests = requests;
        this.requestService = requestService;
        this.frds = frds;
        this.screens = screens;
        this.histories = histories;
        this.workspaces = workspaces;
        this.deliveries = deliveries;
        this.packages = packages;
        this.documents = documents;
        this.projects = projects;
        this.paths = paths;
        this.ids = ids;
    }

    /**
     * 꾸러미를 만들어 전달 전용 브랜치로 올린다.
     *
     * @return 올린 자리 — 브랜치와 커밋
     */
    public synchronized DevRequestDeliveryWorkspace.Published deliver(
            String projectId, String requestId, String accountId) {
        DevelopmentRequest request = requests.selectById(requestId);
        if (request == null || !request.projectId().equals(projectId)) {
            throw new IllegalArgumentException("개발요청서를 찾을 수 없습니다.");
        }
        // ⛔ 막는 항목이 있으면 보내지 않는다 — 「보내기 전 확인」이 판정한다.
        DevRequestPrecheck.Result gate = requestService.precheck(projectId, requestId);
        if (!gate.sendable()) {
            throw new IllegalStateException(
                    "보내기 전 확인에서 막는 항목이 %d건 있습니다.".formatted(gate.blocking().size()));
        }

        String attemptId = ids.next(IdSequence.Kind.DEV_REQUEST_DELIVERY);
        String deliveryKey = deliveryKeyOf(requestId);
        requests.insertDeliveryAttempt(attemptId, requestId, deliveryKey, accountId);
        requests.updateDeliveryState(requestId, DevelopmentRequest.DeliveryState.SENDING.name());

        try {
            materializeTobeDocuments(projectId, request);
            DevelopmentRequestService.View view = requestService.read(projectId, requestId);
            String[] fingerprint = new String[1];
            DevRequestDeliveryWorkspace.Published published = deliveries.publish(
                    projectId, requestId, request.label(), projects.cloneMaterials(projectId).defaultBranch(),
                    projects.cloneMaterials(projectId).authenticatedUrl(),
                    worktree -> fingerprint[0] = writePackage(projectId, request, view, worktree));

            requests.finishDeliveryAttempt(attemptId, DevelopmentRequest.DeliveryState.SENT.name(),
                    published.commit(), fingerprint[0], null);
            requests.updateDeliveryState(requestId, DevelopmentRequest.DeliveryState.SENT.name());
            log.info("개발에 넘겼다 projectId={} 개발요청서={} 브랜치={} 커밋={}",
                    projectId, requestId, published.branch(), published.commit());
            return published;
        } catch (RuntimeException failed) {
            /*
             * ⛔ 실패해도 커밋을 되돌리지 않는다 — 설계서 커밋은 안쪽 일이라 남아도 해가 없고,
             *   되돌리면 다시 보낼 때 재료가 사라진다. 상태만 「대기」로 돌린다.
             */
            requests.finishDeliveryAttempt(attemptId, DevelopmentRequest.DeliveryState.NOT_SENT.name(),
                    null, null, failed.getMessage());
            requests.updateDeliveryState(requestId, DevelopmentRequest.DeliveryState.NOT_SENT.name());
            throw failed;
        }
    }

    /**
     * ⭐ <b>다시 보내면 같은 키다.</b> 앞 시도의 키를 그대로 쓰고, 없을 때만 새로 짓는다.
     */
    private String deliveryKeyOf(String requestId) {
        String existing = requests.selectDeliveryKey(requestId);
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    /**
     * AI 가 만든 변경 예정 기능정의서를 <b>FRD 워크트리에 써서 커밋</b>한다.
     *
     * <p>⭐ <b>이것이 「새로 생성된 설계서」가 개발에 나가는 유일한 길이다.</b> 2026-09-22 실측:
     * 이 자리를 부르는 코드가 없어서 md 가 DB 에만 있었고, 올라간 브랜치의 {@code pages/*.md} 가
     * as-is 와 해시까지 같았다.
     *
     * <p>⚠ 화면이 없거나 md 가 아직 없으면 건너뛴다 — 화면 0장인 FRD 도 나갈 수 있어야 한다.
     */
    private void materializeTobeDocuments(String projectId, DevelopmentRequest request) {
        List<FrdWorkspace.TobeDocument> tobe = new ArrayList<>();
        for (FrdScreen screen : screens.selectByFrdId(request.frdId())) {
            FrdScreenHistory latest = histories.selectLatestByScreenId(screen.id());
            if (latest == null || latest.md() == null || latest.md().isBlank()) {
                continue;
            }
            tobe.add(new FrdWorkspace.TobeDocument(screen.systemCode(), screen.screenId(),
                    deliveryScreenId(screen), latest.md()));
        }
        if (tobe.isEmpty()) {
            return;
        }
        FrdWorkspace.Commit commit = workspaces.materializeTobeDocuments(
                projectId, request.frdId(), request.label(), tobe);
        // ⭐ 전달 기준판이 이 커밋으로 옮겨간다 — 꾸러미의 to-be 는 여기서 뽑힌다.
        requests.updateWorkspaceHeadSha(request.id(), commit.after());
    }

    /** 신규 화면은 개발용 이름으로 옮겨 앉는다. 없으면 제 화면ID 그대로다. */
    private static String deliveryScreenId(FrdScreen screen) {
        return screen.screenId();
    }

    /**
     * 전달 워크트리 <b>뿌리</b>에 꾸러미를 쓴다 — {@code <뿌리>/DR-nnn/}.
     *
     * @return 보낸 몸의 지문 — {@code manifest.json} 의 sha256
     */
    private String writePackage(String projectId, DevelopmentRequest request,
                                DevelopmentRequestService.View view, Path deliveryWorktree) {
        Path dir = deliveryWorktree.resolve(request.label());
        Path frdWorktree = paths.frdWorktree(projectId, request.frdId());
        packages.write(frdWorktree, packageRequest(request, view), dir);
        try {
            Path manifest = dir.resolve("manifest.json");
            Files.writeString(dir.resolve("dev-request.md"),
                    documents.render(meta(request, view), view.content(), manifest),
                    StandardCharsets.UTF_8);
            return sha256(Files.readAllBytes(manifest));
        } catch (IOException failed) {
            throw new UncheckedIOException("계약서 본문을 쓰지 못했습니다.", failed);
        }
    }

    private static DevRequestPackage.Request packageRequest(DevelopmentRequest request,
                                                            DevelopmentRequestService.View view) {
        List<DevRequestPackage.Screen> screens = view.content().screens().stream()
                .map(screen -> new DevRequestPackage.Screen(screen.systemCode(),
                        screen.deliveryScreenId(), screen.displayName(), screen.changes()))
                .toList();
        return new DevRequestPackage.Request(request.label(),
                request.workspaceBaseSha(), request.workspaceHeadSha(), screens);
    }

    private DevRequestDocument.Meta meta(DevelopmentRequest request,
                                         DevelopmentRequestService.View view) {
        Frd frd = frds.selectById(request.frdId());
        return new DevRequestDocument.Meta(request.label(), request.title(), request.systemCode(),
                request.facetList(), frd == null ? null : "FRD-%03d".formatted(frd.number()),
                view.ownerName(), request.createdAt() == null ? null
                        : request.createdAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                request.developmentCompletedOn(), request.deploymentOn(), request.plannerComment(),
                request.attachmentName(), request.attachmentSize());
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", impossible);
        }
    }
}
