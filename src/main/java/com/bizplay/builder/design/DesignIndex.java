package com.bizplay.builder.design;

import java.util.List;
import java.util.Map;

/**
 * 추출기가 굽는 {@code design-index.json} 한 채. <b>판은 {@code we-adk-design-index/1} 이다.</b>
 *
 * <p>⛔ <b>이 기록은 「무엇이 선언됐나」만 말하고 「무엇이 이기나」는 말하지 않는다.</b>
 * 캐스케이드 승자는 {@code @import}·{@code <link>} 순서와 선택자 특이성이 정하는데
 * 이 색인은 {@code @import} 를 따라가지 않는다(물리 파일만 센다). 그래서 토큰마다
 * <b>선언 배열</b>을 그대로 들고 있다 — 하나로 누르는 자리를 만들지 마라.
 *
 * <p>⚠ <b>값이 없는 칸은 아예 없다.</b> 추출기가 빈 객체·빈 배열로 채우지 않는다 —
 * 「봤는데 없었다」와 「안 봤다」의 구별은 {@link Counts} 가 맡는다. 그래서 여기서는
 * 없는 칸을 <b>빈 목록으로 받아 두고</b>, 왜 비었나는 화면이 {@code counts} 로 말한다.
 */
public record DesignIndex(String schema, Map<String, SystemDesign> systems, Counts counts) {

    /** 그 시스템 몫. 없으면 {@code null} 이 아니라 <b>빈 것</b>을 낸다 — 화면이 분기를 덜 한다. */
    public SystemDesign of(String system) {
        SystemDesign found = systems.get(system);
        return found == null ? SystemDesign.empty() : found;
    }

    /**
     * 색인이 스스로 밝히는 커버리지.
     *
     * @param cssFiles     목록에 실린 css 전수
     * @param scannedFiles 그중 <b>실제로 센</b> 수. 차이가 벤더·데모다
     */
    public record Counts(int systems, int cssFiles, int scannedFiles,
                         int tokenSystems, int emptySystems) {

        public static Counts unknown() {
            return new Counts(0, 0, 0, 0, 0);
        }

        /** 세지 않은 장수. 화면이 「무엇을 안 봤나」를 말할 때 쓴다. */
        public int excludedFiles() {
            return Math.max(0, cssFiles - scannedFiles);
        }
    }

    /**
     * 시스템 하나의 값들.
     *
     * @param commonTokens 스킨 폴더 <b>밖</b>에서 선언된 토큰. 기관이 없다
     * @param facetTokens  스킨 폴더 <b>안</b>에서 선언된 토큰. {@code 기관 → 토큰명 → 선언들}
     */
    public record SystemDesign(List<CssFile> files,
                               Map<String, List<TokenDeclaration>> commonTokens,
                               Map<String, Map<String, List<TokenDeclaration>>> facetTokens,
                               List<Tally> colors, List<Tally> rgba, List<Tally> radius,
                               Typography type) {

        public static SystemDesign empty() {
            return new SystemDesign(List.of(), Map.of(), Map.of(),
                    List.of(), List.of(), List.of(), Typography.empty());
        }

        /** 토큰이 기관마다 갈리나. 갈리면 화면이 기관을 밝혀야 한다. */
        public boolean hasFacetTokens() {
            return !facetTokens.isEmpty();
        }

        /** 집계에 들어간 css. */
        public List<CssFile> countedFiles() {
            return files.stream().filter(CssFile::counted).toList();
        }

        /** ⚠ <b>집계에서 뺀 css.</b> 목록에는 남긴다 — 무엇을 안 봤나를 사람이 볼 수 있게. */
        public List<CssFile> excludedFiles() {
            return files.stream().filter(file -> !file.counted()).toList();
        }

        public boolean isEmpty() {
            return files.isEmpty() && commonTokens.isEmpty() && facetTokens.isEmpty()
                    && colors.isEmpty() && radius.isEmpty() && type.isEmpty();
        }
    }

    /**
     * 읽은 css 한 장.
     *
     * @param role  {@code common}·{@code skin}·{@code vendor}·{@code demo} 넷.
     *              ⚠ <b>모르는 값이 오면 「센 것」으로 치지 않는다</b> — 앞으로 역할이 늘 수 있고,
     *              모르는 것을 집계에 넣으면 우리가 색인보다 많이 아는 척하게 된다
     * @param facet {@code skin} 일 때만 있다
     */
    public record CssFile(String path, String role, String facet) {

        public boolean counted() {
            return "common".equals(role) || "skin".equals(role);
        }

        /** 화면에 뜨는 짧은 이름. 경로 전체는 제목 속성으로 따로 보여준다. */
        public String name() {
            int cut = path.lastIndexOf('/');
            return cut < 0 ? path : path.substring(cut + 1);
        }
    }

    /**
     * 토큰 선언 하나.
     *
     * <p>⭐ <b>{@code scope} 가 이 기록의 값이다.</b> 실물 {@code webview/iksan} 의
     * {@code --brand-primary} 는 {@code :root} 에서 {@code #005095} · {@code [data-main="store"]}
     * 에서 {@code #00aca9} 다. 스코프를 버리면 store 모드 색이 화면 어디에도 안 남고,
     * 그 색을 쓰는 화면을 AI 가 지어내게 된다.
     */
    public record TokenDeclaration(String scope, String value, String file, int line) {

        /** 색으로 칠할 수 있는 값인가. 색이 아닌 토큰(길이·그림자)도 실재한다. */
        public boolean isColor() {
            return value != null && (value.startsWith("#")
                    || value.startsWith("rgb") || value.startsWith("hsl"));
        }
    }

    /** 빈도 집계 한 줄. <b>빈도 내림 → 값 오름</b>으로 이미 정렬돼 온다. */
    public record Tally(String value, int n) {
    }

    /**
     * 글꼴·크기·굵기.
     *
     * <p>⚠ <b>{@code families} 는 스택 전체가 한 문자열이다</b>(실물:
     * {@code 'suit','malgun gothic',applegothicneosd,…}). 쪼개면 소스에 없는 목록이 된다.
     */
    public record Typography(List<Tally> families, List<Tally> sizes, List<Tally> weights) {

        public static Typography empty() {
            return new Typography(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return families.isEmpty() && sizes.isEmpty() && weights.isEmpty();
        }
    }
}
