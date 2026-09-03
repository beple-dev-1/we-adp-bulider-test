package com.bizplay.builder.screendesign;

import com.bizplay.builder.screendesign.ScreenDesignContent.Callout;
import com.bizplay.builder.screendesign.ScreenDesignContent.Capture;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.Snapshot;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.VariantMaterial;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 설치된 Playwright Chromium으로 기본 화면과 명시 variant를 PNG·PDF로 캡처한다. */
@Component
public class ScreenCaptureRunner {

    private static final String TARGETS = "button,a,input,select,textarea,[role=button],[role=tab]";
    private static final int MAX_CALLOUTS = 40;
    private static final int SCREEN_VIEWPORT_WIDTH = 1600;
    private static final int MAX_SCREEN_CAPTURE_HEIGHT = 16_000;
    private static final long MAX_SCREEN_CAPTURE_BYTES = 12_000_000L;
    private static final long MAX_SCREEN_BUNDLE_BYTES = 60_000_000L;
    private static final int MAX_SCREEN_VARIANTS = 12;
    private static final int MANUAL_VIEWPORT_WIDTH = 1440;
    private static final int MANUAL_VIEWPORT_HEIGHT = 1000;
    private static final int MAX_MANUAL_CAPTURE_HEIGHT = 16_000;
    private static final long MAX_MANUAL_CAPTURE_BYTES = 12_000_000L;
    private static final double MANUAL_RESOURCE_WAIT_MILLIS = 10_000;
    private static final String MANUAL_IMAGE_FILE = "manual-preview.png";
    private static final Semaphore BROWSER_SLOT = new Semaphore(1, true);

    /**
     * ⭐ <b>실물에서 발견 (2026-08-28).</b> Playwright 자바는 {@link Playwright#create()} 때마다
     * 브라우저 셋(chromium·firefox·webkit)이 다 있는지 재고, 없으면 <b>그 자리에서 내려받는다.</b>
     * 우리는 chromium 하나만 쓰는데, 서버가 firefox 를 받으러 나가다 실패하면 그 실패가
     * {@code BROWSER_UNAVAILABLE} 로 뭉뚱그려져 「브라우저가 설치되지 않았습니다」로 뜬다 —
     * chromium 은 멀쩡히 깔려 있는데도.
     *
     * <p>⛔ 이 플래그를 지우지 마라. 설계가 「앱 기동 중 브라우저를 내려받지 않는다」로 정한 자리다
     * (정본: {@code docs/superpowers/specs/2026-08-27-screen-design-generation-design.md}).
     * 배포 단계에서 {@code install chromium} 으로 한 번 깔아 두는 것이 전제다.
     *
     * <p>⚠ 사내망처럼 TLS 를 가로채는 자리에서는 그 다운로드가 <b>반드시</b> 실패한다
     * ({@code SELF_SIGNED_CERT_IN_CHAIN}) — 그래서 이건 성능 문제가 아니라 동작 문제다.
     */
    private static final Playwright.CreateOptions NO_DOWNLOAD = new Playwright.CreateOptions()
            .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
    private static final String VIRTUAL_HOST = "screen-design.invalid";
    private static final Set<String> SERVABLE = Set.of("css", "png", "jpg", "jpeg", "gif", "svg", "webp",
            "ico", "woff", "woff2", "ttf", "eot");
    private static volatile String pdfFontData;

    public CaptureResult capture(Snapshot snapshot, Path output) throws IOException {
        if (snapshot.variants().isEmpty() || snapshot.variants().size() > MAX_SCREEN_VARIANTS) {
            throw new IOException("화면설계서 변형 수가 허용 범위를 넘습니다.");
        }
        Files.createDirectories(output);
        List<Capture> captures = new ArrayList<>();
        boolean acquired = false;
        try {
            BROWSER_SLOT.acquire();
            acquired = true;
            try (Playwright playwright = Playwright.create(NO_DOWNLOAD);
                 Browser browser = playwright.chromium().launch(new BrowserTypeOptions().options())) {
                for (int i = 0; i < snapshot.variants().size(); i++) {
                    captures.add(captureOne(browser, snapshot, snapshot.variants().get(i), i + 1, output));
                }
                validateBundleSize(output);
                writeCombinedPdf(browser, ScreenDesignContentAssembler.assemble(snapshot, captures), output);
                validateBundleSize(output);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CaptureException("CAPTURE_INTERRUPTED", interrupted);
        } catch (RuntimeException unavailable) {
            throw new CaptureException("BROWSER_UNAVAILABLE", unavailable);
        } finally {
            if (acquired) BROWSER_SLOT.release();
        }
        return new CaptureResult(List.copyOf(captures), manifest(output));
    }

    /** 사용자 매뉴얼에 넣을 대표 화면 한 장을 표식 없이 캡처한다. */
    public ManualCapture captureManualPreview(Snapshot snapshot, Path output) throws IOException {
        VariantMaterial representative = representativeVariant(snapshot);
        Files.createDirectories(output);
        Path image = output.resolve(MANUAL_IMAGE_FILE);
        boolean acquired = false;
        try {
            BROWSER_SLOT.acquire();
            acquired = true;
            try (Playwright playwright = Playwright.create(NO_DOWNLOAD);
                 Browser browser = playwright.chromium().launch(new BrowserTypeOptions().options())) {
                return captureManualPreview(browser, snapshot, representative, image);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(image);
            throw new CaptureException("CAPTURE_INTERRUPTED", interrupted);
        } catch (IOException rejected) {
            Files.deleteIfExists(image);
            throw rejected;
        } catch (RuntimeException unavailable) {
            Files.deleteIfExists(image);
            throw new CaptureException("BROWSER_UNAVAILABLE", unavailable);
        } finally {
            if (acquired) BROWSER_SLOT.release();
        }
    }

    private ManualCapture captureManualPreview(Browser browser, Snapshot snapshot,
                                               VariantMaterial variant, Path image) throws IOException {
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(MANUAL_VIEWPORT_WIDTH, MANUAL_VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1))) {
            context.route("**/*", route -> serve(snapshot.coreRoot(), route));
            Page page = context.newPage();
            page.setContent(captureHtml(snapshot.coreRoot(), variant), new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.LOAD).setTimeout(20_000));
            waitForManualResources(page);
            validateManualHeight(MANUAL_VIEWPORT_HEIGHT);
            page.screenshot(new Page.ScreenshotOptions().setPath(image).setFullPage(false));
            validateManualFile(image);
            return new ManualCapture(variant.code(), variant.label(), MANUAL_IMAGE_FILE,
                    MANUAL_VIEWPORT_WIDTH, MANUAL_VIEWPORT_HEIGHT, sha256(image));
        }
    }

    /** 기본 화면이 있으면 기본 화면, 없으면 저장소가 정한 첫 변형을 대표 화면으로 쓴다. */
    static VariantMaterial representativeVariant(Snapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.variants() == null || snapshot.variants().isEmpty()) {
            throw new IOException("사용자 매뉴얼 대표 화면을 찾을 수 없습니다.");
        }
        return snapshot.variants().stream()
                .filter(variant -> "default".equals(variant.code()))
                .findFirst().orElse(snapshot.variants().get(0));
    }

    private static void waitForManualResources(Page page) {
        page.waitForFunction("""
                () => (!document.fonts || document.fonts.status === 'loaded')
                  && Array.from(document.images).every(image => image.complete)
                """, null, new Page.WaitForFunctionOptions().setTimeout(MANUAL_RESOURCE_WAIT_MILLIS));
    }

    private static int documentHeight(Page page) {
        return ((Number) page.evaluate(
                "Math.max(document.body.scrollHeight,document.documentElement.scrollHeight)")).intValue();
    }

    static void validateManualHeight(int height) throws IOException {
        if (height <= 0 || height > MAX_MANUAL_CAPTURE_HEIGHT) {
            throw new IOException("사용자 매뉴얼 화면 캡처의 세로 길이가 허용 범위를 넘습니다.");
        }
    }

    static void validateManualFile(Path image) throws IOException {
        long size = Files.size(image);
        if (size <= 0 || size > MAX_MANUAL_CAPTURE_BYTES) {
            throw new IOException("사용자 매뉴얼 화면 캡처 파일이 허용 크기를 넘습니다.");
        }
    }

    private Capture captureOne(Browser browser, Snapshot snapshot, VariantMaterial variant,
                               int number, Path output) throws IOException {
        String stem = "screen-" + number;
        String imageFile = stem + ".png";
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(SCREEN_VIEWPORT_WIDTH, 1000).setDeviceScaleFactor(1))) {
            context.route("**/*", route -> serve(snapshot.coreRoot(), route));
            Page page = context.newPage();
            page.setContent(captureHtml(snapshot.coreRoot(), variant), new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.LOAD).setTimeout(20_000));
            int height = ((Number) page.evaluate(
                    "Math.max(document.body.scrollHeight,document.documentElement.scrollHeight)")).intValue();
            validateScreenHeight(height);
            List<Callout> callouts = mark(page);
            Path image = output.resolve(imageFile);
            page.screenshot(new Page.ScreenshotOptions().setPath(image).setFullPage(true));
            validateScreenFile(image);
            int width = page.viewportSize() == null ? SCREEN_VIEWPORT_WIDTH : page.viewportSize().width;
            return new Capture(variant.code(), variant.label(), imageFile, "", width, height, callouts);
        }
    }

    static String captureHtml(Path coreRoot, VariantMaterial variant) {
        Document document = Jsoup.parse(variant.html());
        document.select("script,iframe,object,embed,meta[http-equiv=refresh]").remove();
        document.getAllElements().forEach(element -> {
            List<String> remove = element.attributes().asList().stream()
                    .map(org.jsoup.nodes.Attribute::getKey)
                    .filter(key -> key.toLowerCase().startsWith("on")).toList();
            remove.forEach(element::removeAttr);
            for (String attribute : List.of("href", "src", "action", "formaction")) {
                if (element.hasAttr(attribute)
                        && element.attr(attribute).stripLeading().toLowerCase().startsWith("javascript:")) {
                    element.removeAttr(attribute);
                }
            }
        });
        String relative = coreRoot.relativize(variant.sourceFile().getParent())
                .toString().replace('\\', '/');
        document.head().prependElement("base").attr("href",
                "http://" + VIRTUAL_HOST + "/" + (relative.isBlank() ? "" : relative + "/"));
        document.head().appendElement("style").append("""
                .adk-screen-callout{position:absolute;z-index:2147483647;width:24px;height:24px;border-radius:50%;
                display:flex;align-items:center;justify-content:center;background:#087a57;color:#fff;border:2px solid #fff;
                box-shadow:0 1px 5px rgba(0,0,0,.4);font:700 12px Arial;pointer-events:none}
                """);
        return document.outerHtml();
    }

    private static void serve(Path coreRoot, Route route) {
        try {
            URI uri = URI.create(route.request().url());
            if (!"http".equals(uri.getScheme()) || !VIRTUAL_HOST.equals(uri.getHost())) {
                route.abort();
                return;
            }
            String relative = URLDecoder.decode(uri.getRawPath(), StandardCharsets.UTF_8);
            while (relative.startsWith("/")) relative = relative.substring(1);
            Path core = coreRoot.toRealPath();
            Path target = core.resolve(relative).normalize();
            if (!target.startsWith(core) || !Files.isRegularFile(target)
                    || !target.toRealPath().startsWith(core) || !SERVABLE.contains(extension(target))) {
                route.abort();
                return;
            }
            route.fulfill(new Route.FulfillOptions().setStatus(200).setPath(target));
        } catch (Exception denied) {
            route.abort();
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private List<Callout> mark(Page page) {
        Locator targets = page.locator(TARGETS);
        List<Callout> callouts = new ArrayList<>();
        List<Map<String, Object>> markers = new ArrayList<>();
        int count = Math.min(targets.count(), MAX_CALLOUTS * 3);
        for (int i = 0; i < count && callouts.size() < MAX_CALLOUTS; i++) {
            Locator target = targets.nth(i);
            if (!target.isVisible()) continue;
            BoundingBox box = target.boundingBox();
            if (box == null || box.width < 4 || box.height < 4) continue;
            int number = callouts.size() + 1;
            String kind = kind(target);
            String label = label(page, target, kind, number);
            callouts.add(new Callout(number, kind, label, description(kind, label)));
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("number", number);
            marker.put("x", Math.max(0, box.x - 10));
            marker.put("y", Math.max(0, box.y - 10));
            markers.add(marker);
        }
        page.evaluate("""
                markers => markers.forEach(m => {
                  const badge=document.createElement('span'); badge.className='adk-screen-callout';
                  badge.textContent=String(m.number); badge.style.left=m.x+'px'; badge.style.top=m.y+'px';
                  document.body.appendChild(badge);
                })
                """, markers);
        return List.copyOf(callouts);
    }

    private String label(Page page, Locator target, String kind, int number) {
        String value = first(target.getAttribute("aria-label"), target.getAttribute("placeholder"),
                target.getAttribute("title"), clean(target.textContent()), target.getAttribute("value"));
        String id = target.getAttribute("id");
        if ((value == null || value.isBlank()) && id != null && !id.isBlank()) {
            Locator label = page.locator("label[for=\"" + cssEscape(id) + "\"]").first();
            if (label.count() > 0) value = clean(label.textContent());
        }
        return value == null || value.isBlank() ? kind + " " + number : value;
    }

    private static String kind(Locator target) {
        String tag = String.valueOf(target.evaluate("el => el.tagName.toLowerCase()"));
        String type = target.getAttribute("type");
        if ("a".equals(tag)) return "링크";
        if ("select".equals(tag)) return "선택";
        if ("textarea".equals(tag)) return "입력";
        if ("input".equals(tag)) {
            if ("checkbox".equals(type) || "radio".equals(type)) return "선택";
            if ("button".equals(type) || "submit".equals(type)) return "버튼";
            return "입력";
        }
        if ("tab".equals(target.getAttribute("role"))) return "탭";
        return "버튼";
    }

    private static String description(String kind, String label) {
        return switch (kind) {
            case "입력" -> label + "을 입력합니다.";
            case "선택" -> label + "을 선택합니다.";
            case "링크" -> label + "으로 이동합니다.";
            case "탭" -> label + " 내용을 표시합니다.";
            default -> label + " 기능을 실행합니다.";
        };
    }

    private void writeCombinedPdf(Browser browser, ScreenDesignContent content,
                                  Path output) throws IOException {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            StringBuilder html = new StringBuilder(("""
                    <!doctype html><meta charset=utf-8><style>
                    @font-face{font-family:Pretendard;src:url(data:font/woff2;base64,%s) format('woff2');font-weight:100 900}
                    @page{size:A4 landscape;margin:10mm}.screen-design-pdf-capture{break-after:page}
                    img{display:block;max-width:100%%;max-height:120mm;margin:0 auto 8px}
                    .screen-design-pdf-head{padding:18mm 10mm 12mm;border-top:5px solid #172033;border-bottom:1px solid #ccd3dc}
                    .screen-design-pdf-head p{margin:0 0 4mm;color:#596579}.screen-design-pdf-head h1{margin:0 0 5mm;font-size:28px}
                    h1{font:700 16px Pretendard,sans-serif}table{width:100%%;border-collapse:collapse;font:11px Pretendard,sans-serif}
                    th,td{border:1px solid #ccd3dc;padding:5px;text-align:left;vertical-align:top}th{background:#f1f4f7}
                    body{font:11px/1.55 Pretendard,sans-serif;color:#172033}pre{white-space:pre-wrap;overflow-wrap:anywhere}
                    h2{break-after:avoid;border-bottom:2px solid #172033;padding-bottom:4px}tr{break-inside:avoid}
                    .screen-design-document__definitions{display:grid;grid-template-columns:110px 1fr}.screen-design-document__definitions dt,
                    .screen-design-document__definitions dd{margin:0;padding:6px;border-bottom:1px solid #ccd3dc}.screen-design-document__definitions dt{font-weight:700;background:#f1f4f7}</style>
                    """).formatted(pdfFontData()));
            ScreenDesignRenderer renderer = new ScreenDesignRenderer();
            html.append("<header class=\"screen-design-pdf-head\"><p>화면설계서</p><h1>")
                    .append(org.jsoup.nodes.Entities.escape(content.title())).append("</h1><p>")
                    .append(org.jsoup.nodes.Entities.escape(content.systemCode())).append(" · ")
                    .append(org.jsoup.nodes.Entities.escape(content.screenId())).append("</p></header>")
                    .append(renderer.renderOverview(content)).append("<h2>2. 화면 구성</h2>");
            for (int index = 0; index < content.captures().size(); index++) {
                Capture capture = content.captures().get(index);
                String image = "data:image/png;base64," + Base64.getEncoder()
                        .encodeToString(Files.readAllBytes(output.resolve(capture.imageFile())));
                html.append("<section class=\"screen-design-pdf-capture\"><h3>2.")
                        .append(index + 1).append(" ")
                        .append(org.jsoup.nodes.Entities.escape(capture.label()))
                        .append("</h3><img src=\"").append(image).append("\"></section>");
            }
            html.append(renderer.renderBody(content));
            page.setContent(html.toString(), new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
            page.pdf(new Page.PdfOptions().setPath(output.resolve("screen-design.pdf"))
                    .setFormat("A4").setLandscape(true).setPrintBackground(true));
        }
    }

    private static String manifest(Path output) throws IOException {
        StringBuilder json = new StringBuilder("[");
        try (var files = Files.list(output)) {
            boolean first = true;
            for (Path file : files.sorted().toList()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":\"").append(file.getFileName()).append("\",\"sha256\":\"")
                        .append(sha256(file)).append("\",\"size\":").append(Files.size(file)).append('}');
            }
        }
        return json.append(']').toString();
    }

    static void validateScreenHeight(int height) throws IOException {
        if (height <= 0 || height > MAX_SCREEN_CAPTURE_HEIGHT) {
            throw new IOException("화면설계서 캡처의 세로 길이가 허용 범위를 넘습니다.");
        }
    }

    static void validateScreenFile(Path image) throws IOException {
        long size = Files.size(image);
        if (size <= 0 || size > MAX_SCREEN_CAPTURE_BYTES) {
            throw new IOException("화면설계서 캡처 파일이 허용 크기를 넘습니다.");
        }
    }

    private static void validateBundleSize(Path output) throws IOException {
        long total;
        try (var files = Files.list(output)) {
            total = files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException unreadable) {
                    return MAX_SCREEN_BUNDLE_BYTES + 1;
                }
            }).sum();
        }
        if (total > MAX_SCREEN_BUNDLE_BYTES) {
            throw new IOException("화면설계서 캡처 묶음이 허용 크기를 넘습니다.");
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("화면설계서 캡처 해시를 계산할 수 없습니다.", impossible);
        }
    }

    /** PDF 한글 글꼴은 프로세스에서 한 번만 읽어 생성마다 2MB 파일을 다시 인코딩하지 않는다. */
    private static String pdfFontData() throws IOException {
        String cached = pdfFontData;
        if (cached != null) return cached;
        synchronized (ScreenCaptureRunner.class) {
            if (pdfFontData != null) return pdfFontData;
            try (InputStream input = new ClassPathResource("static/fonts/PretendardVariable.woff2")
                    .getInputStream()) {
                pdfFontData = Base64.getEncoder().encodeToString(input.readAllBytes());
                return pdfFontData;
            }
        }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return clean(value);
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String cssEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record CaptureResult(List<Capture> captures, String manifestJson) { }

    /** 사용자 매뉴얼 독립 문서에 넣을 대표 화면 PNG의 확인 정보다. */
    public record ManualCapture(String code, String label, String fileName,
                                int width, int height, String sha256) { }

    public static final class CaptureException extends IOException {
        private final String reason;

        CaptureException(String reason, Throwable cause) {
            super("화면 캡처 브라우저를 실행하지 못했습니다.", cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Playwright 옵션 생성 지점을 좁혀 브라우저 설치를 앱 기동과 분리한다. */
    private static final class BrowserTypeOptions {
        private com.microsoft.playwright.BrowserType.LaunchOptions options() {
            return new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true);
        }
    }
}
