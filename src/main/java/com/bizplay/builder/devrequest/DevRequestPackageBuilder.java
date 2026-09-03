package com.bizplay.builder.devrequest;

import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenFiles;
import com.bizplay.builder.frd.FrdScreenHistory;
import com.bizplay.builder.frd.FrdScreenHistoryMapper;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.project.ProjectPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 개발에 나갈 꾸러미를 굽는다 — <b>디벨롭과의 계약서 한 채</b>다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>재료가 셋에서 온다.
 * <ul>
 *   <li><b>빌더 DB</b> — 스냅샷 · to-be 목업 html · to-be 기능정의서 md</li>
 *   <li><b>클론</b> — as-is 목업·기능정의서, 그리고 자산</li>
 *   <li><b>클론의 git</b> — 이 꾸러미가 출발한 커밋</li>
 * </ul>
 *
 * <p>⛔ <b>기존 화면 목업 html 의 내용을 한 글자도 고치지 않는다.</b> 자산 상대경로({@code ../assets/…})가
 * 저절로 맞도록 <b>배치</b>로 푼다 — {@code screens/<시스템>/<화면ID>/} 아래에 두면
 * {@code screens/<시스템>/assets/} 를 가리킨다. 파일 안을 손대면 원본과 대조가 안 되고,
 * 기획 레포로 밀 때 우리 손자국이 섞인다. 신규 화면 전달 사본만 내부 {@code tmp} 식별자를
 * 개발용 화면 ID로 바꾼다.
 *
 * <p>⛔ <b>기관 스킨 폴더 이름을 코드가 쥐지 않는다.</b> 그래서 스킨을 <b>고르지 않고</b>
 * {@code core/<시스템>/assets/} 를 통째로 붓는다 — 어느 기관 갈래를 열어도 그림이 뜨고,
 * {@code iks}·{@code tnj} 꼴 글자가 코드에 들어오지 않는다.
 *
 * <p>⛔ <b>{@code README} 를 넣지 않는다.</b> 까닭은 {@link DevRequestPackage} 에 적어 뒀다.
 */
@Component
public class DevRequestPackageBuilder {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);
    private final ProjectPaths paths;
    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final DevRequestDocumentWriter documents;
    private final ScreenChangeNoteWriter notes;
    private final GitCommand git;
    private final ObjectMapper json;
    private final FrdScreenFiles screenFiles;

    public DevRequestPackageBuilder(ProjectPaths paths, FrdScreenMapper screens,
                                    FrdScreenHistoryMapper histories,
                                    DevRequestDocumentWriter documents,
                                    ScreenChangeNoteWriter notes, GitCommand git,
                                    ObjectMapper json, FrdScreenFiles screenFiles) {
        this.paths = paths;
        this.screens = screens;
        this.histories = histories;
        this.documents = documents;
        this.notes = notes;
        this.git = git;
        this.json = json;
        this.screenFiles = screenFiles;
    }

    /**
     * 꾸러미를 굽는다. ⚠ <b>다시 구우면 통째로 갈아 낀다</b> — 판이 남으면 어느 것을 보냈는지 모른다.
     *
     * @param deliveryKey 이 시도를 가리키는 세상에 하나뿐인 값. 다시 보내면 같은 키다
     * @param sentAt      전송 시각. ⚠ 부르는 쪽이 정해 넘긴다 — 여기서 시계를 읽지 않는다
     */
    public DevRequestPackage build(DevelopmentRequestService.View view, String deliveryKey,
                                  String sentAt, String previousLabel) {
        DevelopmentRequest request = view.request();
        verifyWorkspaceRevision(request);
        Path root = paths.devRequestPackageDir(request.projectId(), request.id());
        try {
            FileSystemUtils.deleteRecursively(root);
            Files.createDirectories(root);

            List<DevRequestPackage.Entry> entries = new ArrayList<>();
            Set<String> assetSystems = new LinkedHashSet<>();
            Map<String, String> deliveryAliases = new LinkedHashMap<>();
            view.content().screens().stream()
                    .filter(screen -> !screen.screenId().equals(screen.deliveryScreenId()))
                    .forEach(screen -> deliveryAliases.put(screen.screenId(), screen.deliveryScreenId()));

            for (var screen : view.content().screens()) {
                writeScreen(root, request, view, screen, deliveryAliases, entries);
                assetSystems.add(systemOf(screen));
            }
            for (String system : assetSystems) {
                copyAssets(root, request, system, entries);
            }
            writeAttachment(root, request, entries);

            // ⭐ 반환 대상은 한 번 만들어 expected-back.md와 manifest.json 둘에 준다.
            DevRequestExpectedBack expectedBack = DevRequestExpectedBack.of(view.content());
            String planningRepoCommit = planningRepoCommit(request);
            entries.add(write(root, "expected-back.md",
                    documents.writeExpectedBack(view, deliveryKey, planningRepoCommit, expectedBack),
                    "개발 완료 후 빌더에 돌려줄 대상"));

            // ⛔ 8절은 여기서 만들어진 목록에서 생성된다 — 손으로 적으면 폴더와 갈린다.
            String document = documents.write(view, deliveryKey, sentAt, previousLabel, entries,
                    expectedBack);
            entries.add(write(root, "dev-request.md", document, "개발요청서 본문 — 요구사항·범위·완료 조건"));

            List<String> assetRoots = assetSystems.stream()
                    .map(system -> "core/" + system + "/assets").toList();
            // ⭐ 계약 파일은 낱개로, 자산은 요약 하나로 담는다 — 까닭은 DevRequestManifest.of 에 있다.
            DevRequestManifest manifest = DevRequestManifest.of(
                    request.label(), request.title(), deliveryKey, sentAt, previousLabel,
                    planningRepoCommit, assetRoots, List.copyOf(entries),
                    expectedBack);
            String manifestJson = json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
            entries.add(write(root, "manifest.json", manifestJson,
                    "꾸러미 지문 — 규격 판·전송 키·계약 파일 해시·자산 요약"));

            return new DevRequestPackage(root, entries);
        } catch (IOException failed) {
            throw new UncheckedIOException("개발요청 꾸러미를 만들지 못했습니다.", failed);
        }
    }

    private void writeScreen(Path root, DevelopmentRequest request,
                             DevelopmentRequestService.View view,
                             DevelopmentRequestContent.Screen screen,
                             Map<String, String> deliveryAliases,
                             List<DevRequestPackage.Entry> entries) throws IOException {
        String system = systemOf(screen);
        String prefix = "screens/" + system + "/" + screen.deliveryScreenId() + "/";
        Files.createDirectories(root.resolve(prefix));

        if (usesWorkspace(request)) {
            Path workspace = paths.frdWorktree(request.projectId(), request.frdId());
            Path sourceHtml = screenFiles.targetHtml(
                    request.projectId(), request.frdId(), system, screen.screenId());
            Path sourceMd = screenFiles.document(
                    request.projectId(), request.frdId(), system, screen.screenId());
            Path html = screenFiles.existingHtml(
                    request.projectId(), request.frdId(), system, screen.deliveryScreenId());
            Path md = screenFiles.document(
                    request.projectId(), request.frdId(), system, screen.deliveryScreenId());
            if (html == null) {
                throw new IllegalStateException("개발요청서 화면 파일을 FRD 작업 자리에서 찾지 못했습니다: "
                        + screen.displayName());
            }
            String htmlPath = relative(workspace, sourceHtml);
            String mdPath = relative(workspace, sourceMd);
            copyTextFromRevision(root, workspace, request.workspaceBaseSha(), htmlPath,
                    prefix + "as-is.html", "현재 운영 화면", entries);
            copyTextFromRevision(root, workspace, request.workspaceBaseSha(), mdPath,
                    prefix + "as-is.md", "현재 기능정의서", entries);
            copyForDelivery(root, html,
                    prefix + "to-be.html", "이렇게 바꿔 주세요 — 수정한 화면",
                    screen.isNewScreen() ? deliveryAliases : Map.of(), entries);
            copyForDelivery(root, md,
                    prefix + "to-be.md", "이렇게 바꿔 주세요 — 수정한 기능정의서",
                    screen.isNewScreen() ? deliveryAliases : Map.of(), entries);
        } else {
            // 이 기능 도입 전 개발요청서와 화면 없는 간단 변경을 위한 호환 경로다.
            Path clonePage = paths.cloneDir(request.projectId())
                    .resolve("core").resolve(system).resolve("pages");
            copyIfPresent(root, clonePage.resolve(screen.screenId() + ".html"),
                    prefix + "as-is.html", "현재 운영 화면", entries);
            copyIfPresent(root, clonePage.resolve(screen.screenId() + ".md"),
                    prefix + "as-is.md", "현재 기능정의서", entries);
            FrdScreen row = screens.selectById(screen.frdScreenId());
            if (row != null && row.html() != null && !row.html().isBlank()) {
                entries.add(write(root, prefix + "to-be.html",
                        deliveryText(row.html(), screen.isNewScreen() ? deliveryAliases : Map.of()),
                        "이렇게 바꿔 주세요 — 수정한 화면"));
            }
            FrdScreenHistory latest = histories.selectLatestByScreenId(screen.frdScreenId());
            if (latest != null && latest.md() != null && !latest.md().isBlank()) {
                entries.add(write(root, prefix + "to-be.md",
                        deliveryText(latest.md(), screen.isNewScreen() ? deliveryAliases : Map.of()),
                        "이렇게 바꿔 주세요 — 수정한 기능정의서"));
            }
        }
        entries.add(write(root, prefix + "changes.md",
                notes.write(screen, view.standardScreenId(screen.screenId()),
                        screen.deliveryFileName(), screen.entryPoint()),
                "변경 내용과 화면에 표시한 지시"));
    }

    /**
     * 자산을 시스템마다 한 벌 붓는다.
     *
     * <p>⛔ <b>{@code screens/assets/} 한 벌로 잡지 마라.</b> FRD 하나가 두 시스템 화면을
     * 건드릴 수 있고 그때 자산이 부딪힌다.
     */
    private void copyAssets(Path root, DevelopmentRequest request, String system,
                             List<DevRequestPackage.Entry> entries) throws IOException {
        Path sourceRoot = usesWorkspace(request)
                ? paths.frdWorktree(request.projectId(), request.frdId())
                : paths.cloneDir(request.projectId());
        Path source = sourceRoot.resolve("core").resolve(system).resolve("assets");
        if (!Files.isDirectory(source)) {
            return;
        }
        String prefix = "screens/" + system + "/assets/";
        try (Stream<Path> walk = Files.walk(source)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String relative = slash(source.relativize(file));
                Path target = root.resolve(prefix + relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                entries.add(entry(root, target, "목업이 부르는 자산"));
            }
        }
    }

    private void writeAttachment(Path root, DevelopmentRequest request,
                                 List<DevRequestPackage.Entry> entries) throws IOException {
        if (request.attachmentPath() == null || request.attachmentName() == null) {
            return;
        }
        Path source = Path.of(request.attachmentPath());
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path target = root.resolve("attachments").resolve(request.attachmentName());
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        entries.add(entry(root, target, "기획자가 붙인 파일"));
    }

    /**
     * 이 꾸러미가 출발한 기획 저장소 커밋.
     *
     * <p>⚠ <b>못 읽으면 널이다 — 던지지 않는다.</b> 커밋을 모른다고 계약서가 안 만들어지면
     * 안 된다. 모른다는 것은 {@code manifest.json} 에 널로 적혀 그대로 드러난다.
     */
    private String planningRepoCommit(DevelopmentRequest request) {
        if (usesWorkspace(request)) {
            return request.workspaceHeadSha();
        }
        Path clone = paths.cloneDir(request.projectId());
        if (!Files.isDirectory(clone)) {
            return null;
        }
        try {
            GitResult result = git.run(clone, GIT_TIMEOUT, "rev-parse", "HEAD");
            if (result.exitCode() != 0) {
                return null;
            }
            String sha = result.stdout().strip();
            return sha.isBlank() ? null : sha;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private boolean usesWorkspace(DevelopmentRequest request) {
        return request.workspaceBaseSha() != null && !request.workspaceBaseSha().isBlank()
                && request.workspaceHeadSha() != null && !request.workspaceHeadSha().isBlank();
    }

    /** 저장된 전달 기준판과 현재 작업트리가 정확히 같고 깨끗한지 확인한다. */
    private void verifyWorkspaceRevision(DevelopmentRequest request) {
        if (!usesWorkspace(request)) {
            return;
        }
        Path workspace = paths.frdWorktree(request.projectId(), request.frdId());
        if (!Files.isDirectory(workspace)) {
            throw new IllegalStateException("개발요청서의 FRD 작업 자리가 없습니다.");
        }
        GitResult head = git.run(workspace, GIT_TIMEOUT, "rev-parse", "HEAD");
        if (!head.succeeded() || !request.workspaceHeadSha().equals(head.stdout().strip())) {
            throw new IllegalStateException("개발요청서에 고정한 작업트리 기준판과 현재 작업 자리가 다릅니다.");
        }
        GitResult status = git.run(workspace, GIT_TIMEOUT, "status", "--porcelain");
        if (!status.succeeded() || !status.stdout().isBlank()) {
            throw new IllegalStateException("FRD 작업 자리에 커밋되지 않은 변경이 남아 있습니다.");
        }
    }

    /** as-is 텍스트 파일은 현재 파일시스템이 아니라 작업 시작 커밋에서 직접 꺼낸다. */
    private void copyTextFromRevision(Path root, Path workspace, String revision, String sourcePath,
                                      String relative, String description,
                                      List<DevRequestPackage.Entry> entries) throws IOException {
        GitResult shown = git.run(workspace, GIT_TIMEOUT, "show", revision + ":" + sourcePath);
        if (!shown.succeeded()) {
            return;
        }
        entries.add(write(root, relative, shown.stdout(), description));
    }

    private String relative(Path workspace, Path file) {
        return workspace.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private void copyIfPresent(Path root, Path source, String relative, String description,
                               List<DevRequestPackage.Entry> entries) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        entries.add(entry(root, target, description));
    }

    private void copyForDelivery(Path root, Path source, String relative, String description,
                                 Map<String, String> aliases,
                                 List<DevRequestPackage.Entry> entries) throws IOException {
        if (!Files.isRegularFile(source)) return;
        if (aliases.isEmpty()) {
            copyIfPresent(root, source, relative, description, entries);
            return;
        }
        entries.add(write(root, relative,
                deliveryText(Files.readString(source, StandardCharsets.UTF_8), aliases), description));
    }

    /** tmp 식별자는 FRD 내부 키이므로 신규 화면의 전달 사본에서만 개발용 화면 ID로 바꾼다. */
    private static String deliveryText(String content, Map<String, String> aliases) {
        String delivered = content;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            delivered = delivered.replace(alias.getKey(), alias.getValue());
        }
        return delivered;
    }

    private DevRequestPackage.Entry write(Path root, String relative, String content,
                                          String description) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return entry(root, target, description);
    }

    private DevRequestPackage.Entry entry(Path root, Path file, String description)
            throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new DevRequestPackage.Entry(slash(root.relativize(file)), description,
                bytes.length, sha256(bytes));
    }

    /** ⚠ 구분자를 {@code /} 로 고정한다 — 윈도에서 구운 것을 리눅스에서 읽는다. */
    private static String slash(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte one : digest) {
                out.append(Character.forDigit((one >> 4) & 0xF, 16));
                out.append(Character.forDigit(one & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", impossible);
        }
    }

    private static String systemOf(DevelopmentRequestContent.Screen screen) {
        return screen.systemCode() == null || screen.systemCode().isBlank()
                ? "unknown" : screen.systemCode();
    }
}
