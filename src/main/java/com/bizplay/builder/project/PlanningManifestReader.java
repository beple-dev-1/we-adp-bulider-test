package com.bizplay.builder.project;

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
import java.util.Objects;
import java.util.Optional;

/**
 * 클론된 기획 저장소의 {@code manifest.json} 을 읽는 한 자리.
 *
 * <p><b>레포가 어떤 시스템을 갖고 있나의 정본이다.</b> 채번(시스템 마디)과 프로젝트의 시스템
 * 등록이 둘 다 이 파일을 본다 — 그래서 파싱을 한 곳에 둔다. 종전에는 채번 안에 사적으로
 * 들어 있었고, 두 번째로 읽는 자리가 생기면 같은 계약이 두 벌이 될 자리였다.
 *
 * <p>⚠ <b>없거나 못 읽으면 던지지 않고 빈 목록을 낸다.</b> 이 파일이 없다고 클론이 실패로
 * 뒤집히거나 화면이 깨져서는 안 된다 — 부르는 쪽이 「아무것도 안 한다」를 고르게 한다.
 */
@Component
public class PlanningManifestReader {

    private static final Logger log = LoggerFactory.getLogger(PlanningManifestReader.class);

    private final ProjectPaths paths;
    private final ObjectMapper json;

    public PlanningManifestReader(ProjectPaths paths, ObjectMapper json) {
        this.paths = paths;
        this.json = json;
    }

    /**
     * {@code systems[]} 를 적힌 순서대로 낸다.
     *
     * <p>⛔ {@code id} 가 없는 줄은 버린다 — 지어내지 않는다. {@code prefix} 는 없을 수 있고
     * (그 시스템은 채번에서 빠진다) 그것이 시스템 자체를 없는 것으로 만들지는 않는다.
     */
    public List<ManifestSystem> systems(String projectId) {
        JsonNode systemsNode = root(projectId).map(root -> root.path("systems")).orElse(null);
        if (systemsNode == null) {
            return List.of();
        }
        if (!systemsNode.isArray()) {
            return List.of();
        }
        List<ManifestSystem> systems = new ArrayList<>();
        for (JsonNode system : systemsNode) {
            String id = system.path("id").asText(null);
            if (id == null || id.isBlank()) continue;
            String prefix = system.path("prefix").asText(null);
            systems.add(new ManifestSystem(id.strip(),
                    prefix == null || prefix.isBlank() ? null : prefix.strip(),
                    skinsOf(system.path("skins")),
                    declared(system, "styleguide"),
                    declared(system, "shell")));
        }
        return List.copyOf(systems);
    }

    /**
     * 디자인가이드 색인이 사는 자리 — {@code design-index} 칸이 정본이다.
     *
     * <p>⛔ <b>경로를 코드에 박지 마라</b>(추출기 회신 2절 ⑴). 기관 스킨에서 {@code iks}·{@code tnj}
     * 를 빌더가 알아서는 안 되는 것과 같은 규율이다 — 파일 이름은 그 사업의 짐이고 정본은 레포다.
     *
     * <p>⚠ <b>칸이 없는 옛 레포가 실재한다.</b> 그때는 {@code design-index.json} 을 기본값으로 본다 —
     * 추출기가 회신에서 그렇게 해도 된다고 밝힌 자리다.
     */
    public Path designIndexFile(String projectId) {
        String declared = root(projectId)
                .map(root -> root.path("design-index").asText(null))
                .filter(value -> !value.isBlank())
                .orElse(null);
        return paths.cloneDir(projectId)
                .resolve(declared == null ? "design-index.json" : declared.strip());
    }

    /**
     * 추출기가 만든 HTML 디자인 가이드의 폴더다.
     *
     * <p>이름은 Builder가 정하지 않는다. manifest에 없던 이전 산출물만 기본 이름을 쓴다.
     */
    public Path designGuideDirectory(String projectId) {
        String declared = root(projectId)
                .map(root -> root.path("design-guide").asText(null))
                .filter(value -> !value.isBlank())
                .orElse("design-guide");
        return paths.cloneDir(projectId).resolve(declared.strip()).normalize();
    }

    /**
     * 그 시스템의 스타일가이드가 사는 자리. 선언이 없으면 <b>빈 값</b>이다 — 지어내지 않는다.
     *
     * <p>⚠ 이 파일이 {@code class} 어휘의 정본이다. 검사기 {@code A-5} 가 같은 울타리를 읽는다.
     */
    public Optional<Path> styleguideFile(String projectId, String system) {
        return declaredFile(projectId, system, ManifestSystem::styleguide);
    }

    /**
     * 그 시스템의 셸·공용 조각 계약서가 사는 자리 ({@code systems[].shell}).
     *
     * <p>⚠ 선언이 없으면 <b>빈 값</b>이다 — 추출기가 2026-08-24 판에서 처음 보낸 칸이라
     * 그 전에 나간 레포에는 없다. 없는 것과 빈 것을 구별하는 일은 부르는 쪽이 한다.
     */
    public Optional<Path> shellFile(String projectId, String system) {
        return declaredFile(projectId, system, ManifestSystem::shell);
    }

    /** {@code systems[]} 의 어느 칸이 가리키는 파일. 선언이 없으면 빈 값이다. */
    private Optional<Path> declaredFile(String projectId, String system,
                                        java.util.function.Function<ManifestSystem, String> field) {
        return systems(projectId).stream()
                .filter(candidate -> candidate.id().equals(system))
                .map(field)
                .filter(Objects::nonNull)
                .findFirst()
                .map(declared -> paths.cloneDir(projectId).resolve(declared));
    }

    /**
     * {@code manifest.json} 의 뿌리. 없거나 못 읽으면 <b>빈 값</b>이다.
     *
     * <p>⚠ 파싱을 두 벌로 두지 않으려고 뽑았다 — {@link #systems} 도 이것을 쓴다.
     */
    private Optional<JsonNode> root(String projectId) {
        Path manifest = paths.cloneDir(projectId).resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            log.info("manifest.json 이 없다 projectId={}", projectId);
            return Optional.empty();
        }
        try {
            return Optional.of(json.readTree(Files.readString(manifest)));
        } catch (IOException | RuntimeException unreadable) {
            log.info("manifest.json 을 읽지 못했다 projectId={}", projectId);
            return Optional.empty();
        }
    }

    /** 선언 한 칸. 비었으면 {@code null} — 빈 글자를 경로로 쓰지 않는다. */
    private static String declared(JsonNode system, String field) {
        String value = system.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 기관 스킨 선언 — {@code 기관 → 스킨 폴더}(저장소 뿌리 기준).
     *
     * <p>⛔ <b>여기가 그 매핑을 읽는 유일한 자리다.</b> 어느 기관이 어느 css 폴더인지는 소스가
     * 안 적어 둔 <b>사업 지식</b>이다 — 빌더 코드가 {@code iks}·{@code tnj} 라는 글자를 쥐는 순간
     * 우리가 g2c 를 아는 것이 된다(추출기 회신 #5).
     *
     * <p>⚠ <b>기관이 하나만 적힌 시스템이 실재한다</b> — g2c {@code portal} 은 제주뿐이다.
     * 「둘일 것」을 가정하지 마라. 스킨이 없는 시스템은 빈 지도다.
     */
    private static Map<String, String> skinsOf(JsonNode skins) {
        if (!skins.isObject()) {
            return Map.of();
        }
        Map<String, String> found = new LinkedHashMap<>();
        skins.fields().forEachRemaining(entry -> {
            String folder = entry.getValue().asText("");
            if (entry.getKey().isBlank() || folder.isBlank()) {
                return;
            }
            // ⚠ 뿌리 기준 경로를 「/」 로만 잇는다 — 개발은 윈도우, 운영은 리눅스다.
            found.put(entry.getKey().strip(),
                    folder.strip().replace('\\', '/').replaceAll("/+$", ""));
        });
        return Map.copyOf(found);
    }

    /**
     * {@code manifest.json} 의 {@code systems[]} 한 줄. 우리가 쓰는 세 칸만 담는다.
     *
     * @param skins      기관 → 스킨 폴더. 선언이 없으면 <b>빈 지도</b>이지 {@code null} 이 아니다
     * @param styleguide 그 시스템의 스타일가이드 경로(저장소 뿌리 기준). 선언이 없으면 {@code null}
     * @param shell      셸·공용 조각 계약서 경로. 2026-08-24 판부터 온다 — 없으면 {@code null}
     */
    public record ManifestSystem(String id, String prefix, Map<String, String> skins,
                                 String styleguide, String shell) {
    }
}
