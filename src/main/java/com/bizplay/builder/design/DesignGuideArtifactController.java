package com.bizplay.builder.design;

import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.ProjectPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 추출 HTML과 그 상대 경로 자산을 좁은 읽기 전용 문으로 낸다.
 *
 * <p>가이드 산출물에는 높이 조절용 스크립트가 필요하지만, 기획 저장소의 스크립트를
 * Builder 권한으로 실행해서는 안 된다. HTML의 스크립트와 이벤트 속성은 모두 지우고,
 * Builder가 제공하는 작은 런타임만 넣는다. iframe은 opaque origin으로 가둔다.
 */
@Controller
@RequestMapping(DesignGuideArtifactController.PATH)
public class DesignGuideArtifactController {

    static final String PATH = "/projects/{projectId}/artifacts/design-guide/files/{ticket}";
    public static final String URL_PATTERN = "/projects/*/artifacts/design-guide/files/*/**";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html;charset=UTF-8"),
            Map.entry("css", "text/css;charset=UTF-8"),
            Map.entry("js", "text/javascript;charset=UTF-8"),
            Map.entry("json", "application/json;charset=UTF-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"));
    private static final Pattern PREVIEW_CHECKS = Pattern.compile(
            "\\bvar\\s+DG_CHECK\\s*=\\s*\\[([^]]*)]", Pattern.MULTILINE);
    private static final Pattern PREVIEW_CHECK_NAME = Pattern.compile("[\\\"']([A-Za-z0-9_-]+)[\\\"']");

    private final ProjectPaths paths;
    private final PlanningManifestReader manifests;
    private final DesignGuideArtifactAccess access;

    public DesignGuideArtifactController(ProjectPaths paths,
                                         PlanningManifestReader manifests,
                                         DesignGuideArtifactAccess access) {
        this.paths = paths;
        this.manifests = manifests;
        this.access = access;
    }

    @GetMapping("/**")
    public ResponseEntity<?> file(@PathVariable String projectId,
                                  @PathVariable String ticket,
                                  HttpServletRequest request) {
        if (!access.allows(projectId, ticket)) {
            throw missing();
        }
        Path clone = realPath(paths.cloneDir(projectId));
        Path guide = realPath(manifests.designGuideDirectory(projectId));
        if (!guide.startsWith(clone)) {
            throw missing();
        }

        Path target = resolveInside(clone, relativePath(projectId, ticket, request));
        Path core = clone.resolve("core");
        Path guideAssets = clone.resolve("verify").resolve("assets");
        if (!target.startsWith(guide) && !target.startsWith(core)
                && !target.startsWith(guideAssets)
                && !target.equals(clone.resolve("design-index.json"))) {
            throw missing();
        }

        String extension = extensionOf(target);
        String contentType = CONTENT_TYPES.get(extension);
        if (contentType == null) {
            throw missing();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .header("Content-Type", contentType);
        if (!"html".equals(extension)) {
            return response.body(new FileSystemResource(target));
        }
        return response
                .header("Content-Security-Policy", "sandbox allow-scripts")
                .body(sanitizedHtml(target, target.startsWith(guide)));
    }

    private static String sanitizedHtml(Path target, boolean guideDocument) {
        try {
            String source = Files.readString(target, StandardCharsets.UTF_8);
            List<String> previewChecks = previewChecks(source);
            Document document = Jsoup.parse(source);
            document.select("script").remove();
            for (Element element : document.getAllElements()) {
                for (Attribute attribute : element.attributes().asList()) {
                    String name = attribute.getKey().toLowerCase(Locale.ROOT);
                    String value = attribute.getValue().strip().toLowerCase(Locale.ROOT);
                    if (name.startsWith("on") || ((name.equals("href") || name.equals("src"))
                            && value.startsWith("javascript:"))) {
                        element.removeAttr(attribute.getKey());
                    }
                }
            }
            if (guideDocument) {
                if (!previewChecks.isEmpty()) {
                    document.body().attr("data-dg-check", String.join(" ", previewChecks));
                }
                document.body().appendElement("script")
                        .attr("src", "/js/design-guide-artifact-runtime.js");
            }
            return document.outerHtml();
        } catch (IOException unreadable) {
            throw missing();
        }
    }

    /** 추출기가 선언한 비교 대상은 데이터로만 보존해 Builder 런타임에서 다시 잰다. */
    private static List<String> previewChecks(String source) {
        Matcher declaration = PREVIEW_CHECKS.matcher(source);
        if (!declaration.find()) {
            return List.of();
        }
        Matcher name = PREVIEW_CHECK_NAME.matcher(declaration.group(1));
        List<String> checks = new ArrayList<>();
        while (name.find() && checks.size() < 32) {
            String value = name.group(1);
            if (!checks.contains(value)) {
                checks.add(value);
            }
        }
        return List.copyOf(checks);
    }

    private String relativePath(String projectId, String ticket, HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String full = matched == null ? request.getRequestURI() : matched.toString();
        String prefix = PATH.replace("{projectId}", projectId).replace("{ticket}", ticket) + "/";
        int at = full.indexOf(prefix);
        if (at < 0) {
            throw missing();
        }
        return full.substring(at + prefix.length());
    }

    private static Path resolveInside(Path root, String relative) {
        if (relative.isBlank() || relative.indexOf('\0') >= 0) {
            throw missing();
        }
        try {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                throw missing();
            }
            Path real = realPath(target);
            if (!real.startsWith(root)) {
                throw missing();
            }
            return real;
        } catch (InvalidPathException invalid) {
            throw missing();
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missing) {
            throw missing();
        }
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "디자인 가이드 파일을 찾을 수 없습니다");
    }
}
