package com.bizplay.builder.design;

import com.bizplay.builder.design.DesignIndex.SystemDesign;
import com.bizplay.builder.design.DesignIndex.Tally;
import com.bizplay.builder.design.DesignIndex.TokenDeclaration;
import com.bizplay.builder.design.StyleVocabularyReader.StyleVocabulary;
import com.bizplay.builder.project.PlanningManifestReader;
import com.bizplay.builder.project.PlanningManifestReader.ManifestSystem;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.solution.PreviewFacets;
import com.bizplay.builder.solution.SolutionScreen;
import com.bizplay.builder.solution.SolutionScreenReader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 디자인가이드 화면이 쓸 것을 한 자리에서 모은다.
 *
 * <p><b>이 화면이 답하는 질문 하나: 이 사업 소스의 시각 규칙이 무엇인가.</b>
 * 재료는 추출기가 굽는 파생물 둘 — {@link DesignIndexReader}(색·라운딩·타이포·토큰)와
 * {@link StyleVocabularyReader}(class 어휘)다.
 *
 * <p>⛔ <b>고치는 길을 만들지 마라.</b> 파생물이라 사람이 손대면 다음 재굽기에 날아가고,
 * 그 사이에 기획 레포 검사기가 {@code DESIGN-1} red 를 낸다.
 *
 * <p>⛔ <b>계산값을 읽지 마라.</b> 색인이 {@code @import} 를 따라가지 않아 캐스케이드 승자를
 * 복원할 재료가 없다. 우리가 하는 것은 <b>선언을 그대로 나열하는 것</b>이고, 그러면 화면이
 * 말하는 것과 색인이 준 것이 한 글자도 안 갈린다.
 */
@Service
public class DesignGuideService {

    /**
     * 색 계단에 몇 색을 세우나.
     *
     * <p>⚠ <b>자르는 것을 숨기지 않는다</b> — 화면이 「전체 N색 중 상위 12」라고 적는다.
     * ⛔ 없는 단계를 계산해 채우지 마라(추출기 회신 3절) — 소스에 단계 개념이 없다.
     * 우리가 하는 것은 <b>정렬</b>이고 창작이 아니다.
     */
    private static final int LADDER = 12;

    private final DesignIndexReader indexes;
    private final StyleVocabularyReader vocabularies;
    private final PlanningManifestReader manifests;
    private final ProjectSystemService projectSystems;
    private final PreviewFacets previewFacets;
    private final ShellFragmentReader shells;
    private final SolutionScreenReader screens;

    public DesignGuideService(DesignIndexReader indexes, StyleVocabularyReader vocabularies,
                              PlanningManifestReader manifests, ProjectSystemService projectSystems,
                              PreviewFacets previewFacets, ShellFragmentReader shells,
                              SolutionScreenReader screens) {
        this.indexes = indexes;
        this.vocabularies = vocabularies;
        this.manifests = manifests;
        this.projectSystems = projectSystems;
        this.previewFacets = previewFacets;
        this.shells = shells;
        this.screens = screens;
    }

    public DesignGuideView view(String projectId, String system, String facet, String screenId) {
        Optional<DesignIndex> index = indexes.read(projectId);
        var labels = projectSystems.labels(projectId);

        // ⚠ 시스템의 순서는 레포가 적은 순서다 — 우리가 가나다로 다시 세우지 않는다.
        List<ManifestSystem> declared = manifests.systems(projectId);
        List<Choice> systems = declared.stream()
                .map(candidate -> new Choice(candidate.id(), labels.label(candidate.id())))
                .toList();

        if (index.isEmpty() || systems.isEmpty()) {
            return DesignGuideView.notYet(systems);
        }

        String chosenSystem = pick(system, systems);
        SystemDesign design = index.get().of(chosenSystem);

        List<Choice> facets = facetsOf(projectId, design);
        String chosenFacet = pickFacet(projectId, facet, facets);

        Map<String, List<TokenDeclaration>> tokens = design.hasFacetTokens()
                ? design.facetTokens().getOrDefault(chosenFacet, Map.of())
                : design.commonTokens();
        List<ReferenceScreen> references = referenceScreens(projectId, chosenSystem, chosenFacet);
        return new DesignGuideView(
                systems, chosenSystem, labelOf(systems, chosenSystem),
                facets, chosenFacet, labelOf(facets, chosenFacet), design.hasFacetTokens(),
                tokenRows(tokens), ladderOf(design.colors()), design,
                index.get().counts(), vocabularies.read(projectId, chosenSystem),
                shells.of(projectId, chosenSystem, ShellFragmentReader.Kind.SIDEBAR),
                shells.of(projectId, chosenSystem, ShellFragmentReader.Kind.HEADER),
                references, pickReference(screenId, references));
    }

    /**
     * 디자인 기준 화면은 조각을 다시 조립하지 않고, 추출된 화면 한 장을 그대로 연다.
     *
     * <p>태그 일부만 떼어내면 부모 구조·형제 요소·상태에 의존하는 CSS가 빠져 실제 모양과 달라진다.
     * 화면 목록과 미리보기 모두 이미 기획 저장소가 제공하는 {@code index.json}과 html을 쓴다.
     */
    private List<ReferenceScreen> referenceScreens(String projectId, String system, String facet) {
        return screens.read(projectId).stream()
                .filter(screen -> system.equals(screen.system()))
                .filter(screen -> appliesToFacet(screen, facet))
                .map(screen -> referenceOf(projectId, screen, facet))
                .toList();
    }

    private static boolean appliesToFacet(SolutionScreen screen, String facet) {
        if (facet == null || facet.isBlank()) {
            return true;
        }
        if (screen.facetCode() != null && !screen.facetCode().isBlank()) {
            return facet.equals(screen.facetCode());
        }
        return !screen.hasVariants() || screen.variants().stream()
                .anyMatch(variant -> facet.equals(variant.code()));
    }

    private ReferenceScreen referenceOf(String projectId, SolutionScreen screen, String selectedFacet) {
        String previewFacet = previewFacetOf(projectId, screen, selectedFacet);
        return new ReferenceScreen(screen.screenId(), screen.screenName(), screen.kind(), screen.screenType(),
                screen.summary(), screen.previewPath(previewFacet), previewFacet);
    }

    private String previewFacetOf(String projectId, SolutionScreen screen, String selectedFacet) {
        if (screen.hasVariants()) {
            return screen.variants().stream().map(variant -> variant.code())
                    .filter(code -> code.equals(selectedFacet))
                    .findFirst().orElseGet(() -> screen.variants().get(0).code());
        }
        if (selectedFacet != null && previewFacets.of(projectId, screen.system()).stream()
                .anyMatch(variant -> selectedFacet.equals(variant.code()))) {
            return selectedFacet;
        }
        return previewFacets.only(projectId);
    }

    private static ReferenceScreen pickReference(String asked, List<ReferenceScreen> references) {
        return references.stream().filter(reference -> reference.screenId().equals(asked)).findFirst()
                .orElse(references.isEmpty() ? null : references.get(0));
    }

    /**
     * 토큰을 화면 줄로 바꾼다 — 이름 오름차순.
     *
     * <p>⚠ 정렬을 이름으로만 한다. 선언 배열 안의 순서는 <b>추출기가 준 그대로</b> 둔다 —
     * 그것이 파일·줄 순서이고 사람이 파일을 찾을 때 쓰는 순서다.
     */
    private static List<TokenRow> tokenRows(Map<String, List<TokenDeclaration>> tokens) {
        return tokens.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TokenRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 기관 목록. <b>토큰이 갈리는 기관</b>이 정본이다 — 스킨 선언이 아니라 색인이 실제로
     * 토큰을 담은 기관을 쓴다.
     *
     * <p>⚠ 이름은 빌더 DB 에서 온다. 아직 이름이 안 앉은 코드는 <b>코드를 그대로</b> 보여준다 —
     * 지어내지 않는다.
     */
    private List<Choice> facetsOf(String projectId, SystemDesign design) {
        if (!design.hasFacetTokens()) {
            return List.of();
        }
        Map<String, String> names = previewFacets.names(projectId);
        return design.facetTokens().keySet().stream()
                .sorted()
                .map(code -> new Choice(code, names.getOrDefault(code, code)))
                .toList();
    }

    /**
     * 무엇을 보여주나.
     *
     * <p>차례는 ① 사람이 고른 값 ② 프로젝트의 적용 구분이 하나뿐이면 그것 ③ 첫 기관이다.
     *
     * <p>⚠ 미리보기 축의 「기본 기관을 지어내지 마라」와 부딪히지 않는다 — 거기서는
     * <b>못 정하면 안 갈아끼우는</b> 길이 있었지만, 토큰은 기관마다 갈려 있어서 아무것도
     * 안 고르면 그릴 것이 없다. 고른 값은 <b>언제나 화면에 표시</b>되고 눌러서 바꿀 수 있다 —
     * 조용히 정해지는 것이 아니라서 「제주 사업이 익산으로 보이는」 실패 방식이 안 생긴다.
     */
    private String pickFacet(String projectId, String asked, List<Choice> facets) {
        if (facets.isEmpty()) {
            return null;
        }
        Optional<String> chosen = facets.stream().map(Choice::code)
                .filter(code -> code.equals(asked)).findFirst();
        if (chosen.isPresent()) {
            return chosen.get();
        }
        String only = previewFacets.only(projectId);
        if (only != null && facets.stream().anyMatch(candidate -> candidate.code().equals(only))) {
            return only;
        }
        return facets.get(0).code();
    }

    private static String pick(String asked, List<Choice> choices) {
        return choices.stream().map(Choice::code).filter(code -> code.equals(asked)).findFirst()
                .orElseGet(() -> choices.get(0).code());
    }

    private static String labelOf(List<Choice> choices, String code) {
        return choices.stream().filter(choice -> choice.code().equals(code))
                .map(Choice::label).findFirst().orElse(code);
    }

    /**
     * 색 계단 — <b>빈도 상위를 밝기순으로 늘어놓은 것</b>이다.
     *
     * <p>⭐ 추출기 회신 3절: 「명도순으로 늘어놓으면 Neutral Scale 자리가 그대로 찬다.
     * 정렬이지 창작이 아니다.」 그래서 <b>단계 이름을 붙이지 않는다</b> — {@code 50}·{@code 900}
     * 같은 이름은 소스에 없는 개념이고, 붙이면 우리가 만든 것이 소스처럼 보인다.
     */
    private static List<Tally> ladderOf(List<Tally> colors) {
        List<Tally> top = new ArrayList<>(colors.subList(0, Math.min(LADDER, colors.size())));
        top.sort(Comparator.comparingDouble((Tally tally) -> brightness(tally.value())).reversed());
        return List.copyOf(top);
    }

    /**
     * 대충의 밝기. 정렬에만 쓴다.
     *
     * <p>⚠ 실물의 {@code colors} 는 여섯 자리 소문자 hex 로 정규화돼 오지만
     * <b>모양을 가정하지 않는다</b> — 못 읽는 값은 맨 뒤로 보내고 버리지 않는다.
     */
    private static double brightness(String color) {
        String hex = color == null ? "" : color.replace("#", "").strip();
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            return -1;
        }
        try {
            int red = Integer.parseInt(hex.substring(0, 2), 16);
            int green = Integer.parseInt(hex.substring(2, 4), 16);
            int blue = Integer.parseInt(hex.substring(4, 6), 16);
            return 0.299 * red + 0.587 * green + 0.114 * blue;
        } catch (NumberFormatException notHex) {
            return -1;
        }
    }

    /** 고를 것 하나 — 코드와 사람이 읽는 이름. 시스템과 기관이 같은 모양이다. */
    public record Choice(String code, String label) {
    }

    /** 토큰 한 줄. <b>선언이 여럿일 수 있다</b> — 그것이 이 화면의 급소다. */
    public record TokenRow(String name, List<TokenDeclaration> declarations) {

        public boolean isSplit() {
            return declarations.size() > 1;
        }
    }

    /** 디자인가이드에서 고르는 실제 추출 화면 한 장. */
    public record ReferenceScreen(String screenId, String screenName, String kind, String screenType,
                                  String summary, String previewPath, String previewFacet) {
    }

    /**
     * 화면 한 판.
     *
     * @param ready 색인이 왔나. 안 왔으면 화면은 <b>왜 비었는지</b>만 적는다
     */
    public record DesignGuideView(List<Choice> systems, String system, String systemLabel,
                                  List<Choice> facets, String facet, String facetLabel,
                                  boolean facetSplit, List<TokenRow> tokens, List<Tally> ladder,
                                  SystemDesign design, DesignIndex.Counts counts,
                                  StyleVocabulary vocabulary,
                                  List<ShellFragmentReader.ShellFragment> sidebars,
                                  List<ShellFragmentReader.ShellFragment> headers,
                                  List<ReferenceScreen> references,
                                  ReferenceScreen reference,
                                  boolean ready) {

        public DesignGuideView(List<Choice> systems, String system, String systemLabel,
                               List<Choice> facets, String facet, String facetLabel,
                               boolean facetSplit, List<TokenRow> tokens, List<Tally> ladder,
                               SystemDesign design, DesignIndex.Counts counts,
                               StyleVocabulary vocabulary,
                               List<ShellFragmentReader.ShellFragment> sidebars,
                               List<ShellFragmentReader.ShellFragment> headers,
                               List<ReferenceScreen> references,
                               ReferenceScreen reference) {
            this(systems, system, systemLabel, facets, facet, facetLabel, facetSplit,
                    tokens, ladder, design, counts, vocabulary, sidebars, headers, references, reference, true);
        }

        static DesignGuideView notYet(List<Choice> systems) {
            return new DesignGuideView(systems, null, null, List.of(), null, null, false,
                    List.of(), List.of(), SystemDesign.empty(), DesignIndex.Counts.unknown(),
                    StyleVocabulary.empty(), List.of(), List.of(), List.of(), null, false);
        }

        /** 기관이 하나뿐이면 고르는 자리를 그리지 않는다 — 그래도 이름은 밝힌다. */
        public boolean hasFacetChoice() {
            return facets.size() > 1;
        }
    }
}
