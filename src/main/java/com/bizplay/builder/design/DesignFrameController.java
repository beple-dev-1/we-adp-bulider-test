package com.bizplay.builder.design;

import com.bizplay.builder.design.ShellFragmentReader.ShellFragment;
import com.bizplay.builder.solution.SkinRewriter;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 디자인가이드의 <b>iframe 안쪽</b>을 만들어 낸다 — 소스의 css 를 그대로 입혀서.
 *
 * <p>공통 셸 조각은 추출 화면의 {@code <head>}를 함께 써서 소스 css의 실제 로드 순서를 지킨다.
 * 실제 화면 본문은 이 경로가 아니라 솔루션 목업 미리보기에서 원본 구조 그대로 연다.
 *
 * <p>⛔ <b>css 링크를 우리가 짜 넣지 마라.</b> {@code design-index.json} 의 {@code files} 는
 * 경로 순으로 정렬돼 있고 그건 로드 순서가 아니다. 그것으로 조립하면 캐스케이드가 소스와
 * 달라지고, 화면은 「소스를 보여준다」면서 소스에 없는 모양을 낸다.
 *
 * <p>⛔ <b>새 자산 문을 만들지 마라.</b> {@code <base href>} 로 이미 강화된
 * {@link com.bizplay.builder.solution.SolutionPreviewController} 를 가리킨다 —
 * 클론 밖 차단·확장자 흰 목록·스크립트 금지가 거기 한 자리에 있다. 두 벌로 만들면
 * 한쪽만 고쳐지는 날이 온다.
 *
 * <p>⚠ <b>{@code sandbox} 는 화면 쪽 {@code iframe} 과 같은 글자여야 한다</b> —
 * {@code allow-same-origin} 하나. 그것이 없으면 곁의 css 가 통째로 안 붙는다(출처 없는
 * 문서가 되어 세션 쿠키가 안 붙는다). {@code allow-scripts} 를 더하면 울타리가 스스로
 * 걷힌다 — 더하지 마라.
 */
@Controller
@RequestMapping(DesignFrameController.PATH)
public class DesignFrameController {

    static final String PATH = "/projects/{projectId}/artifacts/design-guide/frame";

    /**
     * {@code SecurityConfig} 가 {@code X-Frame-Options} 를 여기서만 느슨하게 하려고 쓴다.
     *
     * <p>⛔ 위의 {@link #PATH} 와 따로 놀게 두지 마라 — 갈리면 미리보기 칸이 <b>말없이</b>
     * 빈다. 서버는 200 을 내고 브라우저만 안 그린다.
     */
    public static final String URL_PATTERN = "/projects/*/artifacts/design-guide/frame";

    /** 화면의 {@code iframe sandbox} 와 같은 글자여야 한다. */
    public static final String SANDBOX = "allow-same-origin";

    private final ShellFragmentReader shells;
    private final SolutionScreenReader screens;
    private final SkinRewriter skins;

    public DesignFrameController(ShellFragmentReader shells, SolutionScreenReader screens, SkinRewriter skins) {
        this.shells = shells;
        this.screens = screens;
        this.skins = skins;
    }

    /**
     * @param part  {@code sidebar} · {@code header} · {@code footer}
     * @param index 같은 갈래의 조각이 여럿일 때 몇 번째냐(웹뷰 헤더는 넷이다)
     */
    @GetMapping
    public ResponseEntity<String> frame(@PathVariable String projectId,
                                        @RequestParam String system,
                                        @RequestParam(required = false) String facet,
                                        @RequestParam(defaultValue = "sidebar") String part,
                                        @RequestParam(defaultValue = "0") int index) {
        if (!system.matches("^[A-Za-z0-9_-]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시스템 코드의 꼴이 아니다");
        }
        String inner = switch (part) {
            case "sidebar" -> fragmentBody(projectId, system, ShellFragmentReader.Kind.SIDEBAR, index);
            case "header" -> fragmentBody(projectId, system, ShellFragmentReader.Kind.HEADER, index);
            case "footer" -> fragmentBody(projectId, system, ShellFragmentReader.Kind.FOOTER, index);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 갈래가 없다: " + part);
        };
        String document = compose(projectId, system, facet, inner, bodyClassOf(projectId, system));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(document);
    }

    // ── 안쪽 ─────────────────────────────────────────────────────────────

    private String fragmentBody(String projectId, String system,
                                ShellFragmentReader.Kind kind, int index) {
        List<ShellFragment> found = shells.of(projectId, system, kind);
        if (found.isEmpty()) {
            return "<p class=\"dgf-empty\">이 시스템에는 해당 조각이 없습니다.</p>";
        }
        ShellFragment fragment = found.get(Math.floorMod(index, found.size()));
        return "<div class=\"dgf-fragment\">" + fragment.bodyInner() + "</div>";
    }

    // ── 틀 ───────────────────────────────────────────────────────────────

    /**
     * 목업 한 장의 {@code <head>} 링크를 베껴 틀을 만든다.
     *
     * <p>⚠ {@code <base href>} 가 이미 강화된 미리보기 문을 가리키므로 상대 경로가 저절로 맞는다.
     * 기관 치환은 {@link SkinRewriter} 가 그 문과 같은 규칙으로 한다.
     */
    private String compose(String projectId, String system, String facet, String inner, String bodyClass) {
        return compose(projectId, system, facet, inner, bodyClass, null);
    }

    private String compose(String projectId, String system, String facet, String inner, String bodyClass,
                           String sourceFile) {
        String pageDir = "core/" + system + "/pages";
        String headResources = headResourcesOf(projectId, system, sourceFile);
        String base = "/projects/%s/artifacts/solution-mockups/files/%s/pages/".formatted(projectId, system);
        String document = """
                <!doctype html>
                <html lang="ko"><head><meta charset="utf-8">
                <base href="%s">
                %s
                <style>
                  /* 우리가 더하는 최소 보정 — 소스 css 를 덮지 않는 것만 담는다. */
                  html, body { min-width: 0 !important; height: auto !important; overflow: visible !important; background: #fff; }
                  .dgf-group { padding: 18px 20px; border-bottom: 1px solid #e9e9ec; }
                  .dgf-group > h2 { margin: 0 0 2px; font-size: 15px; font-family: system-ui, sans-serif; }
                  .dgf-meta { margin: 0 0 14px; color: #77747c; font-size: 12px; font-family: system-ui, sans-serif; }
                  .dgf-item { margin-bottom: 16px; }
                  .dgf-label { margin-bottom: 6px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline;
                               font-family: ui-monospace, monospace; font-size: 11px; }
                  .dgf-label code { padding: 2px 6px; border-radius: 4px; background: #f3f2f6; color: #3f3b47; }
                  .dgf-label span { color: #9a969f; }
                  .dgf-stage { padding: 14px; border: 1px dashed #ddd; border-radius: 6px; }
                  .dgf-empty { padding: 24px; color: #77747c; font-family: system-ui, sans-serif; }
                  .dgf-fragment { padding: 0; }
                  /* ⭐ 데이터에서 오는 자리를 표시한다 — 값을 지어내지 않고 자리를 보여준다.
                     th:text 가 걸린 요소가 비어 있으면 그 자리는 소스에서 데이터가 채운다. */
                  [th\\:text]:empty::before, [data-th-text]:empty::before {
                    content: "데이터"; padding: 0 6px; border-radius: 3px;
                    background: repeating-linear-gradient(45deg, #efeaf9, #efeaf9 4px, #e3dbf5 4px, #e3dbf5 8px);
                    color: #5b4b86; font-size: 10px; font-family: system-ui, sans-serif;
                  }
                </style>
                </head><body class="%s">%s</body></html>
                """.formatted(escape(base), headResources, escape(bodyClass), inner);
        return skins.draw(projectId, pageDir, facet, document);
    }

    /** 대표 견본을 가져온 화면의 css 연결을 그대로 쓴다. */
    private String headResourcesOf(String projectId, String system, String sourceFile) {
        Path page = sourcePage(projectId, system, sourceFile);
        if (page == null) {
            return "";
        }
        try {
            return Jsoup.parse(Files.readString(page), "")
                    .select("head link[rel=stylesheet], head style").stream()
                    .map(org.jsoup.nodes.Element::outerHtml)
                    .reduce("", (all, one) -> all + one + "\n");
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private String bodyClassOf(String projectId, String system) {
        return bodyClassOf(projectId, system, null);
    }

    private String bodyClassOf(String projectId, String system, String sourceFile) {
        Path page = sourcePage(projectId, system, sourceFile);
        if (page == null) {
            return "";
        }
        try {
            return Jsoup.parse(Files.readString(page), "").body().className();
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private Path sourcePage(String projectId, String system, String sourceFile) {
        Path pages = screens.coreRoot(projectId).resolve(system).resolve("pages");
        if (!Files.isDirectory(pages)) {
            return null;
        }
        if (sourceFile != null && !sourceFile.isBlank()) {
            Path selected = pages.resolve(sourceFile).normalize();
            if (selected.startsWith(pages) && Files.isRegularFile(selected)) {
                return selected;
            }
        }
        try (Stream<Path> walk = Files.list(pages)) {
            Path first = walk.filter(path -> path.getFileName().toString().endsWith(".html"))
                    .sorted().findFirst().orElse(null);
            return first;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String escape(String raw) {
        return org.springframework.web.util.HtmlUtils.htmlEscape(raw == null ? "" : raw);
    }
}
