package com.bizplay.builder.devrequest;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.account.Account;
import com.bizplay.builder.account.AccountMapper;
import com.bizplay.builder.frd.Frd;
import com.bizplay.builder.frd.FrdAnalysisNote;
import com.bizplay.builder.frd.FrdAnalysisNoteMapper;
import com.bizplay.builder.frd.FrdBackendChange;
import com.bizplay.builder.frd.FrdBackendChangeMapper;
import com.bizplay.builder.frd.FrdMapper;
import com.bizplay.builder.frd.FrdInterviewMessage;
import com.bizplay.builder.frd.FrdInterviewMessageMapper;
import com.bizplay.builder.frd.FrdScreen;
import com.bizplay.builder.frd.FrdScreenMapper;
import com.bizplay.builder.frd.FrdScreenMarker;
import com.bizplay.builder.frd.FrdScreenMarkerMapper;
import com.bizplay.builder.frd.ScreenMockupReader;
import com.bizplay.builder.frd.ScreenMockupService;
import com.bizplay.builder.frd.TemporaryScreenId;
import com.bizplay.builder.git.GitCommand;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발에 나가는 <b>꾸러미</b>가 계약서로 서나.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md}.
 *
 * <p>⭐ 여기서 재는 것 넷 — ① 목업이 <b>혼자 서나</b>(자산 상대경로가 실재를 가리키나)
 * ② 목업 바이트가 <b>한 글자도 안 바뀌었나</b> ③ 8절 목록과 {@code manifest.json} 이 <b>같나</b>
 * ④ 화면 0장에서도 서나.
 */
class DevRequestPackageTest extends AbstractDbTest {

    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired FrdMapper frds;
    @Autowired FrdScreenMapper screens;
    @Autowired FrdScreenMarkerMapper markers;
    @Autowired FrdBackendChangeMapper backendChanges;
    @Autowired FrdAnalysisNoteMapper notes;
    @Autowired FrdInterviewMessageMapper interviewMessages;
    @Autowired ScreenMockupService mockups;
    @Autowired DevelopmentRequestService service;
    @Autowired DevelopmentRequestMapper requests;
    @Autowired DevRequestPackageBuilder builder;
    @Autowired ProjectPaths paths;
    @Autowired GitCommand git;

    private static final String TOBE_HTML = """
            <!doctype html><html lang="ko"><head>
            <link rel="stylesheet" href="../assets/css/style.css"></head>
            <body data-screen-id="wv-appr-write">임시저장 버튼</body></html>""";

    @Test
    void 화면마다_as_is_와_to_be_와_변경내용이_담긴다() {
        var built = packageOf("꾸러미-기본");

        assertThat(paths(built)).contains(
                "screens/webview/wv-appr-write/as-is.html",
                "screens/webview/wv-appr-write/as-is.md",
                "screens/webview/wv-appr-write/to-be.html",
                "screens/webview/wv-appr-write/changes.md",
                "dev-request.md", "expected-back.md", "manifest.json");
    }

    @Test
    void 개발요청서_본문에_요청_원문과_인터뷰_요구사항_요약이_함께_담긴다() {
        Project project = readyProject("꾸러미-인터뷰요약");
        seedClone(project, "webview");
        String frdId = draftingFrd(project);
        interviewMessages.insert(FrdInterviewMessage.summary(
                ids.next(IdSequence.Kind.FRD_INTERVIEW_MESSAGE), frdId, 1,
                "직접 저장과 자동 저장을 지원하고 저장한 문서를 다시 열 수 있어야 합니다."));
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");

        String document = read(builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null).root().resolve("dev-request.md"));

        assertThat(document)
                .contains("## 1. 요청 내용", "### 요청 원문", "작성 중인 문서를 임시 저장")
                .contains("### 인터뷰에서 정리한 요구사항",
                        "직접 저장과 자동 저장을 지원하고 저장한 문서를 다시 열 수 있어야 합니다.");
    }

    @Test
    void 개발팀이_빌더에_돌려줄_대상만_별도_파일로_꾸러미에_담긴다() {
        var built = packageOf("꾸러미-회신대상");

        Path expectedBack = built.root().resolve("expected-back.md");
        assertThat(expectedBack).isRegularFile();
        assertThat(read(expectedBack))
                .contains("# 개발 완료 후 반환할 것")
                .contains("| 개발요청서 번호 | `DR-001` |")
                .contains("| 전송 키 | `DRK-0000001` |")
                .contains("`wv-appr-write`")
                .contains("단위테스트 결과")
                .contains("통합테스트 시나리오")
                .contains("## 회신 작성 방법")
                .contains("## 실제 도메인 변경 확인 — 필수")
                .contains("## 요청 외 실제 구현 변경 확인 — 필수")
                .contains("### 변경 없음 예시", "### 변경 있음 예시")
                .doesNotContain("상태코드", "POST /api");
        assertThat(read(built.root().resolve("dev-request.md")))
                .contains("## 11. 개발 완료 후 반환")
                .contains("`expected-back.md`", "`manifest.json`")
                .contains("자동 수신 대상으로 처리할 파일과 항목")
                .contains("계획에 없던 변경은 `expected-back.md`의 별도 검토 대상으로 신고한다")
                .doesNotContain("### 현재 운영 화면 재동기")
                .doesNotContain("## 회신 작성 방법")
                .doesNotContain("requests-to-dev.md");
        assertThat(read(built.root().resolve("manifest.json")))
                .contains("\"specVersion\" : \"2\"")
                .contains("\"path\" : \"expected-back.md\"")
                .contains("개발 완료 후 빌더에 돌려줄 대상");
    }

    @Test
    void 목업이_혼자_선다_상대경로가_실재하는_자산을_가리킨다() {
        var built = packageOf("꾸러미-자산");

        Path mockup = built.root().resolve("screens/webview/wv-appr-write/to-be.html");
        // ⭐ 목업 안의 ../assets/css/style.css 를 그 파일 자리에서 풀어 본다.
        Path resolved = mockup.getParent().resolve("../assets/css/style.css").normalize();
        assertThat(Files.isRegularFile(resolved))
                .as("목업이 부르는 자산이 꾸러미 안에 실재해야 한다").isTrue();
    }

    @Test
    void 목업_html_이_한_글자도_바뀌지_않는다() {
        var built = packageOf("꾸러미-무변환");

        // ⛔ 스킨을 갈아끼우거나 경로를 고치지 않는다 — 원본과 대조가 안 되게 된다.
        assertThat(read(built.root().resolve("screens/webview/wv-appr-write/to-be.html")))
                .isEqualTo(TOBE_HTML);
    }

    @Test
    void 신규_개발요청서는_클론과_DB가_아니라_고정한_작업트리_판에서_나간다() throws Exception {
        Project project = readyProject("꾸러미-작업트리정본");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        Path workspace = paths.frdWorktree(project.getId(), frdId);
        Path page = workspace.resolve("core/webview/pages");
        Path asset = workspace.resolve("core/webview/assets/css/style.css");
        Files.createDirectories(page);
        Files.createDirectories(asset.getParent());
        Files.writeString(page.resolve("wv-appr-write.html"), "<html>작업 시작 화면</html>", StandardCharsets.UTF_8);
        Files.writeString(page.resolve("wv-appr-write.md"), "작업 시작 기능정의서", StandardCharsets.UTF_8);
        Files.writeString(asset, ".base{}", StandardCharsets.UTF_8);
        git(workspace, "init");
        git(workspace, "config", "user.name", "Builder Test");
        git(workspace, "config", "user.email", "builder@example.com");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-m", "기준판");
        String baseSha = git(workspace, "rev-parse", "HEAD");

        Files.writeString(page.resolve("wv-appr-write.html"), "<html>작업트리 수정 화면</html>", StandardCharsets.UTF_8);
        Files.writeString(page.resolve("wv-appr-write.md"), "작업트리 변경 예정 기능정의서", StandardCharsets.UTF_8);
        Files.writeString(asset, ".worktree{}", StandardCharsets.UTF_8);
        git(workspace, "add", "-A");
        git(workspace, "commit", "-m", "완료판");
        String headSha = git(workspace, "rev-parse", "HEAD");

        seedClone(project, "webview");
        Files.writeString(paths.cloneDir(project.getId()).resolve("core/webview/pages/wv-appr-write.html"),
                "<html>오염된 현재 클론</html>", StandardCharsets.UTF_8);
        Files.writeString(paths.cloneDir(project.getId()).resolve("core/webview/assets/css/style.css"),
                ".clone{}", StandardCharsets.UTF_8);

        DevelopmentRequest request = service.createFromCompletedFrd(
                project.getId(), frdId, baseSha, headSha);
        var built = builder.build(service.read(project.getId(), request.id()),
                "DRK-0000001", Instant.now().toString(), null);

        assertThat(read(built.root().resolve("screens/webview/wv-appr-write/as-is.html")))
                .contains("작업 시작 화면").doesNotContain("오염된 현재 클론");
        assertThat(read(built.root().resolve("screens/webview/wv-appr-write/to-be.html")))
                .contains("작업트리 수정 화면").doesNotContain("임시저장 버튼");
        assertThat(read(built.root().resolve("screens/webview/wv-appr-write/to-be.md")))
                .isEqualTo("작업트리 변경 예정 기능정의서");
        assertThat(read(built.root().resolve("screens/webview/assets/css/style.css")))
                .isEqualTo(".worktree{}");
        assertThat(read(built.root().resolve("manifest.json"))).contains(headSha);
    }

    @Test
    void 여덟절_목록과_지문이_같은_파일을_말한다() throws Exception {
        var built = packageOf("꾸러미-목록");

        String manifest = read(built.root().resolve("manifest.json"));
        String document = read(built.root().resolve("dev-request.md"));
        for (String path : paths(built)) {
            if (path.equals("manifest.json")) {
                // ⚠ 지문은 자기 자신을 담지 않는다 — 자기 해시를 자기 안에 넣을 길이 없다.
                assertThat(manifest).doesNotContain("\"manifest.json\"");
                continue;
            }
            if (path.contains("/assets/")) {
                // ⭐ 자산은 낱개로 담지 않는다 (2026-08-25) — 아래 시험이 요약을 잰다.
                continue;
            }
            assertThat(manifest).as("지문이 계약 파일 %s 를 담아야 한다", path).contains(path);
        }
        // 8절은 손으로 적지 않는다 — 화면 파일이 목록에 그대로 뜬다.
        assertThat(document).contains("screens/webview/wv-appr-write/to-be.html");
        assertThat(document).contains("## 8. 화면별 산출물 목록");
    }

    /**
     * ⭐ 실물 실측이 낳은 시험이다 — 나간 꾸러미 {@code DRK-0000002} 의 {@code manifest.json} 이
     * <b>121,497 바이트</b>였고 그중 <b>98.7% 가 자산 항목</b>이었다(파일 519 = 자산 514 + 계약 5).
     */
    @Test
    void 자산은_낱개로_담기지_않고_요약_하나로_담긴다() throws Exception {
        var built = packageOf("꾸러미-자산요약");

        String manifest = read(built.root().resolve("manifest.json"));
        List<String> assetPaths = paths(built).stream()
                .filter(path -> path.contains("/assets/")).toList();

        assertThat(assetPaths).as("이 꾸러미에 자산이 있어야 재는 뜻이 있다").isNotEmpty();
        for (String path : assetPaths) {
            assertThat(manifest).as("자산 %s 는 낱개로 담기지 않는다", path).doesNotContain(path);
        }
        assertThat(manifest).contains("\"assets\"");
        assertThat(manifest).as("자산 수가 담긴다").contains("\"count\" : " + assetPaths.size());
    }

    /**
     * ⛔ 8절이 세는 자산과 {@code manifest.json} 이 요약하는 자산이 <b>갈리면</b>
     * 사람이 읽는 수와 기계가 읽는 수가 달라진다. 기준의 정본은 {@code DevRequestPackage.isAsset} 하나다.
     */
    @Test
    void 여덟절의_자산_수와_지문의_자산_수가_같다() throws Exception {
        var built = packageOf("꾸러미-자산수");

        long counted = paths(built).stream().filter(path -> path.contains("/assets/")).count();
        assertThat(read(built.root().resolve("dev-request.md")))
                .as("8절이 그 수를 적는다").contains("파일 " + counted + "장");
        assertThat(read(built.root().resolve("manifest.json")))
                .as("지문이 같은 수를 적는다").contains("\"count\" : " + counted);
    }

    /** ⚠ 지문(fingerprint)은 자산까지 셈해야 한다 — 전송 이력이 「어느 판을 보냈나」를 그것으로 묶는다. */
    @Test
    void 꾸러미_지문은_자산까지_셈한다() {
        var built = packageOf("꾸러미-지문범위");

        var withoutAssets = new DevRequestPackage(built.root(), built.entries().stream()
                .filter(entry -> !DevRequestPackage.isAsset(entry)).toList());

        assertThat(built.entries().stream().anyMatch(DevRequestPackage::isAsset)).isTrue();
        assertThat(built.fingerprint())
                .as("자산을 빼면 지문이 달라져야 한다 — 그래야 자산 변경이 이력에 잡힌다")
                .isNotEqualTo(withoutAssets.fingerprint());
    }

    @Test
    void 화면이_없어도_꾸러미가_선다() {
        Project project = readyProject("꾸러미-화면0장");
        String frdId = draftingFrd(project);
        var view = created(project, frdId);

        var built = builder.build(view, "DRK-0000001", Instant.now().toString(), null);

        assertThat(paths(built)).contains("dev-request.md", "manifest.json");
        assertThat(paths(built)).noneMatch(path -> path.startsWith("screens/"));
        assertThat(read(built.root().resolve("dev-request.md")))
                .contains("이 개발요청서에는 화면 작업이 없습니다");
    }

    @Test
    void 시스템이_둘이면_자산이_시스템마다_한_벌씩_앉는다() {
        Project project = readyProject("꾸러미-시스템둘");
        seedClone(project, "webview");
        seedClone(project, "backoffice");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        generated(frdId, "bo-appr-write", "결재 관리", "backoffice");
        var built = builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);

        assertThat(paths(built)).contains(
                "screens/webview/assets/css/style.css",
                "screens/backoffice/assets/css/style.css");
    }

    @Test
    void 마커와_메모가_변경내용에_적힌다() {
        Project project = readyProject("꾸러미-마커");
        seedClone(project, "webview");
        String frdId = draftingFrd(project);
        String screenRow = generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        markers.insert(new FrdScreenMarker(ids.next(IdSequence.Kind.FRD_SCREEN_MARKER), screenRow,
                1, planner().getId(), "이영희", "#save-draft", "임시저장 버튼",
                0.4d, 0.1d, 0.3d, 0.1d, "이 버튼을 상단 오른쪽으로 옮긴다",
                Instant.now(), Instant.now()));
        var built = builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);

        String note = read(built.root().resolve("screens/webview/wv-appr-write/changes.md"));
        assertThat(note).contains("화면에 표시한 지시").contains("임시저장 버튼")
                .contains("상단 오른쪽").contains("#save-draft");
    }

    @Test
    void 화면_외_구현이_갈래로_갈라지고_변경_없음이_따로_적힌다() {
        Project project = readyProject("꾸러미-백엔드");
        seedClone(project, "webview");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                frdId, 1, 1, FrdBackendChange.Category.API, "임시저장 API", "새로 만든다",
                "화면 md 에 저장 흐름이 없다", "두 번 저장해도 한 줄만 남는다", true, null));
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                frdId, 2, null, FrdBackendChange.Category.PERMISSION, "결재 권한",
                "확인했고 바꿀 것이 없다", null, null, false, null));
        var built = builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);

        String document = read(built.root().resolve("dev-request.md"));
        assertThat(document).contains("### API").contains("판정 방법: 두 번 저장해도 한 줄만 남는다");
        // ⭐ 백엔드는 as-is 가 빌더 손에 없다 — 「확인했고 변경 없음」이 그 대응물이다.
        assertThat(document).contains("### 확인했고 변경 없음").contains("결재 권한");
        // ⛔ 「공통 정책과 예외」 절을 따로 만들지 않는다 — 권한은 7절 갈래로 산다.
        assertThat(document).doesNotContain("공통 정책과 예외");
    }

    @Test
    void 제외_범위가_개발_범위_바로_뒤에_큰_절로_선다() {
        var built = packageOf("꾸러미-제외범위");

        String document = read(built.root().resolve("dev-request.md"));
        assertThat(document.indexOf("## 4. 제외 범위"))
                .isGreaterThan(document.indexOf("## 3. 개발 범위"));
        assertThat(document.indexOf("## 5. 완료 조건"))
                .isGreaterThan(document.indexOf("## 4. 제외 범위"));
    }

    @Test
    void 꾸러미에_리드미를_넣지_않는다() {
        var built = packageOf("꾸러미-리드미없음");

        // ⛔ 8절·manifest·창구 계약과 갈리는 넷째 사본이 된다 (2026-08-24 병주 확정).
        assertThat(paths(built)).noneMatch(path -> path.toUpperCase().contains("README"));
    }

    @Test
    void 다시_구우면_통째로_갈아_낀다() throws Exception {
        Project project = readyProject("꾸러미-재생성");
        seedClone(project, "webview");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        var view = created(project, frdId);
        var first = builder.build(view, "DRK-0000001", Instant.now().toString(), null);
        Files.writeString(first.root().resolve("남은판.txt"), "지워져야 한다", StandardCharsets.UTF_8);

        var again = builder.build(view, "DRK-0000001", Instant.now().toString(), null);

        assertThat(Files.exists(again.root().resolve("남은판.txt")))
                .as("판이 남으면 어느 것을 보냈는지 알 수 없다").isFalse();
    }

    /**
     * ⭐ 11절과 {@code manifest.json} 의 「돌려받을 것」은 <b>같은 자료</b>에서 난다 — 8절이 파일 목록에서
     * 나는 것과 같은 방식이다. 사본을 따로 만들면 어느 쪽이 맞는지 아무도 모른다.
     */
    @Test
    void 회신서가_지문의_돌려받을_것과_같은_화면과_모듈을_나열한다() {
        Project project = readyProject("꾸러미-돌려받을것");
        seedClone(project, "webview");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                frdId, 1, 1, FrdBackendChange.Category.API, "domains/approval/draft.md",
                "임시저장 흐름을 더한다", null, "두 번 저장해도 한 줄만 남는다", true, null));
        var built = builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);

        String document = read(built.root().resolve("dev-request.md"));
        String expectedBack = read(built.root().resolve("expected-back.md"));
        String manifest = read(built.root().resolve("manifest.json"));
        assertThat(document)
                .contains("## 11. 개발 완료 후 반환", "`expected-back.md`", "`manifest.json`")
                .doesNotContain("| webview | `wv-appr-write` | pages · screen-md · index | 받는다 |")
                .doesNotContain("| approval | draft |");
        // 화면 축 — 시스템 + 화면ID + 필수 구성요소 + 화면 md 받나
        assertThat(expectedBack).contains("| webview | `wv-appr-write` | pages · screen-md · index | 받는다 |");
        assertThat(manifest).contains("\"expectedBack\"").contains("\"wv-appr-write\"")
                .contains("\"pages\"").contains("\"screen-md\"").contains("\"index\"")
                .contains("\"acceptScreenMd\" : true");
        // 도메인 축 — expected-back.md와 manifest.json에만 있다. 백엔드 target 에서 뽑는다.
        assertThat(expectedBack).contains("| approval | draft |");
        assertThat(manifest).contains("\"domain\" : \"approval\"").contains("\"module\" : \"draft\"");
        assertThat(manifest).contains("\"backendChanges\"")
                .contains("\"target\" : \"domains/approval/draft.md\"");
        // 단위테스트 결과 = 7절 항목마다 · 통합테스트 시나리오 = 5절 항목마다
        assertThat(expectedBack).contains("화면 외 구현 1항목")
                .contains("domains/approval/draft.md").contains("두 번 저장해도 한 줄만 남는다");
        assertThat(expectedBack).contains("완료 조건 1건").contains("임시저장한 문서가 목록에 남는다");
        // 별도 파일도 같은 DR 값만 담고 수신 절차와 상태코드는 담지 않는다.
        assertThat(read(built.root().resolve("expected-back.md")))
                .contains("`wv-appr-write`")
                .contains("| approval | draft |")
                .doesNotContain("422", "409", "requests-to-dev.md");
    }

    @Test
    void 신규_화면은_외부_화면ID와_파일명과_연결_안내로_꾸러미에_담긴다() throws Exception {
        Project project = readyProject("꾸러미-신규화면식별자");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String workScreenId = TemporaryScreenId.of(rowId);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, workScreenId,
                "결재 상세 신규", "wv-appr-write", null, "상세 화면을 새로 만든다", "webview"));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup(
                "<html><body data-screen-id=\"" + workScreenId + "\">신규 상세</body></html>",
                List.of("상세 화면을 새로 만든다")));

        Path pages = paths.frdWorktree(project.getId(), frdId).resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(pages.resolve("wv-appr-write.md"), """
                --- 정의 ---
                - 구분: 이동 / 앵커: detail / 이동: %s / 라벨: 상세 열기 / 조건: 행 선택
                """.formatted(workScreenId), StandardCharsets.UTF_8);

        var view = created(project, frdId);
        var newScreen = view.content().screens().stream().filter(DevelopmentRequestContent.Screen::isNewScreen)
                .findFirst().orElseThrow();
        var source = view.content().screens().stream().filter(screen -> !screen.isNewScreen())
                .findFirst().orElseThrow();
        var built = builder.build(view, "DRK-0000001", Instant.now().toString(), null);

        assertThat(newScreen.deliveryScreenId()).doesNotStartWith("tmp-");
        assertThat(newScreen.deliveryFileName()).isEqualTo(newScreen.deliveryScreenId() + ".html");
        assertThat(source.connections()).singleElement().satisfies(connection -> {
            assertThat(connection.targetScreenId()).isEqualTo(newScreen.deliveryScreenId());
            assertThat(connection.anchor()).isEqualTo("detail");
            assertThat(connection.condition()).isEqualTo("행 선택");
        });
        String prefix = "screens/webview/" + newScreen.deliveryScreenId() + "/";
        assertThat(paths(built)).contains(prefix + "to-be.html", prefix + "changes.md")
                .noneMatch(path -> path.contains(workScreenId));
        assertThat(read(built.root().resolve(prefix + "changes.md")))
                .contains("| 화면 ID | `" + newScreen.deliveryScreenId() + "` |")
                .contains("| 파일명 | `" + newScreen.deliveryFileName() + "` |")
                .doesNotContain("| 화면 ID | `" + workScreenId + "` |");
    }

    @Test
    void 신규_화면을_전달_ID로_옮긴_뒤에도_꾸러미를_만든다() throws Exception {
        Project project = readyProject("꾸러미-신규화면전달순서");
        String frdId = draftingFrd(project);
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        String workScreenId = TemporaryScreenId.of(rowId);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, workScreenId,
                "결재 상세 신규", "wv-appr-write", null, "상세 화면을 새로 만든다", "webview"));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup(
                "<html><body data-screen-id=\"" + workScreenId + "\">신규 상세</body></html>",
                List.of("상세 화면을 새로 만든다")));

        Path workspace = paths.frdWorktree(project.getId(), frdId);
        Path pages = workspace.resolve("core/webview/pages");
        Files.createDirectories(pages);
        Files.writeString(workspace.resolve("README.md"), "기준판 " + Instant.now(), StandardCharsets.UTF_8);
        git(workspace, "init");
        git(workspace, "config", "user.name", "Builder Test");
        git(workspace, "config", "user.email", "builder@example.com");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-m", "기준판");
        String baseSha = git(workspace, "rev-parse", "HEAD");

        String draftVersion = Instant.now().toString();
        Files.writeString(pages.resolve(workScreenId + ".html"),
                "<html><body data-screen-id=\"" + workScreenId + "\">신규 상세 " + draftVersion + "</body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(pages.resolve(workScreenId + ".md"),
                "--- 정의 ---\n화면ID: " + workScreenId + "\n버전: " + draftVersion + "\n--- 원본 글 ---\n",
                StandardCharsets.UTF_8);
        git(workspace, "add", "-A");
        git(workspace, "commit", "-m", "FRD 완료판");
        String headSha = git(workspace, "rev-parse", "HEAD");

        DevelopmentRequest request = service.createFromCompletedFrd(
                project.getId(), frdId, baseSha, headSha);
        var beforeDelivery = service.read(project.getId(), request.id());
        var newScreen = beforeDelivery.content().screens().stream()
                .filter(DevelopmentRequestContent.Screen::isNewScreen).findFirst().orElseThrow();
        Files.writeString(pages.resolve(newScreen.deliveryScreenId() + ".html"),
                Files.readString(pages.resolve(workScreenId + ".html"), StandardCharsets.UTF_8)
                        .replace(workScreenId, newScreen.deliveryScreenId()),
                StandardCharsets.UTF_8);
        Files.writeString(pages.resolve(newScreen.deliveryScreenId() + ".md"),
                "--- 정의 ---\n화면ID: " + newScreen.deliveryScreenId() + "\n--- 원본 글 ---\n",
                StandardCharsets.UTF_8);
        Files.delete(pages.resolve(workScreenId + ".html"));
        Files.delete(pages.resolve(workScreenId + ".md"));
        git(workspace, "add", "-A");
        git(workspace, "commit", "-m", "전달 화면ID 확정");
        requests.updateWorkspaceHeadSha(request.id(), git(workspace, "rev-parse", "HEAD"));

        var built = builder.build(service.read(project.getId(), request.id()),
                "DRK-0000001", Instant.now().toString(), null);
        String prefix = "screens/webview/" + newScreen.deliveryScreenId() + "/";
        assertThat(paths(built)).contains(prefix + "to-be.html", prefix + "to-be.md")
                .noneMatch(path -> path.contains(workScreenId));
        assertThat(read(built.root().resolve(prefix + "to-be.html")))
                .contains(newScreen.deliveryScreenId()).doesNotContain(workScreenId);
        assertThat(Files.exists(pages.resolve(workScreenId + ".html"))).isFalse();
        assertThat(Files.isRegularFile(pages.resolve(newScreen.deliveryScreenId() + ".html"))).isTrue();
    }

    @Test
    void 도메인_경로가_자연어여도_백엔드_구현_회신_대상에서_사라지지_않는다() {
        Project project = readyProject("꾸러미-자연어백엔드");
        String frdId = draftingFrd(project);
        backendChanges.insert(new FrdBackendChange(ids.next(IdSequence.Kind.FRD_BACKEND_CHANGE),
                frdId, 1, 1, FrdBackendChange.Category.API, "사용자 CI 조회·전달 API (회원 도메인)",
                "CI를 연동 규격으로 변환해 전달한다", "회원 본인인증 흐름",
                "CI가 동일 사용자로 식별된다", true, null));
        var built = builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);

        String expectedBack = read(built.root().resolve("expected-back.md"));
        assertThat(expectedBack)
                .contains("화면 외 구현 결과 — 1항목")
                .contains("사용자 CI 조회·전달 API (회원 도메인)")
                .contains("도메인 정의가 바뀌었다면")
                .contains("도메인 변경 없음")
                .contains("## 회신 작성 방법")
                .contains("도메인 문서에 반드시 담을 내용")
                .contains("업무 규칙과 흐름", "API 계약", "데이터 계약", "적용 범위")
                .contains("변경 후 전체 문서", "diff·patch·바뀐 문단만 보내지 않는다")
                .contains("### 1. 사용자 CI 조회·전달 API (회원 도메인)")
                .contains("도메인 반영: `<changed | unchanged>`")
                .contains("요청서에 경로가 없더라도 변경이 생겼다면 개발자가 실제 책임 도메인과 모듈을 식별해 적는다")
                .contains("unchanged이고 특정 도메인이 없으면 해당 없음")
                .contains("핵심 코드·설정 위치")
                .contains("기준 기획 저장소 커밋 SHA", "파일 SHA-256", "파일 크기(byte)");
        assertThat(read(built.root().resolve("manifest.json")))
                .contains("\"backendChanges\"")
                .contains("사용자 CI 조회·전달 API (회원 도메인)");
    }

    @Test
    void 화면_외_구현이_0건이어도_필수_확인과_예시를_제공한다() {
        Project project = readyProject("꾸러미-돌려받을것0건");
        String frdId = draftingFrdWithoutNotes(project);
        var built = builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);

        String expectedBack = read(built.root().resolve("expected-back.md"));
        assertThat(expectedBack)
                .contains("화면 외 구현 결과 — 0항목")
                .contains("통합테스트 시나리오 — 완료 조건 0건")
                .contains("## 실제 도메인 변경 확인 — 필수")
                .contains("## 요청 외 실제 구현 변경 확인 — 필수")
                .contains("사전에 경로가 특정된 도메인 모듈이 0건이어도")
                .contains("이 신고가 `manifest.json`의 자동 수신 대상을 늘리지는 않으며")
                .contains("별도 검토용 파일")
                .contains("### 변경 없음 예시", "### 변경 있음 예시");
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    /** 완료 조건도 확인 필요도 없는 FRD — 11절의 「없음」을 재는 자리다. */
    private String draftingFrdWithoutNotes(Project project) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "전자결재 상신 임시저장 지원", "작성 중인 문서를 임시 저장할 수 있어야 한다.",
                planner().getId()));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        return id;
    }

    private DevRequestPackage packageOf(String projectName) {
        Project project = readyProject(projectName);
        seedClone(project, "webview");
        String frdId = draftingFrd(project);
        generated(frdId, "wv-appr-write", "결재 문서 작성", "webview");
        return builder.build(created(project, frdId), "DRK-0000001",
                Instant.now().toString(), null);
    }

    private List<String> paths(DevRequestPackage built) {
        return built.entries().stream().map(DevRequestPackage.Entry::path).toList();
    }

    private DevelopmentRequestService.View created(Project project, String frdId) {
        DevelopmentRequest request = service.createFromCompletedFrd(project.getId(), frdId);
        return service.read(project.getId(), request.id());
    }

    /** 클론에 as-is 화면과 자산을 앉힌다 — 꾸러미의 as-is 는 여기서 온다. */
    private void seedClone(Project project, String system) {
        Path core = paths.cloneDir(project.getId()).resolve("core").resolve(system);
        try {
            Files.createDirectories(core.resolve("pages"));
            Files.createDirectories(core.resolve("assets").resolve("css"));
            Files.writeString(core.resolve("assets/css/style.css"), ".wm-page{}",
                    StandardCharsets.UTF_8);
            String prefix = "webview".equals(system) ? "wv" : "bo";
            Files.writeString(core.resolve("pages/" + prefix + "-appr-write.html"),
                    "<!doctype html><html lang=\"ko\"><body>현재 화면</body></html>",
                    StandardCharsets.UTF_8);
            Files.writeString(core.resolve("pages/" + prefix + "-appr-write.md"),
                    "--- 화면명세 ---\n화면명: 결재 문서 작성\n", StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private String draftingFrd(Project project) {
        String id = ids.next(IdSequence.Kind.FRD);
        frds.insert(Frd.pasted(id, project.getId(), frds.allocateNumber(project.getId()),
                "전자결재 상신 임시저장 지원", "작성 중인 문서를 임시 저장할 수 있어야 한다.",
                planner().getId()));
        frds.updateAfterPick(id, "전자결재 상신 임시저장 지원", "webview", null, Frd.State.PICKED, null);
        frds.updateState(id, Frd.State.DRAFTING);
        notes.insert(new FrdAnalysisNote(ids.next(IdSequence.Kind.FRD_ANALYSIS_NOTE), id, 1,
                FrdAnalysisNote.Kind.ACCEPTANCE_CRITERION, "임시저장한 문서가 목록에 남는다", null));
        return id;
    }

    /** to-be 목업까지 만들어진 화면 한 장. ⚠ to-be 의 정본은 DB 다. */
    private String generated(String frdId, String screenId, String screenName, String system) {
        String rowId = ids.next(IdSequence.Kind.FRD_SCREEN);
        screens.insert(FrdScreen.pickedIn(rowId, frdId, screenId, screenName, screenId, null,
                "임시저장 버튼이 없습니다", system));
        mockups.markGenerated(rowId, new ScreenMockupReader.Mockup(TOBE_HTML,
                List.of("임시저장 버튼을 추가한다")));
        return rowId;
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private String git(Path workspace, String... args) {
        var result = git.run(workspace, java.time.Duration.ofSeconds(10), args);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return result.stdout().strip();
    }

    private Project readyProject(String name) {
        var sealed = sealer.seal("glpat-시험용토큰");
        String id = ids.next(IdSequence.Kind.PROJECT);
        projects.insert(Project.create(id, name, "https://gitlab.example.com/x.git",
                "main", "PS", sealed.cipher(), sealed.nonce()));
        projects.updateState(id, ProjectState.READY, null);
        return projects.selectById(id).orElseThrow();
    }

    private Account planner() {
        return accounts.selectByLoginId("pkgplanner").orElseGet(() -> {
            var fresh = Account.create(ids.next(IdSequence.Kind.ACCOUNT), "pkgplanner", "이영희",
                    "younghee@bizplay.co.kr", encoder.encode("임시1234"), false);
            accounts.insert(fresh);
            return fresh;
        });
    }
}
