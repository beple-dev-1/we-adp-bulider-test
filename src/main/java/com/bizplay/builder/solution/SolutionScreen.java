package com.bizplay.builder.solution;

import java.util.List;

/**
 * ③ 솔루션 목업 한 장 — 기획 저장소에서 추출한 운영 화면(as-is) 하나.
 *
 * <p>정본: 목업 {@code docs/mockups/08-solution-mockups.html}·{@code 08a-solution-mockup-detail.html}.
 *
 * <p>⛔ <b>원본 화면 정보는 DB 에 안 산다.</b> 이 값은 전부 클론된 기획 저장소의 파일에서 나온다 —
 * {@code index.json}(화면ID·시스템·종류·화면유형·유형근거) · 화면 md(화면명·화면 요약·메뉴 경로) ·
 * {@code git log}(수정 이력).
 * 메뉴구조도에서 최초 확정한 종류·화면유형·유형근거만 {@code IaScreenProfile}에 별도로 보존한다.
 *
 * <p>⛔ <b>{@code SOL-W-014} 꼴 자기 이름을 짓지 마라</b>({@code artifacts.md} · 2026-08-13 확정).
 * 화면ID 는 레포의 것을 그대로 쓴다 — 이름이 둘이 되면 BRD 의 자동 복사가 거기서 깨진다.
 */
public record SolutionScreen(
        String screenId,
        String screenName,
        String summary,
        String system,
        String kind,
        String screenType,
        String typeSource,
        String menuPath,
        String iaPath,
        String facetCode,
        String facetName,
        List<SolutionVariant> variants,
        List<String> projectFacetNames,
        String parentScreenId,
        List<String> openingScreenIds,
        boolean shared,
        ScreenHistory history) {

    /** 요약 필드가 생기기 전부터 쓰던 생성 경로는 빈 요약으로 호환한다. */
    public SolutionScreen(String screenId, String screenName, String system, String kind,
                          String screenType, String typeSource, String menuPath, String iaPath,
                          String facetCode, String facetName, List<SolutionVariant> variants,
                          List<String> projectFacetNames, String parentScreenId,
                          List<String> openingScreenIds, boolean shared, ScreenHistory history) {
        this(screenId, screenName, "", system, kind, screenType, typeSource, menuPath, iaPath,
                facetCode, facetName, variants, projectFacetNames, parentScreenId, openingScreenIds,
                shared, history);
    }

    /*
     * ⛔ 시스템 한글 이름표를 여기 두지 마라 (2026-08-21 병주 확정).
     *    2026-08-21 까지 이 자리에 상수 셋(backoffice·webview·online-pg)이 있었는데, 실물 레포의
     *    시스템이 여섯이 되자 나머지 셋(saleoffice·lspnoffice·portal)이 화면에 영문으로 떴다.
     *    ⭐ 이름은 프로젝트마다 다르다 — 정본은 프로젝트 등록 자료
     *    ({@code adk_builder_project_system} · {@code SystemLabels})이고, 코드 목록의 정본은
     *    기획 저장소의 {@code manifest.json} 이다. 이 레코드는 자기가 어느 프로젝트인지 모르므로
     *    (파일에서 읽은 값 묶음이다) 스스로 이름을 찾을 수도 없다.
     */

    /** 메뉴구조도 최초 가져오기 뒤에는 저장된 분류를 화면에 얹어 색인 재생성으로 덮이지 않게 한다. */
    public SolutionScreen withClassification(String storedKind, String storedScreenType, String storedTypeSource) {
        return new SolutionScreen(screenId, screenName, summary, system, storedKind, storedScreenType, storedTypeSource,
                menuPath, iaPath, facetCode, facetName, variants, projectFacetNames, parentScreenId,
                openingScreenIds, shared, history);
    }

    public boolean typeNeedsReview() {
        return "이름".equals(typeSource);
    }

    /**
     * 기관마다 화면이 갈리나.
     *
     * <p>⚠ <b>갈래 화면에는 기저 html 이 없다</b> — 있으면 기획 저장소의 검사기가 {@code A-1}
     * red 를 낸다. 그래서 {@code pages/<ID>.html} 열기는 그 화면들에서 <b>항상</b> 실패한다.
     * 미리보기는 반드시 기관을 하나 골라 연다.
     */
    public boolean hasVariants() {
        return !variants.isEmpty();
    }

    public boolean hasFacetAxis() {
        return !projectFacetNames.isEmpty() || facetCode != null || hasVariants();
    }

    /** 목록과 필터가 사용하는 실제 적용 대상 이름들. 공통 화면은 프로젝트의 모든 구분에 적용된다. */
    public List<String> applicationNames() {
        if (facetCode != null && !facetCode.isBlank()) {
            return List.of(facetName);
        }
        if (hasVariants()) {
            return variants.stream().map(SolutionVariant::name).toList();
        }
        return projectFacetNames;
    }

    public String applicationSummary() {
        if (!hasFacetAxis()) {
            return "";
        }
        if (facetCode != null && !facetCode.isBlank()) {
            return facetName;
        }
        if (hasVariants()) {
            return String.join(", ", applicationNames()) + " · 기관별 화면";
        }
        return "전체 적용";
    }

    public String applicationListSummary() {
        if (!hasFacetAxis()) {
            return "";
        }
        if (hasVariants()) {
            return String.join(", ", applicationNames());
        }
        return "전체";
    }

    public boolean appliesTo(String name) {
        return name == null || name.isBlank() || "전체".equals(name) || applicationNames().contains(name);
    }

    /**
     * 미리보기가 열 파일의 자리 — 클론의 {@code core/} 아래 상대 경로다.
     *
     * <p>⚠ 이 글자가 그대로 주소가 된다. 레포 배치와 같은 모양이라야 추출된 html 속의
     * {@code ../assets/css/style.css} 가 저절로 맞는다 — 고쳐 쓸 것이 없다.
     *
     * @param variant 기관 이름. 갈래가 없는 화면이면 무시한다
     */
    public String previewPath(String variant) {
        if (!hasVariants()) {
            return "%s/pages/%s.html".formatted(system, screenId);
        }
        String chosen = variants.stream().map(SolutionVariant::code).anyMatch(code -> code.equals(variant))
                ? variant : variants.get(0).code();
        return "%s/variants-%s/%s.html".formatted(system, chosen, screenId);
    }
}
