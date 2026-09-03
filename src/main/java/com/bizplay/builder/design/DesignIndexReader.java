package com.bizplay.builder.design;

import com.bizplay.builder.design.DesignIndex.CssFile;
import com.bizplay.builder.design.DesignIndex.SystemDesign;
import com.bizplay.builder.design.DesignIndex.Tally;
import com.bizplay.builder.design.DesignIndex.TokenDeclaration;
import com.bizplay.builder.design.DesignIndex.Typography;
import com.bizplay.builder.project.PlanningManifestReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 클론의 {@code design-index.json} 을 읽는 한 자리.
 *
 * <p>⛔ <b>파일 이름을 여기서 짓지 않는다.</b> 자리의 정본은 {@code manifest.json} 의
 * {@code design-index} 칸이고 그것을 읽는 것은
 * {@link PlanningManifestReader#designIndexFile}이다 — 기관 스킨에서 {@code iks}·{@code tnj} 를
 * 빌더가 알아서는 안 되는 것과 같은 규율이다.
 *
 * <p>⚠ <b>없거나 못 읽으면 던지지 않고 빈 값을 낸다.</b> 갓 나온 레포와 판이 낡은 레포가
 * 실재한다 — 그것 때문에 화면이 깨져서는 안 된다. 부르는 쪽이 「아직 안 왔다」를 그린다.
 * {@link PlanningManifestReader} 와 같은 규율이다.
 */
@Component
public class DesignIndexReader {

    private static final Logger log = LoggerFactory.getLogger(DesignIndexReader.class);

    private final PlanningManifestReader manifests;
    private final ObjectMapper json;

    public DesignIndexReader(PlanningManifestReader manifests, ObjectMapper json) {
        this.manifests = manifests;
        this.json = json;
    }

    public Optional<DesignIndex> read(String projectId) {
        Path file = manifests.designIndexFile(projectId);
        if (!Files.isRegularFile(file)) {
            log.info("design-index 가 없다 projectId={} file={}", projectId, file.getFileName());
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(Files.readString(file));
        } catch (IOException | RuntimeException unreadable) {
            log.info("design-index 를 읽지 못했다 projectId={}", projectId);
            return Optional.empty();
        }
        Map<String, SystemDesign> systems = new LinkedHashMap<>();
        root.path("systems").fields().forEachRemaining(entry ->
                systems.put(entry.getKey(), systemOf(entry.getValue())));
        return Optional.of(new DesignIndex(
                root.path("schema").asText(null), Map.copyOf(systems), countsOf(root.path("counts"))));
    }

    private static DesignIndex.Counts countsOf(JsonNode counts) {
        if (!counts.isObject()) {
            return DesignIndex.Counts.unknown();
        }
        return new DesignIndex.Counts(
                counts.path("systems").asInt(0),
                counts.path("cssFiles").asInt(0),
                counts.path("scannedFiles").asInt(0),
                counts.path("tokenSystems").asInt(0),
                counts.path("emptySystems").asInt(0));
    }

    private static SystemDesign systemOf(JsonNode system) {
        JsonNode tokens = system.path("tokens");
        return new SystemDesign(
                filesOf(system.path("files")),
                tokenMapOf(tokens.path("common")),
                facetTokensOf(tokens.path("byFacet")),
                talliesOf(system.path("colors")),
                talliesOf(system.path("rgba")),
                talliesOf(system.path("radius")),
                typographyOf(system.path("type")));
    }

    private static List<CssFile> filesOf(JsonNode files) {
        if (!files.isArray()) {
            return List.of();
        }
        List<CssFile> found = new ArrayList<>();
        for (JsonNode file : files) {
            String path = file.path("path").asText(null);
            if (path == null || path.isBlank()) continue;
            String facet = file.path("facet").asText(null);
            found.add(new CssFile(path.strip(), file.path("role").asText(""),
                    facet == null || facet.isBlank() ? null : facet.strip()));
        }
        return List.copyOf(found);
    }

    /** {@code 기관 → 토큰명 → 선언들}. */
    private static Map<String, Map<String, List<TokenDeclaration>>> facetTokensOf(JsonNode byFacet) {
        if (!byFacet.isObject()) {
            return Map.of();
        }
        Map<String, Map<String, List<TokenDeclaration>>> found = new LinkedHashMap<>();
        byFacet.fields().forEachRemaining(entry ->
                found.put(entry.getKey(), tokenMapOf(entry.getValue())));
        return Map.copyOf(found);
    }

    /**
     * {@code 토큰명 → 선언들}.
     *
     * <p>⛔ <b>선언이 여럿일 때 하나로 줄이지 마라.</b> 그러면 색인이 소스에 없는 사실을
     * 말하게 되고, 색인 안에서는 일관되니 어떤 검사도 그것을 못 잡는다.
     */
    private static Map<String, List<TokenDeclaration>> tokenMapOf(JsonNode tokens) {
        if (!tokens.isObject()) {
            return Map.of();
        }
        Map<String, List<TokenDeclaration>> found = new LinkedHashMap<>();
        tokens.fields().forEachRemaining(entry -> {
            List<TokenDeclaration> declarations = new ArrayList<>();
            for (JsonNode declaration : entry.getValue()) {
                declarations.add(new TokenDeclaration(
                        declaration.path("scope").asText(""),
                        declaration.path("value").asText(""),
                        declaration.path("file").asText(""),
                        declaration.path("line").asInt(0)));
            }
            found.put(entry.getKey(), List.copyOf(declarations));
        });
        return Map.copyOf(found);
    }

    /** ⚠ <b>정렬을 다시 하지 않는다</b> — 빈도 내림 → 값 오름은 추출기가 계약으로 보증한다. */
    private static List<Tally> talliesOf(JsonNode tallies) {
        if (!tallies.isArray()) {
            return List.of();
        }
        List<Tally> found = new ArrayList<>();
        for (JsonNode tally : tallies) {
            String value = tally.path("value").asText(null);
            if (value == null) continue;
            found.add(new Tally(value, tally.path("n").asInt(0)));
        }
        return List.copyOf(found);
    }

    private static Typography typographyOf(JsonNode type) {
        if (!type.isObject()) {
            return Typography.empty();
        }
        return new Typography(talliesOf(type.path("families")),
                talliesOf(type.path("sizes")), talliesOf(type.path("weights")));
    }
}
