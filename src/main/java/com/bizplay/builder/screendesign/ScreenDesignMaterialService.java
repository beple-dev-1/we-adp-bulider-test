package com.bizplay.builder.screendesign;

import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import com.bizplay.builder.solution.SolutionVariant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 기획 저장소의 화면 HTML·화면 명세·IA를 한 번의 생성 입력으로 고정한다. */
@Service
public class ScreenDesignMaterialService {

    private static final int MAX_VARIANTS = 12;
    private static final long MAX_MARKDOWN_BYTES = 2_000_000L;
    private static final long MAX_HTML_BYTES = 5_000_000L;
    private static final long MAX_ASSET_BYTES = 15_000_000L;
    private static final long MAX_ASSET_TOTAL_BYTES = 60_000_000L;
    private static final int MAX_ASSETS = 200;
    private static final long MAX_MANIFEST_BYTES = 2_000_000L;

    private final SolutionMockupService solutions;
    private final SolutionScreenReader screens;
    private final ObjectMapper json;

    public ScreenDesignMaterialService(SolutionMockupService solutions, SolutionScreenReader screens,
                                       ObjectMapper json) {
        this.solutions = solutions;
        this.screens = screens;
        this.json = json;
    }

    public Snapshot snapshot(String projectId, String systemCode, String screenId) {
        List<SolutionScreen> all = solutions.screens(projectId);
        SolutionScreen screen = all.stream()
                .filter(item -> systemCode.equals(item.system()) && screenId.equals(item.screenId()))
                .findFirst().orElseThrow(() -> new MaterialException("MISSING_SCREEN"));
        Path core = realDirectory(screens.coreRoot(projectId), "MISSING_CORE");
        Path mdPath = safeFile(core, screens.fileInClone(projectId,
                systemCode + "/pages/" + screenId + ".md"), "MISSING_MD", MAX_MARKDOWN_BYTES);
        String md = read(mdPath, "MISSING_MD", MAX_MARKDOWN_BYTES);
        List<VariantMaterial> variants = new ArrayList<>();
        if (screen.hasVariants()) {
            if (screen.variants().size() > MAX_VARIANTS) throw new MaterialException("INPUT_TOO_LARGE");
            for (SolutionVariant variant : screen.variants()) {
                Path file = safeFile(core, screens.fileInClone(projectId, screen.previewPath(variant.code())),
                        "MISSING_HTML", MAX_HTML_BYTES);
                variants.add(new VariantMaterial(variant.code(), variant.name(), file,
                        read(file, "MISSING_HTML", MAX_HTML_BYTES)));
            }
        } else {
            Path file = safeFile(core, screens.fileInClone(projectId, screen.previewPath(null)),
                    "MISSING_HTML", MAX_HTML_BYTES);
            variants.add(new VariantMaterial("default", "기본 화면", file,
                    read(file, "MISSING_HTML", MAX_HTML_BYTES)));
        }
        String context = context(all, screen);
        String fingerprint = fingerprint(core, md, context, variants);
        return new Snapshot(screen, List.copyOf(variants), md, context, core, fingerprint);
    }

    private String context(List<SolutionScreen> all, SolutionScreen screen) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("screenId", screen.screenId());
        value.put("screenName", displayName(screen));
        value.put("systemCode", screen.system());
        value.put("menuPath", text(screen.menuPath()));
        value.put("summary", text(screen.summary()));
        value.put("parentScreenId", text(screen.parentScreenId()));
        value.put("openingScreenIds", screen.openingScreenIds());
        value.put("screenIndex", all.stream().filter(item -> screen.system().equals(item.system()))
                .map(item -> Map.of("screenId", item.screenId(), "screenName", displayName(item),
                        "menuPath", text(item.menuPath()))).toList());
        try {
            return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new MaterialException("INVALID_CONTEXT");
        }
    }

    private String fingerprint(Path core, String md, String context,
                               List<VariantMaterial> variants) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, md);
            update(digest, context);
            for (VariantMaterial variant : variants) {
                update(digest, variant.code());
                update(digest, variant.html());
            }
            long assetBytes = 0;
            for (Path asset : referencedAssets(core, variants)) {
                assetBytes += Files.size(asset);
                if (assetBytes > MAX_ASSET_TOTAL_BYTES) throw new IOException("assets too large");
                update(digest, core.relativize(asset).toString().replace('\\', '/'));
                digest.update(Files.readAllBytes(asset));
                digest.update((byte) 0);
            }
            Path manifest = core.getParent().resolve("manifest.json").normalize();
            if (Files.isRegularFile(manifest) && manifest.toRealPath().startsWith(core.getParent().toRealPath())) {
                if (Files.size(manifest) > MAX_MANIFEST_BYTES) throw new IOException("manifest too large");
                update(digest, "manifest.json");
                digest.update(Files.readAllBytes(manifest));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException unreadable) {
            throw new MaterialException("FINGERPRINT_FAILED");
        }
    }

    /** 선택 HTML이 직접 참조한 로컬 자산과 CSS가 다시 참조한 자산만 지문에 넣는다. */
    private static List<Path> referencedAssets(Path core, List<VariantMaterial> variants) throws IOException {
        LinkedHashSet<Path> found = new LinkedHashSet<>();
        List<Path> pendingCss = new ArrayList<>();
        for (VariantMaterial variant : variants) {
            DocumentLinks links = DocumentLinks.of(variant.html());
            for (String reference : links.references()) {
                addAsset(core, variant.sourceFile().getParent(), reference, found, pendingCss);
            }
        }
        for (int index = 0; index < pendingCss.size(); index++) {
            Path css = pendingCss.get(index);
            String body = Files.readString(css, StandardCharsets.UTF_8);
            java.util.regex.Matcher urls = java.util.regex.Pattern.compile("url\\((?:['\\\"])?([^)'\\\"]+)")
                    .matcher(body);
            while (urls.find()) {
                addAsset(core, css.getParent(), urls.group(1), found, pendingCss);
            }
            java.util.regex.Matcher imports = java.util.regex.Pattern
                    .compile("@import\\s+['\\\"]([^'\\\"]+)['\\\"]", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            while (imports.find()) {
                addAsset(core, css.getParent(), imports.group(1), found, pendingCss);
            }
        }
        return found.stream().sorted().toList();
    }

    private static void addAsset(Path core, Path base, String reference,
                                 LinkedHashSet<Path> found, List<Path> pendingCss) throws IOException {
        Path asset = localAsset(core, base, reference);
        if (asset != null && found.add(asset)) {
            if (found.size() > MAX_ASSETS) throw new IOException("too many assets");
            if (asset.toString().toLowerCase().endsWith(".css")) pendingCss.add(asset);
        }
    }

    private static Path localAsset(Path core, Path base, String reference) throws IOException {
        if (reference == null || reference.isBlank() || reference.startsWith("data:")
                || reference.startsWith("http:") || reference.startsWith("https:")
                || reference.startsWith("//") || reference.startsWith("#")) return null;
        String clean = reference.split("[?#]", 2)[0].replace('\\', '/');
        Path candidate = clean.startsWith("/")
                ? core.resolve(clean.substring(1)).normalize()
                : base.resolve(clean).normalize();
        if (!candidate.startsWith(core) || !Files.isRegularFile(candidate)) return null;
        Path real = candidate.toRealPath();
        if (!real.startsWith(core.toRealPath())) return null;
        if (Files.size(real) > MAX_ASSET_BYTES) throw new IOException("asset too large");
        return real;
    }

    private record DocumentLinks(List<String> references) {
        static DocumentLinks of(String html) {
            List<String> values = new ArrayList<>();
            org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);
            document.select("link[href]").forEach(element -> values.add(element.attr("href")));
            document.select("img[src],script[src],source[src],iframe[src]")
                    .forEach(element -> values.add(element.attr("src")));
            return new DocumentLinks(values);
        }
    }

    private static Path realDirectory(Path path, String reason) {
        try {
            Path real = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) throw new MaterialException(reason);
            return real;
        } catch (IOException unreadable) {
            throw new MaterialException(reason);
        }
    }

    private static Path safeFile(Path core, Path candidate, String reason, long maxBytes) {
        try {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!normalized.startsWith(core) || !Files.isRegularFile(normalized)) {
                throw new MaterialException(reason);
            }
            Path real = normalized.toRealPath();
            if (!real.startsWith(core)) throw new MaterialException(reason);
            if (Files.size(real) > maxBytes) throw new MaterialException("INPUT_TOO_LARGE");
            return real;
        } catch (IOException unreadable) {
            throw new MaterialException(reason);
        }
    }

    private static String read(Path file, String reason, long maxBytes) {
        try {
            if (Files.size(file) > maxBytes) throw new MaterialException("INPUT_TOO_LARGE");
            String value = Files.readString(file, StandardCharsets.UTF_8);
            if (value.isBlank()) throw new MaterialException(reason);
            return value;
        } catch (IOException unreadable) {
            throw new MaterialException(reason);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static String displayName(SolutionScreen screen) {
        return screen.screenName() == null || screen.screenName().isBlank()
                ? screen.screenId() : screen.screenName();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public record Snapshot(SolutionScreen screen, List<VariantMaterial> variants, String md,
                           String contextJson, Path coreRoot, String fingerprint) { }

    public record VariantMaterial(String code, String label, Path sourceFile, String html) { }

    public static final class MaterialException extends RuntimeException {
        private final String reason;

        MaterialException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
