package com.bizplay.builder.intake;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.ReceivedDocument.ContentState;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 받은 문서를 올린다 — 목록 · 등록 · 상세가 실제로 돈다.
 *
 * <p><b>2026-08-15 에 「AI 가 늘 정리한다」가 폐기됐다.</b> 여기서 재는 것의 절반이 그 경계다 —
 * <b>직접 입력과 서버 추출은 AI 를 아예 안 거치고 등록 즉시 완료</b>여야 한다.
 *
 * <p>⛔ 못 읽는 문서도 올라간다. 원본 보존이 규칙이고, 판정이 막는 것은 다음 걸음뿐이다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class IntakeUploadTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired IntakeMapper intakes;
    @Autowired ReceivedDocumentMapper documents;
    @Autowired RequirementMapper requirements;
    @Autowired ProjectFacetMapper projectFacets;
    @MockitoBean FlowPostGateway flowPosts;

    @Test
    void 받은_문서가_없으면_등록으로_이어지는_빈_상태를_보여준다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = mvc.perform(get("/projects/" + p.getId() + "/artifacts/received-docs")
                .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("list-empty-state")
                .contains("조회된 내용이 없습니다.")
                .contains("문서 등록")
                .contains("/artifacts/received-docs/register");
        assertThat(html).doesNotContain("class=\"filter-bar");
    }

    /** ⛔ 「AI 가 먼저 정리합니다」를 되살리지 마라 — 늘 정리하지 않는다. */
    @Test
    void 목록_부제와_빈_상태가_AI_가_늘_정리한다고_말하지_않는다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = mvc.perform(get("/projects/" + p.getId() + "/artifacts/received-docs")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("필요한 문서에서 요구사항을 분석")
                .doesNotContain("AI가 먼저 정리")
                .doesNotContain("AI가 원문을 먼저 정리");
    }

    /**
     * ⛔ <b>여기가 이 회차의 핵심 하나다.</b> 직접 입력은 사람이 친 글이라 읽을 것이 없다 —
     * AI 를 부르지 않고 <b>등록 즉시 완료</b>이고, 친 글이 그대로 문서 내용이 된다.
     */
    @Test
    void 직접_입력은_AI_를_안_부르고_등록_즉시_완료다() throws Exception {
        Project p = readyProject("탐나는전");

        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "8/13 운영회의 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "상신할 때 임시저장이 됐으면 좋겠다")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        var intake = intakes.selectByProjectId(p.getId()).get(0);
        var document = documents.selectByIntakeId(intake.id()).orElseThrow();

        assertThat(document.contentState()).isEqualTo(ContentState.READY);
        assertThat(document.documentContent()).isEqualTo("상신할 때 임시저장이 됐으면 좋겠다");
        assertThat(document.extractedContent()).as("뽑을 파일이 없다").isNull();
        assertThat(document.contentConfirmedAt()).as("확인할 것이 없다").isNull();
        assertThat(intake.requirementState()).isEqualTo(Intake.RequirementState.NOT_STARTED);
    }

    /** 평문 첨부는 서버가 뽑는다 — 이것도 AI 를 안 거친다. */
    @Test
    void 평문_첨부는_서버가_뽑고_등록_즉시_완료다() throws Exception {
        Project p = readyProject("탐나는전");
        var file = new MockMultipartFile("file", "회의록.txt", "text/plain",
                "상신할 때 임시저장이 됐으면 좋겠다".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .file(file)
                        .param("title", "평문 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        var intake = intakes.selectByProjectId(p.getId()).get(0);
        var document = documents.selectByIntakeId(intake.id()).orElseThrow();

        assertThat(document.contentState()).isEqualTo(ContentState.READY);
        assertThat(document.extractedContent()).contains("임시저장이 됐으면 좋겠다");
        assertThat(document.documentContent()).contains("임시저장이 됐으면 좋겠다");
    }

    /** 그림 파일은 서버가 못 읽는다 — 멀티모달이 읽도록 줄에 세운다. ⛔ 오류가 아니다. */
    @Test
    void 그림_파일은_오류가_아니라_내용_분석_대기로_앉는다() throws Exception {
        Project p = readyProject("탐나는전");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        var file = new MockMultipartFile("file", "회의록.png", "image/png", png);

        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .file(file)
                        .param("title", "칠판 사진")
                        .param("documentType", "OTHER")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        var intake = intakes.selectByProjectId(p.getId()).get(0);
        var document = documents.selectByIntakeId(intake.id()).orElseThrow();
        assertThat(document.contentState()).isEqualTo(ContentState.QUEUED);
        assertThat(document.documentContent()).isNull();
    }

    /**
     * ⛔ 한컴·오피스 압축 문서는 <b>멀티모달로도 못 읽는다</b> — 줄에 세우면 영영 실패만 돈다.
     * 등록은 성공하고 다음 걸음만 닫힌다.
     */
    @Test
    void 압축_문서는_등록은_되고_다음_걸음만_닫힌다() throws Exception {
        Project p = readyProject("탐나는전");
        byte[] zipBytes = {'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0};
        var file = new MockMultipartFile("file", "회의록.hwpx",
                "application/octet-stream", zipBytes);

        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .file(file)
                        .param("title", "한컴 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        var intake = intakes.selectByProjectId(p.getId()).get(0);
        var document = documents.selectByIntakeId(intake.id()).orElseThrow();

        assertThat(document.readable()).as("판정이 못읽는다로 앉는다").isFalse();
        assertThat(document.contentState()).isEqualTo(ContentState.FAILED);

        String detailHtml = detail(p.getId(), intake.id());
        assertThat(detailHtml)
                .as("빈 상태가 아니라 다음에 할 것을 준다")
                .contains("한컴")
                .contains("요구사항 분석이 열립니다");
    }

    /** ⛔ 기본 상세는 대조하지 않는다 — 한 칸이다. */
    @Test
    void 기본_상세는_한_칸짜리_문서_내용만_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "8/13 운영회의 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "상신할 때 임시저장이 됐으면 좋겠다")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        var intake = intakes.selectByProjectId(p.getId()).get(0);

        String html = detail(p.getId(), intake.id());

        assertThat(html)
                .contains("문서 내용")
                .contains("상신할 때 임시저장이 됐으면 좋겠다")
                .contains("요구사항 분석");
        assertThat(html)
                .as("폐기된 개념이 화면에 남아 있지 않다")
                .doesNotContain("AI 1차 정리")
                .doesNotContain("AI 정리본")
                .doesNotContain("등록 원문")
                .doesNotContain("원문으로 다시 정리")
                .doesNotContain("정리 내용 확인")
                .doesNotContain("요구사항 대상")
                .doesNotContain("참고 문서");
    }

    /** ⛔ 처리 방향은 개념째 폐기다 — 목록에서도 사라져야 한다. */
    @Test
    void 목록에_처리_구분_열과_거르개와_요약이_없다() throws Exception {
        Project p = readyProject("탐나는전");
        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "8/13 운영회의 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "내용")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/projects/" + p.getId() + "/artifacts/received-docs")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .doesNotContain("처리 구분")
                .doesNotContain("처리 대기")
                .doesNotContain("요구사항 대상")
                .doesNotContain("참고 문서")
                .doesNotContain("name=\"processType\"");
        assertThat(html)
                .contains("생성된 요구사항")
                .contains("0건")
                .doesNotContain("미분석")
                .doesNotContain("요구사항 검토 필요");
        assertThat(html).as("하드코딩한 미생성이 없어졌다").doesNotContain("미생성");
    }

    @Test
    void 기존_요구사항이_있으면_삭제를_안내하고_삭제한_뒤에만_분석을_연다() throws Exception {
        Project p = readyProject("탐나는전");
        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "8/13 운영회의 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "상신할 때 임시저장이 됐으면 좋겠다")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Intake intake = intakes.selectByProjectId(p.getId()).get(0);
        int number = requirements.allocateNumber(p.getId());
        requirements.insert(Requirement.draft(ids.next(IdSequence.Kind.REQUIREMENT),
                p.getId(), intake.id(), number, "임시 저장", "작성 중인 내용을 저장한다.", null));
        intakes.updateRequirementState(intake.id(), Intake.RequirementState.REVIEW_REQUIRED);

        assertThat(detail(p.getId(), intake.id()))
                .contains("다시 분석하려면 기존 요구사항을 삭제해야 합니다")
                // ⛔ 이 삭제는 제외가 아니라 줄을 지우는 것이다 — 확정·직접 수정한 것까지 함께 간다.
                .contains("확정하거나 직접 고친 내용도 함께 사라지며")
                .contains("생성된 요구사항 삭제")
                .contains("/delete-requirements")
                .doesNotContain(">요구사항 다시 분석</button>");

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/received-docs/"
                        + intake.id() + "/delete-requirements")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(requirements.selectByIntakeId(intake.id())).isEmpty();
        assertThat(intakes.selectById(intake.id()).orElseThrow().requirementState())
                .isEqualTo(Intake.RequirementState.NOT_STARTED);
        assertThat(detail(p.getId(), intake.id()))
                .contains(">요구사항 분석</button>")
                .doesNotContain("생성된 요구사항 삭제");
    }

    /** ⛔ 처리 방향 라우트는 사라졌다 — 주소를 손으로 쳐도 안 열린다. */
    @Test
    void 처리_방향_라우트가_사라졌다() throws Exception {
        Project p = readyProject("탐나는전");
        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "8/13 운영회의 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "내용")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        var intake = intakes.selectByProjectId(p.getId()).get(0);

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/received-docs/"
                        + intake.id() + "/process-type")
                        .param("processType", "REQUIREMENTS")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** 받은 문서 목록은 문서 처리 상태만 보여주고 요구사항 분석 상태를 섞지 않는다. */
    @Test
    void 목록의_상태_거르개에는_문서_상태만_보여준다() throws Exception {
        Project p = readyProject("탐나는전");
        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "단계 확인용 회의록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "확인할 내용")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/projects/" + p.getId() + "/artifacts/received-docs")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("문서 상태")
                .contains("name=\"documentStatus\"")
                .contains("내용 분석 대기")
                .contains("내용 분석 중")
                .contains("등록 완료")
                .contains("문서 처리 오류")
                .doesNotContain("요구사항 미분석")
                .doesNotContain("요구사항 분석 중")
                .doesNotContain("요구사항 검토 필요")
                .doesNotContain("요구사항 분석 오류")
                .doesNotContain("name=\"currentStep\"");
        assertThat(html).as("폐기된 단계가 남아 있지 않다")
                .doesNotContain("내용 정리 중")
                .doesNotContain("처리 방향 선택");
    }

    @Test
    void 파일도_직접입력도_없으면_거절하고_친_값을_그대로_돌려준다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "빈 문서")
                        .param("documentType", "OTHER")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("첨부파일 또는 문서 내용을 하나 이상");
        assertThat(html).as("친 값이 날아가지 않는다").contains("빈 문서");
        assertThat(intakes.selectByProjectId(p.getId())).isEmpty();
    }

    /** ⚠ 적용 구분이 <b>있는</b> 프로젝트에서는 하나 이상이 필수다(→ {@code facet-axis}). */
    @Test
    void 적용_구분이_있는_프로젝트에서는_하나_이상_고르지_않으면_거절한다() throws Exception {
        Project p = readyProject("탐나는전");
        projectFacets.insert(ProjectFacet.create(p.getId(), "익산"));
        projectFacets.insert(ProjectFacet.create(p.getId(), "제주"));

        String html = mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("title", "적용 구분 없는 등록")
                        .param("documentType", "MEETING_MINUTES")
                        .param("typedContent", "내용")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("적용 구분을 하나 이상");
        assertThat(intakes.selectByProjectId(p.getId())).isEmpty();
    }

    /**
     * ⚠ <b>0행이 정상이다.</b> 적용 구분이 없는 프로젝트에서는 화면에 입력도 필터도 안 뜬다 —
     * 「법인카드 운영 개선」처럼 적용 구분이 없는 사업에 쓸데없는 칸이 뜨는 것을 막는다.
     */
    @Test
    void 적용_구분이_없는_프로젝트의_등록_화면에는_그_칸이_아예_안_뜬다() throws Exception {
        Project p = readyProject("법인카드 운영 개선");

        String html = mvc.perform(get("/projects/" + p.getId()
                        + "/artifacts/received-docs/register").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).doesNotContain("적용 구분");
    }

    @Test
    void 문서_종류에서_Flow를_고르면_게시물_ID를_입력할_수_있다() throws Exception {
        Project p = readyProject("탐나는전");

        String html = mvc.perform(get("/projects/" + p.getId()
                        + "/artifacts/received-docs/register").with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("<select class=\"field__control\" id=\"register-document-type\" name=\"documentType\" required>")
                .contains("document-register-meta--pending")
                .contains("data-manual-meta hidden")
                .contains("data-manual-source hidden")
                .contains(">목록으로</a>")
                .contains("value=\"FLOW\"")
                .contains(">Flow</option>")
                .contains(">일반문서</option>")
                .contains("name=\"postId\"")
                .doesNotContain("value=\"WORK_REQUEST\"")
                .doesNotContain("value=\"PROPOSAL\"");
        assertThat(html.indexOf("문서 종류")).isLessThan(html.indexOf("문서명"));
    }

    @Test
    void Flow_게시물의_제목과_본문을_받은_문서로_등록한다() throws Exception {
        Project p = readyProject("탐나는전");
        given(flowPosts.get("40001")).willReturn(new FlowPost(
                "40001", "주간 진행 공유", "이번 주 주요 이슈를 공유합니다.",
                "https://flow.team/post/40001"));

        mvc.perform(multipart("/projects/" + p.getId() + "/artifacts/received-docs")
                        .param("documentType", "FLOW")
                        .param("postId", "40001")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Intake intake = intakes.selectByProjectId(p.getId()).get(0);
        ReceivedDocument document = documents.selectByIntakeId(intake.id()).orElseThrow();
        assertThat(intake.title()).isEqualTo("주간 진행 공유");
        assertThat(document.documentType()).isEqualTo(ReceivedDocument.DocumentType.FLOW);
        assertThat(document.typedContent()).isEqualTo("이번 주 주요 이슈를 공유합니다.");
        assertThat(document.documentContent()).isEqualTo("이번 주 주요 이슈를 공유합니다.");
        assertThat(document.contentState()).isEqualTo(ContentState.READY);
        assertThat(detail(p.getId(), intake.id())).contains("Flow 게시물 원문");
    }

    @Test
    void 요구사항으로_넘기지_않은_문서는_상세에서_삭제할_수_있다() throws Exception {
        Project p = readyProject("탐나는전");
        registerTyped(p, "삭제할 문서");
        Intake intake = intakes.selectByProjectId(p.getId()).get(0);

        assertThat(detail(p.getId(), intake.id())).contains("문서 삭제");

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/received-docs/"
                        + intake.id() + "/delete")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(intakes.selectById(intake.id())).isEmpty();
        assertThat(documents.selectByIntakeId(intake.id())).isEmpty();
    }

    @Test
    void 요구사항으로_넘긴_문서는_삭제_버튼도_없고_직접_요청해도_삭제되지_않는다() throws Exception {
        Project p = readyProject("탐나는전");
        registerTyped(p, "분석을 시작한 문서");
        Intake intake = intakes.selectByProjectId(p.getId()).get(0);
        intakes.updateRequirementStateToRunning(intake.id());

        assertThat(detail(p.getId(), intake.id())).doesNotContain("문서 삭제");

        mvc.perform(post("/projects/" + p.getId() + "/artifacts/received-docs/"
                        + intake.id() + "/delete")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(intakes.selectById(intake.id())).isPresent();
        assertThat(documents.selectByIntakeId(intake.id())).isPresent();
    }

    @Test
    void 내용_분석_중에는_상세와_목록에서_진행_상태가_뚜렷하게_보인다() throws Exception {
        Project p = readyProject("탐나는전");
        registerTyped(p, "분석 중인 문서");
        Intake intake = intakes.selectByProjectId(p.getId()).get(0);
        ReceivedDocument document = documents.selectByIntakeId(intake.id()).orElseThrow();
        documents.updateContentState(document.id(), ContentState.PROCESSING);

        assertThat(detail(p.getId(), intake.id()))
                .contains("document-processing-bar")
                .contains("문서 내용 분석 중")
                .contains("완료되면 문서 내용이 표시됩니다")
                .doesNotContain("문서 삭제")
                .doesNotContain("requirement-summary")
                .doesNotContain("생성된 요구사항")
                .doesNotContain("document-processing-state")
                .doesNotContain("내용 분석 중 — 아직 확인된 내용이 없습니다.");

        String listHtml = mvc.perform(get("/projects/" + p.getId() + "/artifacts/received-docs")
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(listHtml)
                .contains("received-doc-row--processing")
                .contains("status-badge--active")
                .contains("내용 분석 중");
    }

    /**
     * ⛔ 원본 파일 이름을 그대로 경로에 쓰면 클론 폴더 밖을 가리킨다.
     * 경로 구분자와 상위 이동 표기를 걷어낸다.
     */
    @Test
    void 원본_이름의_경로_구분자와_상위_이동_표기를_걷어낸다() {
        assertThat(IntakeService.safeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(IntakeService.safeFileName("C:\\temp\\회의록.txt")).isEqualTo("회의록.txt");
        assertThat(IntakeService.safeFileName("..")).isEqualTo("문서");
        assertThat(IntakeService.safeFileName(null)).isEqualTo("문서");
    }

    /** ⚠ 다른 프로젝트의 접수는 그 프로젝트 주소로 열리지 않는다. */
    @Test
    void 남의_프로젝트_접수는_404_다() throws Exception {
        Project here = readyProject("탐나는전");
        Project there = readyProject("전자세금계산서");

        mvc.perform(multipart("/projects/" + here.getId() + "/artifacts/received-docs")
                        .param("title", "회의록").param("documentType", "MEETING_MINUTES").param("typedContent", "내용")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String intakeId = intakes.selectByProjectId(here.getId()).get(0).id();

        mvc.perform(get("/projects/" + there.getId() + "/artifacts/received-docs/" + intakeId)
                        .with(user(superUser())))
                .andExpect(status().isNotFound());
    }

    // ── 도구 ──────────────────────────────────────────────────────────────

    private String detail(String projectId, String intakeId) throws Exception {
        return mvc.perform(get("/projects/" + projectId + "/artifacts/received-docs/" + intakeId)
                        .with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void registerTyped(Project project, String title) throws Exception {
        mvc.perform(multipart("/projects/" + project.getId() + "/artifacts/received-docs")
                        .param("title", title)
                        .param("documentType", "OTHER")
                        .param("typedContent", "문서 내용")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * ⚠ 앉히기가 두 걸음인 것은 {@code insert} 가 늘 {@code RECEIVING} 으로 넣기 때문이다 —
     * ⛔ 「엔티티를 고치고 저장」으로 되돌리지 마라. MyBatis 엔 더티 체킹이 없어 조용히 잃는다.
     */
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
