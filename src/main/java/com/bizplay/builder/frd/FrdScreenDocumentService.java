package com.bizplay.builder.frd;

import com.bizplay.builder.project.ProjectPaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** HTML과 같은 경로·이름으로 저장된 기존 화면 MD를 기능정의서 확인 창에 전달한다. */
@Service
public class FrdScreenDocumentService {

    private final ProjectPaths paths;

    public FrdScreenDocumentService(ProjectPaths paths) {
        this.paths = paths;
    }

    public ScreenDocument read(String projectId, String frdId, FrdScreen screen, String fallbackSystemCode) {
        String systemCode = screen.systemCode() == null || screen.systemCode().isBlank()
                ? fallbackSystemCode : screen.systemCode();
        requireSafePathPart(systemCode, "시스템");
        requireSafePathPart(screen.screenId(), "화면ID");

        Path worktree = paths.frdWorktree(projectId, frdId).toAbsolutePath().normalize();
        if (!Files.isDirectory(worktree)) {
            throw new IllegalStateException("FRD 작업공간이 준비되지 않아 기능정의서를 열 수 없습니다.");
        }
        Path core = worktree.resolve("core").normalize();
        Path document = core.resolve(systemCode).resolve("pages")
                .resolve(screen.screenId() + ".md").normalize();
        if (!document.startsWith(core)) {
            throw new IllegalArgumentException("기능정의서 경로가 올바르지 않습니다.");
        }
        if (!Files.isRegularFile(document)) {
            return new ScreenDocument(screen.screenId(), displayName(screen), "", false);
        }
        try {
            Path realCore = core.toRealPath();
            Path realDocument = document.toRealPath();
            if (!realDocument.startsWith(realCore)) {
                throw new IllegalArgumentException("기능정의서 경로가 작업공간을 벗어났습니다.");
            }
            return new ScreenDocument(screen.screenId(), displayName(screen),
                    Files.readString(realDocument, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            throw new IllegalStateException("기능정의서를 읽지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    private static void requireSafePathPart(String value, String label) {
        if (value == null || !value.matches("^[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException(label + "가 올바르지 않아 기능정의서를 열 수 없습니다.");
        }
    }

    private static String displayName(FrdScreen screen) {
        return screen.screenName() == null || screen.screenName().isBlank()
                ? screen.screenId() : screen.screenName();
    }

    public record ScreenDocument(String screenId, String screenName, String markdown, boolean exists) { }
}
