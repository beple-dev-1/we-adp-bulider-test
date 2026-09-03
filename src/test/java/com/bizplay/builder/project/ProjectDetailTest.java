package com.bizplay.builder.project;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.git.RepoProbe;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.Intake;
import com.bizplay.builder.intake.IntakeFacet;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ProjectDetailTest extends AbstractDbTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectService projects;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 재는 것은 그대로 「DB 에 뭐가 남았나」다. */
    @Autowired ProjectMapper repository;
    @Autowired com.bizplay.builder.intake.ProjectFacetMapper facets;
    @Autowired com.bizplay.builder.intake.IntakeFacetMapper intakeFacets;
    @Autowired com.bizplay.builder.intake.IntakeMapper intakes;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretSealer sealer;
    @Autowired com.bizplay.builder.devrequest.DevIssueTargetService devIssueTargets;
    @MockitoBean RepoProbe probe;
    @MockitoBean CloneWorker cloneWorker;
    @MockitoBean RepositoryUpdateWorker repositoryUpdateWorker;

    private Project readyProject() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        Project p = projects.register("법인카드 운영 개선", "https://gitlab.co/we/card.git", "main",
                "t", java.util.List.of("익산", "제주"));
        projects.markReady(p.getId());
        return p;
    }

    @Test
    void 상세에_저장소와_브랜치와_적용_구분이_뜬다() throws Exception {
        Project p = readyProject();

        mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("법인카드 운영 개선")))
                .andExpect(content().string(containsString("https://gitlab.co/we/card.git")))
                .andExpect(content().string(containsString("익산")))
                .andExpect(content().string(containsString("제주")))
                .andExpect(content().string(containsString("저장소 및 적용 구분")));
    }

    @Test
    void 개발요청_전송_설정은_프로젝트_정보와_분리해_상태와_설정_행동을_보여준다() throws Exception {
        Project p = readyProject();

        String before = mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(before)
                .contains("개발요청 전송", "설정 필요", "전송 설정")
                .contains("id=\"dev-issue-target-dialog\"")
                .doesNotContain("개발요청 이슈 자리", "⛔");

        devIssueTargets.save(p.getId(), "https://gitlab.example.com", "dev/card-api", "glpat-example",
                accounts.selectByLoginId("admin").orElseThrow().getId());

        String after = mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(after)
                .contains("설정 완료", "https://gitlab.example.com", "dev/card-api", "토큰 등록됨", "설정 변경");
    }

    @Test
    void 개발요청_전송_설정이_거절되면_레이어에서_오류와_입력값을_그대로_보여준다() throws Exception {
        Project p = readyProject();

        String html = mvc.perform(post("/admin/projects/" + p.getId() + "/dev-issue-target")
                        .param("baseUrl", "https://gitlab.example.com")
                        .param("projectPath", "dev/card-api")
                        .param("token", "공백 토큰")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("data-open-on-load=\"true\"")
                .contains("토큰에 공백이나 한글이 섞여 있습니다")
                .contains("value=\"https://gitlab.example.com\"")
                .contains("value=\"dev/card-api\"");
    }

    /** ⚠ 등록 시점에 고정되는 값이라 상세에서도 보여야 한다 — 못 보면 고쳐야 할 값인지도 모른다. */
    @Test
    void 상세에_플랫폼_코드가_뜬다() throws Exception {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        Project p = projects.registerConfigured("표준ID첫마디사업", "https://gitlab.co/we/idsource.git", "main", "t",
                "ID9", java.util.List.of());
        projects.markReady(p.getId());

        mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("플랫폼 코드")))
                .andExpect(content().string(containsString("ID9")));
    }

    @Test
    void 준비된_프로젝트에는_관리_행동만_뜬다() throws Exception {
        Project p = readyProject();

        String html = mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("기획 저장소 토큰 변경", "적용 구분 관리", "시스템 관리", "저장소 업데이트")
                .contains("facet-setting-table__head")
                .contains("data-submit-loading=\"저장소 업데이트 요청 중\"")
                .contains("data-submit-loading=\"기획 저장소 토큰 저장 중\"")
                .contains("data-submit-loading=\"적용 구분 저장 중\"")
                .doesNotContain("프로젝트 열기", "최신 내용 가져오기");
        // ⚠ 닫는 자리는 제목 영역이 **열린 다음**부터 찾는다 — 셸 머리에 <header> 가 이미 하나 있어
        //   문서 처음부터 찾으면 그쪽 닫는 자리를 집고 substring 이 거꾸로 뒤집힌다.
        int titleAt = html.indexOf("<header class=\"rq-head\">");
        assertThat(titleAt).as("제목 영역이 있어야 한다").isNotNegative();
        String titleArea = html.substring(titleAt, html.indexOf("</header>", titleAt));
        assertThat(titleArea)
                .contains("적용 구분 관리", "시스템 관리", "저장소 업데이트")
                .doesNotContain("기획 저장소 토큰 변경");
        int repositoryCardAt = html.indexOf("id=\"project-information-title\"");
        int devIssueCardAt = html.indexOf("class=\"rq-card dev-issue-target-card\"");
        assertThat(html.substring(repositoryCardAt, devIssueCardAt))
                .contains("기획 저장소 토큰 변경", "새 기획 저장소 토큰");
    }

    @Test
    void 프로젝트_상세에서_기획_저장소_업데이트를_시작한다() throws Exception {
        Project p = readyProject();
        // ⚠ any() 는 null 도 통과시킨다 — me.accountId() 가 워커에 실제로 닿는지는
        //   accountId 를 값으로 못박아야만 잴 수 있다.
        String accountId = accounts.selectByLoginId("admin").orElseThrow().getId();

        mvc.perform(post("/admin/projects/" + p.getId() + "/repository/update")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(repositoryUpdateWorker).update(eq(p.getId()), eq(accountId));
        assertThat(projects.detail(p.getId()).repositoryUpdate().state())
                .isEqualTo(RepositoryUpdateState.RUNNING);
        mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("repository-update-status--running")))
                .andExpect(content().string(containsString("업데이트 진행 중")))
                .andExpect(content().string(containsString("원격 저장소의 변경 사항을 확인하고 있습니다.")));
    }

    @Test
    void 표시_이름을_바꾸면_이미_등록된_문서도_같이_바뀐다() {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        Project p = projects.registerConfigured("이름변경사업", "https://gitlab.co/we/rename.git", "main", "t", "PS",
                java.util.List.of(new ProjectService.FacetSetting("iksan", "익산")));
        projects.markReady(p.getId());
        String intakeId = seatIntakeUsing(p.getId(), "익산");

        projects.replaceFacetSettings(p.getId(),
                java.util.List.of(new ProjectService.FacetSetting("iksan", "익산 지역")));

        assertThat(intakeFacets.selectByIntakeId(intakeId))
                .extracting(IntakeFacet::name)
                .containsExactly("익산 지역");
    }

    @Test
    void 적용_구분을_다시_넣으면_통째로_갈린다() throws Exception {
        Project p = readyProject();

        mvc.perform(post("/admin/projects/" + p.getId() + "/facets")
                        .param("facets", "익산, 군산")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactly("군산", "익산");   // 제주가 빠지고 군산이 든다
    }

    @Test
    void 실패한_프로젝트에는_이유와_다시_받기가_뜨고_프로젝트_열기는_안_뜬다() throws Exception {
        when(probe.probe(any(), any(), any())).thenReturn(new RepoProbe.ProbeResult(true, null));
        Project p = projects.register("출장비 정산 개선", "https://gitlab.co/we/travel.git", "main", "t");
        projects.markFailed(p.getId(), "저장소 연결이 중간에 끊겼다");

        mvc.perform(get("/admin/projects/" + p.getId()).with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("저장소 연결이 중간에 끊겼다")))
                .andExpect(content().string(containsString("저장소 받기 재시도")))
                .andExpect(content().string(containsString("data-submit-loading=\"저장소 받기 재시도 중\"")))
                .andExpect(content().string(containsString("기획 저장소 토큰 변경")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("프로젝트 열기"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("저장소 및 적용 구분"))));
    }

    @Test
    void 목록에는_복구_행동이_없고_이름이_상세로_이어진다() throws Exception {
        Project p = readyProject();

        mvc.perform(get("/admin/projects").with(user(superUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/projects/" + p.getId())))
                .andExpect(content().string(containsString("/admin/projects/new")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("토큰만 다시 넣는다"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("클론을 다시 받는다"))));
    }

    /**
     * ⛔ FK 결함 재현 — {@code adk_builder_intake_facet} 은 {@code (project_id, name)} 을 통째로
     * {@code adk_builder_project_facet} 에 건다. 지웠다 다시 넣는 낡은 구현은 <b>바뀐 것이 없어도</b>
     * 지우기 자체가 그 FK 를 건드려 500 을 낸다 — 접수가 하나라도 있는 프로젝트에서는 실제로
     * 걸리는 경로다(2026-08-15 최종 검토가 짚었다).
     */
    @Test
    void 접수가_걸린_적용_구분을_그대로_다시_제출해도_통과한다() throws Exception {
        Project p = readyProject();   // 익산, 제주
        seatIntakeUsing(p.getId(), "익산");

        mvc.perform(post("/admin/projects/" + p.getId() + "/facets")
                        .param("facets", "익산, 제주")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactly("익산", "제주");
    }

    @Test
    void 기존_것을_두고_새_적용_구분만_더해도_성공한다() throws Exception {
        Project p = readyProject();   // 익산, 제주
        seatIntakeUsing(p.getId(), "익산");

        mvc.perform(post("/admin/projects/" + p.getId() + "/facets")
                        .param("facets", "익산, 제주, 군산")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactly("군산", "익산", "제주");
    }

    @Test
    void 접수가_없는_적용_구분을_지우면_성공한다() throws Exception {
        Project p = readyProject();   // 익산, 제주 — 아무 접수도 안 걸어 둔다

        mvc.perform(post("/admin/projects/" + p.getId() + "/facets")
                        .param("facets", "익산")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactly("익산");
    }

    @Test
    void 접수가_걸린_적용_구분을_지우려_하면_거절되고_그대로_남는다() throws Exception {
        Project p = readyProject();   // 익산, 제주
        seatIntakeUsing(p.getId(), "익산");

        mvc.perform(post("/admin/projects/" + p.getId() + "/facets")
                        .param("facets", "제주")
                        .with(user(superUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("익산")));

        assertThat(facets.selectByProjectId(p.getId()))
                .extracting(com.bizplay.builder.intake.ProjectFacet::name)
                .containsExactly("익산", "제주");
    }

    /**
     * ⛔ FIX 4 가 났던 자리 — {@code register()} 를 거치지 않고 앉은 행(씨딩 · 수동 삽입 흉내)에서
     * 상세가 등록일시 때문에 던지지 않는지 본다.
     *
     * <p>⚠ <b>2026-08-15 에 재는 값이 바뀌었다.</b> 종전에는 {@code "없음"} 을 기대했다 —
     * JPA 가 쓰기를 커밋 직전까지 미뤄서(write-behind) {@code created_at} 이
     * {@code insertable = false} 인 자바 쪽 필드에 <b>여전히 null 로 남던</b> 것이 근거였고,
     * 그 null 을 서식에 넘기면 500 이 났다. 프로젝트가 MyBatis 로 넘어오며 그 전제가 통째로 사라졌다 —
     * INSERT 가 곧장 들어가고 상세는 늘 DB 에서 되읽는데 그 열은 {@code not null default now()} 라
     * <b>null 이 될 길이 없다.</b> 그래서 {@code "없음"} 갈래는 {@code ProjectService.detail} 에서
     * 같이 걷어냈고, 여기서는 <b>진짜 등록일시가 서식대로 나오는지</b>를 잰다 — 던지지 않는지를
     * 보는 것은 그대로이고, 재는 값은 더 세졌다.
     */
    @Test
    void 등록으로_안_들어온_행도_상세가_등록일시를_보여준다() {
        var sealed = sealer.seal("glpat-임시토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        // ⛔ ProjectService.register() 를 일부러 안 거친다 — 씨딩·수동 삽입을 흉내 내는 자리다
        repository.insert(Project.create(id, "손으로 앉힌-" + id,
                "https://gitlab.co/we/no-created-at.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        repository.updateState(id, ProjectState.READY, null);

        ProjectDetailView view = projects.detail(id);

        assertThat(view.createdAtText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    private String seatIntakeUsing(String projectId, String facetName) {
        var uploader = accounts.selectByLoginId("admin").orElseThrow();
        String intakeId = ids.next(IdSequence.Kind.INTAKE);
        // ⚠ 프로젝트도 2026-08-15 부터 MyBatis 라 register() 가 곧장 넣는다 — 그래서 여기 INSERT 가
        //    FK(adk_builder_intake.project_id)를 바로 채운다. 남은 FK 는 uploaded_by 쪽인데
        //    그 계정은 부팅 때 커밋된 슈퍼계정이라 이 자리도 안전하다.
        //    ⚠ 2026-08-15 에 계정도 MyBatis 가 됐다 — 이 트랜잭션에서 계정을 손수 만들어 써도
        //       INSERT 가 곧장 들어가므로 이제 flush 를 걱정할 자리가 아예 없다.
        intakes.insert(Intake.create(intakeId, projectId, "회의록", uploader.getId()));
        intakeFacets.insert(IntakeFacet.create(intakeId, projectId, facetName));
        return intakeId;
    }

    @Test
    void 기획자는_관리_상세를_못_연다() throws Exception {
        Project p = readyProject();

        mvc.perform(get("/admin/projects/" + p.getId()).with(user(planner())))
                .andExpect(status().isForbidden());
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    /** ⚠ 비밀번호를 바꿔 둬야 한다 — 안 그러면 {@code FirstLoginFilter} 가 관문에서 /password 로
     * 302 를 먼저 던져서, 이 테스트가 재려는 「관리 화면 자체의 403」에 닿지도 못한다. */
    private BuilderUser planner() {
        var account = accounts.selectByLoginId("detailplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT),
                    "detailplanner", "이영희", "younghee@bizplay.co.kr",
                    encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }
}
