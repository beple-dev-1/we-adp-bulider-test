package com.bizplay.builder.solution;

import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.PlanningManifestReader.ManifestSystem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <b>이 화면을 어느 기관으로 볼 수 있나.</b> 미리보기의 기관 축을 한 자리에서 답한다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-preview-skin-design.md}.
 *
 * <p>⭐ <b>기관은 한 값이고 지렛대는 둘이다.</b> 갈래 화면이면 <b>파일</b>이 갈리고
 * ({@link SolutionScreen#previewPath}), 스킨 화면이면 <b>css 폴더</b>가 갈린다
 * ({@link SkinRewriter}). 부르는 쪽은 그 둘을 구분하지 않는다.
 *
 * <p>⚠ 코드의 정본은 기획 저장소({@code manifest.json} 의 {@code systems[].skins})이고
 * <b>이름의 정본은 빌더 DB</b>({@code adk_builder_project_facet})다 — 시스템 코드와 이름이
 * 갈려 사는 것과 같은 결이다. 이름이 아직 안 앉은 코드는 <b>지어내지 않고 그대로 보여준다.</b>
 */
@Component
public class PreviewFacets {

    private final PlanningManifestReader manifests;
    private final ProjectFacetMapper projectFacets;

    public PreviewFacets(PlanningManifestReader manifests, ProjectFacetMapper projectFacets) {
        this.manifests = manifests;
        this.projectFacets = projectFacets;
    }

    /**
     * 그 시스템을 그릴 수 있는 기관들. 스킨 선언이 없으면 <b>빈 목록</b>이다.
     *
     * <p>⚠ <b>기관이 하나뿐인 시스템이 실재한다</b>(g2c {@code portal} 은 제주뿐). 그때는 고를
     * 것이 없으니 탭도 뜨지 않는다 — 「전환 대상이 아니다」와 같은 뜻이다.
     */
    public List<SolutionVariant> of(String projectId, String system) {
        if (system == null || system.isBlank()) {
            return List.of();
        }
        Map<String, String> names = names(projectId);
        List<SolutionVariant> found = new ArrayList<>();
        for (ManifestSystem candidate : manifests.systems(projectId)) {
            if (!candidate.id().equals(system)) {
                continue;
            }
            candidate.skins().keySet().forEach(code ->
                    found.add(new SolutionVariant(code, names.getOrDefault(code, code))));
        }
        if (found.size() < 2) {
            // 고를 것이 하나뿐이면 고르는 자리가 아니다.
            return List.of();
        }
        found.sort(Comparator.comparing(SolutionVariant::code));
        return List.copyOf(found);
    }

    /**
     * 적용 구분 이름({@code 제주})을 코드({@code jeju})로 옮긴다.
     *
     * <p>⚠ FRD 는 적용 대상을 <b>이름으로</b> 들고 있고({@code adk_builder_frd_facet}) 스킨 선언은
     * <b>코드로</b> 열쇠를 짓는다. 그 사이를 잇는 자리가 여기 하나여야 한다.
     */
    public String codeOfName(String projectId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return projectFacets.selectByProjectId(projectId).stream()
                .filter(facet -> name.strip().equals(facet.name()))
                .map(ProjectFacet::code)
                .findFirst()
                .orElse(null);
    }

    /**
     * 프로젝트의 적용 구분이 <b>하나뿐이면</b> 그 코드. 여럿이거나 없으면 {@code null}.
     *
     * <p>⛔ 여럿일 때 아무거나 고르지 마라 — 그것이 「제주 사업인데 익산으로 보이는」 고장의 씨다.
     * 못 정하면 색인이 그린 그대로 두는 것이 맞다.
     */
    public String only(String projectId) {
        List<ProjectFacet> facets = projectFacets.selectByProjectId(projectId);
        return facets.size() == 1 ? facets.get(0).code() : null;
    }

    /**
     * {@code 기관 코드 → 사람이 읽는 이름}.
     *
     * <p>⚠ <b>이 표를 두 벌로 만들지 마라.</b> 코드의 정본은 기획 레포({@code systems[].skins})이고
     * 이름의 정본은 빌더 DB({@code adk_builder_project_facet})다 — 그 사이를 잇는 자리가
     * 여기 하나여야 한다. 디자인가이드도 이것을 쓴다.
     *
     * <p>⚠ 이름이 아직 안 앉은 코드는 <b>여기 없다</b> — 부르는 쪽이 코드를 그대로 보여준다.
     */
    public Map<String, String> names(String projectId) {
        return projectFacets.selectByProjectId(projectId).stream()
                .collect(Collectors.toMap(ProjectFacet::code, ProjectFacet::name, (first, second) -> first));
    }
}
