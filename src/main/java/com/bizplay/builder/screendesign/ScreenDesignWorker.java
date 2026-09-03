package com.bizplay.builder.screendesign;

import com.bizplay.builder.config.BuilderProperties;
import com.bizplay.builder.screendesign.ScreenCaptureRunner.CaptureException;
import com.bizplay.builder.screendesign.ScreenCaptureRunner.CaptureResult;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.MaterialException;
import com.bizplay.builder.screendesign.ScreenDesignMaterialService.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 상세 진입에서 필요한 화면설계서만 비동기로 만들고 정상본을 개정판으로 승격한다. */
@Component
public class ScreenDesignWorker {

    private static final Logger log = LoggerFactory.getLogger(ScreenDesignWorker.class);
    public static final String GENERATOR_VERSION = "screen-design-2";
    public static final String SCHEMA_VERSION = "screen-design-schema-1";
    private static final Duration STALE_MARGIN = Duration.ofMinutes(5);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(2);

    private final ScreenDesignMapper designs;
    private final ScreenDesignStorage storage;
    private final ScreenDesignMaterialService materials;
    private final ScreenCaptureRunner captures;
    private final ScreenDesignBundleStore bundles;
    private final ScreenDesignRenderer renderer;
    private final BuilderProperties properties;
    private final TaskExecutor executor;
    private final ObjectMapper json;

    public ScreenDesignWorker(ScreenDesignMapper designs, ScreenDesignStorage storage,
                              ScreenDesignMaterialService materials, ScreenCaptureRunner captures,
                              ScreenDesignBundleStore bundles, ScreenDesignRenderer renderer,
                              BuilderProperties properties,
                              @Qualifier("aiExecutor") TaskExecutor executor, ObjectMapper json) {
        this.designs = designs;
        this.storage = storage;
        this.materials = materials;
        this.captures = captures;
        this.bundles = bundles;
        this.renderer = renderer;
        this.properties = properties;
        this.executor = executor;
        this.json = json;
    }

    /** 최신 정상본이 없을 때만 생성 시도를 선점한다. 브라우저 설치는 이 시점에도 수행하지 않는다. */
    public void requestIfNeeded(String projectId, String systemCode, String screenId) {
        Snapshot snapshot;
        try {
            snapshot = materials.snapshot(projectId, systemCode, screenId);
        } catch (MaterialException missing) {
            recordMaterialFailure(projectId, systemCode, screenId, missing.reason());
            return;
        }
        ScreenDesignCurrent current = designs.selectCurrent(projectId, systemCode, screenId).orElse(null);
        ScreenDesignRevision revision = current == null || !current.hasRevision() ? null
                : designs.selectRevision(current.currentRevisionId()).orElse(null);
        if (isCurrent(snapshot, revision)) return;
        String generationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        int begun = designs.beginGeneration(projectId, systemCode, screenId, generationId,
                snapshot.fingerprint(), GENERATOR_VERSION, SCHEMA_VERSION,
                now.minus(properties.aiRunTimeout()).minus(STALE_MARGIN), now);
        if (begun != 1) return;
        try {
            executor.execute(() -> generate(projectId, systemCode, screenId, generationId, snapshot));
        } catch (TaskRejectedException rejected) {
            fail(projectId, systemCode, screenId, generationId, "QUEUE_UNAVAILABLE");
        }
    }

    private void recordMaterialFailure(String projectId, String systemCode, String screenId, String reason) {
        String generationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (designs.beginGeneration(projectId, systemCode, screenId, generationId, "0".repeat(64),
                GENERATOR_VERSION, SCHEMA_VERSION,
                now.minus(properties.aiRunTimeout()).minus(STALE_MARGIN), now) == 1) {
            fail(projectId, systemCode, screenId, generationId, reason);
        }
    }

    private void generate(String projectId, String systemCode, String screenId,
                          String generationId, Snapshot snapshot) {
        Path runRoot = properties.dataRoot().toAbsolutePath().normalize().resolve("screen-design-runs");
        Path runDir = runRoot.resolve(generationId).normalize();
        if (!runDir.startsWith(runRoot)) {
            fail(projectId, systemCode, screenId, generationId, "INVALID_RUN_PATH");
            return;
        }
        String published = null;
        try {
            Path prepared = runDir.resolve("bundle");
            Files.createDirectories(prepared);
            CaptureResult captured = captures.capture(snapshot, prepared);
            Snapshot current = materials.snapshot(projectId, systemCode, screenId);
            if (!snapshot.fingerprint().equals(current.fingerprint())) {
                fail(projectId, systemCode, screenId, generationId, "SOURCE_CHANGED");
                return;
            }
            ScreenDesignContent content = ScreenDesignContentAssembler.assemble(snapshot, captured.captures());
            String contentJson = json.writeValueAsString(content);
            String revisionId = UUID.randomUUID().toString();
            published = bundles.publish(prepared, projectId, revisionId);
            boolean saved = storage.save(projectId, systemCode, screenId, generationId, revisionId,
                    snapshot.fingerprint(), GENERATOR_VERSION, SCHEMA_VERSION, contentJson,
                    renderer.renderBody(content), published, captured.manifestJson());
            if (!saved) {
                bundles.deleteQuietly(published);
                published = null;
            }
        } catch (CaptureException unavailable) {
            fail(projectId, systemCode, screenId, generationId, unavailable.reason());
        } catch (MaterialException changed) {
            fail(projectId, systemCode, screenId, generationId, "SOURCE_CHANGED");
        } catch (JsonProcessingException invalid) {
            fail(projectId, systemCode, screenId, generationId, "CONTENT_SERIALIZATION_FAILED");
        } catch (IOException trouble) {
            log.warn("화면설계서 생성 중 입출력 오류 projectId={} screenId={}", projectId, screenId, trouble);
            if (published != null) bundles.deleteQuietly(published);
            fail(projectId, systemCode, screenId, generationId, "INPUT_OUTPUT_FAILED");
        } catch (RuntimeException unexpected) {
            log.warn("화면설계서 생성기가 예상하지 못하게 끝났다 projectId={} screenId={}",
                    projectId, screenId, unexpected);
            if (published != null) bundles.deleteQuietly(published);
            fail(projectId, systemCode, screenId, generationId, "UNEXPECTED");
        } finally {
            FileSystemUtils.deleteRecursively(runDir.toFile());
        }
    }

    public boolean isCurrent(Snapshot snapshot, ScreenDesignRevision revision) {
        return revision != null && snapshot.fingerprint().equals(revision.sourceFingerprint())
                && GENERATOR_VERSION.equals(revision.generatorVersion())
                && SCHEMA_VERSION.equals(revision.schemaVersion());
    }

    private void fail(String projectId, String systemCode, String screenId,
                      String generationId, String reason) {
        designs.markFailed(projectId, systemCode, screenId, generationId, reason,
                Instant.now().plus(RETRY_DELAY));
    }

}
