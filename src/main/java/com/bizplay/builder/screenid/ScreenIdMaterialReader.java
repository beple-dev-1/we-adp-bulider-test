package com.bizplay.builder.screenid;

import com.bizplay.builder.ia.IaDocumentCodec;
import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.solution.SolutionMockupService;
import com.bizplay.builder.solution.SolutionScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 클론에서 채번 재료를 읽는다.
 *
 * <p>재료가 셋에서 온다 — {@code index.json}(화면의 시스템·종류·화면유형·<b>{@code ia.경로}</b>,
 * {@link SolutionMockupService} 를 거쳐 온다), {@code core/<시스템>/ia.md} 의 {@code ## 이름표}
 * (slug → 한글), {@code manifest.json} 의 {@code systems[].prefix}(표준 화면ID 의 시스템 마디).
 *
 * <p>⭐ <b>자리 재료를 2026-08-21 에 {@code ia.md} 배치에서 색인으로 옮겼다 (병주 확정)</b> —
 * 메뉴구조도({@link com.bizplay.builder.ia.IaTreeBuilder})와 같은 재료다. 배치는 색인보다 좁았고
 * (백오피스 실측 82줄 대 240장), 배치 표 아래 산문 한 줄이 시스템 전체를 채번에서 밀어내기도 했다
 * (lspnoffice 19장). ⚠ <b>트리와 재료는 같아도 쓰는 깊이가 다르다</b> — 채번은 경로의
 * <b>앞 두 마디</b>(업무영역·기능그룹)만 쓴다. 사슬 마디를 그대로 넣으면 안 된다.
 *
 * <p>⛔ <b>빌더 DB 의 IA 를 보지 않는다 — 일부러다.</b> 채번은 <b>클론 직후</b>에 도는데
 * 메뉴구조도 DB 는 그때 비어 있다({@code IaService.findOrImport} 가 상세 화면을 처음 열 때 채운다).
 * 그 시점에 언제나 있는 재료는 클론의 색인과 {@code ia.md} 뿐이다.
 *
 * <p>⚠ <b>IA 경로를 못 얻은 화면은 그냥 빠진다.</b> 웹뷰는 {@code ia.md} 가 아직 없고,
 * 백오피스에도 공용 팝업 2장과 자리를 못 얻은 3장이 있다(2026-08-15 실측).
 * ⛔ <b>억지 코드를 지어 붙이지 마라</b> — 「모른다」가 아니라 <b>틀린 정보</b>가 되고,
 * 나중에 진짜 경로가 와도 「없는 것만 채운다」에 걸려 안 고쳐진다.
 *
 * <p>⚠ <b>시스템 두 글자도 마찬가지다(2026-08-20).</b> 종전에는 {@code builder.screen-id.systems}
 * (yml) 이 시스템 표를 들고 있었는데, 사업마다 시스템이 다른데 yml 은 사업 하나 것만 담을 수 있어
 * 나머지 시스템의 화면이 조용히 채번을 못 받았다. 이제 그 표는 <b>레포 자신</b>({@code manifest.json})
 * 에서 나온다 — {@code manifest.json} 이 없거나 그 시스템에 {@code prefix} 가 없으면
 * <b>그 시스템 전체를 건너뛴다</b>. 지어내지 않는다.
 */
@Component
public class ScreenIdMaterialReader {

    private static final Logger log = LoggerFactory.getLogger(ScreenIdMaterialReader.class);

    private final ProjectPaths paths;
    private final SolutionMockupService solutions;
    private final IaDocumentCodec codec;
    private final PlanningManifestReader manifests;

    public ScreenIdMaterialReader(ProjectPaths paths, SolutionMockupService solutions, IaDocumentCodec codec,
                                  PlanningManifestReader manifests) {
        this.paths = paths;
        this.solutions = solutions;
        this.codec = codec;
        this.manifests = manifests;
    }

    /** {@code pathKey} 사전순 → {@code screenId} 사전순으로 <b>이미 정렬해서</b> 낸다. */
    public List<ScreenIdMaterial> read(String projectId) {
        Map<String, SolutionScreen> screens = new LinkedHashMap<>();
        Set<String> systems = new LinkedHashSet<>();
        for (SolutionScreen screen : solutions.screens(projectId)) {
            screens.put(screen.screenId(), screen);
            if (screen.system() != null && !screen.system().isBlank()) systems.add(screen.system());
        }

        Map<String, String> systemCodes = manifestSystemCodes(projectId);

        List<ScreenIdMaterial> materials = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        // ① 색인에 자기 경로(ia.경로)를 가진 화면. 업무영역·기능그룹의 1차 재료다.
        for (String system : systems) {
            String systemCode2 = systemCodes.get(system);
            if (systemCode2 == null) {
                log.info("manifest.json 에 시스템 두 글자가 없어 통째로 건너뛴다 projectId={} system={}",
                        projectId, system);
                continue;
            }
            Map<String, String> labels = labelsOf(projectId, system);
            if (labels == null) continue;
            for (SolutionScreen screen : screens.values()) {
                if (!system.equals(screen.system())) continue;
                if (screen.iaPath() == null || screen.iaPath().isBlank()) continue;
                if (!emitted.add(screen.screenId())) continue;

                String pathKey = screen.iaPath().strip();
                String[] keys = pathKey.split("/");
                String areaKey = keys[0];
                String groupKey = keys.length > 1 ? keys[1] : "";
                materials.add(new ScreenIdMaterial(
                        screen.screenId(), system, systemCode2,
                        areaKey, labels.getOrDefault(areaKey, areaKey),
                        groupKey, groupKey.isEmpty() ? null : labels.getOrDefault(areaKey + "/" + groupKey, groupKey),
                        StandardScreenIdFormat.letterOf(screen.kind(), screen.screenType()),
                        pathKey));
            }
        }
        // ② 배치를 못 얻은 화면은 부모에서 물려받는다 — 팝업·모달이 메뉴 트리에 줄이 없는 것은
        //    결함이 아니라 설계다(메뉴로 직접 들어가는 물건이 아니다). 실측(planning-g2c)에서
        //    화면 537장 중 배치는 235장뿐이고, 부모 사슬을 따라가면 514장이 IA 배치에 닿는다.
        inheritFromParents(projectId, screens, systemCodes, materials, emitted);

        materials.sort(Comparator.comparing(ScreenIdMaterial::pathKey)
                .thenComparing(ScreenIdMaterial::screenId));
        return List.copyOf(materials);
    }

    /**
     * {@code manifest.json} 의 {@code systems[]} 에서 {@code id → prefix(대문자)} 표를 만든다.
     *
     * <p>⚠ 없거나 못 읽으면 <b>던지지 않고 빈 표가 된다</b> — 그러면 {@link #read} 의 모든 시스템이
     * 건너뛰어진다(클론이 실패해서는 안 된다). 파일을 읽는 것은 {@link PlanningManifestReader} 다.
     * ⛔ {@code prefix} 가 없거나 빈 시스템은 표에 안 넣는다 — 지어내지 않는다.
     */
    private Map<String, String> manifestSystemCodes(String projectId) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (PlanningManifestReader.ManifestSystem system : manifests.systems(projectId)) {
            if (system.prefix() == null) continue;
            codes.put(system.id(), system.prefix().toUpperCase(Locale.ROOT));
        }
        return codes;
    }

    /**
     * {@code ## 이름표} 블록을 slug → 한글로 읽는다. <b>널이면 그 시스템을 통째로 건너뛴다.</b>
     *
     * <p>⚠ {@code ia.md} 가 없거나 못 읽으면 <b>던지지 않고 널을 낸다</b> — 웹뷰가 실제로 그렇다.
     * 이름표 없이 채번하면 업무영역 한글이 slug 가 되어 AI 3글자 코더가 틀린 재료를 받는다.
     */
    private Map<String, String> labelsOf(String projectId, String system) {
        Path file;
        try {
            file = paths.iaFile(projectId, system);
        } catch (IllegalArgumentException badSystem) {
            log.debug("시스템 코드의 꼴이 아니라 건너뛴다 projectId={} system={}", projectId, system);
            return null;
        }
        if (!Files.isRegularFile(file)) return null;
        try {
            return codec.labels(Files.readString(file));
        } catch (IOException | IllegalArgumentException unreadable) {
            log.info("ia.md 를 읽지 못해 채번에서 건너뛴다 projectId={} system={}", projectId, system);
            return null;
        }
    }

    /**
     * 배치를 못 얻은 화면에 <b>부모의 업무영역·기능그룹을 물려준다.</b>
     *
     * <p>⭐ <b>팝업·모달이 {@code --- 배치 ---} 에 없는 것은 결함이 아니다.</b> 메뉴에서 직접
     * 들어가는 물건이 아니라 부모 화면이 여는 것이라서 메뉴 트리에 줄이 없는 것이 맞다.
     * 그 팝업의 업무는 <b>자기를 여는 화면의 업무</b>다 — 가맹점 목록이 여는 검색 팝업은 가맹점 업무다.
     * 보스가 준 예시가 그 모양이다: {@code PS-WV-MRC-010-P01-S 검색조건 선택 팝업}.
     *
     * <p>⛔ <b>화면 md 의 {@code 기능:} 꼬리표로 우회하지 마라.</b> 그것은 {@code ia.md} 와
     * <b>다른 출처</b>여서 정본이 둘이 된다. 부모 사슬로도 안 닿는 화면은 <b>번호 없이 남긴다</b> —
     * 그 대부분은 검사기가 이미 red 로 잡아 둔 「부모 0장인 모달」이고, 기획 레포가 부모를 채우면
     * 다음 저장소 업데이트에서 저절로 붙는다. 우회로를 만들면 그 red 를 덮어 버린다.
     *
     * <p>⚠ <b>유형 글자는 물려받지 않는다.</b> 부모가 목록({@code L})이어도 팝업은 {@code P} 다 —
     * 그래서 부모와 같은 묶음에서 번호가 부딪히지 않는다.
     */
    private void inheritFromParents(String projectId, Map<String, SolutionScreen> screens,
                                    Map<String, String> systemCodes, List<ScreenIdMaterial> materials,
                                    Set<String> emitted) {
        Map<String, ScreenIdMaterial> placed = new LinkedHashMap<>();
        materials.forEach(material -> placed.put(material.screenId(), material));

        List<ScreenIdMaterial> inherited = new ArrayList<>();
        for (SolutionScreen screen : screens.values()) {
            if (emitted.contains(screen.screenId())) continue;
            String systemCode2 = systemCodes.get(screen.system());
            if (systemCode2 == null) continue;

            ScreenIdMaterial parent = placedAncestorOf(screen, screens, placed, new LinkedHashSet<>());
            if (parent == null) {
                log.info("부모 사슬로 IA 배치에 닿지 않아 번호를 주지 않는다 projectId={} screenId={}",
                        projectId, screen.screenId());
                continue;
            }
            inherited.add(new ScreenIdMaterial(
                    screen.screenId(), screen.system(), systemCode2,
                    parent.areaKey(), parent.areaLabel(),
                    parent.groupKey(), parent.groupLabel(),
                    StandardScreenIdFormat.letterOf(screen.kind(), screen.screenType()),
                    parent.pathKey()));
            emitted.add(screen.screenId());
        }
        materials.addAll(inherited);
    }

    /**
     * 부모를 따라 올라가 <b>배치를 얻은 조상</b>을 찾는다. 못 찾으면 널이다.
     *
     * <p>순서는 <b>{@code 상위화면} 먼저, 그다음 {@code 여는화면} 을 화면ID 사전순</b>이다.
     * ⛔ <b>여는 화면이 여럿일 때 아무 것이나 고르면 안 된다</b> — 실측에 여는 화면이 8장인 공용
     * 팝업이 있고(4갈래), 고르는 순서가 흔들리면 <b>재색인마다 다른 업무영역이 붙는다.</b>
     * 사전순 첫째로 못 박아 둔 것이 그 이유다.
     *
     * @param seen 이미 지난 화면. ⚠ <b>팝업이 서로를 여는 고리가 실제로 있을 수 있다</b> —
     *             없으면 여기서 무한 재귀로 죽는다
     */
    private ScreenIdMaterial placedAncestorOf(SolutionScreen screen, Map<String, SolutionScreen> screens,
                                              Map<String, ScreenIdMaterial> placed, Set<String> seen) {
        if (screen == null || !seen.add(screen.screenId())) return null;

        List<String> parents = new ArrayList<>();
        if (screen.parentScreenId() != null && !screen.parentScreenId().isBlank()) {
            parents.add(screen.parentScreenId());
        }
        if (screen.openingScreenIds() != null) {
            parents.addAll(screen.openingScreenIds().stream().filter(java.util.Objects::nonNull)
                    .filter(id -> !id.isBlank()).sorted().toList());
        }
        for (String parentId : parents) {
            ScreenIdMaterial direct = placed.get(parentId);
            if (direct != null) return direct;
        }
        for (String parentId : parents) {
            ScreenIdMaterial up = placedAncestorOf(screens.get(parentId), screens, placed, seen);
            if (up != null) return up;
        }
        return null;
    }

}
