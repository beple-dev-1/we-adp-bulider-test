package com.bizplay.builder.frd;

import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.solution.PreviewFacets;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** FRD 화면의 실제 워크트리 파일 위치를 한 자리에서 결정한다. */
@Component
public class FrdScreenFiles {

    private final ProjectPaths paths;
    private final FrdFacetMapper facets;
    private final PreviewFacets previewFacets;

    public FrdScreenFiles(ProjectPaths paths, FrdFacetMapper facets, PreviewFacets previewFacets) {
        this.paths = paths;
        this.facets = facets;
        this.previewFacets = previewFacets;
    }

    /** 기존 화면 HTML. 기관별 갈래가 있으면 FRD 적용 대상과 같은 갈래를 돌려준다. */
    public Path existingHtml(String projectId, String frdId, String systemCode, String screenId) {
        return resolveHtml(paths.frdWorktree(projectId, frdId), systemCode, screenId,
                selectedCodes(projectId, frdId), false);
    }

    /** 화면별 적용 대상을 알고 있으면 FRD 전체가 아니라 그 기관의 갈래만 찾는다. */
    public Path existingHtml(String projectId, String frdId, String systemCode, String screenId,
                             String facetName) {
        return resolveHtml(paths.frdWorktree(projectId, frdId), systemCode, screenId,
                selectedCodes(projectId, frdId, facetName), false);
    }

    /** 새 화면을 만들 자리. 같은 ID의 기관별 갈래가 이미 있으면 그 파일을 그대로 쓴다. */
    public Path targetHtml(String projectId, String frdId, String systemCode, String screenId) {
        return resolveHtml(paths.frdWorktree(projectId, frdId), systemCode, screenId,
                selectedCodes(projectId, frdId), true);
    }

    /** 화면별 적용 대상을 알고 있으면 그 기관의 갈래 파일을 수정 대상으로 돌려준다. */
    public Path targetHtml(String projectId, String frdId, String systemCode, String screenId,
                           String facetName) {
        return resolveHtml(paths.frdWorktree(projectId, frdId), systemCode, screenId,
                selectedCodes(projectId, frdId, facetName), true);
    }

    /** 선택한 기관 가운데 실제로 서로 다른 HTML 갈래가 있는 적용 대상 이름. */
    public List<String> variantFacets(String projectId, String frdId, String systemCode, String screenId) {
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        Path core = workspace.resolve("core").normalize();
        String system = safe(systemCode, "시스템");
        String id = safe(screenId, "화면ID");
        return selectedTargets(projectId, frdId).stream()
                .filter(target -> Files.isRegularFile(core.resolve(system)
                        .resolve("variants-" + safe(target.code(), "적용 대상"))
                        .resolve(id + ".html")))
                .map(FacetTarget::name)
                .distinct().toList();
    }

    /** FRD가 한 기관을 가리키면 저장소의 기관 코드를 돌려준다. */
    public String selectedCode(String projectId, String frdId) {
        List<String> codes = selectedCodes(projectId, frdId);
        return codes.size() == 1 ? codes.get(0) : null;
    }

    /** 기능정의서는 기관별 HTML과 달리 {@code pages/<화면ID>.md} 한 벌이다. */
    public Path document(String projectId, String frdId, String systemCode, String screenId) {
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        Path core = workspace.resolve("core").normalize();
        Path document = core.resolve(safe(systemCode, "시스템"))
                .resolve("pages").resolve(safe(screenId, "화면ID") + ".md").normalize();
        if (!document.startsWith(core)) {
            throw new IllegalArgumentException("기능정의서 경로가 FRD 작업 자리 밖을 가리킵니다.");
        }
        return document;
    }

    /** 메뉴에 직접 놓는 신규 화면을 기록할 시스템 IA 문서다. */
    public Path iaDocument(String projectId, String frdId, String systemCode) {
        Path workspace = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        Path core = workspace.resolve("core").normalize();
        Path document = core.resolve(safe(systemCode, "시스템")).resolve("ia.md").normalize();
        if (!document.startsWith(core)) {
            throw new IllegalArgumentException("IA 문서 경로가 FRD 작업 자리 밖을 가리킵니다.");
        }
        return document;
    }

    /** 워크트리 HTML에서 새 화면으로 들어오는 링크가 하나뿐이면 그 화면ID를 찾는다. */
    public String inboundParent(String projectId, String frdId, String systemCode, String screenId) {
        Path systemRoot = paths.frdWorktree(projectId, frdId).resolve("core")
                .resolve(safe(systemCode, "시스템")).toAbsolutePath().normalize();
        return findInboundParent(systemRoot, safe(screenId, "화면ID"));
    }

    static String findInboundParent(Path systemRoot, String screenId) {
        Pattern link = Pattern.compile("data-nav-target\\s*=\\s*[\\\"']"
                + Pattern.quote(screenId) + "(?:\\.[A-Za-z0-9_-]+)?[\\\"']");
        try (var files = Files.walk(systemRoot)) {
            List<String> parents = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".html"))
                    .filter(file -> !file.getFileName().toString().equals(screenId + ".html"))
                    .filter(file -> contains(file, link))
                    .map(file -> file.getFileName().toString().replaceFirst("\\.html$", ""))
                    .distinct().toList();
            return parents.size() == 1 ? parents.get(0) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static boolean contains(Path file, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(file)).find();
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** 테스트 가능한 순수 경로 결정 규칙. */
    static Path resolveHtml(Path workspace, String systemCode, String screenId,
                            List<String> selectedCodes, boolean allowCreate) {
        Path root = workspace.toAbsolutePath().normalize();
        Path core = root.resolve("core").normalize();
        String system = safe(systemCode, "시스템");
        String id = safe(screenId, "화면ID");
        Path page = core.resolve(system).resolve("pages").resolve(id + ".html").normalize();
        if (!page.startsWith(core)) {
            throw new IllegalArgumentException("화면 경로가 FRD 작업 자리 밖을 가리킵니다.");
        }

        List<Path> variants = selectedCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> core.resolve(system).resolve("variants-" + safe(code, "적용 대상"))
                        .resolve(id + ".html").normalize())
                .filter(Files::isRegularFile)
                .distinct().toList();
        if (variants.size() > 1) {
            throw new IllegalStateException("여러 적용 대상의 화면이 갈라져 있어 한 화면으로 수정할 수 없습니다: " + id);
        }
        if (variants.size() == 1) {
            if (Files.isRegularFile(page)) {
                throw new IllegalStateException("공통 화면과 기관별 화면이 함께 있어 수정할 파일을 정할 수 없습니다: " + id);
            }
            return safeExisting(core, variants.get(0));
        }
        if (Files.isRegularFile(page)) {
            return safeExisting(core, page);
        }
        if (!allowCreate) {
            return null;
        }
        Path parent = page.getParent();
        if (!Files.isDirectory(parent)) {
            throw new IllegalStateException("화면을 만들 pages 폴더가 없습니다: " + system);
        }
        return safeParent(core, page);
    }

    private List<String> selectedCodes(String projectId, String frdId) {
        List<String> codes = selectedTargets(projectId, frdId).stream().map(FacetTarget::code).toList();
        if (!codes.isEmpty()) {
            return codes;
        }
        String only = previewFacets.only(projectId);
        return only == null ? List.of() : List.of(only);
    }

    private List<String> selectedCodes(String projectId, String frdId, String facetName) {
        if (facetName == null || facetName.isBlank()) {
            return selectedCodes(projectId, frdId);
        }
        return selectedTargets(projectId, frdId).stream()
                .filter(target -> target.name().equals(facetName))
                .map(FacetTarget::code)
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException(
                        "선택한 적용 대상이 이 FRD에 포함되어 있지 않습니다: " + facetName));
    }

    private List<FacetTarget> selectedTargets(String projectId, String frdId) {
        return facets.selectByFrdId(frdId).stream()
                .map(FrdFacet::name)
                .map(name -> new FacetTarget(name, previewFacets.codeOfName(projectId, name)))
                .filter(target -> target.code() != null && !target.code().isBlank())
                .distinct().toList();
    }

    private record FacetTarget(String name, String code) { }

    private static Path safeExisting(Path core, Path file) {
        try {
            Path realCore = core.toRealPath();
            Path real = file.toRealPath();
            if (!real.startsWith(realCore)) {
                throw new IllegalArgumentException("화면 파일이 FRD 작업 자리 밖을 가리킵니다.");
            }
            return real;
        } catch (IOException failure) {
            throw new IllegalStateException("화면 파일 경로를 확인하지 못했습니다.", failure);
        }
    }

    private static Path safeParent(Path core, Path file) {
        try {
            if (!file.getParent().toRealPath().startsWith(core.toRealPath())) {
                throw new IllegalArgumentException("화면 파일이 FRD 작업 자리 밖을 가리킵니다.");
            }
            return file;
        } catch (IOException failure) {
            throw new IllegalStateException("화면 파일을 만들 경로를 확인하지 못했습니다.", failure);
        }
    }

    private static String safe(String value, String label) {
        if (value == null || !value.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$")) {
            throw new IllegalArgumentException(label + " 코드의 꼴이 올바르지 않습니다: " + value);
        }
        return value;
    }
}
