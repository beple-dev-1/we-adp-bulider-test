package com.bizplay.builder.frd;

import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.ProjectPaths;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 전체 캔버스에서 선택한 현재 화면을 독립된 신규 작업 화면으로 복제한다. */
@Service
public class FrdScreenDuplicationService {

    private static final Set<String> SCREEN_LINK_FIELDS = Set.of(
            "이동", "이동modal", "이동native", "이동cross");

    private final FrdMapper frds;
    private final FrdScreenMapper screens;
    private final FrdScreenHistoryMapper histories;
    private final ScreenMockupService mockups;
    private final IdSequence ids;
    private final ProjectPaths paths;
    private final FrdScreenIaPlacementService iaPlacements;

    public FrdScreenDuplicationService(FrdMapper frds, FrdScreenMapper screens,
                                       FrdScreenHistoryMapper histories, ScreenMockupService mockups,
                                       IdSequence ids, ProjectPaths paths,
                                       FrdScreenIaPlacementService iaPlacements) {
        this.frds = frds;
        this.screens = screens;
        this.histories = histories;
        this.mockups = mockups;
        this.ids = ids;
        this.paths = paths;
        this.iaPlacements = iaPlacements;
    }

    /** 원본의 현재 화면 구성만 복사하고 화면 연결·대화·이전 변경 이력은 가져오지 않는다. */
    @Transactional
    public FrdScreen duplicate(String projectId, String frdId, String sourceRowId,
                               String screenName) {
        Frd frd = frds.selectById(frdId);
        FrdScreen source = screens.selectById(sourceRowId);
        if (frd == null || !frd.projectId().equals(projectId)
                || source == null || !source.frdId().equals(frdId)) {
            throw new IllegalArgumentException("복제할 작업 화면을 찾을 수 없습니다.");
        }
        if (frd.state() != Frd.State.DRAFTING) {
            throw new IllegalStateException("FRD를 수정하는 중에만 화면을 복제할 수 있습니다.");
        }
        if (source.state() == FrdScreen.State.GENERATING) {
            throw new IllegalStateException("AI 초안을 만드는 중인 화면은 복제할 수 없습니다.");
        }
        String name = requiredName(screenName);
        String type = source.screenType();
        String systemCode = source.systemCode() == null || source.systemCode().isBlank()
                ? frd.systemCode() : source.systemCode();
        if (systemCode == null || !systemCode.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalStateException("복제할 화면의 시스템을 확인하지 못했습니다.");
        }

        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String targetScreenId = TemporaryScreenId.of(rowId);
        Path pages = pages(projectId, frdId, systemCode);
        Path sourceHtmlFile = pages.resolve(source.screenId() + ".html").normalize();
        Path sourceMdFile = pages.resolve(source.screenId() + ".md").normalize();
        Path targetHtmlFile = pages.resolve(targetScreenId + ".html").normalize();
        Path targetMdFile = pages.resolve(targetScreenId + ".md").normalize();
        ensureInside(pages, sourceHtmlFile, sourceMdFile, targetHtmlFile, targetMdFile);

        Path writtenHtml = null;
        Path writtenMd = null;
        try {
            Files.createDirectories(pages);
            verifyRealPages(projectId, frdId, pages);
            String fileHtml = readIfSafe(pages, sourceHtmlFile);
            String sourceHtml = fileHtml == null ? source.html() : fileHtml;
            if (sourceHtml == null || sourceHtml.isBlank()) {
                throw new IllegalStateException("현재 화면의 초안을 만든 뒤 복제해 주세요.");
            }
            String sourceMd = readIfSafe(pages, sourceMdFile);
            String html = duplicateHtml(sourceHtml, sourceMd, source.screenId(), targetScreenId);
            String md = duplicateMd(sourceMd, source, targetScreenId, name, systemCode);

            Files.writeString(targetHtmlFile, html, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            writtenHtml = targetHtmlFile;
            Files.writeString(targetMdFile, md, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            writtenMd = targetMdFile;

            String change = "\"%s\" 화면을 복제해 신규 화면을 만들었습니다.".formatted(source.screenName());
            screens.insert(new FrdScreen(rowId, frdId, targetScreenId, name, source.screenId(),
                    source.facet(), null, FrdScreen.State.WAITING, null, null, null,
                    null, null, systemCode, type, change));
            FrdScreenIaPlacement sourcePlacement = iaPlacements.of(source.id());
            FrdScreenIaPlacement.ScreenKind kind = sourcePlacement == null
                    ? FrdScreenIaPlacement.ScreenKind.SCREEN : sourcePlacement.screenKind();
            iaPlacements.save(rowId, new FrdScreenIaPlacementService.Request(
                    "UNRESOLVED", null, null, kind.name(), "USER"));
            long historyId = mockups.markGenerated(rowId, new ScreenMockupReader.Mockup(html, List.of(change)));
            histories.fillMd(historyId, md);
            return screens.selectById(rowId);
        } catch (IOException failure) {
            deleteQuietly(writtenMd);
            deleteQuietly(writtenHtml);
            throw new IllegalStateException("화면 복제 파일을 만들지 못했습니다.", failure);
        } catch (RuntimeException failure) {
            deleteQuietly(writtenMd);
            deleteQuietly(writtenHtml);
            throw failure;
        }
    }

    private Path pages(String projectId, String frdId, String systemCode) {
        Path core = paths.frdWorktree(projectId, frdId).resolve("core").toAbsolutePath().normalize();
        if (!Files.isDirectory(core)) {
            throw new IllegalStateException("FRD 작업 공간이 준비되지 않아 화면을 복제할 수 없습니다.");
        }
        Path pages = core.resolve(systemCode).resolve("pages").normalize();
        if (!pages.startsWith(core)) {
            throw new IllegalStateException("복제할 화면 파일 경로가 작업 공간을 벗어났습니다.");
        }
        return pages;
    }

    private void ensureInside(Path pages, Path... files) {
        for (Path file : files) {
            if (!file.startsWith(pages)) {
                throw new IllegalStateException("복제할 화면 파일 경로가 작업 공간을 벗어났습니다.");
            }
        }
    }

    private void verifyRealPages(String projectId, String frdId, Path pages) throws IOException {
        Path core = paths.frdWorktree(projectId, frdId).resolve("core").toAbsolutePath().normalize();
        if (!pages.toRealPath().startsWith(core.toRealPath())) {
            throw new IllegalStateException("복제할 화면 파일 경로가 작업 공간을 벗어났습니다.");
        }
    }

    private String readIfSafe(Path pages, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        Path real = file.toRealPath();
        if (!real.startsWith(pages.toRealPath())) {
            throw new IllegalStateException("복제할 화면 파일 경로가 작업 공간을 벗어났습니다.");
        }
        return Files.readString(real, StandardCharsets.UTF_8);
    }

    private String requiredName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isBlank()) throw new IllegalArgumentException("새 화면명을 입력해 주세요.");
        if (name.length() > 255) throw new IllegalArgumentException("새 화면명은 255자까지 입력할 수 있습니다.");
        return name;
    }

    private String changeAnchorNamespace(String content, String sourceScreenId, String targetScreenId) {
        return content.replace(sourceScreenId + "-e", targetScreenId + "-e")
                .replace("data-screen-id=\"" + sourceScreenId + "\"",
                        "data-screen-id=\"" + targetScreenId + "\"");
    }

    private String duplicateHtml(String sourceHtml, String sourceMd,
                                 String sourceScreenId, String targetScreenId) {
        Document document = Jsoup.parse(sourceHtml);
        Set<String> relationAnchors = relationAnchors(sourceMd);
        for (Element element : document.select("[data-nav-target], [id], [data-element-id]")) {
            String anchor = element.hasAttr("data-element-id")
                    ? element.attr("data-element-id").strip() : element.id().strip();
            if (!element.hasAttr("data-nav-target") && !relationAnchors.contains(anchor)) continue;
            element.removeAttr("data-nav-target");
            element.removeAttr("onclick");
            element.removeAttr("href");
            element.removeAttr("formaction");
            element.removeAttr("data-href");
            element.removeAttr("target");
        }
        document.outputSettings().prettyPrint(false);
        return changeAnchorNamespace(document.outerHtml(), sourceScreenId, targetScreenId);
    }

    private String duplicateMd(String sourceMd, FrdScreen source, String targetScreenId,
                               String screenName, String systemCode) {
        if (sourceMd == null || sourceMd.isBlank()) {
            return """
                    --- 꼬리표 ---
                    id: %s / system: %s / 기능: %s / 과업: []

                    --- 화면명세 ---
                    화면명: %s
                    목적: %s 화면을 복제해 만든 신규 화면

                    --- IA ---
                    - 종류: 화면

                    --- 정의 ---

                    --- 원본 글 ---
                    """.formatted(targetScreenId, systemCode, screenName, screenName, source.screenName());
        }
        String md = changeAnchorNamespace(withoutScreenRelations(sourceMd),
                source.screenId(), targetScreenId);
        md = md.replaceFirst("(?m)^(id:\\s*)" + Pattern.quote(source.screenId()) + "(\\s*/)",
                "$1" + Matcher.quoteReplacement(targetScreenId) + "$2");
        return ScreenDefinitionDocument.normalizeStructure(
                md.replaceFirst("(?m)^(화면명:\\s*).*$", "$1" + Matcher.quoteReplacement(screenName)));
    }

    private String withoutScreenRelations(String sourceMd) {
        if (sourceMd == null || sourceMd.isBlank()) return sourceMd;
        String lineBreak = sourceMd.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = sourceMd.split("\\R", -1);
        StringBuilder result = new StringBuilder(sourceMd.length());
        for (String line : lines) {
            if (isScreenRelation(line)) continue;
            if (!result.isEmpty()) result.append(lineBreak);
            result.append(line);
        }
        return result.toString();
    }

    private Set<String> relationAnchors(String sourceMd) {
        if (sourceMd == null || sourceMd.isBlank()) return Set.of();
        Set<String> anchors = new LinkedHashSet<>();
        for (String line : sourceMd.split("\\R")) {
            if (!isScreenRelation(line)) continue;
            String anchor = fields(line.strip().substring(2)).get("앵커");
            if (anchor != null && !anchor.isBlank()) anchors.add(anchor.strip());
        }
        return anchors;
    }

    private boolean isScreenRelation(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("- ")) return false;
        Map<String, String> fields = fields(trimmed.substring(2));
        if (!"이동".equals(fields.get("구분"))) return false;
        return SCREEN_LINK_FIELDS.stream().anyMatch(field -> {
            String target = fields.get(field);
            return target != null && !target.isBlank();
        });
    }

    private Map<String, String> fields(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : line.split(" / ")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            result.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
        }
        return result;
    }

    private void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // DB 작업을 실패시킨 원래 예외를 유지한다.
        }
    }
}
