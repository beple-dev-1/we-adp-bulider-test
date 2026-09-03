package com.bizplay.builder.usermanual;

import com.bizplay.builder.AbstractDbTest;
import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectMapper;
import com.bizplay.builder.project.ProjectState;
import com.bizplay.builder.secret.SecretSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 매뉴얼 저장 (그린존 A2 · 2026-08-27).
 *
 * <p><b>자리는 {@code (project_id, system_code, screen_id)} 하나다</b> — 화면 하나에 매뉴얼 하나이고,
 * 다시 만들면 <b>덮어쓴다</b>. 이력을 쌓지 않는다: 매뉴얼은 as-is 화면의 현재 모습을 설명하는 글이라
 * 낡은 판을 되살릴 자리가 없다({@code docs/artifacts.md} 의 「같은 재료」).
 *
 * <p>⛔ <b>클론에 파일로 게시하지 않는다.</b> 게시는 as-is 재동기가 선 뒤에 정할 일이고
 * ({@code docs/artifacts.md:78}), 지금 파일을 깔면 기획 저장소에 정본이 둘이 된다.
 */
class UserManualStorageTest extends AbstractDbTest {

    @Autowired UserManualMapper manuals;
    @Autowired ProjectMapper projects;
    @Autowired SecretSealer sealer;

    @Test
    void 매뉴얼을_넣으면_화면ID로_다시_읽힌다() {
        String projectId = readyProject("매뉴얼-저장").getId();

        manuals.upsert(UserManual.of(projectId, "backoffice", "bo-delivery-detail",
                "<h1>선불카드 배송 상세</h1>"));

        Optional<UserManual> found = manuals.selectOne(projectId, "backoffice", "bo-delivery-detail");

        assertThat(found).isPresent();
        assertThat(found.get().html()).contains("선불카드 배송 상세");
        assertThat(found.get().createdAt()).isNotNull();
        assertThat(manuals.selectCapture(projectId, "backoffice", "bo-delivery-detail")).isEmpty();
    }

    /**
     * ⭐ <b>다시 만들면 덮어쓴다 — 두 줄이 되지 않는다.</b> 이것이 안 서면 목록의 「작성일」이
     * 어느 판의 것인지 말할 수 없게 된다.
     */
    @Test
    void 같은_화면에_다시_만들면_덮어쓴다() {
        String projectId = readyProject("매뉴얼-덮기").getId();

        manuals.upsert(UserManual.of(projectId, "backoffice", "bo-delivery-detail", "<p>첫 판</p>"));
        manuals.upsert(UserManual.of(projectId, "backoffice", "bo-delivery-detail", "<p>두 번째 판</p>"));

        assertThat(manuals.selectByProject(projectId)).hasSize(1);
        assertThat(manuals.selectOne(projectId, "backoffice", "bo-delivery-detail")
                .orElseThrow().html()).contains("두 번째 판");
    }

    /** ⚠ 안 만든 화면은 빈 것이다 — 던지지 않는다. 목록이 640장을 도는데 하나로 깨지면 안 된다. */
    @Test
    void 안_만든_화면은_빈_것이다() {
        String projectId = readyProject("매뉴얼-없음").getId();

        assertThat(manuals.selectOne(projectId, "backoffice", "bo-nothing")).isEmpty();
        assertThat(manuals.selectByProject(projectId)).isEmpty();
    }

    @Test
    void 재생성_중과_실패에도_마지막_정상본을_보존한다() {
        String projectId = readyProject("매뉴얼-정상본-보존").getId();
        manuals.upsert(UserManual.of(projectId, "backoffice", "bo-detail", "<p>정상본</p>"));
        UserManual before = manuals.selectOne(projectId, "backoffice", "bo-detail").orElseThrow();
        String generationId = UUID.randomUUID().toString();

        assertThat(manuals.beginGeneration(projectId, "backoffice", "bo-detail", generationId,
                Instant.now().minusSeconds(900))).isOne();
        UserManual running = manuals.selectOne(projectId, "backoffice", "bo-detail").orElseThrow();
        assertThat(running.state()).isEqualTo(UserManualState.RUNNING);
        assertThat(running.html()).isEqualTo("<p>정상본</p>");
        assertThat(running.createdAt()).isEqualTo(before.createdAt());

        assertThat(manuals.markFailed(projectId, "backoffice", "bo-detail", generationId,
                "AI_EXECUTION_FAILED")).isOne();
        UserManual failed = manuals.selectOne(projectId, "backoffice", "bo-detail").orElseThrow();
        assertThat(failed.state()).isEqualTo(UserManualState.FAILED);
        assertThat(failed.html()).isEqualTo("<p>정상본</p>");
        assertThat(failed.createdAt()).isEqualTo(before.createdAt());
    }

    @Test
    void 진행_중인_생성은_중복_선점하지_않고_오래되면_회복한다() {
        String projectId = readyProject("매뉴얼-선점").getId();
        String firstGenerationId = UUID.randomUUID().toString();
        String nextGenerationId = UUID.randomUUID().toString();

        assertThat(manuals.beginGeneration(projectId, "backoffice", "bo-detail", firstGenerationId,
                Instant.now().minusSeconds(900))).isOne();
        assertThat(manuals.beginGeneration(projectId, "backoffice", "bo-detail", nextGenerationId,
                Instant.EPOCH)).isZero();
        assertThat(manuals.beginGeneration(projectId, "backoffice", "bo-detail", nextGenerationId,
                Instant.now().plusSeconds(1))).isOne();

        UserManual recovered = manuals.selectOne(projectId, "backoffice", "bo-detail").orElseThrow();
        assertThat(recovered.generationId()).isEqualTo(nextGenerationId);
        assertThat(recovered.state()).isEqualTo(UserManualState.RUNNING);
    }

    @Test
    void 이전_생성의_늦은_완료와_실패는_새_시도를_덮지_못한다() {
        String projectId = readyProject("매뉴얼-역전-방지").getId();
        String oldGenerationId = UUID.randomUUID().toString();
        String currentGenerationId = UUID.randomUUID().toString();
        manuals.beginGeneration(projectId, "backoffice", "bo-detail", oldGenerationId,
                Instant.now().minusSeconds(900));
        manuals.beginGeneration(projectId, "backoffice", "bo-detail", currentGenerationId,
                Instant.now().plusSeconds(1));

        assertThat(manuals.saveDone(projectId, "backoffice", "bo-detail", oldGenerationId,
                "<p>옛 결과</p>", "old-source", "old-generator",
                capture("old-bundle", "old-sha"))).isZero();
        assertThat(manuals.markFailed(projectId, "backoffice", "bo-detail", oldGenerationId,
                "OLD_FAILURE")).isZero();
        assertThat(manuals.saveDone(projectId, "backoffice", "bo-detail", currentGenerationId,
                "<p>새 결과</p>", "new-source", "new-generator",
                capture("new-bundle", "new-sha"))).isOne();

        UserManual completed = manuals.selectOne(projectId, "backoffice", "bo-detail").orElseThrow();
        assertThat(completed.html()).isEqualTo("<p>새 결과</p>");
        assertThat(completed.sourceFingerprint()).isEqualTo("new-source");
        assertThat(completed.generatorVersion()).isEqualTo("new-generator");
        assertThat(completed.state()).isEqualTo(UserManualState.DONE);
        assertThat(manuals.selectCapture(projectId, "backoffice", "bo-detail").orElseThrow())
                .isEqualTo(capture("new-bundle", "new-sha"));
        UserManualArtifact artifact = manuals.selectArtifact(projectId, "backoffice", "bo-detail")
                .orElseThrow();
        assertThat(artifact.html()).isEqualTo("<p>새 결과</p>");
        assertThat(artifact.capture()).isEqualTo(capture("new-bundle", "new-sha"));
    }

    @Test
    void 재생성_중과_실패에도_마지막_정상_캡처를_보존한다() {
        String projectId = readyProject("매뉴얼-캡처-보존").getId();
        String completedGenerationId = UUID.randomUUID().toString();
        UserManualCapture completedCapture = capture("completed-bundle", "completed-sha");
        manuals.beginGeneration(projectId, "backoffice", "bo-detail", completedGenerationId,
                Instant.now().minusSeconds(900));
        manuals.saveDone(projectId, "backoffice", "bo-detail", completedGenerationId,
                "<p>정상본</p>", "source", "generator", completedCapture);

        String retryGenerationId = UUID.randomUUID().toString();
        assertThat(manuals.beginGeneration(projectId, "backoffice", "bo-detail", retryGenerationId,
                Instant.now().minusSeconds(900))).isOne();
        assertThat(manuals.selectCapture(projectId, "backoffice", "bo-detail"))
                .contains(completedCapture);

        manuals.markFailed(projectId, "backoffice", "bo-detail", retryGenerationId,
                "AI_EXECUTION_FAILED");
        assertThat(manuals.selectCapture(projectId, "backoffice", "bo-detail"))
                .contains(completedCapture);
    }

    private UserManualCapture capture(String bundlePath, String shaSeed) {
        String sha256 = (shaSeed + "0".repeat(64)).substring(0, 64)
                .replaceAll("[^0-9a-f]", "a");
        return new UserManualCapture(bundlePath, "screen.png", "대표 화면", 1440, 1800, sha256);
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
