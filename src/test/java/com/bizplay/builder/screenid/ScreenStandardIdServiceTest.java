package com.bizplay.builder.screenid;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ScreenStandardIdServiceTest extends AbstractDbTest {

    @Autowired ProjectMapper projects;
    @Autowired ScreenStandardIdMapper standardIds;
    @Autowired ScreenIdGroupMapper groups;
    @Autowired SecretSealer sealer;
    @Autowired ScreenStandardIdService service;
    @Autowired ProjectPaths paths;
    @Autowired ObjectMapper json;

    /**
     * ⛔ 실물 {@code ClaudeBusinessAreaCoder} 가 불리면 정말로 {@code claude} 프로세스를 띄운다.
     * {@code @MockitoBean} 으로 갈아 끼워 항상 대역만 타게 한다 — CloneWorkerTest 가 쓰는 것과 같은 길이다.
     */
    @MockitoBean BusinessAreaCoder coder;

    /** {@code stubCoder} 대역이 실제로 물어본 업무영역 slug 를 쌓는다. */
    private final List<String> askedAreas = new ArrayList<>();

    private Path cloneToClean;

    @AfterEach
    void cleanClone() {
        if (cloneToClean != null) FileSystemUtils.deleteRecursively(cloneToClean.toFile());
    }

    @Test
    void 매핑표는_한_프로젝트에서_같은_표준ID를_두_번_받지_않는다() {
        String projectId = newProject();
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                projectId, "bo-usag-list", "PS-BO-MRC-010-L01", ScreenStandardId.Origin.S, 1));

        assertThat(standardIds.selectByProject(projectId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.screenId()).isEqualTo("bo-usag-list");
                    assertThat(row.standardId()).isEqualTo("PS-BO-MRC-010-L01");
                    assertThat(row.origin()).isEqualTo(ScreenStandardId.Origin.S);
                });

        assertThatThrownBy(() -> standardIds.insert(new ScreenStandardId(
                ids.next(IdSequence.Kind.SCREEN_STANDARD_ID),
                projectId, "bo-usag-detail", "PS-BO-MRC-010-L01", ScreenStandardId.Origin.S, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 코드표는_같은_업무영역에_같은_세글자를_두_번_주지_않는다() {
        String projectId = newProject();
        groups.insert(new ScreenIdGroup(ids.next(IdSequence.Kind.SCREEN_ID_GROUP),
                projectId, "backoffice", "merchant", "MRC", "가맹점", "master", 10, "기준정보"));

        assertThat(groups.selectByProject(projectId)).singleElement()
                .satisfies(row -> {
                    assertThat(row.areaKey()).isEqualTo("merchant");
                    assertThat(row.areaCode()).isEqualTo("MRC");
                    assertThat(row.groupNo()).isEqualTo(10);
                });

        assertThatThrownBy(() -> groups.insert(new ScreenIdGroup(
                ids.next(IdSequence.Kind.SCREEN_ID_GROUP),
                projectId, "backoffice", "customer", "MRC", "고객관리", "base", 10, "기본")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 못_지은_XXX_는_여럿이어도_된다() {
        // XXX 는 코드가 아니라 「못 지었다」는 표시다. 이것까지 막으면 AI 가 실패한 날 채번이 통째로 깨진다.
        String projectId = newProject();
        groups.insert(new ScreenIdGroup(ids.next(IdSequence.Kind.SCREEN_ID_GROUP),
                projectId, "backoffice", "merchant", "XXX", "가맹점", "base", 10, "기준정보"));
        groups.insert(new ScreenIdGroup(ids.next(IdSequence.Kind.SCREEN_ID_GROUP),
                projectId, "backoffice", "customer", "XXX", "고객관리", "usag", 10, "이용기관"));

        assertThat(groups.selectByProject(projectId)).hasSize(2);
    }

    @Test
    void 최초_채번은_경로순으로_일번부터_붙인다() throws Exception {
        String projectId = seedClone();     // ScreenIdMaterialReaderTest 와 같은 클론
        stubCoder("merchant", "MRC", "customer", "CUS");

        assertThat(service.assign(projectId, null)).isEqualTo(3);

        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::screenId, ScreenStandardId::standardId)
                .containsExactly(
                        tuple("bo-usag-list",   "PS-BO-CUS-010-L01"),
                        tuple("bo-merc-detail", "PS-BO-MRC-010-D01"),
                        tuple("bo-merc-list",   "PS-BO-MRC-010-L01"));
    }

    @Test
    void 프로젝트의_플랫폼_코드가_표준ID_첫_마디에_실린다() throws Exception {
        // ⚠ 기본값 PS 가 아닌 값을 써서 「properties.platform() 이 아니라 프로젝트 열에서 왔다」를 증명한다.
        String projectId = seedClone("KTX");
        stubCoder("merchant", "MRC", "customer", "CUS");

        assertThat(service.assign(projectId, null)).isEqualTo(3);

        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::standardId)
                .containsExactlyInAnyOrder("KTX-BO-CUS-010-L01", "KTX-BO-MRC-010-D01", "KTX-BO-MRC-010-L01");
    }

    @Test
    void 두_번_돌려도_같은_값이고_새로_박히는_것이_없다() throws Exception {
        String projectId = seedClone();
        stubCoder("merchant", "MRC", "customer", "CUS");

        service.assign(projectId, null);
        // ⚠ standardId 목록만 비교하면 같은 ID 집합이 다른 화면으로 재배정돼도 통과한다 —
        //   screenId ↔ standardId 짝(과 sortNo)까지 그대로인지를 봐야 이 기능의 핵심 불변식을 잰다.
        List<List<?>> first = standardIds.selectByProject(projectId).stream()
                .<List<?>>map(row -> List.of(row.screenId(), row.standardId(), row.sortNo())).toList();

        assertThat(service.assign(projectId, null)).isZero();
        List<List<?>> second = standardIds.selectByProject(projectId).stream()
                .<List<?>>map(row -> List.of(row.screenId(), row.standardId(), row.sortNo())).toList();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 나중에_들어온_화면은_사전순_중간이어도_뒤에_붙는다() throws Exception {
        String projectId = seedClone();
        stubCoder("merchant", "MRC", "customer", "CUS");
        service.assign(projectId, null);

        // 사전순으로는 bo-merc-list 앞이지만, 이미 L01 이 나갔으므로 L02 여야 한다.
        addScreenToClone(projectId, "bo-merc-approve", "merchant/base", "목록");

        assertThat(service.assign(projectId, null)).isEqualTo(1);
        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::screenId, ScreenStandardId::standardId)
                .contains(tuple("bo-merc-list",    "PS-BO-MRC-010-L01"),
                          tuple("bo-merc-approve", "PS-BO-MRC-010-L02"));
    }

    @Test
    void 상위_메뉴에_직접_배치한_신규_화면은_새_기능그룹을_받고_하위_화면도_이어_채번된다() throws Exception {
        String projectId = seedClone();
        stubCoder("merchant", "MRC", "customer", "CUS");
        service.assign(projectId, null);

        var list = service.allocateForNewScreenAtMenu(projectId, "tmp-0000067",
                "backoffice", "merchant", "폐업가맹점 목록 조회", "목록");
        var detail = service.allocateForNewScreen(projectId, "tmp-0000068",
                "tmp-0000067", "상세");

        assertThat(list).contains("PS-BO-MRC-020-L01");
        assertThat(detail).contains("PS-BO-MRC-020-D01");
        assertThat(groups.selectByProject(projectId))
                .extracting(ScreenIdGroup::systemCode, ScreenIdGroup::areaKey,
                        ScreenIdGroup::groupKey, ScreenIdGroup::groupNo, ScreenIdGroup::groupLabel)
                .contains(tuple("backoffice", "merchant", "tmp-0000067", 20, "폐업가맹점 목록 조회"));
    }

    @Test
    void 이미_있는_업무영역은_AI_에게_다시_묻지_않는다() throws Exception {
        String projectId = seedClone();
        stubCoder("merchant", "MRC", "customer", "CUS");
        service.assign(projectId, null);

        askedAreas.clear();
        addScreenToClone(projectId, "bo-merc-approve", "merchant/base", "목록");
        service.assign(projectId, null);

        assertThat(askedAreas).isEmpty();
    }

    @Test
    void IA_경로가_없는_화면은_번호를_안_받는다() throws Exception {
        String projectId = seedClone();
        stubCoder("merchant", "MRC", "customer", "CUS");

        service.assign(projectId, null);

        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::screenId)
                .doesNotContain("bo-front-lnkgpop");
    }

    @Test
    void IA_경로가_한_마디뿐인_화면은_그룹번호_000을_받고_형제_그룹을_밀지_않는다() throws Exception {
        String projectId = seedClone();
        // "merchant" 는 한 마디뿐이다 — 기능그룹이 없다. merchant/base 보다 사전순으로 앞선다.
        addScreenToClone(projectId, "bo-merc-home", "merchant", "안내");
        stubCoder("merchant", "MRC", "customer", "CUS");

        service.assign(projectId, null);

        assertThat(groups.selectByProject(projectId))
                .extracting(ScreenIdGroup::areaKey, ScreenIdGroup::groupKey, ScreenIdGroup::groupNo)
                .contains(tuple("merchant", "", 0), tuple("merchant", "base", 10));
        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::screenId, ScreenStandardId::standardId)
                .contains(tuple("bo-merc-home", "PS-BO-MRC-000-G01"),
                          tuple("bo-merc-list", "PS-BO-MRC-010-L01"));
    }

    @Test
    void 매핑안된_시스템의_화면은_그룹도_채번도_안_받고_다른_시스템의_번호를_밀지_않는다() throws Exception {
        String projectId = seedClone();
        addScreenInUnmappedSystem(projectId, "fr-merc-home", "merchant");
        stubCoder("merchant", "MRC", "customer", "CUS");

        // 백오피스 셋만 받는다 — "front" 는 manifest.json 의 systems[] 에 없다.
        assertThat(service.assign(projectId, null)).isEqualTo(3);

        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::screenId)
                .doesNotContain("fr-merc-home");
        // ⚠ 이 재검토 라운드에서 group_no 카운터가 (systemCode, areaKey) 복합열로 바뀌었다
        //   (Finding 새로운1 — 아래 참고). 그래서 "front" 가 걸러지든 말든 backoffice 의
        //   merchant/base 카운터는 이제 구조적으로 못 밀린다 — 그 단정을 여기 다시 넣으면
        //   무엇을 재는지 착각하게 만드는 "항상 통과하는 단정"이 된다. 이 테스트에서
        //   필터가 실제로 일하는지 증명하는 것은 아래 group 행 부재 단정 하나뿐이다.
        assertThat(groups.selectByProject(projectId))
                .extracting(ScreenIdGroup::systemCode)
                .doesNotContain("front");
    }

    /**
     * Finding 2(재검토) — {@code codeByArea} 는 슬러그 하나로 여러 시스템이 나눠 쓰지만
     * {@code group_no} 카운터는 시스템마다 따로 세야 한다. 경로가 한 마디뿐인 화면이
     * 두 시스템에 같은 업무영역 슬러그로 동시에 있어도 {@code assign} 이 안 깨지고,
     * 둘 다 {@code group_no} 0 을 받으며 표준ID 는 시스템 마디로만 갈린다.
     */
    @Test
    void 서로_다른_시스템이_같은_업무영역_슬러그를_경로_한마디로_쓰면_둘_다_그룹번호_0을_받는다() throws Exception {
        String projectId = seedClone();
        addManifestSystem(projectId, "webview", "wv");   // manifest.json 의 systems[] 에도 올려야 채번된다
        addScreenToClone(projectId, "bo-merc-home", "merchant", "안내");
        addScreenInSystem(projectId, "webview", "wv-merc-home", "merchant", "안내");
        stubCoder("merchant", "MRC", "customer", "CUS");

        assertThatCode(() -> service.assign(projectId, null)).doesNotThrowAnyException();

        assertThat(groups.selectByProject(projectId))
                .extracting(ScreenIdGroup::systemCode, ScreenIdGroup::areaKey,
                        ScreenIdGroup::groupKey, ScreenIdGroup::groupNo)
                .contains(tuple("backoffice", "merchant", "", 0),
                          tuple("webview", "merchant", "", 0));
        // ⚠ 웹뷰는 WB 가 아니라 WV 다 — manifest.json 의 systems[].prefix 가 wv 이기 때문이다
        //   (2026-08-20 재확인. 보스 예시의 WB 는 근거가 없었다).
        assertThat(standardIds.selectByProject(projectId))
                .extracting(ScreenStandardId::screenId, ScreenStandardId::standardId)
                .contains(tuple("bo-merc-home", "PS-BO-MRC-000-G01"),
                          tuple("wv-merc-home", "PS-WV-MRC-000-G01"));
    }

    /** 대역이 물어본 업무영역을 {@code askedAreas} 에 쌓아 두고, 주어진 표를 그대로 낸다. */
    private void stubCoder(String... slugAndCode) {
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = 0; i < slugAndCode.length; i += 2) {
            table.put(slugAndCode[i], slugAndCode[i + 1]);
        }
        when(coder.codesOf(any(), any(), any())).thenAnswer(invocation -> {
            Map<String, String> requestedAreas = invocation.getArgument(2);
            askedAreas.addAll(requestedAreas.keySet());
            Map<String, String> result = new LinkedHashMap<>();
            requestedAreas.keySet().forEach(area -> result.put(area, table.getOrDefault(area, "XXX")));
            return result;
        });
    }

    /**
     * 클론에 화면 한 장을 더 앉힌다 — {@code index.json} 한 줄(색인 {@code 경로} 째로) ·
     * {@code pages/<screenId>.md}. {@code ia.md} 는 안 건드린다 — 자리 재료는 색인이다.
     *
     * <p>⚠ {@code SolutionScreenReader} 가 {@code index.json} 을 프로젝트마다 캐시한다
     * ({@code git HEAD + index.json mtime + facetStamp}). 같은 밀리초 안에서 다시 읽으면
     * 캐시가 낡은 값을 그대로 낸다 — {@code index.json} 의 수정 시각을 미래로 밀어 강제로
     * 도장을 갈아 준다({@code IaScreenTest} 가 {@code FileTime} 을 쓰는 것과 같은 까닭이다).
     */
    private void addScreenToClone(String projectId, String screenId, String pathKey, String screenType)
            throws Exception {
        Path clone = paths.cloneDir(projectId);
        Path index = clone.resolve("index.json");
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(index, StandardCharsets.UTF_8));
        ObjectNode screens = (ObjectNode) root.get("screens");
        ObjectNode screen = json.createObjectNode();
        screen.put("system", "backoffice");
        ObjectNode ia = json.createObjectNode();
        ia.put("종류", "화면");
        ia.put("화면유형", screenType);
        ia.put("유형근거", "ID");
        ia.put("경로", pathKey);
        screen.set("ia", ia);
        screens.set(screenId, screen);
        Files.writeString(index, json.writeValueAsString(root), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(index, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        Path core = clone.resolve("core").resolve("backoffice");
        Files.writeString(core.resolve("pages").resolve(screenId + ".md"),
                "화면명: " + screenId + "\n", StandardCharsets.UTF_8);
    }

    /**
     * {@code manifest.json} 의 {@code systems[]} 에 없는 시스템("front")에 화면 한 장을 심는다.
     * Finding 2 — 매핑 안 된 시스템이 채번을 타지 않는지 재는 자리에 쓴다.
     */
    private void addScreenInUnmappedSystem(String projectId, String screenId, String pathKey) throws Exception {
        Path clone = paths.cloneDir(projectId);
        Path front = clone.resolve("core").resolve("front");
        Files.createDirectories(front.resolve("pages"));
        Files.writeString(front.resolve("ia.md"), """
                # front IA 이름표
                ## 이름표
                - merchant: 가맹점
                """, StandardCharsets.UTF_8);
        Files.writeString(front.resolve("pages").resolve(screenId + ".md"),
                "화면명: " + screenId + "\n", StandardCharsets.UTF_8);

        Path index = clone.resolve("index.json");
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(index, StandardCharsets.UTF_8));
        ObjectNode screens = (ObjectNode) root.get("screens");
        ObjectNode screen = json.createObjectNode();
        screen.put("system", "front");
        ObjectNode ia = json.createObjectNode();
        ia.put("종류", "화면");
        ia.put("화면유형", "안내");
        ia.put("유형근거", "ID");
        ia.put("경로", pathKey);
        screen.set("ia", ia);
        screens.set(screenId, screen);
        Files.writeString(index, json.writeValueAsString(root), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(index, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    }

    /**
     * {@code system} 에 화면 한 장을 심는다({@code manifest.json} 의 {@code systems[]} 에 이미
     * 있거나 {@link #addManifestSystem} 으로 올려 둔 시스템용).
     * 다른 시스템끼리 같은 업무영역 슬러그를 써도 안 깨지는지 재는 자리에 쓴다.
     */
    private void addScreenInSystem(String projectId, String system, String screenId, String pathKey,
                                   String screenType) throws Exception {
        Path clone = paths.cloneDir(projectId);
        Path core = clone.resolve("core").resolve(system);
        Files.createDirectories(core.resolve("pages"));
        Files.writeString(core.resolve("ia.md"), """
                # %s IA 이름표
                ## 이름표
                - merchant: 가맹점
                """.formatted(system), StandardCharsets.UTF_8);
        Files.writeString(core.resolve("pages").resolve(screenId + ".md"),
                "화면명: " + screenId + "\n", StandardCharsets.UTF_8);

        Path index = clone.resolve("index.json");
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(index, StandardCharsets.UTF_8));
        ObjectNode screens = (ObjectNode) root.get("screens");
        ObjectNode screen = json.createObjectNode();
        screen.put("system", system);
        ObjectNode ia = json.createObjectNode();
        ia.put("종류", "화면");
        ia.put("화면유형", screenType);
        ia.put("유형근거", "ID");
        ia.put("경로", pathKey);
        screen.set("ia", ia);
        screens.set(screenId, screen);
        Files.writeString(index, json.writeValueAsString(root), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(index, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    }

    /**
     * manifest.json 의 systems[] 에 시스템 하나를 더한다({@code prefix} 째로).
     *
     * <p>⚠ 실물로 채번을 태우려면 {@code addScreenInSystem} 만으로는 안 된다 — 그것은 클론의
     * {@code ia.md}·{@code index.json} 만 심고, {@code ScreenIdMaterialReader} 는
     * {@code manifest.json} 의 {@code systems[]} 에 없는 시스템을 통째로 건너뛴다(2026-08-20).
     */
    private void addManifestSystem(String projectId, String systemId, String prefix) throws Exception {
        Path manifest = paths.cloneDir(projectId).resolve("manifest.json");
        ObjectNode root = (ObjectNode) json.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        ArrayNode systems = (ArrayNode) root.get("systems");
        ObjectNode system = json.createObjectNode();
        system.put("id", systemId);
        system.put("prefix", prefix);
        systems.add(system);
        Files.writeString(manifest, json.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    /**
     * 최소 클론 하나를 앉힌다. {@code ScreenIdMaterialReaderTest.seedClone()} 을 그대로 베꼈다
     * (상속으로 묶지 않는다 — 읽는 사람이 두 파일을 오가게 된다).
     */
    private String seedClone() throws Exception {
        return seedClone("PS");
    }

    /** 플랫폼 코드를 골라 앉히는 갈래 — 표준ID 의 첫 마디가 프로젝트 값에서 오는지 재는 자리에 쓴다. */
    private String seedClone(String platformCode) throws Exception {
        var sealed = sealer.seal("glpat-시험용토큰");
        String projectId = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(projectId, "채번 시험 " + projectId,
                "https://gitlab.example.com/x.git", "main", platformCode, sealed.cipher(), sealed.nonce()));
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

        // ⚠ 2026-08-20 실측 — manifest.json 의 systems[] 가 시스템마다 prefix 를 갖는다.
        //   그것을 대문자로 올린 것이 표준 화면ID 의 시스템 마디다(builder.screen-id yml 을 대신한다).
        Files.writeString(clone.resolve("manifest.json"), """
                {"schema":"we-adk-planning-repo/1","systems":[{"id":"backoffice","prefix":"bo"}]}
                """, StandardCharsets.UTF_8);

        return projectId;
    }

    /** ⚠ Project 는 final 필드 + private 생성자다. 세터가 없다 — IaScreenTest.readyProject 와 같은 길이다. */
    private String newProject() {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "표준 화면ID 시험 " + id,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return id;
    }
}
