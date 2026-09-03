package com.bizplay.builder.design;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 추출 후보를 덮어쓰지 않고 Builder가 확정한 의미만 프로젝트별로 저장한다. */
@Service
public class DesignSystemCurationService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Set<String> CATEGORIES = Set.of(
            "button", "text-field", "textarea", "select", "checkbox", "radio", "switch",
            "tabs", "pagination", "status", "modal", "table", "upload", "etc");

    private final DesignSystemCurationMapper mapper;
    private final ObjectMapper json;

    public DesignSystemCurationService(DesignSystemCurationMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public Snapshot read(String projectId, String systemId) {
        DesignSystemCuration stored = mapper.select(projectId, systemId);
        return stored == null ? Snapshot.empty() : snapshotOf(stored);
    }

    @Transactional
    public int saveComponent(String projectId, String systemId, int expectedVersion,
                             ComponentInput input, String updatedBy) {
        requireId(systemId, "시스템");
        requireId(input.id(), "컴포넌트");
        String label = requireLabel(input.label(), "컴포넌트 이름");
        String category = CATEGORIES.contains(input.category()) ? input.category() : "etc";
        int order = Math.max(0, Math.min(999, input.displayOrder()));

        DesignSystemCuration stored = mapper.selectForUpdate(projectId, systemId);
        int currentVersion = stored == null ? 0 : stored.version();
        if (currentVersion != expectedVersion) {
            throw new IllegalStateException("다른 사용자가 디자인 시스템을 먼저 수정했습니다. 새로고침 후 다시 저장해 주세요.");
        }

        Snapshot current = stored == null ? Snapshot.empty() : snapshotOf(stored);
        Map<String, VariantRule> variants = new LinkedHashMap<>();
        for (VariantInput variant : input.variants()) {
            requireId(variant.id(), "variant");
            String variantLabel = optionalLabel(variant.label());
            variants.put(variant.id(), new VariantRule(variantLabel, variant.hidden()));
        }
        Map<String, ComponentRule> components = new LinkedHashMap<>(current.components());
        components.put(input.id(), new ComponentRule(label, category, input.hidden(), order, Map.copyOf(variants)));
        String content = write(new CurationDocument(Map.copyOf(components)));

        if (stored == null) {
            mapper.insert(new DesignSystemCuration(projectId, systemId, content, 1, Instant.now(), updatedBy));
            return 1;
        }
        if (mapper.update(projectId, systemId, content, expectedVersion, updatedBy) != 1) {
            throw new IllegalStateException("디자인 시스템 수정본을 저장하지 못했습니다. 새로고침 후 다시 시도해 주세요.");
        }
        return expectedVersion + 1;
    }

    private Snapshot snapshotOf(DesignSystemCuration stored) {
        try {
            CurationDocument document = json.readValue(stored.contentJson(), CurationDocument.class);
            Map<String, ComponentRule> components = document.components() == null
                    ? Map.of() : Map.copyOf(document.components());
            return new Snapshot(stored.version(), components, stored.updatedAt(), stored.updatedBy());
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("저장된 디자인 시스템 편집 정보를 읽을 수 없습니다.", broken);
        }
    }

    private String write(CurationDocument document) {
        try {
            return json.writeValueAsString(document);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("디자인 시스템 편집 정보를 저장할 수 없습니다.", impossible);
        }
    }

    private static void requireId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 식별자를 확인해 주세요.");
        }
    }

    private static String requireLabel(String value, String field) {
        String label = optionalLabel(value);
        if (label == null) {
            throw new IllegalArgumentException(field + "을 입력해 주세요.");
        }
        return label;
    }

    private static String optionalLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String label = value.strip();
        if (label.length() > 60) {
            throw new IllegalArgumentException("표시 이름은 60자 이내로 입력해 주세요.");
        }
        return label;
    }

    public record Snapshot(int version, Map<String, ComponentRule> components,
                           Instant updatedAt, String updatedBy) {
        static Snapshot empty() {
            return new Snapshot(0, Map.of(), null, null);
        }

        public ComponentRule component(String id) {
            return components.get(id);
        }
    }

    public record ComponentInput(String id, String label, String category, boolean hidden,
                                 int displayOrder, List<VariantInput> variants) {
        public ComponentInput {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    public record VariantInput(String id, String label, boolean hidden) {
    }

    public record ComponentRule(String label, String category, boolean hidden, int displayOrder,
                                Map<String, VariantRule> variants) {
        public ComponentRule {
            variants = variants == null ? Map.of() : Map.copyOf(variants);
        }

        public VariantRule variant(String id) {
            return variants.get(id);
        }
    }

    public record VariantRule(String label, boolean hidden) {
    }

    private record CurationDocument(Map<String, ComponentRule> components) {
    }
}
