package com.bizplay.builder.intake;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ReceivedDocument.DocumentIntakePlan;
import com.bizplay.builder.intake.ReceivedDocument.DocumentType;
import com.bizplay.builder.intake.Requirement.ReviewState;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요구사항 화면 둘 — 목록({@code 02})과 상세({@code 02a}).
 *
 * <p>여기가 초록이면 <b>뽑은 요구사항을 사람이 볼 자리가 섰고 확정·제외를 찍을 수 있다</b>는 뜻이다.
 * 그 전까지 43건은 받은 문서 상세의 읽기 전용 요약에만 있었고 {@code requirement_state} 의
 * {@code COMPLETED} 를 <b>아무도 안 찍었다</b>.
 *
 * <p>⭐ <b>이 시험의 심장은 접수 되굴림이다</b> — 한 접수의 요구사항이 다 정해지는 순간
 * 그 접수가 {@code COMPLETED} 로 넘어간다. 화면 둘은 사람이 그것을 찍게 하는 도구다.
 *
 * <p>정본: 목업 {@code docs/mockups/02-requirements.html}·{@code 02a-requirement-detail.html},
 * ⚠ 이 화면은 2026-08-20 개정으로 왼쪽 메뉴에서 빠졌다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class RequirementScreenTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired IntakeMapper intakes;
    @Autowired ReceivedDocumentMapper documents;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired IntakeFacetMapper intakeFacets;
    @Autowired RequirementMapper requirements;

    // ── 목록 ──────────────────────────────────────────────────────────────

    /**
     * ⚠ {@code ArtifactListTest} 가 열쇠 열넷을 돌며 같은 것을 재는데, 그것은 <b>빈 화면</b>으로도
     * 통과한다. 여기서는 실물 목록이 그 계약을 그대로 이어받는지를 본다.
     *
     * <p>⛔ <b>「{@code aria-current="page"} 가 페이지에 딱 하나」로 재지 마라.</b> 줄이 있으면
     * 쪽 이동이 지금 쪽에 같은 표시를 하나 더 단다 — 그것도 올바른 ARIA 다(받은 문서 목록도 같다).
     * 여기서 지켜야 할 것은 <b>맨 처음 표시가 메뉴의 그 열쇠에 붙는다</b>는 것이다.
     * 머리의 프로젝트 바꾸기가 {@code aria-current="true"} 를 쓰는 것도 같은 까닭이다.
     */
    @Test
    void 목록이_껍데기_계약을_지키고_제_열쇠에_지금_표시를_받는다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 전자결재 회의록");
        seedRequirement(p, intakeId, "상신 화면 임시 저장", "상신 화면에서 임시 저장할 수 있어야 한다.");

        String html = list(p.getId());

        assertThat(html).contains("<title>요구사항 · 빌더</title>");
        assertThat(com.bizplay.builder.shell.ShellContractTest.markedLinkHref(html))
                .as("껍데기가 메뉴를 본문보다 먼저 그리므로 첫 표시는 메뉴의 것이다")
                .isEqualTo("/projects/" + p.getId() + "/artifacts/requirements");
        assertThat(html).as("머리에 프로젝트 이름이 뜬다").contains("탐나는전");
    }

    @Test
    void 목록이_번호와_요구_내용과_출처_문서와_상태를_적는다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 전자결재 회의록");
        String requirementId =
                seedRequirement(p, intakeId, "상신 화면 임시 저장", "상신 화면에서 임시 저장할 수 있어야 한다.");

        String html = list(p.getId());

        assertThat(html)
                .contains("REQ-001")
                .contains("상신 화면 임시 저장")
                .contains("8/12 전자결재 회의록")
                .contains("생성 완료")
                .as("요구 내용이 상세로 가는 문이다")
                .contains("/projects/" + p.getId() + "/artifacts/requirements/" + requirementId);
    }

    /** ⚠ 목업의 「적용 구분」 열은 그 축이 있는 프로젝트에서만 뜬다 — 받은 문서 목록과 같은 규칙이다. */
    @Test
    void 적용_구분_열은_그_축이_있는_프로젝트에서만_뜬다() throws Exception {
        Project bare = readyProject("탐나는전");
        String bareIntake = seedIntake(bare, "8/12 회의록");
        seedRequirement(bare, bareIntake, "임시 저장", "임시 저장할 수 있어야 한다.");

        assertThat(list(bare.getId())).doesNotContain("req-facet");

        Project faceted = readyProject("지역화폐");
        projectFacets.insert(ProjectFacet.create(faceted.getId(), "익산"));
        String intakeId = seedIntake(faceted, "8/12 회의록");
        intakeFacets.insert(IntakeFacet.create(intakeId, faceted.getId(), "익산"));
        seedRequirement(faceted, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        assertThat(list(faceted.getId())).contains("req-facet").contains("익산");
    }

    @Test
    void 거르개가_상태와_출처_문서와_검색어로_실제로_거른다() throws Exception {
        Project p = readyProject("탐나는전");
        String august5 = seedIntake(p, "8/5 전자결재 회의록");
        String august12 = seedIntake(p, "8/12 전자결재 회의록");
        String confirmed = seedRequirement(p, august5, "결재선 자동 추천", "최근 결재선을 기본값으로 제안한다.");
        seedRequirement(p, august12, "상신 화면 임시 저장", "상신 화면에서 임시 저장할 수 있어야 한다.");
        confirm(p.getId(), confirmed);

        assertThat(listWith(p.getId(), "reviewState=확정 완료"))
                .contains("결재선 자동 추천")
                .doesNotContain("상신 화면 임시 저장");

        assertThat(listWith(p.getId(), "sourceIntakeId=" + august12))
                .contains("상신 화면 임시 저장")
                .doesNotContain("결재선 자동 추천");

        assertThat(listWith(p.getId(), "query=결재선"))
                .contains("결재선 자동 추천")
                .doesNotContain("상신 화면 임시 저장");
    }

    /**
     * ⛔ 「정의서 생성 요청」은 <b>비활성 표시만</b>이다 (2026-08-16 병주 확정) —
     * 요구사항정의서는 계획 3 이라 아직 없다. 눌리는 버튼을 달면 화면이 거짓말을 한다.
     */
    @Test
    void 정의서_생성_요청은_잠긴_채_요청_전으로만_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        String requirementId = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");
        confirm(p.getId(), requirementId);

        assertThat(list(p.getId()))
                .contains("요청 전")
                .doesNotContain("요청 완료");

        assertThat(detail(p.getId(), requirementId))
                .as("확정한 뒤에도 잠긴 채다")
                .containsPattern("(?s)<button[^>]*id=\"request-definition\"[^>]*disabled");
    }

    @Test
    void 뽑은_요구사항이_없으면_받은_문서에서_분석하라고_안내한다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = list(p.getId());

        assertThat(html)
                .contains("조회된 내용이 없습니다.")
                .doesNotContain("받은 문서로 가기");
        assertThat(html).doesNotContain("class=\"filter-bar");
    }

    // ── 상세 ──────────────────────────────────────────────────────────────

    @Test
    void 상세가_번호와_본문과_출처_문서를_낸다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 전자결재 회의록");
        seedDocument(intakeId, DocumentType.MEETING_MINUTES);
        String requirementId = seedRequirement(p, intakeId, "상신 화면 임시 저장",
                "상신 화면에서 작성 중인 결재 문서를 직접 임시 저장할 수 있어야 한다.");

        String html = detail(p.getId(), requirementId);

        assertThat(html)
                .contains("<title>상신 화면 임시 저장 · 빌더</title>")
                .contains("REQ-001")
                .contains("상신 화면에서 작성 중인 결재 문서를 직접 임시 저장할 수 있어야 한다.")
                .contains("8/12 전자결재 회의록")
                .contains("회의록")
                .as("출처 문서로 건너가는 문이 있다")
                .contains("/projects/" + p.getId() + "/artifacts/received-docs/" + intakeId);
    }

    /**
     * ⛔ 원문 인용문을 만들지 마라. 요구 하나가 원문의 어느 대목에서 나왔는지를 담는 자리가 없다 —
     * 문서 내용의 앞토막을 대신 넣으면 <b>근거가 아닌 것을 근거라고 적는 것</b>이 된다.
     */
    @Test
    void 상세가_없는_근거를_지어내지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        seedDocument(intakeId, DocumentType.MEETING_MINUTES);
        String requirementId = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        assertThat(detail(p.getId(), requirementId)).doesNotContain("rq-source");
    }

    @Test
    void 화면_후보는_AI_가_적은_것이_있을_때만_뜬다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        String withHints = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.",
                "상신 작성 · 임시 저장 문서 목록");
        String without = seedRequirement(p, intakeId, "결재선 추천", "최근 결재선을 제안한다.", null);

        assertThat(detail(p.getId(), withHints))
                .contains("관련 화면 후보")
                .contains("상신 작성 · 임시 저장 문서 목록");
        assertThat(detail(p.getId(), without)).doesNotContain("관련 화면 후보");
    }

    // ── 확정 · 제외 · 내용 수정 ────────────────────────────────────────────

    @Test
    void 확정이_확정_완료를_찍는다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        String requirementId = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        confirm(p.getId(), requirementId);

        assertThat(requirements.selectById(requirementId).orElseThrow().reviewState())
                .isEqualTo(ReviewState.CONFIRMED);
        assertThat(detail(p.getId(), requirementId)).contains("확정 완료");
    }

    /** ⚠ 제외와 사유는 짝이다 — DB {@code CHECK} 도 같은 것을 지킨다. */
    @Test
    void 제외는_사유가_있어야_찍히고_빈_사유는_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        String requirementId = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/requirements/" + requirementId
                        + "/exclude")
                        .param("reason", "   ")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(requirements.selectById(requirementId).orElseThrow().reviewState())
                .as("사유가 없으면 상태를 안 바꾼다")
                .isEqualTo(ReviewState.DRAFTED);

        exclude(p.getId(), requirementId, "REQ-001 과 같은 요구다");

        Requirement excluded = requirements.selectById(requirementId).orElseThrow();
        assertThat(excluded.reviewState()).isEqualTo(ReviewState.EXCLUDED);
        assertThat(excluded.excludedReason()).isEqualTo("REQ-001 과 같은 요구다");
        assertThat(detail(p.getId(), requirementId))
                .contains("제외")
                .contains("REQ-001 과 같은 요구다");
    }

    @Test
    void 내용_수정이_제목과_본문을_바꾸고_마지막_수정을_남긴다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        String requirementId = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        assertThat(requirements.selectById(requirementId).orElseThrow().updatedAt())
                .as("한 번도 안 고친 것은 널이다")
                .isNull();

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/requirements/" + requirementId
                        + "/content")
                        .param("title", "상신 화면 임시 저장")
                        .param("body", "저장 뒤에도 작성 화면을 유지한다.")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Requirement edited = requirements.selectById(requirementId).orElseThrow();
        assertThat(edited.title()).isEqualTo("상신 화면 임시 저장");
        assertThat(edited.body()).isEqualTo("저장 뒤에도 작성 화면을 유지한다.");
        assertThat(edited.updatedAt()).isNotNull();
        assertThat(edited.reviewState()).as("고쳐도 검토 상태는 안 움직인다").isEqualTo(ReviewState.DRAFTED);
    }

    /** ⛔ 빈 제목·본문으로 저장하지 마라 — DB {@code CHECK} 가 막는 것을 화면이 먼저 말한다. */
    @Test
    void 빈_제목이나_빈_본문으로는_내용을_못_고친다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        String requirementId = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/requirements/" + requirementId
                        + "/content")
                        .param("title", "  ").param("body", "본문")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(requirements.selectById(requirementId).orElseThrow().title())
                .isEqualTo("임시 저장");
    }

    // ── 접수 되굴림 ────────────────────────────────────────────────────────

    /**
     * ⭐ <b>이 시험의 심장이다.</b> V7 이 세운 {@code COMPLETED} 를 여기서 처음 찍는다.
     *
     * <p>⛔ 확정만으로 넘기지 마라 — 제외도 「정해진 것」이다. 하나라도 초안이면 접수는 검토 필요다.
     */
    @Test
    void 한_접수의_요구사항이_다_정해지면_접수가_완료로_넘어간다() throws Exception {
        Project p = readyProject("탐나는전");
        String intakeId = seedIntake(p, "8/12 회의록");
        intakes.updateRequirementState(intakeId, Intake.RequirementState.REVIEW_REQUIRED);
        String first = seedRequirement(p, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");
        String second = seedRequirement(p, intakeId, "결재선 추천", "최근 결재선을 제안한다.");
        String third = seedRequirement(p, intakeId, "모바일 결재", "모바일에서 결재한다.");

        confirm(p.getId(), first);
        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .as("아직 초안이 둘 남았다")
                .isEqualTo(Intake.RequirementState.REVIEW_REQUIRED);

        confirm(p.getId(), second);
        exclude(p.getId(), third, "이번 범위가 아니다");

        assertThat(intakes.selectById(intakeId).orElseThrow().requirementState())
                .as("확정과 제외가 섞여도 다 정해졌으면 완료다")
                .isEqualTo(Intake.RequirementState.COMPLETED);
    }

    /** ⚠ 되굴림은 <b>그 접수만</b> 넘긴다 — 같은 프로젝트의 다른 접수를 끌고 가면 안 된다. */
    @Test
    void 되굴림이_같은_프로젝트의_다른_접수를_끌고_가지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        String mine = seedIntake(p, "8/12 회의록");
        String other = seedIntake(p, "8/5 회의록");
        intakes.updateRequirementState(mine, Intake.RequirementState.REVIEW_REQUIRED);
        intakes.updateRequirementState(other, Intake.RequirementState.REVIEW_REQUIRED);
        String here = seedRequirement(p, mine, "임시 저장", "임시 저장할 수 있어야 한다.");
        seedRequirement(p, other, "결재선 추천", "최근 결재선을 제안한다.");

        confirm(p.getId(), here);

        assertThat(intakes.selectById(mine).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.COMPLETED);
        assertThat(intakes.selectById(other).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.REVIEW_REQUIRED);
    }

    // ── 경계 ──────────────────────────────────────────────────────────────

    @Test
    void 남의_프로젝트_요구사항은_404_다() throws Exception {
        Project here = readyProject("탐나는전");
        Project there = readyProject("전자세금계산서");
        String intakeId = seedIntake(here, "8/12 회의록");
        String requirementId = seedRequirement(here, intakeId, "임시 저장", "임시 저장할 수 있어야 한다.");

        mvc.perform(get("/projects/" + there.getId() + "/artifacts/requirements/" + requirementId)
                        .with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 목록은_그_프로젝트의_요구사항만_담는다() throws Exception {
        Project here = readyProject("탐나는전");
        Project there = readyProject("전자세금계산서");
        seedRequirement(here, seedIntake(here, "8/12 회의록"), "임시 저장", "임시 저장할 수 있어야 한다.");
        seedRequirement(there, seedIntake(there, "8/5 회의록"), "결재선 추천", "최근 결재선을 제안한다.");

        assertThat(list(here.getId()))
                .contains("임시 저장")
                .doesNotContain("결재선 추천");
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String list(String projectId) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/requirements")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String listWith(String projectId, String queryString) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/requirements?" + queryString)
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String detail(String projectId, String requirementId) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/requirements/" + requirementId)
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void confirm(String projectId, String requirementId) throws Exception {
        mvc.perform(post("/projects/" + projectId + "/artifacts/requirements/" + requirementId
                        + "/confirm")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private void exclude(String projectId, String requirementId, String reason) throws Exception {
        mvc.perform(post("/projects/" + projectId + "/artifacts/requirements/" + requirementId
                        + "/exclude")
                        .param("reason", reason)
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private String seedIntake(Project project, String title) {
        String id = ids.next(IdSequence.Kind.INTAKE);
        intakes.insert(Intake.create(id, project.getId(), title, superUser().accountId()));
        return id;
    }

    private void seedDocument(String intakeId, DocumentType type) {
        documents.insert(ReceivedDocument.create(ids.next(IdSequence.Kind.RECEIVED_DOCUMENT),
                intakeId, type, null, null, null, "상신할 때 임시저장이 됐으면 좋겠다", null, null,
                DocumentIntakePlan.typedOnly("상신할 때 임시저장이 됐으면 좋겠다")));
    }

    private String seedRequirement(Project project, String intakeId, String title, String body) {
        return seedRequirement(project, intakeId, title, body, null);
    }

    /** ⚠ 번호는 손으로 박지 않는다 — 실물과 같은 문(`allocateNumber`)으로 집는다. */
    private String seedRequirement(Project project, String intakeId, String title, String body,
                                   String screenHints) {
        String id = ids.next(IdSequence.Kind.REQUIREMENT);
        requirements.insert(Requirement.draft(id, project.getId(), intakeId,
                requirements.allocateNumber(project.getId()), title, body, screenHints));
        return id;
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
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
