package com.bizplay.builder.screenid;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenIdMaterialReaderTest extends AbstractDbTest {

    private Path cloneToClean;

    @Autowired ProjectMapper projects;
    @Autowired ProjectPaths paths;
    @Autowired SecretSealer sealer;
    @Autowired ScreenIdMaterialReader reader;

    @AfterEach
    void cleanClone() {
        if (cloneToClean != null) FileSystemUtils.deleteRecursively(cloneToClean.toFile());
    }

    @Test
    void IA_경로를_얻은_화면만_경로순으로_나온다() throws Exception {
        String projectId = seedClone();

        List<ScreenIdMaterial> materials = reader.read(projectId);

        // 정렬은 ① IA 경로 사전순 → ② 화면ID 사전순 이다 (스펙 §2.2).
        // "customer/usag" < "merchant/base" 이므로 고객관리가 먼저 온다.
        assertThat(materials).extracting(ScreenIdMaterial::screenId)
                .containsExactly("bo-usag-list", "bo-merc-detail", "bo-merc-list");
        assertThat(materials).extracting(ScreenIdMaterial::areaKey)
                .containsExactly("customer", "merchant", "merchant");
        assertThat(materials.get(0).areaLabel()).isEqualTo("고객관리");
        assertThat(materials.get(0).groupKey()).isEqualTo("usag");
        assertThat(materials.get(0).groupLabel()).isEqualTo("이용기관");
        assertThat(materials.get(0).letter()).isEqualTo("L");
        assertThat(materials.get(1).letter()).isEqualTo("D");
    }

    @Test
    void ia_md_가_없는_시스템은_통째로_빠진다() throws Exception {
        String projectId = seedClone();
        Files.delete(paths.iaFile(projectId, "backoffice"));

        assertThat(reader.read(projectId)).isEmpty();
    }

    @Test
    void 경로를_못_얻은_화면은_빠진다() throws Exception {
        String projectId = seedClone();
        // bo-front-lnkgpop 은 index.json 에는 있지만 색인에 경로가 없고 부모도 없다.
        assertThat(reader.read(projectId)).extracting(ScreenIdMaterial::screenId)
                .doesNotContain("bo-front-lnkgpop");
    }

    @Test
    void 채번은_ia_md_배치를_보지_않는다() throws Exception {
        String projectId = seedClone();
        // ia.md 배치가 색인에 경로 없는 화면(bo-front-lnkgpop)을 배치해도 — 자리 재료는 색인이다
        // (2026-08-21 병주 확정). 배치 줄만으로는 번호를 받지 못해야 한다.
        Files.writeString(paths.iaFile(projectId, "backoffice"), """
                # backoffice IA 이름표
                ## 이름표
                - merchant: 가맹점
                - merchant/base: 기준정보
                - customer: 고객관리
                - customer/usag: 이용기관

                --- 배치 ---
                - 순서: 010 / 경로: merchant/base / 화면: bo-front-lnkgpop
                """, StandardCharsets.UTF_8);

        assertThat(reader.read(projectId)).extracting(ScreenIdMaterial::screenId)
                .doesNotContain("bo-front-lnkgpop");
    }

    @Test
    void ia_md_배치_아래_산문이_있어도_그_시스템이_채번된다() throws Exception {
        // lspnoffice 실측 (2026-08-21) — 배치 표 아래 설명 문단이 시스템 19장 전부를
        // 채번에서 밀어냈다. 이름표만 쓰는 지금은 배치 구간이 어떤 모양이어도 살아야 한다.
        String projectId = seedClone();
        Files.writeString(paths.iaFile(projectId, "backoffice"), """
                # backoffice IA 이름표
                ## 이름표
                - merchant: 가맹점
                - merchant/base: 기준정보
                - customer: 고객관리
                - customer/usag: 이용기관

                --- 배치 ---
                - 순서: 010 / 경로: merchant/base / 화면: bo-merc-list
                - **상위화면 사슬이 나르는 다섯**: 목록이 상세를 열고, 상세가 팝업을 연다.
                """, StandardCharsets.UTF_8);

        assertThat(reader.read(projectId)).extracting(ScreenIdMaterial::screenId)
                .contains("bo-merc-list", "bo-merc-detail", "bo-usag-list");
    }

    @Test
    void 색인_경로가_세_마디여도_업무영역과_기능그룹은_앞_두_마디다() throws Exception {
        // 색인 경로는 상위화면 사슬까지 실려 두 마디를 넘을 수 있다 — 트리와 재료는 같아도
        // 채번이 쓰는 깊이는 업무영역·기능그룹 둘뿐이다.
        String projectId = seedClone();
        addScreen(projectId, "backoffice", "bo-merc-deep", "화면", "상세",
                null, java.util.List.of(), "merchant/base/extra");

        java.util.Optional<ScreenIdMaterial> deep = reader.read(projectId).stream()
                .filter(m -> m.screenId().equals("bo-merc-deep")).findFirst();

        assertThat(deep).isPresent();
        assertThat(deep.get().areaKey()).isEqualTo("merchant");
        assertThat(deep.get().groupKey()).isEqualTo("base");
        assertThat(deep.get().pathKey()).isEqualTo("merchant/base/extra");
    }

    @Test
    void 같은_화면ID는_한_번만_소속_시스템으로_나온다() throws Exception {
        String projectId = seedClone();
        Path clone = paths.cloneDir(projectId);
        // webview 도 manifest.json 의 systems[] 에 있어야 건너뛰지 않는다.
        writeManifest(clone, "backoffice", "bo", "webview", "wv");

        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/4","screens":{
                  "bo-merc-list":{"system":"backoffice","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"merchant/base"}},
                  "bo-merc-detail":{"system":"backoffice","ia":{"종류":"화면","화면유형":"상세","유형근거":"ID","경로":"merchant/base"}},
                  "bo-usag-list":{"system":"backoffice","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"customer/usag"}},
                  "bo-front-lnkgpop":{"system":"backoffice","ia":{"종류":"팝업","화면유형":"미분류"}},
                  "wv-x-screen":{"system":"webview","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"other"}}
                },"iaShared":{"backoffice":["bo-front-lnkgpop"]}}
                """, StandardCharsets.UTF_8);

        // webview 의 ia.md 배치가 backoffice 소속 화면(bo-merc-list)을 잘못 배치한 실측 모양 —
        // 자리 재료가 색인이라 배치는 안 읽히지만, 같은 screenId 로 material 이 두 번 나오면
        // unique (project_id, screen_id) 가 깨져 프로젝트 전체 채번이 영구히 막힌다. 그 계약을 고정한다.
        Path webview = clone.resolve("core").resolve("webview");
        Files.createDirectories(webview.resolve("pages"));
        Files.writeString(webview.resolve("pages").resolve("wv-x-screen.md"),
                "화면명: wv-x-screen\n", StandardCharsets.UTF_8);
        Files.writeString(webview.resolve("ia.md"), """
                # webview IA 이름표
                ## 이름표
                - other: 기타

                --- 배치 ---
                - 순서: 010 / 경로: other / 화면: wv-x-screen
                - 순서: 020 / 경로: other / 화면: bo-merc-list
                """, StandardCharsets.UTF_8);

        List<ScreenIdMaterial> materials = reader.read(projectId);

        assertThat(materials).filteredOn(m -> m.screenId().equals("bo-merc-list"))
                .hasSize(1)
                .extracting(ScreenIdMaterial::systemCode)
                .containsExactly("backoffice");
        assertThat(materials).filteredOn(m -> m.screenId().equals("wv-x-screen"))
                .extracting(ScreenIdMaterial::systemCode2)
                .containsExactly("WV");
    }

    /**
     * 최소 클론 하나를 앉힌다.
     *
     * ⚠ index.json 과 화면 md 의 실제 모양은 {@code SolutionScreenReader} 의 javadoc 과
     * 기존 테스트({@code IaScreenTest.seedPlanningRepo})가 정본이다 — <b>그것을 먼저 읽고 베껴라.</b>
     */
    private String seedClone() throws Exception {
        var sealed = sealer.seal("glpat-시험용토큰");
        String projectId = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(projectId, "재료 읽기 시험 " + projectId,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(projectId, ProjectState.READY, null);

        Path clone = paths.cloneDir(projectId);
        FileSystemUtils.deleteRecursively(clone);
        cloneToClean = clone;
        Path core = clone.resolve("core").resolve("backoffice");
        Files.createDirectories(core.resolve("pages"));

        // ⚠ 실측한 모양이다 — 분류와 경로가 ia 블록 안에 든다. IaScreenTest.seedPlanningRepo 가 정본이다.
        //   2026-08-21 부터 채번의 자리 재료는 ia.md 배치가 아니라 이 색인의 경로다.
        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/4","screens":{
                  "bo-merc-list":{"system":"backoffice","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"merchant/base"}},
                  "bo-merc-detail":{"system":"backoffice","ia":{"종류":"화면","화면유형":"상세","유형근거":"ID","경로":"merchant/base"}},
                  "bo-usag-list":{"system":"backoffice","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"customer/usag"}},
                  "bo-front-lnkgpop":{"system":"backoffice","ia":{"종류":"팝업","화면유형":"미분류"}}
                },"iaShared":{"backoffice":["bo-front-lnkgpop"]}}
                """, StandardCharsets.UTF_8);
        for (String screenId : java.util.List.of("bo-merc-list", "bo-merc-detail",
                "bo-usag-list", "bo-front-lnkgpop")) {
            Files.writeString(core.resolve("pages").resolve(screenId + ".md"),
                    "화면명: " + screenId + "\n", StandardCharsets.UTF_8);
        }

        // ⚠ ia.md 는 한글 이름표의 정본으로만 쓰인다 — 배치 블록이 없어도 채번은 돌아야 한다.
        Files.writeString(core.resolve("ia.md"), """
                # backoffice IA 이름표
                ## 이름표
                - merchant: 가맹점
                - merchant/base: 기준정보
                - customer: 고객관리
                - customer/usag: 이용기관
                """, StandardCharsets.UTF_8);

        // ⚠ 2026-08-20 실측 — manifest.json 의 systems[] 가 시스템마다 prefix 를 갖는다
        // (bo·wv·pg·so·lo·pt). 그것을 대문자로 올린 것이 표준 화면ID 의 시스템 마디다.
        writeManifest(clone, "backoffice", "bo");

        return projectId;
    }

    /** {@code manifest.json} 을 (다시) 쓴다 — 뒤에 부른 것이 앞선 것을 덮는다. */
    private void writeManifest(Path clone, String... idAndPrefixPairs) throws Exception {
        StringBuilder systems = new StringBuilder();
        for (int i = 0; i < idAndPrefixPairs.length; i += 2) {
            if (systems.length() > 0) systems.append(',');
            systems.append("{\"id\":\"").append(idAndPrefixPairs[i])
                    .append("\",\"prefix\":\"").append(idAndPrefixPairs[i + 1]).append("\"}");
        }
        Files.writeString(clone.resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":[" + systems + "]}",
                StandardCharsets.UTF_8);
    }

    @Test
    void 시스템_마디는_manifest_json_의_prefix_를_대문자로_옮긴_것이다() throws Exception {
        String projectId = seedClone();
        Path clone = paths.cloneDir(projectId);
        // ⚠ bo 가 아니라 낯선 접두어를 써서 「BO 가 코드에 박힌 것이 아니라 manifest 에서 왔다」를 증명한다.
        writeManifest(clone, "backoffice", "zz");

        List<ScreenIdMaterial> materials = reader.read(projectId);

        assertThat(materials).extracting(ScreenIdMaterial::systemCode2)
                .allMatch("ZZ"::equals);
    }

    @Test
    void manifest_json_의_systems_에_없는_시스템은_통째로_빠진다() throws Exception {
        String projectId = seedClone();     // manifest.json 은 backoffice 뿐이다
        Path clone = paths.cloneDir(projectId);

        // webview 화면을 실제로 심어도 — manifest.json 의 systems[] 에 없으면 건너뛴다.
        Files.writeString(clone.resolve("index.json"), """
                {"schema":"we-adk-index/4","screens":{
                  "bo-merc-list":{"system":"backoffice","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"merchant/base"}},
                  "bo-merc-detail":{"system":"backoffice","ia":{"종류":"화면","화면유형":"상세","유형근거":"ID","경로":"merchant/base"}},
                  "bo-usag-list":{"system":"backoffice","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"customer/usag"}},
                  "bo-front-lnkgpop":{"system":"backoffice","ia":{"종류":"팝업","화면유형":"미분류"}},
                  "wv-x-screen":{"system":"webview","ia":{"종류":"화면","화면유형":"목록","유형근거":"ID","경로":"other"}}
                },"iaShared":{"backoffice":["bo-front-lnkgpop"]}}
                """, StandardCharsets.UTF_8);
        Path webview = clone.resolve("core").resolve("webview");
        Files.createDirectories(webview.resolve("pages"));
        Files.writeString(webview.resolve("pages").resolve("wv-x-screen.md"),
                "화면명: wv-x-screen\n", StandardCharsets.UTF_8);
        Files.writeString(webview.resolve("ia.md"), """
                # webview IA 이름표
                ## 이름표
                - other: 기타
                """, StandardCharsets.UTF_8);

        List<ScreenIdMaterial> materials = reader.read(projectId);

        assertThat(materials).extracting(ScreenIdMaterial::screenId).doesNotContain("wv-x-screen");
        assertThat(materials).extracting(ScreenIdMaterial::screenId)
                .contains("bo-merc-list", "bo-merc-detail", "bo-usag-list");   // backoffice 는 그대로다
    }

    @Test
    void manifest_json_이_없으면_전부_건너뛴다() throws Exception {
        String projectId = seedClone();
        Files.delete(paths.cloneDir(projectId).resolve("manifest.json"));

        assertThat(reader.read(projectId)).isEmpty();
    }

    @Test
    void manifest_json_이_깨져도_던지지_않고_전부_건너뛴다() throws Exception {
        String projectId = seedClone();
        Files.writeString(paths.cloneDir(projectId).resolve("manifest.json"), "{이건 JSON 이 아니다",
                StandardCharsets.UTF_8);

        assertThat(reader.read(projectId)).isEmpty();
    }

    @Test
    void 배치를_못_얻은_팝업은_여는_화면의_업무영역을_물려받는다() throws Exception {
        String projectId = seedClone();
        // bo-merc-list 가 여는 팝업. ia.md 배치에는 줄이 없다 — 메뉴로 직접 들어가는 물건이 아니다.
        addScreen(projectId, "backoffice", "bo-merc-search-pop", "팝업", "미분류",
                null, java.util.List.of("bo-merc-list"));

        java.util.Optional<ScreenIdMaterial> pop = reader.read(projectId).stream()
                .filter(m -> m.screenId().equals("bo-merc-search-pop")).findFirst();

        assertThat(pop).isPresent();
        assertThat(pop.get().areaKey()).isEqualTo("merchant");
        assertThat(pop.get().groupKey()).isEqualTo("base");
        // ⚠ 유형 글자는 물려받지 않는다 — 부모가 목록(L)이어도 팝업은 P 다.
        assertThat(pop.get().letter()).isEqualTo("P");
    }

    @Test
    void 상위화면이_있으면_여는화면보다_먼저_본다() throws Exception {
        String projectId = seedClone();
        addScreen(projectId, "backoffice", "bo-two-parents", "화면", "상세",
                "bo-usag-list", java.util.List.of("bo-merc-list"));

        java.util.Optional<ScreenIdMaterial> child = reader.read(projectId).stream()
                .filter(m -> m.screenId().equals("bo-two-parents")).findFirst();

        assertThat(child).isPresent();
        // 상위화면(bo-usag-list → customer)이 이기고 여는화면(bo-merc-list → merchant)은 진다.
        assertThat(child.get().areaKey()).isEqualTo("customer");
    }

    @Test
    void 여는_화면이_여럿이면_화면ID_사전순_첫째를_따른다() throws Exception {
        String projectId = seedClone();
        // 넣는 순서를 일부러 뒤집어 둔다 — 사전순이 이겨야 한다.
        addScreen(projectId, "backoffice", "bo-shared-pop", "팝업", "미분류",
                null, java.util.List.of("bo-usag-list", "bo-merc-list"));

        java.util.Optional<ScreenIdMaterial> pop = reader.read(projectId).stream()
                .filter(m -> m.screenId().equals("bo-shared-pop")).findFirst();

        assertThat(pop).isPresent();
        // "bo-merc-list" < "bo-usag-list" 이므로 가맹점이 이긴다. 재색인마다 흔들리면 안 된다.
        assertThat(pop.get().areaKey()).isEqualTo("merchant");
    }

    @Test
    void 부모의_부모까지_올라가_배치를_찾는다() throws Exception {
        String projectId = seedClone();
        addScreen(projectId, "backoffice", "bo-mid-pop", "팝업", "미분류",
                null, java.util.List.of("bo-merc-list"));
        addScreen(projectId, "backoffice", "bo-leaf-pop", "팝업", "미분류",
                null, java.util.List.of("bo-mid-pop"));

        java.util.Optional<ScreenIdMaterial> leaf = reader.read(projectId).stream()
                .filter(m -> m.screenId().equals("bo-leaf-pop")).findFirst();

        assertThat(leaf).isPresent();
        assertThat(leaf.get().areaKey()).isEqualTo("merchant");
    }

    @Test
    void 서로를_여는_고리가_있어도_죽지_않고_번호를_안_준다() throws Exception {
        String projectId = seedClone();
        // 실제로 있을 수 있는 모양이다. 고리 감시가 없으면 여기서 무한 재귀로 죽는다.
        addScreen(projectId, "backoffice", "bo-loop-a", "팝업", "미분류",
                null, java.util.List.of("bo-loop-b"));
        addScreen(projectId, "backoffice", "bo-loop-b", "팝업", "미분류",
                null, java.util.List.of("bo-loop-a"));

        java.util.List<ScreenIdMaterial> materials = reader.read(projectId);

        assertThat(materials).extracting(ScreenIdMaterial::screenId)
                .doesNotContain("bo-loop-a", "bo-loop-b");
    }

    @Test
    void 부모_사슬로도_안_닿는_화면은_번호를_안_받는다() throws Exception {
        String projectId = seedClone();
        addScreen(projectId, "backoffice", "bo-orphan", "모달", "미분류", null, java.util.List.of());

        assertThat(reader.read(projectId)).extracting(ScreenIdMaterial::screenId)
                .doesNotContain("bo-orphan");
    }

    /** 색인에 자기 경로가 없는 화면을 더한다 — 부모 상속을 재는 시험이 쓴다. */
    private void addScreen(String projectId, String system, String screenId, String kind,
                           String screenType, String parent, java.util.List<String> opening) throws Exception {
        addScreen(projectId, system, screenId, kind, screenType, parent, opening, null);
    }

    /**
     * index.json 에 화면 한 줄을 더한다.
     *
     * ⚠ SolutionScreenReader 가 index.json 의 mtime 으로 캐시를 판별하므로 시각을 밀어 둔다.
     */
    private void addScreen(String projectId, String system, String screenId, String kind,
                           String screenType, String parent, java.util.List<String> opening,
                           String iaPath) throws Exception {
        Path clone = paths.cloneDir(projectId);
        Path index = clone.resolve("index.json");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(index));
        com.fasterxml.jackson.databind.node.ObjectNode screens =
                (com.fasterxml.jackson.databind.node.ObjectNode) root.get("screens");
        com.fasterxml.jackson.databind.node.ObjectNode one = screens.putObject(screenId);
        one.put("system", system);
        com.fasterxml.jackson.databind.node.ObjectNode ia = one.putObject("ia");
        ia.put("종류", kind);
        ia.put("화면유형", screenType);
        if (iaPath != null) ia.put("경로", iaPath);
        if (parent != null) ia.put("상위화면", parent);
        if (opening != null && !opening.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = ia.putArray("여는화면");
            opening.forEach(arr::add);
        }
        Files.writeString(index, json.writeValueAsString(root), StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("core").resolve(system).resolve("pages").resolve(screenId + ".md"),
                "화면명: " + screenId + "\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(index,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
    }
}
