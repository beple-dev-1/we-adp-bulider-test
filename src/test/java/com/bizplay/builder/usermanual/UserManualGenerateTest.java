package com.bizplay.builder.usermanual;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.ai.ClaudeCredentialRunner;
import com.bizplay.builder.ai.ClaudeRunner.ClaudeResult;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectPaths;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.project.ProjectSystemService;
import com.bizplay.builder.secret.SecretSealer;
import com.bizplay.builder.solution.SolutionMockupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 사용자 매뉴얼 만들기 (그린존 A2 · 2026-08-27).
 *
 * <p><b>AI 가 글을 쓰고 파일은 안 고친다.</b> 매뉴얼은 <b>글 한 장을 새로 쓰는 것</b>이라
 * 파일 권한을 열 이유가 없다 — 응답 JSON 안에서 받는다({@code ScreenTobeDocumentReader} 와 같은 판단).
 *
 * <p>⛔ <b>{@code AiRunKind} 에 값을 더하지 않는다.</b> 그 넷은 산출물 사슬이고 이것은
 * 화면 단위 문서다 — 변경 예정 기능정의서가 이미 전용 워커로 간 길을 그대로 따른다.
 */
class UserManualGenerateTest extends AbstractDbTest {

    @Autowired UserManualWorker worker;
    @Autowired UserManualMapper manuals;
    @Autowired UserManualCaptureStore captureStore;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;
    @Autowired ProjectPaths paths;
    @Autowired ProjectSystemService projectSystems;
    @Autowired SolutionMockupService solutions;

    @MockitoBean ClaudeCredentialRunner credentialRunner;

    @Test
    void 시스템과_화면이_맞지_않으면_생성을_시작하지_않는다() throws Exception {
        String projectId = readyProject("매뉴얼-화면경계").getId();
        seedClone(projectId);

        assertThat(worker.generateNow(projectId, "webview", "bo-delivery-detail", "0000002")).isFalse();
        assertThat(manuals.selectOne(projectId, "webview", "bo-delivery-detail")).isEmpty();
    }

    @Test
    void 응답_JSON_에서_매뉴얼을_읽어_저장한다() throws Exception {
        String projectId = readyProject("매뉴얼-생성").getId();
        seedClone(projectId);
        aiAnswers(manualJson("배송 상세 사용법"));

        assertThat(worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002")).isTrue();

        UserManual found = manuals.selectOne(projectId, "backoffice", "bo-delivery-detail").orElseThrow();
        assertThat(found.state()).isEqualTo(UserManualState.DONE);
        assertThat(found.html()).contains("선불카드 배송 상세").doesNotContain("배송 상세 사용법");
        assertThat(found.createdAt()).isNotNull();
        assertThat(found.sourceFingerprint()).hasSize(64);
        assertThat(found.generatorVersion()).isEqualTo(UserManualWorker.GENERATOR_VERSION);
        UserManualCapture capture = manuals.selectCapture(projectId, "backoffice", "bo-delivery-detail")
                .orElseThrow();
        assertThat(capture.label()).isEqualTo("기본 화면");
        assertThat(capture.width()).isEqualTo(1440);
        assertThat(capture.height()).isEqualTo(1000);
        assertThat(Files.readAllBytes(captureStore.file(capture.bundlePath(), capture.fileName())))
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);

        var screen = solutions.screens(projectId).stream().filter(item ->
                item.system().equals("backoffice") && item.screenId().equals("bo-delivery-detail"))
                .findFirst().orElseThrow();
        assertThat(worker.isCurrent(projectId, solutions.screens(projectId), screen, found)).isTrue();
        UserManual failed = new UserManual(found.projectId(), found.systemCode(), found.screenId(),
                found.html(), found.createdAt(), UserManualState.FAILED, "INVALID_RESPONSE",
                found.generationId(), found.generationStartedAt(),
                found.sourceFingerprint(), found.generatorVersion());
        assertThat(worker.isCurrent(projectId, solutions.screens(projectId), screen, failed)).isFalse();
        Files.writeString(paths.cloneDir(projectId).resolve("core/backoffice/pages/bo-delivery-detail.md"),
                "\n같은 날 추가된 변경", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThat(worker.isCurrent(projectId, solutions.screens(projectId), screen, found)).isFalse();
    }

    /** ⚠ 모델이 코드 울타리로 감싸는 일이 잦다 — 그것 하나로 실패로 떨어지면 안 된다. */
    @Test
    void 코드_울타리로_감싼_응답도_읽는다() throws Exception {
        String projectId = readyProject("매뉴얼-울타리").getId();
        seedClone(projectId);
        aiAnswers("```json\n" + manualJson("울타리 안")
                .replace("배송 건을 확인하고 처리하는 화면입니다.", "울타리 응답을 확인합니다.") + "\n```");

        worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002");

        assertThat(manuals.selectOne(projectId, "backoffice", "bo-delivery-detail")
                .orElseThrow().html()).contains("울타리 응답을 확인합니다.");
    }

    /**
     * ⛔ <b>「못 만들었다」를 글로 받지 않는다</b> — 그걸 저장하면 매뉴얼이 사과문을 싣는다.
     * 실패로 남겨야 사람이 다시 누를 수 있다.
     */
    @Test
    void 빈_매뉴얼은_실패로_남는다() throws Exception {
        String projectId = readyProject("매뉴얼-빈답").getId();
        seedClone(projectId);
        aiAnswers("{\"title\":\"\",\"overview\":\"\",\"overviewEvidence\":\"md:id\",\"openingSteps\":[],"
                + "\"tasks\":[],\"fields\":[],\"nextScreens\":[]}");

        worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002");

        UserManual found = manuals.selectOne(projectId, "backoffice", "bo-delivery-detail").orElseThrow();
        assertThat(found.state()).isEqualTo(UserManualState.FAILED);
        assertThat(found.failedReason()).isNotBlank();
    }

    @Test
    void 원본에_없는_기능을_근거로_들면_저장하지_않는다() throws Exception {
        String projectId = readyProject("매뉴얼-근거없음").getId();
        seedClone(projectId);
        aiAnswers(manualJson("없는 기능 사용법").replace("html:btnReturn", "html:btnInvented"));

        assertThat(worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002")).isFalse();

        UserManual found = manuals.selectOne(projectId, "backoffice", "bo-delivery-detail").orElseThrow();
        assertThat(found.state()).isEqualTo(UserManualState.FAILED);
        assertThat(found.failedReason()).isEqualTo("INVALID_RESPONSE");
        assertThat(found.html()).isNull();
    }

    @Test
    void 개요도_원본_근거가_없으면_저장하지_않는다() throws Exception {
        String projectId = readyProject("매뉴얼-개요근거없음").getId();
        seedClone(projectId);
        aiAnswers(manualJson("근거 없는 개요")
                .replace("md:배송 건을 조회하고 반송을 처리한다", "md:원본에 없는 개요"));

        assertThat(worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002")).isFalse();
        assertThat(manuals.selectOne(projectId, "backoffice", "bo-delivery-detail")
                .orElseThrow().failedReason()).isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void 화면_여는_순서도_원본_근거가_없으면_저장하지_않는다() throws Exception {
        String projectId = readyProject("매뉴얼-진입근거없음").getId();
        seedClone(projectId);
        aiAnswers(manualJson("근거 없는 진입 순서")
                .replace("ia:선불카드 관리", "ia:원본에 없는 메뉴"));

        assertThat(worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002")).isFalse();
        assertThat(manuals.selectOne(projectId, "backoffice", "bo-delivery-detail")
                .orElseThrow().failedReason()).isEqualTo("INVALID_RESPONSE");
    }

    /** ⚠ 자격이 없는 것은 고장이 아니라 <b>사람이 연결을 안 한 것</b>이다 — 까닭이 남아야 한다. */
    @Test
    void 자격이_없으면_실패로_남는다() throws Exception {
        String projectId = readyProject("매뉴얼-자격없음").getId();
        seedClone(projectId);
        given(credentialRunner.run(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .willReturn(Optional.empty());

        worker.generateNow(projectId, "backoffice", "bo-delivery-detail", "0000002");

        UserManual found = manuals.selectOne(projectId, "backoffice", "bo-delivery-detail").orElseThrow();
        assertThat(found.state()).isEqualTo(UserManualState.FAILED);
        assertThat(found.failedReason()).isEqualTo("NO_CREDENTIAL");
    }

    /** ⛔ {@code void @Async} 의 예외는 로그만 남는다 — 던지면 조용히 사라진다. */
    @Test
    void 없는_프로젝트에도_던지지_않는다() throws Exception {
        aiAnswers(manualJson("아무거나"));

        assertThat(worker.generateNow("0009999", "backoffice", "bo-nothing", "0000002")).isFalse();
    }

    /** AI 가 이렇게 답하게 세운다. ⚠ @Test 가 아닌 메서드는 영문이다 — KoreanIdentifierTest 가 잰다. */
    private void aiAnswers(String body) throws IOException {
        given(credentialRunner.run(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .willReturn(Optional.of(new ClaudeResult(0, false, null, null, body)));
    }

    private String manualJson(String title) {
        return """
                {
                  "title": "%s",
                  "overview": "배송 건을 확인하고 처리하는 화면입니다.",
                  "overviewEvidence": "md:배송 건을 조회하고 반송을 처리한다",
                  "openingSteps": [{
                    "text": "배송 목록에서 확인할 건을 선택합니다.",
                    "evidence": "ia:선불카드 관리"
                  }],
                  "tasks": [{
                    "title": "반송 처리",
                    "steps": ["반송 버튼을 누릅니다."],
                    "result": "선택한 배송 건이 반송 처리됩니다.",
                    "evidence": "html:btnReturn"
                  }],
                  "fields": [{
                    "name": "배송 상태",
                    "description": "배송 건의 현재 상태입니다.",
                    "evidence": "md:bo-delivery-detail-e01"
                  }],
                  "nextScreens": []
                }
                """.formatted(title);
    }

    /** ⚠ 클론이 없으면 화면을 못 찾아 MISSING_SCREEN 이 맞는 답이 된다 — 재료가 있어야 잰다. */
    private void seedClone(String projectId) throws IOException {
        Path root = paths.cloneDir(projectId);
        Path core = root.resolve("core");
        write(root.resolve("index.json"), """
                {
                  "schema": "we-adk-index/3",
                  "screens": {
                    "bo-delivery-detail": {"system": "backoffice", "ia": {"종류": "화면"}}
                  },
                  "counts": {"screens": 1}
                }
                """);
        write(core.resolve("backoffice/pages/bo-delivery-detail.md"), """
                --- 꼬리표 ---
                id: bo-delivery-detail / system: backoffice / 기능: 선불카드 관리 > 배송 관리 > 상세 / 과업: []

                --- 화면명세 ---
                화면명: 선불카드 배송 상세
                목적: 배송 건을 조회하고 반송을 처리한다

                --- 정의 ---
                - 구분: 기능 / 좌표: id=btnReturn / 앵커: bo-delivery-detail-e01 / 해설: 반송 처리
                """);
        write(core.resolve("backoffice/pages/bo-delivery-detail.html"),
                "<html><body><button id=\"btnReturn\">반송</button></body></html>");
        write(root.resolve("manifest.json"),
                "{\"schema\":\"we-adk-planning-repo/1\",\"systems\":["
                        + "{\"id\":\"backoffice\",\"prefix\":\"bo\"}]}");
        projectSystems.syncFromRepo(projectId);
        projectSystems.replaceNames(projectId, new LinkedHashMap<>(Map.of("backoffice", "백오피스")));
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
}
