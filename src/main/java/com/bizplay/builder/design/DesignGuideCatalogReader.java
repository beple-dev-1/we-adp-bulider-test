package com.bizplay.builder.design;

import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectSystemService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 추출기가 만든 디자인 시스템 계약을 Builder 화면 모델로 읽는다.
 *
 * <p>Builder는 추출기의 검수용 {@code index.html}을 제품 화면에 포함하지 않는다. 대신 구조화된
 * JSON, 범위가 격리된 스타일시트와 정적 HTML fragment만 읽는다. fragment는 계획 저장소에서 온
 * 외부 산출물이므로 script와 이벤트 속성을 제거한 뒤에만 템플릿에 넣는다.</p>
 */
@Component
public class DesignGuideCatalogReader {

    private static final String SCHEMA = "we-adk-design-guide/7";

    private final ObjectMapper json;
    private final PlanningManifestReader manifests;
    private final ProjectPaths paths;
    private final ProjectSystemService projectSystems;
    private final DesignSystemCurationService curations;

    public DesignGuideCatalogReader(ObjectMapper json, PlanningManifestReader manifests,
                                    ProjectPaths paths, ProjectSystemService projectSystems,
                                    DesignSystemCurationService curations) {
        this.json = json;
        this.manifests = manifests;
        this.paths = paths;
        this.projectSystems = projectSystems;
        this.curations = curations;
    }

    public Catalog read(String projectId) {
        Path guideDirectory = manifests.designGuideDirectory(projectId);
        Path contract = guideDirectory.resolve("design-guide.json");
        if (!Files.isRegularFile(contract)) {
            return Catalog.notReady("최신 디자인 시스템 산출물이 없습니다.");
        }

        try {
            Path clone = paths.cloneDir(projectId).toRealPath();
            Path guide = guideDirectory.toRealPath();
            if (!guide.startsWith(clone)) {
                return Catalog.notReady("디자인 시스템 경로를 확인할 수 없습니다.");
            }

            JsonNode root = json.readTree(contract.toFile());
            if (!SCHEMA.equals(root.path("schema").asText()) || !root.path("systems").isObject()) {
                return Catalog.notReady("최신 디자인 시스템 산출물이 아닙니다.");
            }

            Map<String, String> labels = projectSystems.labels(projectId).byCode();
            List<SystemGuide> systems = new ArrayList<>();
            for (PlanningManifestReader.ManifestSystem declared : manifests.systems(projectId)) {
                JsonNode source = root.path("systems").path(declared.id());
                if (!source.isObject()) {
                    continue;
                }
                SystemGuide system = systemOf(declared.id(), labels.getOrDefault(declared.id(), declared.id()),
                        source, guide, clone, curations.read(projectId, declared.id()));
                if (system != null) {
                    systems.add(system);
                }
            }
            if (systems.isEmpty()) {
                return Catalog.notReady("표시할 디자인 시스템이 없습니다.");
            }
            return new Catalog(true, List.copyOf(systems), null);
        } catch (IOException unreadable) {
            return Catalog.notReady("디자인 시스템 산출물을 읽을 수 없습니다.");
        }
    }

    private SystemGuide systemOf(String id, String label, JsonNode source, Path guide, Path clone,
                                 DesignSystemCurationService.Snapshot curation) throws IOException {
        List<StyleAsset> styles = stylesOf(source.path("styles"), guide);
        if (styles.isEmpty()) {
            return null;
        }
        Map<String, StyleAsset> stylesById = styles.stream()
                .collect(java.util.stream.Collectors.toMap(StyleAsset::id, style -> style,
                        (first, ignored) -> first, LinkedHashMap::new));

        Map<String, String> itemLabels = new LinkedHashMap<>();
        List<GuideItem> components = new ArrayList<>();
        int sourceOrder = 0;
        for (JsonNode component : arrayOf(source.path("components"))) {
            String componentId = text(component, "id");
            String itemLabel = text(component, "label");
            if (itemLabel == null) {
                itemLabel = componentId;
            }
            String category = Objects.requireNonNullElse(text(component, "category"), "etc");
            DesignSystemCurationService.ComponentRule rule = curation.component(componentId);
            if (rule != null) {
                itemLabel = rule.label();
                category = rule.category();
            }
            if (componentId != null && itemLabel != null) {
                itemLabels.put(componentId, itemLabel);
            }
            GuideItem item = itemOf(componentId, itemLabel, category, text(component, "description"), component,
                    stylesById, guide, clone, count(component.path("usage").path("count")), rule,
                    rule == null ? sourceOrder : rule.displayOrder());
            if (item != null) {
                components.add(item);
            }
            sourceOrder++;
        }
        components.sort(java.util.Comparator.comparingInt(GuideItem::displayOrder));

        List<GuideItem> compositions = new ArrayList<>();
        for (JsonNode composition : arrayOf(source.path("compositions"))) {
            String compositionId = text(composition, "id");
            String compositionLabel = text(composition, "label");
            if (compositionId != null && compositionLabel != null) {
                itemLabels.put(compositionId, compositionLabel);
            }
            GuideItem item = itemOf(compositionId, compositionLabel, "composition",
                    text(composition, "description"), composition,
                    stylesById, guide, clone, count(composition.path("usage").path("count")), null, 0);
            if (item != null) {
                compositions.add(item);
            }
        }
        preferCompleteModalSpecimen(components, compositions);

        List<LayoutGuide> layouts = new ArrayList<>();
        for (JsonNode layout : arrayOf(source.path("layouts"))) {
            List<Specimen> specimens = new ArrayList<>();
            for (JsonNode variant : arrayOf(layout.path("variants"))) {
                Specimen specimen = specimenOf(text(variant, "id"), text(variant, "label"),
                        variant.path("specimen"), stylesById, guide, null);
                if (specimen != null) {
                    specimens.add(specimen);
                }
            }
            if (!specimens.isEmpty()) {
                layouts.add(new LayoutGuide(text(layout, "id"), text(layout, "label"),
                        text(layout, "kind"), text(layout, "description"),
                        count(layout.path("usage").path("count")), regionsOf(layout.path("regions")),
                        List.copyOf(specimens), evidenceOf(layout.path("evidence"))));
            }
        }

        List<TemplateGuide> templates = new ArrayList<>();
        for (JsonNode template : arrayOf(source.path("templates"))) {
            GuideItem item = itemOf(text(template, "id"), text(template, "label"), "template",
                    text(template, "purpose"), template, stylesById, guide, clone,
                    count(template.path("usage").path("count")), null, 0);
            if (item != null) {
                templates.add(new TemplateGuide(item, text(template, "purpose"), text(template, "layoutId"),
                        componentNames(template.path("componentIds"), itemLabels),
                        regionsOf(template.path("regions")), representativeScreensOf(template.path("representativeScreens"), clone)));
            }
        }

        return new SystemGuide(id, label, styles, foundationsOf(source.path("foundations")),
                List.copyOf(components), List.copyOf(compositions), List.copyOf(layouts), List.copyOf(templates),
                curation.version());
    }

    /**
     * 모달 원자 조각이 런타임 데이터에 의존해 비어 보이면, 같은 시스템에서 렌더 검증을 통과한
     * 완결 확인 모달 구성을 표본으로 사용한다. 원본 산출물의 DOM·CSS·근거는 그대로 유지한다.
     */
    private static void preferCompleteModalSpecimen(List<GuideItem> components,
                                                     List<GuideItem> compositions) {
        GuideItem completeModal = compositions.stream()
                .filter(item -> "modal-confirm".equals(item.id()))
                .findFirst().orElse(null);
        if (completeModal == null) {
            return;
        }
        for (int index = 0; index < components.size(); index++) {
            GuideItem component = components.get(index);
            if (!"modal".equals(component.category())) {
                continue;
            }
            components.set(index, new GuideItem(component.id(), component.label(), component.category(),
                    component.description(), component.variants(), component.states(), completeModal.specimen(),
                    completeModal.sourceScreen(), completeModal.evidence(), component.usageCount(),
                    component.hidden(), component.displayOrder()));
        }
    }

    private List<StyleAsset> stylesOf(JsonNode source, Path guide) throws IOException {
        List<StyleAsset> styles = new ArrayList<>();
        for (JsonNode style : arrayOf(source)) {
            String id = text(style, "id");
            String css = safeRelativeFile(guide, text(style, "css"));
            if (id == null || css == null) {
                continue;
            }
            String facet = text(style, "facet");
            styles.add(new StyleAsset(id, css, facet, facet == null ? "기본" : facet));
        }
        return List.copyOf(styles);
    }

    private GuideItem itemOf(String id, String label, String category, String description, JsonNode source,
                             Map<String, StyleAsset> styles, Path guide, Path clone, int usageCount,
                             DesignSystemCurationService.ComponentRule rule, int displayOrder)
            throws IOException {
        Specimen specimen = specimenOf(id, label, source.path("specimen"), styles, guide, rule);
        if (id == null || specimen == null) {
            return null;
        }
        String sourceScreen = safeSourceScreen(clone, firstText(source, "sourceScreen",
                source.path("evidence"), "sourceScreen"));
        Evidence evidence = evidenceOf(source.path("evidence"));
        List<Variant> variants = variantsOf(source.path("variants"), rule);
        List<ComponentState> states = variants.stream().flatMap(variant -> variant.states().stream())
                .collect(java.util.stream.Collectors.toMap(ComponentState::id, state -> state,
                        (first, ignored) -> first, LinkedHashMap::new)).values().stream().toList();
        return new GuideItem(id, label == null ? id : label, category, description,
                variants, states, specimen, sourceScreen, evidence, usageCount,
                rule != null && rule.hidden(), displayOrder);
    }

    private Specimen specimenOf(String id, String label, JsonNode source,
                                Map<String, StyleAsset> styles, Path guide,
                                DesignSystemCurationService.ComponentRule rule) throws IOException {
        if (!"visible".equals(text(source, "status"))) {
            return null;
        }
        String htmlPath = safeRelativeFile(guide, text(source, "html"));
        String styleId = text(source, "systemStyleId");
        if (htmlPath == null || styleId == null || !styles.containsKey(styleId)) {
            return null;
        }
        String fragment = sanitizedFragment(guide.resolve(htmlPath), guide.getParent());
        if (fragment.isBlank()) {
            return null;
        }
        fragment = curatedFragment(fragment, rule);
        return new Specimen(id, label == null ? id : label, fragment, styleId,
                boundedPositive(source.path("width"), 48, 2400),
                boundedPositive(source.path("height"), 32, 2400));
    }

    private static List<Variant> variantsOf(JsonNode source, DesignSystemCurationService.ComponentRule rule) {
        return arrayOf(source).stream().map(variant -> {
                    String id = text(variant, "id");
                    DesignSystemCurationService.VariantRule variantRule = rule == null ? null : rule.variant(id);
                    String label = variantRule != null && variantRule.label() != null
                            ? variantRule.label() : text(variant, "label");
                    return new Variant(id, label, text(variant, "class"), statesOf(variant.path("states")),
                            variantRule != null && variantRule.hidden());
                })
                .filter(variant -> variant.id() != null).toList();
    }

    private static String curatedFragment(String fragment, DesignSystemCurationService.ComponentRule rule) {
        if (rule == null || rule.variants().isEmpty()) {
            return fragment;
        }
        Document document = Jsoup.parseBodyFragment(fragment);
        for (Element cell : document.select(".dg-sp-cell[data-dg-variant]")) {
            DesignSystemCurationService.VariantRule variant = rule.variant(cell.attr("data-dg-variant"));
            if (variant == null) {
                continue;
            }
            if (variant.hidden()) {
                cell.remove();
            } else if (variant.label() != null) {
                Element tag = cell.selectFirst(".dg-sp-tag");
                if (tag != null) {
                    tag.text(variant.label());
                }
            }
        }
        return document.body().html();
    }

    private Foundations foundationsOf(JsonNode source) {
        List<ColorRole> colors = new ArrayList<>();
        for (JsonNode role : arrayOf(source.path("colorRoles").path("roles"))) {
            String label = text(role, "label");
            List<String> values = arrayOf(role.path("entries")).stream()
                    .map(entry -> text(entry, "value")).filter(Objects::nonNull).limit(6).toList();
            if (label != null && !values.isEmpty()) {
                colors.add(new ColorRole(label, values));
            }
        }
        JsonNode typography = source.path("typography");
        List<FontFamily> fonts = arrayOf(typography.path("fonts")).stream()
                .map(font -> new FontFamily(text(font, "family"), texts(font.path("sampleWeights"))))
                .filter(font -> font.family() != null).toList();
        return new Foundations(List.copyOf(colors), List.copyOf(fonts),
                topMeasures(typography.path("sizes")), topMeasures(typography.path("weights")),
                topMeasures(source.path("spacing")), topMeasures(source.path("radius")),
                effectValues(source.path("effects")), texts(source.path("breakpoints")));
    }

    private static List<String> topMeasures(JsonNode source) {
        return arrayOf(source).stream().map(value -> text(value, "value"))
                .filter(Objects::nonNull).filter(value -> !value.equals("0") && !value.startsWith("0 ")
                        && !value.contains("!important"))
                .limit(8).toList();
    }

    private static List<String> effectValues(JsonNode source) {
        List<String> values = new ArrayList<>();
        for (String key : List.of("shadows", "blurs", "gradients")) {
            for (JsonNode effect : arrayOf(source.path(key))) {
                String value = text(effect, "value");
                if (value != null && !values.contains(value)) {
                    values.add(value);
                }
                if (values.size() == 6) {
                    return List.copyOf(values);
                }
            }
        }
        return List.copyOf(values);
    }

    private static List<String> componentNames(JsonNode source, Map<String, String> labels) {
        return arrayOf(source).stream()
                .map(component -> component.isTextual() ? component.asText() : text(component, "componentId"))
                .filter(Objects::nonNull).map(id -> labels.getOrDefault(id, id)).toList();
    }

    private static List<ComponentState> statesOf(JsonNode source) {
        return arrayOf(source).stream()
                .map(state -> new ComponentState(text(state, "state"), text(state, "label"),
                        text(state, "selector"), state.path("renderable").asBoolean(false)))
                .filter(state -> state.label() != null).toList();
    }

    private static List<Region> regionsOf(JsonNode source) {
        return arrayOf(source).stream().map(region -> {
            JsonNode behavior = region.path("behavior");
            JsonNode measured = behavior.path("measured");
            String size = measured.isObject() && measured.path("visible").asBoolean(false)
                    ? measured.path("width").asInt() + " × " + measured.path("height").asInt()
                    : null;
            return new Region(text(region, "id"), text(region, "label"),
                    region.path("required").asBoolean(false), text(region, "componentId"),
                    text(behavior, "position"), size);
        }).filter(region -> region.label() != null).toList();
    }

    private static List<RepresentativeScreen> representativeScreensOf(JsonNode source, Path clone) {
        List<RepresentativeScreen> screens = new ArrayList<>();
        for (JsonNode screen : arrayOf(source)) {
            try {
                String path = safeSourceScreen(clone, text(screen, "path"));
                if (path != null) {
                    screens.add(new RepresentativeScreen(text(screen, "id"), path, text(screen, "purpose")));
                }
            } catch (IOException ignored) {
                // 외부 산출물의 잘못된 경로는 사용자 화면에 노출하지 않는다.
            }
        }
        return List.copyOf(screens);
    }

    private static String firstText(JsonNode first, String firstField, JsonNode second, String secondField) {
        String value = text(first, firstField);
        return value == null ? text(second, secondField) : value;
    }

    private static Evidence evidenceOf(JsonNode source) {
        JsonNode range = source.path("range").isObject() ? source.path("range") : source;
        String file = text(range, "file");
        int from = range.path("from").asInt(0);
        int to = range.path("to").asInt(0);
        return file == null || from < 1 || to < from ? null : new Evidence(file, from, to);
    }

    private static String safeSourceScreen(Path clone, String source) throws IOException {
        if (source == null || !source.startsWith("core/")) {
            return null;
        }
        Path candidate = clone.resolve(source).normalize();
        if (!candidate.startsWith(clone) || !Files.isRegularFile(candidate)
                || !candidate.toRealPath().startsWith(clone)) {
            return null;
        }
        return source;
    }

    private static String safeRelativeFile(Path root, String source) throws IOException {
        if (source == null || source.isBlank()) {
            return null;
        }
        Path candidate = root.resolve(source).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)
                || !candidate.toRealPath().startsWith(root)) {
            return null;
        }
        return source.replace('\\', '/');
    }

    private static String sanitizedFragment(Path fragment, Path cloneRoot) throws IOException {
        Document document = Jsoup.parseBodyFragment(Files.readString(fragment));
        document.select("script, iframe, object, embed, link, base, meta").remove();
        document.select("form").forEach(form -> {
            form.removeAttr("action");
            form.removeAttr("method");
            form.removeAttr("target");
        });
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
        document.select("[src]").forEach(element -> {
            String source = element.attr("src").strip();
            if (source.isBlank()) {
                element.removeAttr("src");
                return;
            }
            if (source.startsWith("data:") || source.startsWith("blob:")
                    || source.startsWith("http://") || source.startsWith("https://")
                    || source.startsWith("//") || source.startsWith("/")) {
                return;
            }
            String pathOnly = source.split("[?#]", 2)[0];
            Path resolved = fragment.getParent().resolve(pathOnly).normalize();
            if (resolved.startsWith(cloneRoot) && Files.isRegularFile(resolved)) {
                element.attr("data-dg-artifact-src", cloneRoot.relativize(resolved).toString().replace('\\', '/'));
            }
            element.removeAttr("src");
        });
        return document.body().html();
    }

    private static int boundedPositive(JsonNode value, int min, int max) {
        return Math.min(max, Math.max(min, value.asInt(min)));
    }

    private static int count(JsonNode source) {
        return Math.max(0, source.asInt(0));
    }

    private static String text(JsonNode source, String field) {
        String value = source.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<JsonNode> arrayOf(JsonNode source) {
        if (!source.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        source.forEach(values::add);
        return values;
    }

    private static List<String> texts(JsonNode source) {
        return arrayOf(source).stream().filter(JsonNode::isValueNode)
                .map(JsonNode::asText).filter(value -> value != null && !value.isBlank()).toList();
    }

    public record Catalog(boolean ready, List<SystemGuide> systems, String reason) {
        static Catalog notReady(String reason) {
            return new Catalog(false, List.of(), reason);
        }
    }

    public record SystemGuide(String id, String label, List<StyleAsset> styles, Foundations foundations,
                              List<GuideItem> components, List<GuideItem> compositions, List<LayoutGuide> layouts,
                              List<TemplateGuide> templates, int curationVersion) {
        public int componentCount() {
            return (int) components.stream().filter(component -> !component.hidden()
                    && component.variants().stream().anyMatch(variant -> !variant.hidden())).count();
        }

        public List<LayoutGuide> shellLayouts() {
            return layouts.stream().filter(layout -> "shell".equals(layout.kind())).toList();
        }
    }

    public record StyleAsset(String id, String css, String facet, String label) {
    }

    public record Foundations(List<ColorRole> colors, List<FontFamily> fonts, List<String> sizes,
                              List<String> weights, List<String> spacing, List<String> radius,
                              List<String> effects, List<String> breakpoints) {
    }

    public record ColorRole(String label, List<String> values) {
    }

    public record FontFamily(String family, List<String> weights) {
    }

    public record GuideItem(String id, String label, String category, String description,
                            List<Variant> variants, List<ComponentState> states, Specimen specimen,
                            String sourceScreen, Evidence evidence, int usageCount,
                            boolean hidden, int displayOrder) {
        public String categoryLabel() {
            return switch (category) {
                case "button" -> "버튼";
                case "text-field", "input" -> "입력";
                case "textarea" -> "여러 줄 입력";
                case "select" -> "선택";
                case "checkbox" -> "체크박스";
                case "radio" -> "라디오";
                case "switch" -> "스위치";
                case "tabs", "tab" -> "탭";
                case "pagination" -> "페이지 이동";
                case "status" -> "상태 표시";
                case "modal" -> "모달";
                case "table" -> "표";
                default -> "공통 UI";
            };
        }

        public boolean wideSpecimen() {
            return specimen.width() > 900 || specimen.height() > 260;
        }

        public boolean tallSpecimen() {
            return specimen.height() > 640;
        }

        public boolean hasVisibleVariants() {
            return variants.isEmpty() || variants.stream().anyMatch(variant -> !variant.hidden());
        }

        public int visibleVariantCount() {
            return variants.isEmpty() ? 1 : (int) variants.stream().filter(variant -> !variant.hidden()).count();
        }

        public String semanticDescription() {
            return switch (category) {
                case "button" -> "화면의 주요·보조 행동에 사용하는 버튼입니다.";
                case "text-field", "input" -> "한 줄 정보와 검색 조건을 입력하는 필드입니다.";
                case "textarea" -> "여러 줄 정보를 입력하거나 확인하는 영역입니다.";
                case "select" -> "정해진 선택지에서 값을 고르는 입력입니다.";
                case "checkbox" -> "여러 항목을 독립적으로 선택하는 컨트롤입니다.";
                case "radio" -> "여러 선택지 중 하나를 고르는 컨트롤입니다.";
                case "switch" -> "기능의 사용 여부를 즉시 전환하는 컨트롤입니다.";
                case "tabs", "tab" -> "같은 맥락의 콘텐츠를 구분해 이동하는 탐색입니다.";
                case "pagination" -> "목록의 페이지를 이동하는 탐색입니다.";
                case "status" -> "처리 결과와 현재 상태를 구분해 보여 줍니다.";
                case "modal" -> "현재 흐름 위에서 확인이나 짧은 작업을 처리합니다.";
                case "table" -> "여러 건의 업무 정보를 행과 열로 비교합니다.";
                default -> description;
            };
        }
    }

    public record Variant(String id, String label, String cssClass, List<ComponentState> states, boolean hidden) {
    }

    public record ComponentState(String id, String label, String selector, boolean renderable) {
    }

    public record Specimen(String id, String label, String html, String styleId, int width, int height) {
    }

    public record LayoutGuide(String id, String label, String kind, String description, int usageCount,
                              List<Region> regions, List<Specimen> specimens, Evidence evidence) {
    }

    public record TemplateGuide(GuideItem item, String purpose, String layoutId,
                                List<String> components, List<Region> regions,
                                List<RepresentativeScreen> representativeScreens) {
    }

    public record Region(String id, String label, boolean required, String componentId,
                         String position, String size) {
    }

    public record RepresentativeScreen(String id, String path, String purpose) {
    }

    public record Evidence(String file, int from, int to) {
    }
}
