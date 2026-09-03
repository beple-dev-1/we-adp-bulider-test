package com.bizplay.builder.shell;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.account.BuilderUser;
import com.bizplay.builder.claude.FakeClaudeAuthGateway;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.intake.Intake;
import com.bizplay.builder.intake.IntakeFacet;
import com.bizplay.builder.intake.IntakeFacetMapper;
import com.bizplay.builder.intake.IntakeMapper;
import com.bizplay.builder.intake.ProjectFacet;
import com.bizplay.builder.intake.ProjectFacetMapper;
import com.bizplay.builder.intake.ReceivedDocument;
import com.bizplay.builder.intake.ReceivedDocumentMapper;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면 껍데기 — 아홉 화면이 공유하는 것이 실제로 붙는지 본다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-09-screen-shell-design.md}
 *
 * <p>⚠ 여기서 재는 것은 <b>붙었나</b>까지다. 색이 맞나 · 여백이 고른가 · 글꼴이 실제로
 * Pretendard 로 뜨나는 자동으로 못 잰다 — {@link #렌더된_화면을_파일로_뽑는다()} 가 뽑아 놓은
 * 파일을 사람이 브라우저로 열어 눈으로 본다.
 */
@AutoConfigureMockMvc
@Import(FakeClaudeAuthGateway.Wiring.class)
class ShellTest extends AbstractDbTest {

    /** 껍데기가 걸어야 하는 CSS 여섯 장. 넷은 퍼블리셔 것이고 마지막 둘이 빌더 몫이다(Task 3b). */
    private static final String[] CSS_SIX = {
            "/css/tokens.css", "/css/reset.css", "/css/typography.css",
            "/css/components.css", "/css/shell.css", "/css/screens.css"
    };

    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다. */
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    /** ⚠ 2026-08-15 에 JPA 리포지터리에서 MyBatis 매퍼로 바뀌었다 — 재는 것은 그대로 화면에 뜨는 글이다. */
    @Autowired IntakeMapper intakes;
    @Autowired ReceivedDocumentMapper documents;
    @Autowired ProjectFacetMapper projectFacets;
    @Autowired IntakeFacetMapper intakeFacets;
    /** 요구사항 화면 둘(계획 6)을 뽑을 때 쓴다. */
    @Autowired com.bizplay.builder.intake.RequirementMapper requirements;
    /** 솔루션 목업이 읽을 씨앗 클론을 어디에 깔지 — 실물과 같은 자리 계산을 쓴다. */
    @Autowired com.bizplay.builder.project.ProjectPaths paths;
    /** FRD 화면 셋(계획 8)을 뽑을 때 쓴다. */
    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper frdScreens;

    @Test
    void 틀_밖_화면도_틀_안_화면도_같은_CSS_여섯_장과_글꼴을_건다() throws Exception {
        String loginHtml = body(get("/login"));
        String adminHtml = body(get("/admin/accounts").with(user(superUser())));

        for (String css : CSS_SIX) {
            assertThat(loginHtml).as("로그인 화면이 %s 를 건다", css).contains(css);
            assertThat(adminHtml).as("관리 화면이 %s 를 건다", css).contains(css);
        }
        // 글꼴은 미리 받는다 — 늦게 바뀌며 깜빡이는 것을 막는다
        assertThat(loginHtml).contains("rel=\"preload\"").contains("/fonts/PretendardVariable.woff2");
        assertThat(adminHtml).contains("rel=\"preload\"").contains("/fonts/PretendardVariable.woff2");
    }

    /**
     * 스프링은 클래스패스 {@code static/} 을 뿌리 경로로 내보낸다 — {@code /static/**} 이 아니다.
     * 이걸 안 열면 <b>로그인 화면에서 CSS 와 글꼴이 인증에 막힌다.</b>
     */
    @Test
    void CSS_와_글꼴은_로그인_전에도_받아진다() throws Exception {
        for (String css : CSS_SIX) {
            mvc.perform(get(css)).andExpect(status().isOk());
        }
        mvc.perform(get("/fonts/PretendardVariable.woff2")).andExpect(status().isOk());
        mvc.perform(get("/js/shell.js")).andExpect(status().isOk());
    }

    /**
     * ⚠ 2026-08-09 코덱스 적대검증이 잡은 자리다. {@code SecurityConfig} 만 열어 두면 모자란다 —
     * {@link com.bizplay.builder.web.FirstLoginFilter} 가 관문에 걸린 사람의 요청을 통째로
     * 되튕기므로, <b>비밀번호를 아직 안 바꾼 사람은 CSS 를 받으려다 {@code /password} 로 302 를 받는다.</b>
     * 그러면 비밀번호 화면이 스타일도 글꼴도 없이 맨몸으로 뜬다.
     *
     * <p>바로 위 테스트는 <b>익명</b> 요청만 재므로 이 경로를 놓친다. 둘 다 있어야 한다.
     */
    @Test
    void 관문에_걸린_사람도_CSS_와_글꼴을_받는다() throws Exception {
        var gated = tempPasswordUser();

        for (String css : CSS_SIX) {
            mvc.perform(get(css).with(user(gated)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/fonts/PretendardVariable.woff2").with(user(gated)))
                .andExpect(status().isOk());
        mvc.perform(get("/js/shell.js").with(user(gated)))
                .andExpect(status().isOk());
    }

    @Test
    void Top_제품명은_WE_ADP_Builder다() throws Exception {
        String version = environment.getRequiredProperty("builder.application-version");
        assertThat(body(get("/login")))
                .contains("class=\"app-header__brand\"", "WE-ADP Builder",
                        "class=\"app-header__version\">v" + version + "</small>")
                .doesNotContain("class=\"app-header__brand\" href=")
                .doesNotContain("<span>WE</span>");
    }

    @Test
    void 글자_위계는_Admin_기준을_따른다() throws Exception {
        String css = Files.readString(
                Path.of("src", "main", "resources", "static", "css", "shell.css"));

        assertThat(css).containsPattern("body\\s*\\{[^}]*font-size:\\s*0\\.9375rem;");
        assertThat(css).containsPattern("\\.data-table\\s*\\{[^}]*font-size:\\s*0\\.8125rem;");
        assertThat(css).containsPattern("\\.app-nav a\\s*\\{[^}]*font-size:\\s*0\\.875rem;");
        assertThat(css).containsPattern("\\.app-main > \\* > h1\\s*\\{[^}]*font-size:\\s*1\\.5rem;");
        assertThat(css).containsPattern("\\.app-main > \\* > h2\\s*\\{[^}]*font-size:\\s*1\\.125rem;");
    }

    @Test
    void 로그인_전에는_머리에_사람도_알림도_없다() throws Exception {
        String loginHtml = body(get("/login"));

        assertThat(loginHtml).doesNotContain("알림");     // 알림은 로그인한 뒤에만
        assertThat(loginHtml).doesNotContain("나간다");   // 로그아웃도 그렇다
    }

    /**
     * ⚠ 이 테스트가 실측으로 잡은 함정이 있다 — Thymeleaf 에서 {@code th:replace} 의 우선순위가
     * {@code th:if} 보다 높다. 둘을 같은 태그에 걸면 조건이 무시되고 조각이 무조건 끼어들어서
     * <b>로그인 화면에 메뉴 열이 딸려 나왔다.</b> 감싸는 {@code th:block} 이 조건을 들어야 한다.
     */
    @Test
    void 로그인_계열은_메뉴_열이_없고_관리는_있다() throws Exception {
        assertThat(body(get("/login")))
                .contains("app-shell--card")
                .contains("app-card")
                .doesNotContain("app-nav");

        assertThat(body(get("/admin/accounts").with(user(superUser()))))
                .contains("app-nav")
                .doesNotContain("app-shell--card");
    }

    @Test
    void 관리_메뉴는_항목_둘이고_지금_있는_자리에만_표시가_붙는다() throws Exception {
        String accountsHtml = body(get("/admin/accounts").with(user(superUser())));

        assertThat(accountsHtml).contains("프로젝트").contains("계정");
        // 지금 있는 자리에만 붙는다 — 그 표시가 라일락으로 찬다
        assertThat(accountsHtml).containsOnlyOnce("aria-current=\"page\"");
        assertThat(accountsHtml).contains("href=\"/admin/projects\"");
    }

    @Test
    void 슈퍼계정에게만_머리에_관리가_보인다() throws Exception {
        assertThat(body(get("/admin/projects").with(user(superUser())))).contains(">프로젝트 관리<");

        // 기획자는 관리 화면 자체에 못 들어가므로 로그인 계열 화면의 머리로 본다
        assertThat(body(get("/password").with(user(planner())))).doesNotContain(">프로젝트 관리<");
    }

    @Test
    void 관리_화면의_메뉴_접기_옆에는_Builder_이동_링크가_있다() throws Exception {
        String adminHtml = body(get("/admin/projects").with(user(superUser())));
        String version = environment.getRequiredProperty("builder.application-version");
        int navToggle = adminHtml.indexOf("class=\"app-nav-toggle\"");
        int builderEntry = adminHtml.indexOf("class=\"app-header__builder-entry\"");
        int headerGap = adminHtml.indexOf("class=\"app-header__gap\"");

        assertThat(adminHtml)
                .contains("class=\"app-header__brand\"", "WE-ADP Builder",
                        "class=\"app-header__version\">v" + version + "</small>")
                .contains("class=\"app-header__builder-entry\"")
                .contains("href=\"/projects?from=admin\"")
                .contains(">Builder로 이동</a>")
                .doesNotContain("app-header__builder-entry\"><span")
                .doesNotContain("class=\"app-header__brand\" href=");
        assertThat(builderEntry).isGreaterThan(navToggle).isLessThan(headerGap);

        assertThat(body(get("/password").with(user(planner()))))
                .doesNotContain("app-header__builder-entry", "Builder로 이동");
    }

    /**
     * {@code DQ.RENDER} — 렌더된 화면이 곧 증거다. 여기서 뽑아 {@code target/rendered/} 에 둔다.
     * CSS·글꼴의 절대 경로를 저장소 안 상대 경로로 바꿔 놓으므로 브라우저로 그냥 열면 된다.
     */
    @Test
    void 렌더된_화면을_파일로_뽑는다() throws Exception {
        Path dir = Path.of("target", "rendered");
        Files.createDirectories(dir);

        dump(dir, "login.html", body(get("/login")));
        dump(dir, "admin-projects.html", body(get("/admin/projects").with(user(superUser()))));
        dump(dir, "password.html", body(get("/password").with(user(tempPasswordUser()))));
        // ⚠ 이 화면이 렌더 대상에서 빠져 있어서 승인 링크가 누르기 하한(44px)을 어기는 것을
        // 아무도 못 봤다 — 2026-08-09 코덱스 적대검증이 짚었다.
        dump(dir, "claude-connect.html", body(get("/claude/connect").with(user(plannerWithoutClaude()))));

        // ⚠ 이 계정 목록 뽑기는 반드시 위 tempPasswordUser()·plannerWithoutClaude() 뒤에 둔다 —
        //    그래야 뽑힌 표에 슈퍼 행 하나만 아니라 waiting·review 배지와 기획자 행이 같이 있다.
        //    2026-08-15 코덱스 리뷰 1회차가 짚었다: 계정 설정 전에 뽑으면 사람이 열어도 새 열 여섯의
        //    대조(완료 대 진행 중 대 해당 없음)를 하나도 못 본다.
        dump(dir, "admin-accounts.html", body(get("/admin/accounts").with(user(superUser()))));

        // 받은 문서 셋 — 목록·등록·상세. 상세는 세 갈래를 다 뽑는다: 몸이 서로 다르다.
        // ⚠ 안내가 사람에게 실제로 읽히는지는 자동으로 못 잰다 — 눈으로 볼 자리를 만들어 둔다.
        // ⚠ 2026-08-15 에 갈래의 뜻이 바뀌었다: 「정리본 확인 → 처리 방향」이 없어지고
        //    「등록 완료(한 칸) · 내용 분석 중 · 문서 처리 오류」 셋이 됐다.
        Project p = projectWithFacets();
        String intakeId = seatUnreadableDocument(p.getId());
        String processingId = seatUnderstoodDocument(p.getId(), "8/5 전자결재 회의록 (스캔)", "익산");
        String readyId = seatReadyDocument(p.getId(), "8/12 전자결재 회의록", "익산", "제주");

        // ⚠ 여기 있던 em.flush() + em.clear() 는 2026-08-15 에 지웠다. 그것은 「등록일시」가
        //    DB 가 넣는 값이라(JPA 의 insertable=false) 방금 저장한 객체에는 없어서, 비우고 다시
        //    읽지 않으면 뽑힌 화면의 그 칸이 통째로 비던 것을 막던 장치였다. 아래 화면들이 읽는 것이
        //    전부 MyBatis 로 넘어와 매번 DB 를 새로 읽으므로 그 까닭이 사라졌다.
        //    ⛔ 되살리지 마라 — 되살릴 만한 증상이 보이면 그건 다른 원인이다.

        String base = "/projects/" + p.getId() + "/artifacts/received-docs";
        dump(dir, "received-docs.html", body(get(base).with(user(superUser()))));
        dump(dir, "received-doc-register.html", body(get(base + "/register").with(user(superUser()))));
        dump(dir, "received-doc.html", body(get(base + "/" + intakeId).with(user(superUser()))));
        dump(dir, "received-doc-processing.html", body(get(base + "/" + processingId).with(user(superUser()))));
        dump(dir, "received-doc-ready.html", body(get(base + "/" + readyId).with(user(superUser()))));

        // 요구사항 둘 (계획 6) — 목록과 상세. 상세는 세 갈래를 다 뽑는다: 머리와 카드가 서로 다르다.
        // ⚠ 목록은 「거르개 다섯 · 제외 배지 · 잠긴 정의서 요청」이 한 화면에 같이 있어야 눈으로 볼 값이 있다.
        String drafted = seatRequirement(p.getId(), readyId, "상신 화면 임시 저장",
                "상신 화면에서 작성 중인 결재 문서를 사용자가 직접 임시 저장할 수 있어야 합니다.",
                "상신 작성 · 임시 저장 문서 목록");
        String confirmed = seatRequirement(p.getId(), readyId, "결재선 자동 추천",
                "최근에 쓴 결재선을 기본값으로 제안해야 합니다.", null);
        // ⚠ 출처가 다른 문서인 줄도 하나 둔다 — 목록의 「출처 문서」 열과 거르개가 그때만 볼 값이 된다.
        String excluded = seatRequirement(p.getId(), processingId, "모바일 결재",
                "모바일에서도 결재할 수 있어야 합니다.", null);
        requirements.updateReviewState(confirmed,
                com.bizplay.builder.intake.Requirement.ReviewState.CONFIRMED, null);
        requirements.updateReviewState(excluded,
                com.bizplay.builder.intake.Requirement.ReviewState.EXCLUDED, "이번 범위가 아닙니다");

        String requirementBase = "/projects/" + p.getId() + "/artifacts/requirements";
        dump(dir, "requirements.html", body(get(requirementBase).with(user(superUser()))));
        dump(dir, "requirement.html",
                body(get(requirementBase + "/" + drafted).with(user(superUser()))));
        dump(dir, "requirement-edit.html",
                body(get(requirementBase + "/" + drafted + "?edit=true").with(user(superUser()))));
        dump(dir, "requirement-excluded.html",
                body(get(requirementBase + "/" + excluded).with(user(superUser()))));

        /*
         * 솔루션 목업 둘 (계획 7) — 목록과 상세. 상세는 기저와 기관 갈래를 다 뽑는다: 머리가 다르다.
         * ⚠ 뽑힌 파일에서 <b>미리보기 틀은 비어 보인다</b> — 그 안은 앱이 클론에서 내주는 것이라
         *   파일로 열면 안 온다. 미리보기까지 보려면 앱을 띄워 실제 클론이 앉은 프로젝트로 들어간다.
         *   여기서 눈으로 볼 것은 배치·거르개·배지·확대축소 조작이다.
         */
        seatSolutionClone(p.getId());
        String solutionBase = "/projects/" + p.getId() + "/artifacts/solution-mockups";
        dump(dir, "solution-mockups.html", body(get(solutionBase).with(user(superUser()))));
        dump(dir, "solution-mockup.html",
                body(get(solutionBase + "/bo-sample-list").with(user(superUser()))));
        dump(dir, "solution-mockup-variant.html",
                body(get(solutionBase + "/wv-sample-home").with(user(superUser()))));

        /*
         * FRD 셋 (계획 8) — 목록 · 마법사 걸음 2 · 작업대.
         * ⚠ 작업대의 미리보기 틀은 뽑힌 파일에서 비어 보인다 — 그 안은 앱이 DB 에서 내주는 것이라
         *   파일로는 안 온다. 미리보기까지 보려면 앱을 띄워라.
         * ⚠ 목록은 상태 여섯이 다 보이게 여섯 줄을 심는다 — 한 줄만 심으면 라벨을 눈으로 못 고른다.
         * ⚠ 위에서 심은 솔루션 클론(bo-sample-list · bo-sample-pop · wv-sample-home) 뒤에 둔다 —
         *   마법사 걸음 2 의 「화면 직접 고르기」 후보가 그 클론에서 나온다.
         */
        String[] frdIds = seedFrdsForRender(p);
        String pickedFrdId = frdIds[0];
        String draftingFrdId = frdIds[1];
        String frdBase = "/projects/" + p.getId() + "/artifacts/frds";
        dump(dir, "frds.html", body(get(frdBase).with(user(superUser()))));
        dump(dir, "frd-wizard.html",
                body(get(frdBase + "/" + pickedFrdId + "/pick").with(user(superUser()))));
        dump(dir, "frd.html", body(get(frdBase + "/" + draftingFrdId).with(user(superUser()))));

        // 관리 화면 다섯 (계획 4). ⚠ 상세는 준비됨·실패 두 갈래를 다 뽑는다 — 몸이 다르다.
        // 위에서 만든 준비된 프로젝트 p 를 그대로 쓴다 — 이름이 유니크라 또 만들면 겹친다.
        dump(dir, "admin-project-register.html",
                body(get("/admin/projects/new").with(user(superUser()))));
        dump(dir, "admin-project-detail.html",
                body(get("/admin/projects/" + p.getId()).with(user(superUser()))));

        // ⚠ 2026-08-15 부터 상태를 고치는 길은 매퍼의 update 하나다 — 엔티티에서 markFailed 를
        //    걷어냈다. 남겨 뒀다면 여기서 부르고 DB 는 안 바뀌는데 예외도 안 났을 것이다.
        projects.updateState(p.getId(), ProjectState.FAILED, "저장소 연결이 중간에 끊겼다");
        dump(dir, "admin-project-detail-failed.html",
                body(get("/admin/projects/" + p.getId()).with(user(superUser()))));

        dump(dir, "admin-account-register.html",
                body(get("/admin/accounts/new").with(user(superUser()))));
        dump(dir, "admin-account-detail.html",
                body(get("/admin/accounts/" + accounts.selectByLoginId("admin").orElseThrow().getId())
                        .with(user(superUser()))));

        assertThat(dir.resolve("login.html")).exists();
        assertThat(dir.resolve("claude-connect.html")).exists();
        assertThat(dir.resolve("received-doc-register.html")).exists();
        assertThat(dir.resolve("requirements.html")).exists();
        assertThat(dir.resolve("requirement.html")).exists();
        assertThat(dir.resolve("frds.html")).exists();
        assertThat(dir.resolve("frd-wizard.html")).exists();
        assertThat(dir.resolve("frd.html")).exists();
        assertThat(dir.resolve("admin-account-detail.html")).exists();
    }

    /**
     * FRD 목록 여섯 줄 — 상태 여섯이 다 보이게 심는다.
     *
     * <p>⚠ {@code seatSolutionClone} 뒤에 불러야 한다 — 마법사 걸음 2 의 「화면 직접 고르기」
     * 후보가 그 클론(index.json)에서 나온다.
     *
     * @return {마법사 걸음 2 를 뽑을 PICKED FRD 아이디, 작업대를 뽑을 DRAFTING FRD 아이디}
     */
    private String[] seedFrdsForRender(Project project) {
        String projectId = project.getId();
        String adminId = accounts.selectByLoginId("admin").orElseThrow().getId();

        // ① ANALYZING — 아직 화면을 안 찾았다. 화면 0장이라 진행은 「화면 없음」, 남은 작업은 「—」다.
        String analyzing = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(analyzing, projectId, frds.allocateNumber(projectId),
                "야간 정산 배치 주기 변경", "정산 배치를 매일 새벽 2시에서 4시로 옮겨야 한다.", null));

        // ② ANALYSIS_FAILED — 화면 수와 무관하게 「분석 실패」다. 화면 둘을 심어 그 무관함을 보인다.
        String failed = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(failed, projectId, frds.allocateNumber(projectId),
                "정산 대사 화면 개선", "정산 대사 화면에서 차이가 나는 건을 바로 보여줘야 한다.", null));
        frdScreens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), failed,
                "bo-recon-list", "정산 대사 목록", "bo-recon-list", null, "차이 건이 안 보인다"));
        frdScreens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), failed,
                "bo-recon-detail", "정산 대사 상세", "bo-recon-detail", null, "차이 사유가 안 보인다"));
        frds.updateAfterPick(failed, "정산 대사 화면 개선", null, null,
                Frd.State.ANALYSIS_FAILED, "Claude 자격이 만료됐다");

        // ③ PICKED — 짚은 화면 둘을 확인하는 자리. 마법사 걸음 2 를 이 아이디로 뽑는다.
        String picked = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(picked, projectId, frds.allocateNumber(projectId),
                "전자결재 상신 임시저장 지원",
                "상신 화면에서 작성 중인 문서를 임시 저장할 수 있어야 한다.", null));
        frdScreens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), picked,
                "wv-appr-write", "결재 문서 작성", "wv-appr-write", null, "상단에 임시저장 버튼이 없습니다"));
        frdScreens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), picked,
                "wv-appr-list", "임시저장 문서 목록", "wv-appr-list", null, "목록에 상태 열이 없습니다"));
        frds.updateAfterPick(picked, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);

        // ④ DRAFTING — 셋 중 하나만 완료, 하나는 실패다. 작업대를 이 아이디로 뽑는다.
        String drafting = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(drafting, projectId, frds.allocateNumber(projectId),
                "공지 게시 기능 개선", "공지 게시에 예약 게시 기능이 있어야 한다.", adminId));
        String noticeDone = ids.next(IdSequence.Kind.FRD_SCREEN);
        frdScreens.insert(FrdScreen.picked(noticeDone, drafting,
                "wv-notice-write", "공지 작성", "wv-notice-write", null, "예약 게시가 없다"));
        frdScreens.updateGenerated(noticeDone, "<article>완료된 목업</article>", null, Instant.now());
        String noticeFailed = ids.next(IdSequence.Kind.FRD_SCREEN);
        frdScreens.insert(FrdScreen.picked(noticeFailed, drafting,
                "wv-notice-list", "공지 목록", "wv-notice-list", null, "게시 기간 칸이 없다"));
        frdScreens.updateFailed(noticeFailed, "미리보기 서버가 응답하지 않았다");
        frdScreens.insert(FrdScreen.picked(ids.next(IdSequence.Kind.FRD_SCREEN), drafting,
                "wv-notice-detail", "공지 상세", "wv-notice-detail", null, "상세에 예약 시각이 없다"));
        frds.updateAfterPick(drafting, "공지 게시 기능 개선", "webview", null, Frd.State.DRAFTING, null);

        // ⑤ REVIEW — 화면이 다 완료돼 남은 작업이 없다.
        String review = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(review, projectId, frds.allocateNumber(projectId),
                "선불카드 상태 변경 확인팝업 문구 수정",
                "카드 상태 변경 확인팝업 문구를 명확히 해야 한다.", null));
        String popupDone = ids.next(IdSequence.Kind.FRD_SCREEN);
        frdScreens.insert(FrdScreen.picked(popupDone, review,
                "bo-sample-pop", "선불카드 카드상태 변경 확인 팝업", "bo-sample-pop", null, "문구가 모호하다"));
        frdScreens.updateGenerated(popupDone, "<article>완료된 목업</article>", null, Instant.now());
        frds.updateAfterPick(review, "선불카드 상태 변경 확인팝업 문구 수정", "backoffice", null,
                Frd.State.REVIEW, null);

        // ⑥ DONE — 화면 없는 요건으로 확정하고 완료됐다.
        String done = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(done, projectId, frds.allocateNumber(projectId),
                "정산 배치 재시도 횟수 조정", "정산 배치 실패 시 재시도 횟수를 3회로 늘려야 한다.", null));
        frds.updateAfterPick(done, "정산 배치 재시도 횟수 조정", null,
                "배치 일이라 화면이 없다", Frd.State.DONE, null);

        return new String[] {picked, drafting};
    }

    /**
     * 솔루션 목업이 읽을 씨앗 클론 — 색인 하나 · 화면 셋(백오피스 둘 · 웹뷰 기관 갈래 하나).
     *
     * <p>⚠ 갈래 화면에는 {@code pages/<ID>.html} 을 <b>일부러 안 만든다</b> — 실물이 그렇다.
     * ⚠ git 저장소로 만들지 않는다: 뽑아 놓은 화면에서 이력 자리가 「—」로 뜨는 것도 봐야 한다.
     */
    private void seatSolutionClone(String projectId) throws java.io.IOException {
        Path clone = paths.cloneDir(projectId);
        seat(clone.resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    "bo-sample-list": {"system": "backoffice", "ia": {"종류": "화면"}},
                    "bo-sample-pop":  {"system": "backoffice", "ia": {"종류": "팝업"}},
                    "wv-sample-home": {"system": "webview",    "ia": {"종류": "화면"}}
                  },
                  "variantIndex": {"iksan": ["wv-sample-home"], "jeju": ["wv-sample-home"]}
                }
                """);
        Path core = clone.resolve("core");
        seatScreen(core, "backoffice", "bo-sample-list", "선불카드 관리 > 선불카드 관리 > 목록",
                "선불카드 관리 (목록)");
        seatScreen(core, "backoffice", "bo-sample-pop", "선불카드 관리 > 선불카드 관리 > 상세 > 확인팝업",
                "선불카드 카드상태 변경 확인 팝업");
        seatScreen(core, "webview", "wv-sample-home", "홈 > 메인", "메인 홈");
    }

    private void seatScreen(Path core, String system, String screenId, String menuPath, String name)
            throws java.io.IOException {
        seat(core.resolve(system).resolve("pages").resolve(screenId + ".md"), """
                --- 꼬리표 ---
                id: %s / system: %s / 기능: %s / 과업: []

                --- 화면명세 ---
                화면명: %s
                """.formatted(screenId, system, menuPath, name));
    }

    private void seat(Path target, String body) throws java.io.IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 요구사항 낱개 하나를 앉힌다.
     * ⚠ 번호는 손으로 박지 않는다 — 실물과 같은 문({@code allocateNumber})으로 집는다.
     */
    private String seatRequirement(String projectId, String intakeId, String title, String body,
                                   String screenHints) {
        String id = ids.next(IdSequence.Kind.REQUIREMENT);
        requirements.insert(com.bizplay.builder.intake.Requirement.draft(id, projectId, intakeId,
                requirements.allocateNumber(projectId), title, body, screenHints));
        return id;
    }

    /**
     * ⛔ 2026-08-13 실측으로 잡은 자리다. 받은 문서 화면 셋이 링크에서 프로젝트 번호를
     * 한글 이름으로 읽고 있었는데 그 이름을 모델에 얹는 자리가 없어서(컨트롤러는
     * {@code projectId} 로 얹는다) <b>「문서 등록」 버튼과 목록 행 링크가
     * {@code /projects//artifacts/…} 로 나갔다.</b> 누르면 고르기로 튕긴다.
     *
     * <p>{@link ShellContract} 가 메뉴에서 막는 것과 <b>똑같은 조용한 실패</b>인데,
     * 계약은 껍데기 인자만 보므로 <b>본문 링크는 계약이 안 보는 자리</b>였다.
     * 계약을 본문까지 넓히는 대신 렌더된 글자로 잰다 — 이름이 또 갈라지면 여기가 빨개진다.
     */
    @Test
    void 본문_링크에도_프로젝트_번호가_빠진_자리가_없다() throws Exception {
        Project p = projectWithFacets();
        String intakeId = seatUnreadableDocument(p.getId());
        String base = "/projects/" + p.getId() + "/artifacts/received-docs";

        for (String url : new String[]{base, base + "/register", base + "/" + intakeId}) {
            assertThat(body(get(url).with(user(superUser()))))
                    .as("%s 의 링크에 프로젝트 번호가 빠진 자리가 없다", url)
                    .doesNotContain("/projects//");
        }
    }

    /**
     * ⚠ 2026-08-09 코덱스 관문 3회차가 「한 번도 안 재본 조합」으로 남긴 자리다.
     *
     * <p>뽑아 두는 화면이 전부 1440×900 이고 {@code <details>} 가 <b>닫힌 상태</b>라서,
     * <b>열린 팝업 × 좁은 화면</b>이 한 번도 안 재였다. {@code .pop__body} 는 트리거 오른쪽에 붙는
     * 최소 280px 폭이고 알림 트리거 <b>뒤에 사람 메뉴가 하나 더</b> 있으므로, 좁은 창에서 알림을 열면
     * 왼쪽 경계가 음수가 되거나 <b>페이지에 가로 스크롤이 생길</b> 수 있다. 가로 스크롤은 하한 위반이다.
     *
     * <p>여기서는 <b>열린 상태를 파일로 뽑기만</b> 한다 — 실제로 재는 것은 브라우저다.
     */
    @Test
    void 팝업이_열린_화면도_파일로_뽑는다() throws Exception {
        Path dir = Path.of("target", "rendered");
        Files.createDirectories(dir);

        String closed = body(get("/admin/accounts").with(user(superUser())));

        // 팝업 둘은 같은 자리에 앉으므로 **하나씩** 열어서 재야 한다. 둘을 같이 열면 서로 가린다.
        String first = closed.replaceFirst("<details class=\"pop pop--notifications\" name=\"headerPopup\">",
                "<details class=\"pop pop--notifications\" open>");
        String second = openSecond(closed);

        dump(dir, "shell-pop-alarm.html", first);
        dump(dir, "shell-pop-person.html", second);

        assertThat(dir.resolve("shell-pop-alarm.html")).exists();
        assertThat(dir.resolve("shell-pop-person.html")).exists();
        assertThat(first).containsOnlyOnce("<details class=\"pop pop--notifications\" open>");
        assertThat(second).containsOnlyOnce("<details class=\"pop\" open>");
    }

    /**
     * ⛔ 팝업 둘이 <b>동시에 열리면 같은 자리에서 겹쳐 알림이 통째로 가린다</b> — 좁은 화면만이 아니라
     * 1440px 에서도 그렇다(2026-08-09 실측). 자바스크립트를 안 쓰기로 했으므로 막는 길은
     * 같은 {@code name} 을 주어 <b>한 번에 하나만 열리게</b> 하는 HTML 표준 하나뿐이다.
     *
     * <p>이 테스트가 지키는 것은 「둘의 {@code name} 이 같다」이다. 브라우저가 실제로 하나만 여는지는
     * 브라우저의 몫이라 여기서 못 잰다 — 그건 렌더로 본다.
     */
    @Test
    void 머리의_팝업_둘은_한_번에_하나만_열린다() throws Exception {
        String adminHtml = body(get("/admin/accounts").with(user(superUser())));

        assertThat(adminHtml)
                .as("알림과 사람 팝업이 같은 name 을 달고 있어야 서로를 닫는다")
                .contains("<details class=\"pop pop--notifications\" name=\"headerPopup\">")
                .contains("<details class=\"pop\" name=\"headerPopup\">");
        assertThat(adminHtml).doesNotContain("<details class=\"pop\">");
    }

    /** 알림 뒤에 있는 사람 {@code details} 만 연다. */
    private String openSecond(String html) {
        String marker = "<details class=\"pop\" name=\"headerPopup\">";
        int at = html.indexOf(marker);
        return html.substring(0, at) + "<details class=\"pop\" open>"
                + html.substring(at + marker.length());
    }

    /**
     * ⚠ <b>2026-08-15 에 {@code flush} + {@code em.refresh} 가 여기서 사라졌다.</b> 그 둘은
     * 프로젝트가 JPA 라 ① {@code created_at}({@code insertable = false})이 저장 직후에도
     * 자바 쪽에서 null 이라 되읽어야 했고 ② 바로 아래 적용 구분 MyBatis INSERT 가 FK 를
     * 못 채우던 것을 막던 장치였다. <b>프로젝트도 MyBatis 가 되어 INSERT 가 곧장 들어가고
     * {@code selectById} 가 늘 DB 를 읽으므로 두 까닭이 다 사라졌다.</b> ⛔ 되살리지 마라.
     *
     * <p>⚠ 앉히기가 두 걸음인 것은 {@code insert} 가 늘 {@code RECEIVING} 으로 넣기 때문이다
     * ({@code Project.create} 가 처음 상태의 정본이다). ⛔ 「엔티티를 고치고 저장」으로 되돌리지 마라 —
     * MyBatis 엔 더티 체킹이 없어 조용히 잃는다.
     *
     * <p>⚠ 이름 뒤에 채번한 아이디를 붙여 <b>몇 번을 불러도 겹치지 않게</b> 한다 — {@code name} 은
     * 유니크 제약이라, 고정 문자열이면 한 테스트 안에서 두 번 부르는 순간(또는 나중에 다른
     * 테스트가 그렇게 하는 순간) {@code adk_builder_project_name_key} 위반으로 죽는다
     * (2026-08-15 코덱스 리뷰 1회차가 짚었다).
     */
    private Project projectWithFacets() {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, "탐나는전-" + id,
                "https://gitlab.example.com/x.git", "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        projectFacets.insert(ProjectFacet.create(id, "익산"));
        projectFacets.insert(ProjectFacet.create(id, "제주"));
        return projects.selectById(id).orElseThrow();
    }

    /** ⛔ 못 읽는 문서도 올라가고 보존된다 — 상세가 까닭과 다음 행동을 보여주는 자리다. */
    private String seatUnreadableDocument(String projectId) {
        String intakeId = ids.next(IdSequence.Kind.INTAKE);
        var account = accounts.selectByLoginId("admin").orElseThrow();
        intakes.insert(Intake.create(intakeId, projectId, "8/13 운영회의 회의록", account.getId()));
        documents.insert(ReceivedDocument.create(ids.next(IdSequence.Kind.RECEIVED_DOCUMENT), intakeId,
                ReceivedDocument.DocumentType.MEETING_MINUTES, "회의록.hwpx", "/tmp/회의록.hwpx", 1_843_200L,
                null, null, null,
                ReceivedDocument.DocumentIntakePlan.unreadable(
                        "한컴·오피스 압축 문서라 글자가 그대로 안 나온다. 변환기를 먼저 붙여야 한다")));
        return intakeId;
    }

    /**
     * 서버가 글자를 뽑아 <b>등록 즉시 완료</b>인 문서 — 2026-08-15 뒤의 흔한 모습이다.
     * ⛔ AI 를 안 거친다: 여기서 바로 「요구사항 분석」이 열린다.
     */
    private String seatReadyDocument(String projectId, String title, String... facets) {
        String intakeId = ids.next(IdSequence.Kind.INTAKE);
        var account = accounts.selectByLoginId("admin").orElseThrow();
        intakes.insert(Intake.create(intakeId, projectId, title, account.getId()));
        documents.insert(ReceivedDocument.create(ids.next(IdSequence.Kind.RECEIVED_DOCUMENT), intakeId,
                ReceivedDocument.DocumentType.MEETING_MINUTES, "8월12일_회의록.pdf", "/tmp/회의록.pdf",
                2_516_582L, null, null, "이영희 외 2명",
                ReceivedDocument.DocumentIntakePlan.extracted("""
                        전자결재 상신 화면의 임시 저장 기능을 검토했다.

                        작성 중인 내용은 1분 간격으로 자동 저장하고, 사용자가 직접 임시 저장할 수도 있어야 한다.

                        임시 저장 데이터의 보관 기간과 복구 기준은 다음 회의에서 확정한다.""")));
        for (String facet : facets) {
            intakeFacets.insert(IntakeFacet.create(intakeId, projectId, facet));
        }
        return intakeId;
    }

    /**
     * 멀티모달이 읽어 내고 <b>사람의 확인을 기다리는</b> 자리 — 상세의 <b>확인 모드</b>가 여기서만 열린다.
     *
     * <p>⚠ <b>2026-08-15 에 뜻이 바뀌었다.</b> 그전에는 「AI 1차 정리본 확인」이었고 <b>모든 문서</b>가
     * 이 자리를 지났다. 지금은 서버가 글자를 못 뽑은 스캔 PDF·그림만 온다.
     */
    private String seatUnderstoodDocument(String projectId, String title, String... facets) {
        String intakeId = ids.next(IdSequence.Kind.INTAKE);
        var account = accounts.selectByLoginId("admin").orElseThrow();
        intakes.insert(Intake.create(intakeId, projectId, title, account.getId()));
        documents.insert(ReceivedDocument.create(ids.next(IdSequence.Kind.RECEIVED_DOCUMENT), intakeId,
                ReceivedDocument.DocumentType.MEETING_MINUTES, "8월5일_회의록_스캔.pdf",
                "/tmp/회의록-스캔.pdf", 3_251_200L, null, null, "이영희 외 2명",
                ReceivedDocument.DocumentIntakePlan.needsUnderstanding(
                        "PDF 에서 글자가 안 나와 내용 분석이 필요하다")));
        for (String facet : facets) {
            intakeFacets.insert(IntakeFacet.create(intakeId, projectId, facet));
        }
        // ⚠ 고치는 길은 매퍼의 update 하나다 — 엔티티에 상태 변경 메서드가 없다
        //    (더티 체킹이 없으니 두면 저장된 줄 알고 DB 는 안 바뀐다).
        var document = documents.selectByIntakeId(intakeId).orElseThrow();
        documents.updateContentState(document.id(), ReceivedDocument.ContentState.PROCESSING);
        return intakeId;
    }

    private void dump(Path dir, String name, String html) throws Exception {
        String fixed = html
                .replace("\"/css/", "\"../../src/main/resources/static/css/")
                .replace("\"/fonts/", "\"../../src/main/resources/static/fonts/");
        Files.writeString(dir.resolve(name), fixed);
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private BuilderUser superUser() {
        var account = accounts.selectByLoginId("admin").orElseThrow();
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — Account 는 불변이라 방금 고친 값을 들고 있지 않다.
        //    옛 객체로 신원을 만들면 mustChangePassword 가 참인 채라 /password 로 되튕긴다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), true);
    }

    /** 임시 비밀번호만 쥔 사람 — 비밀번호 관문에 걸려 있다. */
    private BuilderUser tempPasswordUser() {
        var account = accounts.selectByLoginId("shellfirst").orElseGet(() -> {
            var fresh = Account.create(ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT), "shellfirst", "김철수", "kim@bizplay.co.kr",
                    encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
        return BuilderUser.of(account, false);
    }

    private BuilderUser planner() {
        var account = accounts.selectByLoginId("shellplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT), "shellplanner", "이영희", "lee@bizplay.co.kr",
                    encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
        return BuilderUser.of(account, false);
    }

    /** 비밀번호는 바꿨고 Claude 는 아직 안 연결한 사람 — 연결 관문에 걸려 있다. */
    private BuilderUser plannerWithoutClaude() {
        var account = accounts.selectByLoginId("shellconnect").orElseGet(() -> {
            var fresh = Account.create(ids.next(com.bizplay.builder.id.IdSequence.Kind.ACCOUNT), "shellconnect", "박지민", "park@bizplay.co.kr",
                    encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
        accounts.updatePassword(account.getId(), encoder.encode("바꾼비번1234"));
        // ⚠ 넣고 다시 읽는다 — 「비밀번호는 바꿨다」가 이 사람의 정의라, 옛 객체를 그대로 넘기면
        //    비밀번호 관문에 걸려 이 도구가 세우려던 사람이 아니게 된다.
        return BuilderUser.of(accounts.selectById(account.getId()).orElseThrow(), false);
    }
}
