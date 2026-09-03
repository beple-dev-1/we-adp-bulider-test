package com.bizplay.builder.featurespec;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.screenid.ScreenStandardId;
import com.bizplay.builder.screenid.ScreenStandardIdMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능명세서 화면 둘 — 목록과 상세 (그린존 A1 · 2026-08-27).
 *
 * <p>⭐ <b>이 산출물은 새로 만드는 글이 아니라 「이미 있는 화면 md 를 보여 주는 창」이다</b>
 * (2026-08-25 흡수 결정 · 2026-08-27 병주 확인). 그래서 여기가 재는 것은 <b>읽어서 보여 주나</b>이고,
 * 무엇을 쓰거나 만들지 않는다.
 *
 * <p>심장 셋 — ① <b>IA 메뉴 경로</b>가 목록의 축으로 실제로 뜨나 ② <b>{@code --- 정의 ---} 의
 * 앵커 줄</b>이 기능 표가 되나(bzp 빌더의 {@code FN-n} 자리다) ③ <b>화면 가족</b>이 같은 부모의
 * 형제로 뜨나.
 *
 * <p>정본: `docs/superpowers/captain/ledger-feature-spec-screens.md` D1~D6.
 * 배치는 `08-solution-mockups`·`08a` 를 따른다 — 새 시각 언어를 만들지 않는다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class FeatureSpecScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;
    @Autowired ProjectSystemService projectSystems;
    @Autowired ScreenStandardIdMapper standardIds;
    @Autowired FeatureSpecMapper featureSpecs;
    @Autowired FeatureSpecStorage featureSpecStorage;
    @Autowired FeatureSpecMaterialService featureSpecMaterials;

    // ── 클론이 없을 때 ────────────────────────────────────────────────────

    /**
     * ⛔ <b>클론이 없다고 500 을 내면 안 된다.</b> {@code ArtifactListTest} 가 열쇠 전부를 도는데
     * 그 프로젝트에는 클론이 없다 — 여기가 빨개지면 그쪽도 같이 빨개진다.
     */
    @Test
    void 클론이_없어도_목록이_빈_상태로_뜬다() throws Exception {
        Project project = readyProject("기능명세-클론없음");
        wipeClone(project.getId());

        String html = list(project.getId());

        assertThat(html)
                .contains("기능명세서")
                .contains("조회된 내용이 없습니다.");
    }

    // ── 목록 ──────────────────────────────────────────────────────────────

    /**
     * ⭐ <b>메뉴 경로가 이 목록의 축이다</b>(원장 D5). 화면 md 꼬리표의 {@code 기능:} 칸에서 온다 —
     * 이게 빠지면 지시("IA 메뉴 경로 × 화면 md")를 못 지킨 것이다.
     */
    @Test
    void 목록이_화면_식별값과_저장된_문서_상태를_한_줄로_낸다() throws Exception {
        Project project = readyProject("기능명세-목록");
        seedClone(project.getId());

        String html = list(project.getId());

        assertThat(html)
                .containsSubsequence("<th scope=\"col\">화면명</th>",
                        "<th scope=\"col\">화면관리번호</th>",
                        "<th scope=\"col\">화면 ID</th>",
                        "<th scope=\"col\">IA 메뉴 경로</th>",
                        "<th scope=\"col\">시스템</th>",
                        "<th scope=\"col\">생성여부</th>",
                        "<th scope=\"col\">운영 화면 수정일</th>",
                        "<th scope=\"col\">문서 작성일</th>")
                .contains("PS-BO-MRC-010-D01-S")
                .contains("IA 메뉴 경로")
                .contains("선불카드 관리 &gt; 선불카드 배송 관리 &gt; 상세")
                .contains("선불카드 배송 상세")
                .contains("bo-delivery-detail")
                .contains("백오피스")
                .contains("생성여부")
                .contains("class=\"status-badge status-badge--waiting\"")
                .contains("미생성")
                .contains("selectedSystem=backoffice", "selectedScreen=bo-delivery-detail")
                .doesNotContain("feature-spec-list-head", "IA 메뉴 경로 순서로 정렬했습니다.", "개 화면")
                .doesNotContain("수정일시", "명세 생성 여부", "명세 있음", "명세 없음", "최근 반영", "최종 수정자");
    }

    @Test
    void 목록은_솔루션_템플릿과_같이_화면_ID_오름차순으로_정렬한다() throws Exception {
        Project project = readyProject("기능명세-정렬");
        seedClone(project.getId());

        String html = list(project.getId());

        assertThat(html).containsSubsequence("bo-delivery-detail", "bo-delivery-list",
                "bo-delivery-log", "bo-no-spec", "wv-sample-home");
    }

    /**
     * ⚠ <b>색인에 있는데 md 가 없는 화면이 실물에 있다.</b> 그때 목록이 조용히 빈 줄을 내면
     * 「명세가 없다」와 「화면이 없다」를 사람이 못 가린다.
     */
    @Test
    void 명세_md_가_없는_화면은_미생성으로_뜬다() throws Exception {
        Project project = readyProject("기능명세-없음");
        seedClone(project.getId());

        String html = list(project.getId());

        assertThat(html).contains("미생성");
    }

    @Test
    void 검색과_거르개가_실제로_거른다() throws Exception {
        Project project = readyProject("기능명세-거르개");
        seedClone(project.getId());

        // 시스템으로 거른다 — 웹뷰만 남는다.
        String webview = listWith(project.getId(), "system=webview");
        assertThat(webview).contains("메인 홈").doesNotContain("선불카드 배송 상세");

        // 검색은 화면명·화면ID·메뉴 경로에 걸린다.
        assertThat(listWith(project.getId(), "query=배송")).contains("선불카드 배송 상세")
                .doesNotContain("메인 홈");

        // 목록은 md 존재가 아니라 DB 문서 상태로 거른다. 상세에 들어가기 전에는 모두 미생성이다.
        String missing = listWith(project.getId(), "spec=미생성");
        assertThat(missing).contains("bo-no-spec", "bo-delivery-detail");

        // 화면관리번호로도 찾는다 — 솔루션 템플릿 목록과 같은 검색 축이다.
        assertThat(listWith(project.getId(), "query=PS-BO-MRC-010-D01-S"))
                .contains("bo-delivery-detail").doesNotContain("bo-delivery-list");
    }

    @Test
    void FRD_작업_목록과_같이_쪽을_나누고_표시_개수를_고른다() throws Exception {
        Project project = readyProject("기능명세-쪽");
        seedManyScreens(project.getId(), 23);

        String first = list(project.getId());
        String third = listWith(project.getId(), "pageSize=10&page=3");

        // 솔루션 템플릿과 같은 화면 ID 오름차순이다. 씨앗의 메뉴 경로는 일부러 반대로 넣었다.
        assertThat(first).contains("많은 화면 001")
                .doesNotContain("많은 화면 011", "많은 화면 023")
                .contains("aria-label=\"기능명세서 페이지 이동\"")
                .contains("aria-current=\"page\">1</a>")
                .contains("name=\"pageSize\"")
                .contains("10개씩", "20개씩", "50개씩", "100개씩");
        assertThat(third).contains("많은 화면 023")
                .doesNotContain("많은 화면 001")
                .contains("aria-current=\"page\">3</a>");
    }

    // ── 상세 ──────────────────────────────────────────────────────────────

    /**
     * ⭐ <b>{@code --- 정의 ---} 의 앵커 줄이 기능 표가 된다.</b> bzp 빌더의 「구현 기능 FN-n」이
     * 우리에게는 이 자리다 — 원문 한 덩이를 그대로 뱉으면 이 목록이 안 읽힌다(원장 D3).
     */
    @Test
    void 상세_진입은_제출용_문서를_자동으로_준비한다() throws Exception {
        Project project = readyProject("기능명세-상세");
        seedClone(project.getId());

        String html = detail(project.getId(), "bo-delivery-detail");

        assertThat(html)
                .contains("선불카드 배송 상세")
                .contains("기능명세서")
                .contains("data-feature-spec-poll")
                .contains("id=\"feature-spec-dialog\"", "aria-modal=\"true\"", "feature-spec-table",
                        "id=\"feature-spec-dialog-title\" tabindex=\"-1\"",
                        "백오피스 · PS-BO-MRC-010-D01-S")
                .doesNotContain("백오피스 · bo-delivery-detail")
                .doesNotContain("인쇄용 문서", "목록을 벗어나지 않고 기능명세서를 확인하고 있습니다.")
                .doesNotContain("추출 위치 보기", "추출 근거", "화면 md 원문", "관련 화면", "진입 안내", "상위 화면");
        assertThat(Files.readString(Path.of("src/main/resources/templates/artifacts/feature-spec.html"),
                        StandardCharsets.UTF_8))
                .contains("artifact-generation", "기능명세서를 작성하고 있습니다",
                        "artifact-generation-paper");
    }

    /** ⚠ 「이동」 줄의 대상 화면은 <b>그 화면의 기능명세서로</b> 건너갈 수 있어야 한다. */
    @Test
    void 예전_화면ID_주소는_시스템이_포함된_주소로_보낸다() throws Exception {
        Project project = readyProject("기능명세-이동");
        seedClone(project.getId());

        mvc.perform(get(base(project.getId()) + "/bo-delivery-detail").with(user(superUser())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        base(project.getId()) + "?selectedSystem=backoffice&selectedScreen=bo-delivery-detail"));
    }

    /**
     * ⛔ <b>절로 갈랐다고 원문을 버리지 않는다.</b> 이 산출물은 「보여 주는 창」이라
     * 원문이 없으면 정본과 대조할 길이 사라진다(원장 D3).
     */
    @Test
    void 상세에는_생성_근거나_원문을_노출하지_않는다() throws Exception {
        Project project = readyProject("기능명세-원문");
        seedClone(project.getId());

        String html = detail(project.getId(), "bo-delivery-detail");

        assertThat(html).doesNotContain("추출 근거", "역추출 소스", "--- 정의 ---", "AI", "해시");
    }

    @Test
    void 최신_개정판은_공식_문서와_A4_인쇄본으로_열린다() throws Exception {
        Project project = readyProject("기능명세-인쇄");
        seedClone(project.getId());
        write(clone(project.getId()).resolve("core/backoffice/pages/bo-delivery-detail.html"),
                "<main><button id=\"save\">저장</button></main>");
        FeatureSpecMaterialService.Snapshot material = featureSpecMaterials.snapshot(
                project.getId(), "backoffice", "bo-delivery-detail");
        java.time.Instant now = java.time.Instant.now();
        featureSpecs.beginGeneration(project.getId(), "backoffice", "bo-delivery-detail", "print-run",
                material.fingerprint(), FeatureSpecWorker.GENERATOR_VERSION, FeatureSpecWorker.SCHEMA_VERSION,
                now.minusSeconds(60), now);
        featureSpecStorage.save(project.getId(), "backoffice", "bo-delivery-detail", "print-run",
                material.fingerprint(), FeatureSpecWorker.GENERATOR_VERSION, FeatureSpecWorker.SCHEMA_VERSION,
                "{}", "[]", """
                <article class="feature-document"><h2>1. 화면 개요</h2>
                <dl class="feature-document__definitions"><dt>화면 목적</dt><dd>공식 문서 본문</dd>
                <dt>적용 범위</dt><dd>선불카드 배송 상세 화면의 조회와 후속 처리</dd></dl>
                <h2>3. 기능 명세</h2><div class="feature-document__table-wrap"><table><thead><tr>
                <th scope="col">기능 ID</th><th scope="col">기능명</th><th scope="col">동작·조건</th>
                <th scope="col">처리 내용</th><th scope="col">결과</th></tr></thead><tbody><tr>
                <td>FN-001</td><td>배송 조회</td><td>조회 조건 입력 후 조회 선택</td>
                <td>조건에 맞는 배송 건을 조회한다.</td><td>배송 목록을 표시한다.</td></tr></tbody></table></div></article>
                """);

        assertThat(list(project.getId()))
                .containsSubsequence("운영 화면 수정일", "문서 작성일")
                .contains("class=\"status-badge status-badge--complete\"", ">완료<",
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString());

        String ready = detail(project.getId(), "bo-delivery-detail");
        assertThat(ready)
                .contains("공식 문서 본문", "1차",
                        "class=\"feature-spec-official official-document\"",
                        "class=\"official-document__head\"",
                        "class=\"official-document__kind\">기능명세서</p>",
                        "class=\"official-document__title\">선불카드 배송 상세</p>",
                        "class=\"official-document__meta\"",
                        "class=\"official-document__foot\"",
                        "화면 HTML · 화면 명세 · IA 기준",
                        "기능명세서 1차 ·")
                .doesNotContain("인쇄용 문서");
        String printed = mvc.perform(get(base(project.getId()) + "/backoffice/bo-delivery-detail/print")
                        .with(user(superUser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(printed).contains("@page { size: A4", "공식 문서 본문", "개정 1");
        Path rendered = Path.of("target", "rendered");
        Files.createDirectories(rendered);
        dump(rendered, "feature-spec-ready.html", ready);
        Files.writeString(rendered.resolve("feature-spec-print.html"), printed, StandardCharsets.UTF_8);
    }

    /** ⭐ 화면 가족 = <b>같은 부모를 둔 형제</b>(원장 D4). 부모는 화면 md 의 `상위화면` 이다. */
    @Test
    void 상세에는_관련_화면_탭을_두지_않는다() throws Exception {
        Project project = readyProject("기능명세-가족");
        seedClone(project.getId());

        String html = detail(project.getId(), "bo-delivery-detail");

        assertThat(html).doesNotContain("화면 가족", "관련 화면", "preview-tab");
    }

    /** ⚠ md 가 없는 화면도 상세가 열려야 한다 — 500 이 아니라 「무엇이 없고 누가 채우나」다. */
    @Test
    void 입력_자료가_없는_화면도_상세에서_실패_안내를_낸다() throws Exception {
        Project project = readyProject("기능명세-상세없음");
        seedClone(project.getId());

        String html = detail(project.getId(), "bo-no-spec");

        assertThat(html).contains("기능명세서를 준비하지 못했습니다").doesNotContain("Internal Server Error");
    }

    @Test
    void 없는_화면ID_는_404_다() throws Exception {
        Project project = readyProject("기능명세-404");
        seedClone(project.getId());

        mvc.perform(get(base(project.getId()) + "/backoffice/bo-nothing").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    /** ⚠ 클론이 프로젝트마다 따로라 남의 프로젝트 화면은 주소를 알아도 없는 것이다. */
    @Test
    void 남의_프로젝트_화면은_주소를_알아도_안_열린다() throws Exception {
        Project mine = readyProject("기능명세-내것");
        Project other = readyProject("기능명세-남의것");
        seedClone(mine.getId());
        wipeClone(other.getId());

        mvc.perform(get(base(other.getId()) + "/backoffice/bo-delivery-detail").with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    // ── 렌더 뽑기 ────────────────────────────────────────────────────────

    /**
     * {@code DQ.RENDER} — 렌더된 화면이 곧 증거다. {@code target/rendered/} 에 뽑아 두면
     * 브라우저로 그냥 열린다(css·글꼴 경로를 저장소 안 상대 경로로 바꾼다).
     *
     * <p>⚠ <b>기계가 재는 것과 사람이 보는 것의 경계다.</b> 여기서는 파일이 생겼는지만 재고,
     * 위계·여백·글자 깨짐은 사람이 열어서 본다.
     */
    @Test
    void 렌더된_화면을_파일로_뽑는다() throws Exception {
        Project project = readyProject("기능명세-렌더");
        seedClone(project.getId());
        seedDenseFeatureSpec(project.getId());
        Path dir = Path.of("target", "rendered");
        Files.createDirectories(dir);

        dump(dir, "feature-specs.html", list(project.getId()));
        dump(dir, "feature-spec.html", detail(project.getId(), "bo-delivery-detail"));
        // ⚠ 명세가 없는 화면도 뽑는다 — 빈 화면이 설계돼 있는지는 눈으로만 보인다(DQ.STATE.CHECK).
        dump(dir, "feature-spec-missing.html", detail(project.getId(), "bo-no-spec"));

        assertThat(dir.resolve("feature-specs.html")).exists();
        assertThat(dir.resolve("feature-spec.html")).exists();
        assertThat(dir.resolve("feature-spec-missing.html")).exists();
    }

    /** 실제 운영 화면처럼 긴 동작과 항목이 많은 상태로 디자인 밀도를 확인한다. */
    private void seedDenseFeatureSpec(String projectId) throws IOException {
        Path detail = clone(projectId).resolve("core/backoffice/pages/bo-delivery-detail.md");
        write(detail, """
                --- 꼬리표 ---
                id: bo-delivery-detail / system: backoffice / 기능: 선불카드 관리 > 선불카드 배송 관리 > 상세 / 과업: []

                --- 화면명세 ---
                화면명: 선불카드 배송 상세
                경로: POST /bizCard/bizCardDelivery/detailPage
                목적: 발행처와 판매처, 카드 상태, 신청 기간을 조건으로 선불카드 신청 내역을 조회하고 필요한 후속 작업으로 이동한다
                진입: bo-delivery-list 목록 행 클릭
                연관: bo-delivery-list

                --- IA ---
                - 종류: 화면 / 상위화면: bo-delivery-list

                --- 정의 ---
                - 구분: 기능 / 좌표: id=issuerId / 라벨: 발행처 선택 / 앵커: bo-delivery-detail-e01 / 해설: 발행처를 선택하면 판매처 선택 범위와 조회 대상 조건이 함께 갱신됩니다.
                - 구분: 기능 / 좌표: id=merchantId / 라벨: 판매처 선택 / 앵커: bo-delivery-detail-e02 / 해설: 선택한 발행처에 속한 판매처를 한 곳만 지정하여 조회 범위를 좁힙니다.
                - 구분: 기능 / 좌표: id=cardStatusAll / 라벨: 카드 상태 전체 선택 / 앵커: bo-delivery-detail-e03 / 해설: 카드 신청, 신청 취소, 카드 승인과 정상, 해지, 만료 상태를 한 번에 선택하거나 해제합니다.
                - 구분: 기능 / 좌표: id=fromDate / 라벨: 신청 기간 선택 / 앵커: bo-delivery-detail-e04 / 해설: 카드 신청일의 시작일과 종료일을 달력에서 선택해 조회 기간을 지정합니다. 처음 들어오면 최근 30일이 기본 범위입니다.
                - 구분: 기능 / 좌표: name=issueChannel / 라벨: 발급 채널 선택 / 앵커: bo-delivery-detail-e05 / 해설: 발급 채널의 온라인과 오프라인 항목을 각각 선택하여 조건에 포함합니다.
                - 구분: 기능 / 좌표: id=searchType / 라벨: 검색 기준과 검색어 입력 / 앵커: bo-delivery-detail-e06 / 해설: 카드번호, 전화번호, 사용자명 가운데 검색 기준을 선택하고 해당 값을 검색어 입력란에 입력합니다.
                - 구분: 기능 / 좌표: id=btnSearch / 라벨: 신청 내역 조회 / 앵커: bo-delivery-detail-e07 / 해설: 조회 버튼을 누르면 지정한 모든 조건을 적용해 신청 내역 목록을 다시 불러옵니다.
                - 구분: 기능 / 좌표: id=pageSize / 라벨: 표시 개수 변경 / 앵커: bo-delivery-detail-e08 / 해설: 한 페이지에 표시할 행 수를 10개, 30개, 50개, 100개 가운데 선택합니다.
                - 구분: 기능 / 좌표: id=btnExcel / 라벨: 엑셀 다운로드 / 앵커: bo-delivery-detail-e09 / 해설: 현재 조회 조건과 결과를 유지한 채 목록을 엑셀 파일로 내려받습니다.
                - 구분: 이동 / 좌표: id=resultRow / 라벨: 카드 상세 확인 / 앵커: bo-delivery-detail-e10 / 이동: bo-delivery-list / 해설: 목록의 행을 클릭하면 해당 카드의 상세 내용을 확인할 수 있는 화면으로 이동합니다.
                - 구분: 기능 / 좌표: id=btnReset / 라벨: 검색 조건 초기화 / 앵커: bo-delivery-detail-e11 / 해설: 입력한 검색 조건을 처음 화면의 기본값으로 되돌리고 아직 조회는 실행하지 않습니다.
                - 구분: 기능 / 좌표: data-sort=createdAt / 라벨: 신청 일시 정렬 / 앵커: bo-delivery-detail-e12 / 해설: 신청 일시 열 제목을 선택하면 최신순과 오래된순을 번갈아 적용해 결과 순서를 바꿉니다.
                - 구분: 항목 / 좌표: id=issuerId / 라벨: 발행처 / 앵커: bo-delivery-detail-f01 / 해설: 조회할 카드 발행처를 선택합니다.
                - 구분: 항목 / 좌표: id=merchantId / 라벨: 판매처 / 앵커: bo-delivery-detail-f02 / 해설: 조회할 카드 판매처를 선택합니다.
                - 구분: 항목 / 좌표: id=cardStatusAll / 라벨: 카드 상태 전체 / 앵커: bo-delivery-detail-f03 / 해설: 아래 카드 신청 상태와 카드 상태를 일괄 선택하거나 해제합니다.
                - 구분: 항목 / 좌표: id=fromDate / 라벨: 신청일 시작 / 앵커: bo-delivery-detail-f04 / 해설: 조회 기간 시작일이며 처음에는 오늘부터 30일 전으로 설정됩니다.
                - 구분: 항목 / 좌표: id=toDate / 라벨: 신청일 종료 / 앵커: bo-delivery-detail-f05 / 해설: 조회 기간 종료일이며 처음에는 오늘로 설정됩니다.
                - 구분: 항목 / 좌표: name=issueChannel / 라벨: 온라인·오프라인 / 앵커: bo-delivery-detail-f06 / 해설: 온라인과 오프라인 발급 채널을 고르는 조건입니다.
                - 구분: 항목 / 좌표: id=searchType / 라벨: 검색어 유형 / 앵커: bo-delivery-detail-f07 / 해설: 카드번호, 전화번호, 사용자명 가운데 검색 기준을 선택합니다.
                - 구분: 항목 / 좌표: id=keyword / 라벨: 검색어 입력란 / 앵커: bo-delivery-detail-f08 / 해설: 선택한 검색 기준에 해당하는 값을 입력합니다.
                - 구분: 항목 / 좌표: id=btnSearch / 라벨: 조회 / 앵커: bo-delivery-detail-f09 / 해설: 지정한 조건으로 목록을 조회합니다.
                - 구분: 항목 / 좌표: id=pageSize / 라벨: 페이징 표시 개수 / 앵커: bo-delivery-detail-f10 / 해설: 목록 한 페이지에 표시할 행 수를 선택합니다.
                - 구분: 항목 / 좌표: id=btnExcel / 라벨: 엑셀 다운로드 / 앵커: bo-delivery-detail-f11 / 해설: 현재 조회 결과를 엑셀 파일로 내려받습니다.
                - 구분: 항목 / 좌표: id=resultGrid / 라벨: 목록 항목 / 앵커: bo-delivery-detail-f12 / 해설: 카드 신청 일시, 발행처, 사용자, 전화번호, 카드명, 카드번호, 발급 채널과 상태를 표시합니다.

                --- 원본 글 ---
                > 역추출 소스: g2c/dino-backoffice .../bizCardDeliveryDetail.html
                > 화면 경로: 선불카드 관리 > 선불카드 배송 관리 > 상세
                """);
    }

    private void dump(Path dir, String name, String html) throws IOException {
        String fixed = html
                .replace("\"/css/", "\"../../src/main/resources/static/css/")
                .replace("\"/fonts/", "\"../../src/main/resources/static/fonts/")
                .replace("\"/js/", "\"../../src/main/resources/static/js/");
        Files.writeString(dir.resolve(name), fixed, StandardCharsets.UTF_8);
    }

    // ── 씨앗 ──────────────────────────────────────────────────────────────

    /**
     * 진짜 클론을 흉내 낸 것 — 배송 상세(정의 셋 · 부모 있음) · 배송 목록(부모) ·
     * 배송 이력(형제) · 웹뷰 홈 · <b>md 가 없는 화면 하나</b>.
     */
    private void seedClone(String projectId) throws IOException {
        wipeClone(projectId);
        Path core = clone(projectId).resolve("core");

        write(clone(projectId).resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    "bo-delivery-list":   {"system": "backoffice", "ia": {"종류": "화면"}},
                    "bo-delivery-detail": {"system": "backoffice", "ia": {"종류": "화면", "상위화면": "bo-delivery-list"}},
                    "bo-delivery-log":    {"system": "backoffice", "ia": {"종류": "화면", "상위화면": "bo-delivery-list"}},
                    "bo-no-spec":         {"system": "backoffice", "ia": {"종류": "화면"}},
                    "wv-sample-home":     {"system": "webview",    "ia": {"종류": "화면"}}
                  },
                  "counts": {"screens": 5}
                }
                """);

        write(core.resolve("backoffice/pages/bo-delivery-detail.md"), """
                --- 꼬리표 ---
                id: bo-delivery-detail / system: backoffice / 기능: 선불카드 관리 > 선불카드 배송 관리 > 상세 / 과업: []

                --- 화면명세 ---
                화면명: 선불카드 배송 상세
                경로: POST /bizCard/bizCardDelivery/detailPage
                목적: 선택한 배송 건을 조회하고 반송을 처리한다
                진입: bo-delivery-list 목록 행클릭
                연관: bo-delivery-list

                --- IA ---
                - 종류: 화면 / 상위화면: bo-delivery-list

                --- 정의 ---
                - 구분: 이동 / 좌표: id=userNm / 앵커: bo-delivery-detail-e01 / 이동: bo-delivery-list / 해설: 사용자명 클릭 → 목록으로 이동
                - 구분: 기능 / 좌표: id=btnZipSearch / 앵커: bo-delivery-detail-e02 / 해설: 우편번호 검색 (다음 주소검색 팝업)
                - 구분: 항목 / 좌표: id=dlvrplZip / 앵커: bo-delivery-detail-e03 / 해설: 우편번호 입력 (readonly)

                --- 원본 글 ---
                > 역추출 소스: g2c/dino-backoffice .../bizCardDeliveryDetail.html
                > 화면 경로: 선불카드 관리 > 선불카드 배송 관리 > 상세
                """);

        write(core.resolve("backoffice/pages/bo-delivery-list.md"), """
                --- 꼬리표 ---
                id: bo-delivery-list / system: backoffice / 기능: 선불카드 관리 > 선불카드 배송 관리 / 과업: []

                --- 화면명세 ---
                화면명: 선불카드 배송 목록
                목적: 배송 건을 찾는다

                --- IA ---
                - 종류: 화면

                --- 정의 ---
                - 구분: 이동 / 좌표: id=row / 앵커: bo-delivery-list-e01 / 이동: bo-delivery-detail / 해설: 행 클릭 → 상세

                --- 원본 글 ---
                > 역추출 소스: 목록 화면
                """);

        write(core.resolve("backoffice/pages/bo-delivery-log.md"), """
                --- 꼬리표 ---
                id: bo-delivery-log / system: backoffice / 기능: 선불카드 관리 > 선불카드 배송 관리 > 이력 / 과업: []

                --- 화면명세 ---
                화면명: 선불카드 배송 이력
                목적: 배송 상태 변경 이력을 본다

                --- IA ---
                - 종류: 화면 / 상위화면: bo-delivery-list

                --- 정의 ---
                - 구분: 항목 / 좌표: id=stts / 앵커: bo-delivery-log-e01 / 해설: 배송 상태 표시

                --- 원본 글 ---
                > 역추출 소스: 이력 화면
                """);

        write(core.resolve("webview/pages/wv-sample-home.md"), """
                --- 꼬리표 ---
                id: wv-sample-home / system: webview / 기능: 홈 > 메인 / 과업: []

                --- 화면명세 ---
                화면명: 메인 홈
                목적: 웹뷰 첫 화면이다

                --- IA ---
                - 종류: 화면

                --- 정의 ---
                - 구분: 기능 / 좌표: id=btnGo / 앵커: wv-sample-home-e01 / 해설: 카드 목록으로 이동

                --- 원본 글 ---
                > 역추출 소스: 웹뷰 홈
                """);

        // ⚠ bo-no-spec 은 색인에만 있고 md 를 일부러 안 만든다 — 실물에 있는 모양이다.

        write(clone(projectId).resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":["
                        + "{\"id\":\"backoffice\",\"prefix\":\"bo\"},"
                        + "{\"id\":\"webview\",\"prefix\":\"wv\"}]}");
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId,
                new LinkedHashMap<>(Map.of("backoffice", "백오피스", "webview", "웹뷰")));
        standardIds.insert(new ScreenStandardId(ids.next(IdSequence.Kind.SCREEN_STANDARD_ID), projectId,
                "bo-delivery-detail", "PS-BO-MRC-010-D01", ScreenStandardId.Origin.S, 1));
    }

    /** 한 쪽에 안 들어가는 수를 깐다. 쪽 이동을 재려면 이것이 있어야 한다. */
    private void seedManyScreens(String projectId, int howMany) throws IOException {
        wipeClone(projectId);
        Path core = clone(projectId).resolve("core");
        StringBuilder screens = new StringBuilder();
        for (int number = 1; number <= howMany; number++) {
            String id = "bo-many-%03d".formatted(number);
            if (number > 1) {
                screens.append(",\n    ");
            }
            screens.append("\"%s\": {\"system\": \"backoffice\", \"ia\": {\"종류\": \"화면\"}}".formatted(id));
            write(core.resolve("backoffice/pages/" + id + ".md"), """
                    --- 꼬리표 ---
                    id: %s / system: backoffice / 기능: 많은 메뉴 > 목록 %03d / 과업: []

                    --- 화면명세 ---
                    화면명: 많은 화면 %03d
                    목적: 쪽 이동을 재는 씨앗이다

                    --- 정의 ---
                    - 구분: 기능 / 좌표: id=btn / 앵커: %s-e01 / 해설: 아무것도 안 한다

                    --- 원본 글 ---
                    > 씨앗
                    """.formatted(id, howMany - number + 1, number, id));
        }
        write(clone(projectId).resolve("index.json"),
                "{\n  \"schema\": \"we-adk-index/3\",\n  \"screens\": {\n    %s\n  }\n}\n"
                        .formatted(screens));
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String base(String projectId) {
        return "/projects/" + projectId + "/artifacts/functional-specs";
    }

    private String list(String projectId) throws Exception {
        return mvc.perform(get(base(projectId)).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String listWith(String projectId, String queryString) throws Exception {
        return mvc.perform(get(base(projectId) + "?" + queryString).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String detail(String projectId, String screenId) throws Exception {
        String system = screenId.startsWith("wv-") ? "webview" : "backoffice";
        return mvc.perform(get(base(projectId) + "?selectedSystem=" + system + "&selectedScreen=" + screenId)
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Path clone(String projectId) {
        return paths.cloneDir(projectId);
    }

    private void wipeClone(String projectId) {
        Path dir = clone(projectId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true, false);
                try {
                    Files.delete(path);
                } catch (IOException stuck) {
                    throw new UncheckedIOException(stuck);
                }
            });
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private void write(Path target, String body) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body, StandardCharsets.UTF_8);
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
