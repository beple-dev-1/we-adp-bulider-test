package com.bizplay.builder.frd;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.git.GitResult;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.ia.IaPublisher;
import com.bizplay.builder.project.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** FRD가 실제 기획 산출물을 고칠 전용 Git 워크트리를 준비한다. */
@Component
public class FrdWorkspace {

    private static final Logger log = LoggerFactory.getLogger(FrdWorkspace.class);

    private final ProjectPaths paths;
    private final GitCommand git;
    private final BuilderProperties properties;

    public FrdWorkspace(ProjectPaths paths, GitCommand git, BuilderProperties properties) {
        this.paths = paths;
        this.git = git;
        this.properties = properties;
    }

    /**
     * 준비 결과. 상태 전환 실패 때 이번 요청이 만든 것만 되돌리기 위해 생성 여부를 나눠 갖는다.
     */
    public record Prepared(Path clonePath, Path path, String branch,
                           boolean workspaceCreated, boolean branchCreated) {
    }

    /**
     * FRD 전용 브랜치와 워크트리를 만들거나 기존 짝을 확인해 다시 쓴다.
     *
     * <p>서버 한 대에서 같은 버튼을 동시에 눌러도 Git의 검사와 생성을 한 줄로 세운다. DB 상태의
     * 최종 경합은 {@link FrdService#startDrafting(String)}의 조건부 갱신이 다시 막는다.
     */
    public synchronized Prepared ensure(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호의 꼴이 아닙니다.");
        }
        Path clone = paths.cloneDir(projectId).toAbsolutePath().normalize();
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        String branch = branch(frdId);

        if (!Files.isDirectory(clone.resolve(".git"))) {
            throw new IllegalStateException(
                    "기획 저장소가 준비되지 않아 FRD 작업을 시작하지 못했습니다. "
                            + "프로젝트 저장소 상태를 확인한 뒤 다시 시도해 주세요.");
        }

        if (Files.exists(workspace)) {
            verifyExisting(clone, workspace, branch);
            return new Prepared(clone, workspace, branch, false, false);
        }

        try {
            Files.createDirectories(workspace.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("FRD 작업 폴더를 준비하지 못했습니다. 다시 시도해 주세요.", e);
        }

        boolean branchExists = succeeded(clone, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        GitResult added = branchExists
                ? git.run(clone, properties.checkTimeout(), "worktree", "add", workspace.toString(), branch)
                : git.run(clone, properties.checkTimeout(),
                        "worktree", "add", "-b", branch, workspace.toString(), "HEAD");
        if (!added.succeeded()) {
            throw new IllegalStateException("FRD 작업 공간을 만들지 못했습니다: " + detail(added));
        }

        Prepared prepared = new Prepared(clone, workspace, branch, true, !branchExists);
        try {
            verifyExisting(clone, workspace, branch);
            return prepared;
        } catch (RuntimeException invalid) {
            rollback(prepared);
            throw invalid;
        }
    }

    /**
     * 기존 FRD 워크트리와 브랜치를 버리고 기획 저장소의 현재 HEAD에서 새 작업 공간을 만든다.
     *
     * <p>같은 경로에 다른 작업 폴더가 있으면 {@link #verifyExisting(Path, Path, String)}가 먼저
     * 거절한다. 확인하지 않은 폴더를 재귀 삭제하지 않는다.
     */
    public synchronized Prepared reset(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호의 꼴이 아닙니다.");
        }
        Path clone = paths.cloneDir(projectId).toAbsolutePath().normalize();
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        String branch = branch(frdId);

        if (!Files.isDirectory(clone.resolve(".git"))) {
            throw new IllegalStateException(
                    "기획 저장소가 준비되지 않아 FRD 작업을 초기화하지 못했습니다. "
                            + "프로젝트 저장소 상태를 확인한 뒤 다시 시도해 주세요.");
        }

        if (Files.exists(workspace)) {
            verifyExisting(clone, workspace, branch);
            GitResult removed = git.run(clone, properties.checkTimeout(),
                    "worktree", "remove", "--force", workspace.toString());
            if (!removed.succeeded()) {
                throw new IllegalStateException("FRD 작업 공간을 삭제하지 못했습니다: " + detail(removed));
            }
        }

        if (succeeded(clone, "show-ref", "--verify", "--quiet", "refs/heads/" + branch)) {
            GitResult deleted = git.run(clone, properties.checkTimeout(), "branch", "-D", branch);
            if (!deleted.succeeded()) {
                throw new IllegalStateException("FRD 작업 브랜치를 초기화하지 못했습니다: " + detail(deleted));
            }
        }

        return ensure(projectId, frdId);
    }

    /** 현재 FRD 작업공간에 아직 커밋하지 않은 파일 변경이 있는지 확인한다. */
    public synchronized boolean hasChanges(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return false;
        }
        GitResult status = git.run(workspace, properties.checkTimeout(),
                "status", "--porcelain", "--untracked-files=all");
        if (!status.succeeded()) {
            throw new IllegalStateException("FRD 작업공간의 변경 여부를 확인하지 못했습니다. " + detail(status));
        }
        return !status.stdout().isBlank();
    }

    /**
     * 작업 대상에서 제외한 화면의 HTML·MD 변경을 워크트리에서 없앤다.
     *
     * <p>기존 화면 파일은 지우면 운영 화면 삭제 변경으로 잡히므로 {@code HEAD}의 원본으로 되돌린다.
     * 신규 화면처럼 {@code HEAD}에 없던 파일만 실제로 삭제한다. 워크트리를 만들기 전인 개발 범위
     * 확인 단계에서는 정리할 파일이 없으므로 아무 일도 하지 않는다.
     */
    public synchronized void discardScreenFiles(String projectId, String frdId,
                                                String systemCode, String screenId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return;
        }
        verifyExisting(paths.cloneDir(projectId).toAbsolutePath().normalize(), workspace, branch(frdId));

        if (systemCode == null || systemCode.isBlank() || screenId == null || screenId.isBlank()) {
            throw new IllegalStateException("제외할 화면의 파일 위치를 확인하지 못했습니다.");
        }
        Path core = workspace.resolve("core").normalize();
        Path system = core.resolve(systemCode.strip()).normalize();
        Path pages = system.resolve("pages").normalize();
        if (!pages.startsWith(core)) {
            throw new IllegalArgumentException("제외할 화면의 시스템 경로가 올바르지 않습니다.");
        }
        discardScreenFile(workspace, pages, screenId.strip(), ".html");
        discardScreenFile(workspace, pages, screenId.strip(), ".md");
        if (TemporaryScreenId.isTemporary(screenId.strip())) {
            removeIncomingReferences(pages, screenId.strip());
        }
        try (var children = Files.list(system)) {
            for (Path variant : children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("variants-"))
                    .toList()) {
                discardScreenFile(workspace, variant, screenId.strip(), ".html");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("기관별 화면 파일을 확인하지 못했습니다.", failure);
        }
    }

    /** 신규 화면을 제외할 때 다른 화면 문서에 남은 IA·이동 참조만 지운다. */
    private void removeIncomingReferences(Path pages, String screenId) {
        if (!Files.isDirectory(pages)) return;
        try (var documents = Files.list(pages)) {
            for (Path document : documents.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.getFileName().toString().equals(screenId + ".md"))
                    .toList()) {
                String current = Files.readString(document, StandardCharsets.UTF_8);
                String changed = removeScreenReferences(current, screenId);
                if (!changed.equals(current)) {
                    Files.writeString(document, changed, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("제외한 신규 화면을 가리키는 연결을 정리하지 못했습니다.", failure);
        }
    }

    static String removeScreenReferences(String document, String screenId) {
        Pattern lines = Pattern.compile("(?m)^.*$");
        Pattern reference = Pattern.compile("^(?:이동modal|이동|상위화면)\\s*:\\s*"
                + Pattern.quote(screenId) + "(?:\\.[A-Za-z0-9_-]+)?$");
        Matcher matcher = lines.matcher(document);
        StringBuilder changed = new StringBuilder(document.length());
        while (matcher.find()) {
            String line = matcher.group();
            if (!line.startsWith("- ")) {
                matcher.appendReplacement(changed, Matcher.quoteReplacement(line));
                continue;
            }
            List<String> fields = Arrays.stream(line.substring(2).split("\\s+/\\s+"))
                    .filter(field -> !reference.matcher(field.strip()).matches())
                    .collect(Collectors.toList());
            String replacement = fields.size() == line.substring(2).split("\\s+/\\s+").length
                    ? line
                    : fields.isEmpty() ? "" : "- " + String.join(" / ", fields);
            matcher.appendReplacement(changed, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(changed);
        return changed.toString();
    }

    private void discardScreenFile(Path workspace, Path pages, String screenId, String extension) {
        Path target = pages.resolve(screenId + extension).normalize();
        if (!pages.equals(target.getParent())) {
            throw new IllegalArgumentException("제외할 화면의 파일 이름이 올바르지 않습니다.");
        }
        String relative = workspace.relativize(target).toString().replace('\\', '/');
        GitResult inHead = git.run(workspace, properties.checkTimeout(),
                "ls-tree", "--name-only", "HEAD", "--", relative);
        if (!inHead.succeeded()) {
            throw new IllegalStateException("제외한 화면 파일의 원본 여부를 확인하지 못했습니다. " + detail(inHead));
        }
        if (!inHead.stdout().isBlank()) {
            require(workspace, "제외한 기존 화면 파일을 원본으로 되돌리지 못했습니다.",
                    "restore", "--source=HEAD", "--staged", "--worktree", "--", relative);
            return;
        }
        require(workspace, "제외한 신규 화면 파일을 커밋 대상에서 내리지 못했습니다.",
                "rm", "--cached", "--ignore-unmatch", "--", relative);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("제외한 신규 화면 파일을 삭제하지 못했습니다.", e);
        }
    }

    /** FRD 작업공간의 모든 변경을 한 커밋으로 확정한다. */
    public synchronized Commit commitChanges(String projectId, String frdId, String message) {
        Path workspace = verifiedWorkspace(projectId, frdId);
        String before = require(workspace, "현재 커밋을 확인하지 못했습니다.", "rev-parse", "HEAD")
                .stdout().strip();
        reindexQuietly(workspace, frdId);
        require(workspace, "FRD 변경 파일을 커밋 대상으로 올리지 못했습니다.", "add", "-A");
        GitResult changed = git.run(workspace, properties.checkTimeout(), "diff", "--cached", "--quiet");
        if (changed.exitCode() == 0) {
            throw new IllegalStateException("완료할 FRD 변경 내용이 없습니다.");
        }
        if (changed.exitCode() != 1) {
            throw new IllegalStateException("FRD 변경 내용을 확인하지 못했습니다. " + detail(changed));
        }
        require(workspace, "FRD 작업 커밋을 만들지 못했습니다.", "commit", "-m", message);
        String after = require(workspace, "완료한 FRD 커밋을 확인하지 못했습니다.", "rev-parse", "HEAD")
                .stdout().strip();
        return new Commit(workspace, before, after);
    }

    /**
     * AI가 만든 변경 예정 기능정의서를 FRD 작업트리에 반영하고 전달 기준판으로 커밋한다.
     * DB의 md는 화면 진행 상태용 사본이며, 개발에 나가는 파일의 정본은 이 커밋이다.
     */
    public synchronized Commit materializeTobeDocuments(String projectId, String frdId,
                                                        String requestLabel,
                                                        List<TobeDocument> documents) {
        Path workspace = verifiedWorkspace(projectId, frdId);
        if (hasChanges(projectId, frdId)) {
            throw new IllegalStateException("FRD 작업 자리에 커밋되지 않은 변경이 남아 있어 개발요청서를 확정할 수 없습니다.");
        }

        String before = require(workspace, "현재 FRD 작업 커밋을 확인하지 못했습니다.",
                "rev-parse", "HEAD").stdout().strip();
        Map<String, String> deliveryScreenIds = new LinkedHashMap<>();
        documents.forEach(document -> deliveryScreenIds.put(document.sourceScreenId(), document.screenId()));
        Set<String> relativePaths = new LinkedHashSet<>();
        try {
            for (TobeDocument document : documents) {
                requireScreenId(document.sourceScreenId());
                requireScreenId(document.screenId());
                Path pages = workspace.resolve("core").resolve(document.systemCode())
                        .resolve("pages").toAbsolutePath().normalize();
                Path target = pages.resolve(document.screenId() + ".md").normalize();
                if (!pages.startsWith(workspace) || !target.startsWith(pages)) {
                    throw new IllegalArgumentException("기능정의서 경로가 FRD 작업 자리 밖을 가리킵니다.");
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, document.content(), StandardCharsets.UTF_8);
                relativePaths.add(workspace.relativize(target).toString().replace('\\', '/'));

                if (!document.sourceScreenId().equals(document.screenId())) {
                    Path sourceMd = pages.resolve(document.sourceScreenId() + ".md").normalize();
                    Path sourceHtml = pages.resolve(document.sourceScreenId() + ".html").normalize();
                    Path targetHtml = pages.resolve(document.screenId() + ".html").normalize();
                    boolean sourceMdExisted = Files.exists(sourceMd);
                    boolean sourceHtmlExisted = Files.exists(sourceHtml);
                    Path htmlToRead = sourceHtmlExisted ? sourceHtml : targetHtml;
                    if (Files.exists(htmlToRead)) {
                        String html = Files.readString(htmlToRead, StandardCharsets.UTF_8);
                        Files.writeString(targetHtml,
                                ScreenDefinitionDocument.replaceScreenIds(html, deliveryScreenIds),
                                StandardCharsets.UTF_8);
                        relativePaths.add(workspace.relativize(targetHtml).toString().replace('\\', '/'));
                    }
                    Files.deleteIfExists(sourceHtml);
                    Files.deleteIfExists(sourceMd);
                    if (sourceHtmlExisted) {
                        relativePaths.add(workspace.relativize(sourceHtml).toString().replace('\\', '/'));
                    }
                    if (sourceMdExisted) {
                        relativePaths.add(workspace.relativize(sourceMd).toString().replace('\\', '/'));
                    }
                }
            }
        } catch (IOException failure) {
            restoreMaterializationFiles(workspace, before);
            throw new IllegalStateException("변경 예정 기능정의서를 FRD 작업 자리에 쓰지 못했습니다.", failure);
        }

        if (relativePaths.isEmpty()) {
            return new Commit(workspace, before, before);
        }
        try {
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(relativePaths);
            require(workspace, "변경 예정 기능정의서를 커밋 대상으로 올리지 못했습니다.",
                    add.toArray(String[]::new));
            List<String> diff = new ArrayList<>(List.of("diff", "--cached", "--quiet", "--"));
            diff.addAll(relativePaths);
            GitResult changed = git.run(workspace, properties.checkTimeout(), diff.toArray(String[]::new));
            if (changed.exitCode() == 0) {
                return new Commit(workspace, before, before);
            }
            if (changed.exitCode() != 1) {
                throw new IllegalStateException("변경 예정 기능정의서 변경 내용을 확인하지 못했습니다. " + detail(changed));
            }
            require(workspace, "변경 예정 기능정의서 커밋을 만들지 못했습니다.",
                    "commit", "-m", "docs: " + requestLabel + " 기능정의서 확정");
            String after = require(workspace, "개발요청서 전달 기준 커밋을 확인하지 못했습니다.",
                    "rev-parse", "HEAD").stdout().strip();
            return new Commit(workspace, before, after);
        } catch (RuntimeException failure) {
            restoreMaterializationFiles(workspace, before);
            throw failure;
        }
    }

    private void restoreMaterializationFiles(Path workspace, String before) {
        GitResult restored = git.run(workspace, properties.checkTimeout(), "reset", "--hard", before);
        if (!restored.succeeded()) {
            log.warn("개발요청 준비 실패 뒤 FRD 작업 자리를 원래 판으로 되돌리지 못했다 workspace={} detail={}",
                    workspace, detail(restored));
        }
    }

    private static void requireScreenId(String screenId) {
        if (screenId == null || !screenId.matches("^[a-z0-9][a-z0-9-]*$")) {
            throw new IllegalArgumentException("화면 ID 형식이 올바르지 않습니다: " + screenId);
        }
    }

    /** 색인 갱신 실패는 완료를 막지 않는다. 전송 전 검사가 낡은 색인을 다시 잡는다. */
    private void reindexQuietly(Path workspace, String frdId) {
        try {
            IaPublisher.reindexWhenAvailable(workspace);
        } catch (RuntimeException failure) {
            log.warn("FRD 작업 완료 전 기획 저장소 색인을 갱신하지 못했다 frdId={}", frdId, failure);
        }
    }

    /** DB 상태 전환이 실패했을 때 방금 만든 커밋만 되돌리고 파일 변경은 보존한다. */
    public synchronized void rollbackCommit(Commit commit) {
        if (commit == null || !Files.isDirectory(commit.workspace())) {
            return;
        }
        String current = require(commit.workspace(), "현재 커밋을 확인하지 못했습니다.", "rev-parse", "HEAD")
                .stdout().strip();
        if (!commit.after().equals(current)) {
            throw new IllegalStateException("다른 커밋이 추가되어 FRD 작업 커밋을 되돌릴 수 없습니다.");
        }
        require(commit.workspace(), "FRD 작업 커밋을 되돌리지 못했습니다.", "reset", "--mixed", commit.before());
    }

    /** 개발요청 전 검사 실패 시 전달용 커밋과 그 파일 변경을 함께 원래의 깨끗한 판으로 되돌린다. */
    public synchronized void rollbackMaterialization(Commit commit) {
        if (commit == null || commit.before().equals(commit.after()) || !Files.isDirectory(commit.workspace())) {
            return;
        }
        String current = require(commit.workspace(), "현재 커밋을 확인하지 못했습니다.", "rev-parse", "HEAD")
                .stdout().strip();
        if (!commit.after().equals(current)) {
            throw new IllegalStateException("다른 커밋이 추가되어 개발요청 준비 커밋을 되돌릴 수 없습니다.");
        }
        require(commit.workspace(), "개발요청 준비 커밋을 되돌리지 못했습니다.",
                "reset", "--hard", commit.before());
    }

    public record Commit(Path workspace, String before, String after) { }

    public record TobeDocument(String systemCode, String sourceScreenId, String screenId, String content) {
        public TobeDocument(String systemCode, String screenId, String content) {
            this(systemCode, screenId, screenId, content);
        }
    }

    /** 기획 저장소 최신 반영의 결과. */
    public enum Sync {
        /** 워크트리가 없다 — 간단 변경 FRD 는 정상이다. */
        NO_WORKTREE,
        /** 클론 HEAD 가 이미 워크트리 이력 안에 있다. */
        UP_TO_DATE,
        /** 클론 HEAD 를 병합해 넣었다. */
        MERGED,
        /** 수정 중인 파일과 겹치거나 충돌해 병합하지 않았다 — 워크트리는 그대로다. */
        CONFLICT
    }

    /** 최신 기본 브랜치 반영 결과와 그 사이 변경된 파일이다. */
    public record SyncResult(Sync state, String cloneHead, List<String> changedPaths) {
        public SyncResult {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }

    /** 화면 변화 확인을 마칠 때까지 Git 작업공간 안에만 보관하는 확인 표식이다. */
    public record PendingReview(String cloneHead, String screenRowId) { }

    private static final String SYNC_MESSAGE = "chore: 기획 저장소 최신 반영";

    /**
     * 워크트리가 클론 HEAD 보다 낡았나 — 클론 HEAD 가 워크트리 이력의 조상이 아니면 낡은 것이다.
     * ⚠ 워크트리가 없으면 거짓이다(맞출 것이 없다).
     */
    public boolean isBehindClone(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return false;
        }
        return !git.run(workspace, properties.checkTimeout(),
                "merge-base", "--is-ancestor", cloneHead(projectId), "HEAD").succeeded();
    }

    /**
     * 워크트리를 클론 HEAD 에 맞춘다 — 기획 저장소가 새 판으로 올라간 뒤에도 FRD 작업이 옛 규격·옛 검사기 위에
     * 남지 않게 (2026-08-25 실측: 워크트리 {@code manifest.json} 이 /4, 클론 검사기가 /5 라 검사기가
     * 첫 관문에서 멈추고 전송 전 검증이 늘 「검사기를 돌리지 못했습니다」였다).
     *
     * <p>⭐ <b>수정 중인 파일이 있어도 돈다.</b> git 은 병합이 건드리지 않는 파일의 변경은 그대로 두고 병합한다.
     * 겹치면 git 이 거절하고, 우리는 <b>사람 작업이 이긴다</b>로 읽어 되돌리고 {@link Sync#CONFLICT} 를 낸다 —
     * 워크트리에 병합 중 상태를 남기지 않는다.
     *
     * <p>기존 호환 메서드는 결과 enum만 돌려주며, 완료 흐름은 {@link #syncWithCloneDetails}로
     * 변경 파일까지 확인해 충돌과 화면 검토를 처리한다.
     */
    public synchronized Sync syncWithClone(String projectId, String frdId) {
        return syncWithCloneDetails(projectId, frdId).state();
    }

    /** 클론 HEAD를 병합하고 최신 변경 파일까지 돌려준다. 충돌한 병합은 반드시 원복한다. */
    public synchronized SyncResult syncWithCloneDetails(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return new SyncResult(Sync.NO_WORKTREE, null, List.of());
        }
        String head = cloneHead(projectId);
        if (git.run(workspace, properties.checkTimeout(), "merge-base", "--is-ancestor", head, "HEAD").succeeded()) {
            return new SyncResult(Sync.UP_TO_DATE, head, List.of());
        }
        GitResult base = git.run(workspace, properties.checkTimeout(), "merge-base", "HEAD", head);
        if (!base.succeeded() || base.stdout().isBlank()) {
            throw new IllegalStateException("FRD 작업공간과 최신 기획 저장소의 공통 기준을 확인하지 못했습니다.");
        }
        GitResult changed = git.run(workspace, properties.checkTimeout(),
                "diff", "--name-only", base.stdout().strip(), head);
        if (!changed.succeeded()) {
            throw new IllegalStateException("최신 기획 저장소의 변경 파일을 확인하지 못했습니다. " + detail(changed));
        }
        List<String> changedPaths = changed.stdout().lines().map(String::strip)
                .filter(path -> !path.isBlank()).toList();
        GitResult merged = git.run(workspace, properties.checkTimeout(),
                "merge", "--no-edit", "-m", SYNC_MESSAGE, head);
        if (merged.succeeded()) {
            return new SyncResult(Sync.MERGED, head, changedPaths);
        }
        // 겹침·충돌 — 시작됐을 수 있는 병합을 무른다. 시작도 안 됐으면 abort 가 실패하는데 그건 무해하다.
        git.run(workspace, properties.checkTimeout(), "merge", "--abort");
        return new SyncResult(Sync.CONFLICT, head, changedPaths);
    }

    /** 최신 반영이 화면에 영향을 주었음을 기록해 확인 없이 완료되지 않게 한다. */
    public synchronized void requireLatestReview(String projectId, String frdId,
                                                 String cloneHead, String screenRowId) {
        if (cloneHead == null || cloneHead.isBlank() || screenRowId == null || screenRowId.isBlank()) {
            throw new IllegalArgumentException("최신 내용 확인 표식에 필요한 정보가 없습니다.");
        }
        Path marker = reviewMarker(verifiedWorkspace(projectId, frdId));
        try {
            Files.writeString(marker, cloneHead.strip() + "\n" + screenRowId.strip() + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("최신 내용 확인 상태를 저장하지 못했습니다.", failure);
        }
    }

    public synchronized PendingReview pendingLatestReview(String projectId, String frdId) {
        Path workspace = verifiedWorkspace(projectId, frdId);
        Path marker = reviewMarker(workspace);
        if (!Files.isRegularFile(marker)) return null;
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() < 2 || lines.get(0).isBlank() || lines.get(1).isBlank()) return null;
            return new PendingReview(lines.get(0).strip(), lines.get(1).strip());
        } catch (IOException failure) {
            throw new IllegalStateException("최신 내용 확인 상태를 읽지 못했습니다.", failure);
        }
    }

    /** 확인한 기준판이 현재 클론과 같을 때만 표식을 지운다. */
    public synchronized boolean confirmLatestReview(String projectId, String frdId, String confirmedHead) {
        PendingReview pending = pendingLatestReview(projectId, frdId);
        if (pending == null || confirmedHead == null
                || !pending.cloneHead().equals(confirmedHead.strip())
                || !pending.cloneHead().equals(cloneHead(projectId))) {
            return false;
        }
        try {
            Files.deleteIfExists(reviewMarker(verifiedWorkspace(projectId, frdId)));
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException("최신 내용 확인 상태를 완료하지 못했습니다.", failure);
        }
    }

    /** 병합된 최신 CSS와 화면 파일을 기준으로 색인을 다시 만든다. 실패는 기존 완료 정책처럼 로그만 남긴다. */
    public synchronized void refreshDerivedFiles(String projectId, String frdId) {
        reindexQuietly(verifiedWorkspace(projectId, frdId), frdId);
    }

    private Path reviewMarker(Path workspace) {
        GitResult gitPath = git.run(workspace, properties.checkTimeout(),
                "rev-parse", "--git-path", "builder-frd-latest-review");
        if (!gitPath.succeeded() || gitPath.stdout().isBlank()) {
            throw new IllegalStateException("최신 내용 확인 상태를 저장할 위치를 찾지 못했습니다.");
        }
        Path marker = Path.of(gitPath.stdout().strip());
        return marker.isAbsolute() ? marker.normalize() : workspace.resolve(marker).normalize();
    }

    private String cloneHead(String projectId) {
        Path clone = paths.cloneDir(projectId).toAbsolutePath().normalize();
        return require(clone, "기획 저장소의 현재 커밋을 확인하지 못했습니다.", "rev-parse", "HEAD").stdout().strip();
    }

    /** 작업 완료가 만드는 커밋의 제목. ⚠ 되돌리기가 이 글자로 「우리가 만든 커밋」임을 확인한다 — 한 곳에 둔다. */
    public static String completionMessage(String frdLabel) {
        return "docs: " + frdLabel + " 작업 완료";
    }

    /**
     * 「FRD 로 되돌리기」 — 작업 완료가 만든 커밋을 풀어 파일 변경을 다시 「수정 중」으로 돌린다 (2026-08-25).
     *
     * <p>⭐ <b>왜 필요한가.</b> 「작업 완료」 버튼은 커밋 안 된 변경이 있을 때만 켜진다. 완료가 변경을
     * 커밋했으니 되돌아온 워크트리는 「변경 없음」으로 읽혀 버튼이 꺼져 있었다 (병주 실측).
     * 완료를 무르는 것이니 완료가 한 일(커밋)도 무른다. 파일은 그대로 남는다({@code --mixed}).
     *
     * <p>⛔ <b>HEAD 가 우리 완료 커밋일 때만 푼다.</b> 제목이 다르면 남이 만든 커밋이다 — 손대지 않고
     * 거짓을 돌려준다. 첫 커밋(부모 없음)도 풀 수 없다.
     *
     * @return 풀었으면 참. 워크트리가 없거나 HEAD 가 완료 커밋이 아니면 거짓
     */
    public synchronized boolean uncommitCompletion(String projectId, String frdId, String expectedMessage) {
        Path workspace = completionWorkspace(projectId, frdId);
        if (workspace == null) {
            return false;
        }
        int commitsToUnwind = completionCommitsToUnwind(workspace, expectedMessage);
        if (commitsToUnwind == 0) {
            return false;
        }
        String target = "HEAD~" + commitsToUnwind;
        GitResult parent = git.run(workspace, properties.checkTimeout(),
                "rev-parse", "--verify", "--quiet", target);
        if (!parent.succeeded()) {
            return false;
        }
        require(workspace, "FRD 작업 완료 커밋을 되돌리지 못했습니다.", "reset", "--mixed", target);
        return true;
    }

    /**
     * 개발요청서에서 돌아왔지만 완료 커밋이 아직 HEAD에 남은 복구 대상을 찾는다.
     * 상태 조회에서는 Git을 바꾸지 않고 버튼 활성화 여부만 판단한다.
     */
    public synchronized boolean hasCompletionToReopen(String projectId, String frdId, String expectedMessage) {
        Path workspace = completionWorkspace(projectId, frdId);
        return workspace != null && completionCommitsToUnwind(workspace, expectedMessage) > 0;
    }

    private Path completionWorkspace(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return null;
        }
        return workspace;
    }

    private int completionCommitsToUnwind(Path workspace, String expectedMessage) {
        GitResult head = git.run(workspace, properties.checkTimeout(), "log", "-1", "--pretty=%s");
        if (!head.succeeded()) {
            // git 저장소가 아니거나 커밋이 없다 — 풀 것이 없다. ⚠ 던지면 되돌리기 전체가 막힌다.
            return 0;
        }
        String headMessage = head.stdout().strip();
        if (expectedMessage.equals(headMessage)) {
            return 1;
        }
        if (!headMessage.matches("docs: DR-[0-9]+ 기능정의서 확정")) {
            return 0;
        }
        GitResult completion = git.run(workspace, properties.checkTimeout(),
                "log", "-1", "--pretty=%s", "HEAD~1");
        return completion.succeeded() && expectedMessage.equals(completion.stdout().strip()) ? 2 : 0;
    }

    private Path verifiedWorkspace(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path clone = paths.cloneDir(projectId).toAbsolutePath().normalize();
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalStateException("FRD 작업공간을 찾을 수 없습니다. 목록에서 다시 작업을 시작해 주세요.");
        }
        verifyExisting(clone, workspace, branch(frdId));
        return workspace;
    }

    private GitResult require(Path workspace, String message, String... args) {
        GitResult result = git.run(workspace, properties.checkTimeout(), args);
        if (!result.succeeded()) {
            throw new IllegalStateException(message + " " + detail(result));
        }
        return result;
    }

    /** DB 상태 전환이 실패했을 때 이번 요청이 새로 만든 빈 자리만 보상 정리한다. */
    public synchronized void rollback(Prepared prepared) {
        if (prepared == null || !prepared.workspaceCreated()) {
            return;
        }
        try {
            Path workspace = prepared.path().toAbsolutePath().normalize();
            Path clone = prepared.clonePath().toAbsolutePath().normalize();
            GitResult removed = git.run(clone, properties.checkTimeout(),
                    "worktree", "remove", "--force", workspace.toString());
            if (!removed.succeeded()) {
                log.warn("상태 전환에 실패한 FRD 작업 공간을 치우지 못했다 workspace={} reason={}",
                        workspace, detail(removed));
                return;
            }
            if (prepared.branchCreated()) {
                GitResult deleted = git.run(clone, properties.checkTimeout(),
                        "branch", "-D", prepared.branch());
                if (!deleted.succeeded()) {
                    log.warn("상태 전환에 실패한 FRD 브랜치를 치우지 못했다 branch={} reason={}",
                            prepared.branch(), detail(deleted));
                }
            }
        } catch (RuntimeException cleanupFailure) {
            log.warn("상태 전환에 실패한 FRD 작업 공간을 치우는 중 오류가 났다 path={}", prepared.path());
        }
    }

    /** 삭제된 FRD가 사용하던 Git 워크트리와 전용 브랜치를 정리한다. */
    public synchronized void discard(String projectId, String frdId) {
        if (!IdSequence.isValidId(frdId)) {
            throw new IllegalArgumentException("FRD 번호 형식이 올바르지 않습니다.");
        }
        Path clone = paths.cloneDir(projectId).toAbsolutePath().normalize();
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        String branch = branch(frdId);
        if (Files.isDirectory(workspace)) {
            verifyExisting(clone, workspace, branch);
            require(clone, "FRD 작업공간을 삭제하지 못했습니다.",
                    "worktree", "remove", "--force", workspace.toString());
        }
        if (Files.isDirectory(clone)
                && succeeded(clone, "show-ref", "--verify", "--quiet", "refs/heads/" + branch)) {
            require(clone, "FRD 작업 브랜치를 삭제하지 못했습니다.", "branch", "-D", branch);
        }
    }

    static String branch(String frdId) {
        return "frd/" + frdId;
    }

    private void verifyExisting(Path clone, Path workspace, String expectedBranch) {
        GitResult current = git.run(workspace, properties.checkTimeout(), "branch", "--show-current");
        if (!current.succeeded() || !expectedBranch.equals(current.stdout().strip())) {
            throw collision();
        }

        GitResult common = git.run(workspace, properties.checkTimeout(), "rev-parse", "--git-common-dir");
        if (!common.succeeded() || common.stdout().isBlank()) {
            throw collision();
        }
        try {
            Path reported = Path.of(common.stdout().strip());
            if (!reported.isAbsolute()) {
                reported = workspace.resolve(reported);
            }
            if (!reported.toRealPath().equals(clone.resolve(".git").toRealPath())) {
                throw collision();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException rejected) {
                throw rejected;
            }
            throw collision();
        }
    }

    private boolean succeeded(Path clone, String... args) {
        return git.run(clone, properties.checkTimeout(), args).succeeded();
    }

    private static IllegalStateException collision() {
        return new IllegalStateException(
                "FRD 작업 폴더가 다른 Git 작업을 가리킵니다. 폴더를 확인한 뒤 다시 시도해 주세요.");
    }

    private static String detail(GitResult result) {
        String stderr = GitCommand.mask(result.stderr());
        String stdout = GitCommand.mask(result.stdout());
        String detail = stderr == null || stderr.isBlank() ? stdout : stderr;
        return detail == null || detail.isBlank() ? "Git 명령이 실패했습니다." : detail.strip();
    }
}
