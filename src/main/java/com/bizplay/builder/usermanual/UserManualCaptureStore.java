package com.bizplay.builder.usermanual;

import com.bizplay.builder.config.BuilderProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 사용자 매뉴얼 캡처를 데이터 루트 아래의 불변 디렉터리에 게시한다. */
@Component
public class UserManualCaptureStore {

    private final Path root;

    public UserManualCaptureStore(BuilderProperties properties) {
        this.root = properties.dataRoot().toAbsolutePath().normalize().resolve("user-manual-captures");
    }

    /** 준비가 끝난 캡처 디렉터리를 한 세대의 불변 경로로 옮긴다. */
    public String publish(Path prepared, String projectId, String generationId) throws IOException {
        String project = safePart(projectId);
        String generation = safePart(generationId);
        Path source = prepared.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("사용자 매뉴얼 캡처 준비 경로를 찾을 수 없습니다.");
        }
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        Path projectDirectory = root.resolve(project).normalize();
        Files.createDirectories(projectDirectory);
        Path realProject = projectDirectory.toRealPath();
        if (!realProject.startsWith(realRoot)) {
            throw new IOException("사용자 매뉴얼 캡처를 저장할 경로가 올바르지 않습니다.");
        }
        Path target = realProject.resolve(generation).normalize();
        if (!target.startsWith(realProject) || Files.exists(target)) {
            throw new IOException("사용자 매뉴얼 캡처를 저장할 경로를 만들 수 없습니다.");
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
        return realRoot.relativize(target).toString().replace('\\', '/');
    }

    /** 게시된 캡처 안의 안전한 파일 하나를 연다. */
    public Path file(String capturePath, String fileName) throws IOException {
        String checkedPath = safeCapturePath(capturePath);
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("사용자 매뉴얼 캡처 파일 이름이 올바르지 않습니다.");
        }
        Path bundle = root.resolve(checkedPath).normalize();
        Path target = bundle.resolve(fileName).normalize();
        if (!bundle.startsWith(root) || bundle.equals(root) || !target.startsWith(bundle)
                || !Files.isRegularFile(target)) {
            throw new IOException("사용자 매뉴얼 캡처 파일을 찾을 수 없습니다.");
        }
        Path realRoot = root.toRealPath();
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(realRoot)) {
            throw new IOException("사용자 매뉴얼 캡처 파일 경로가 올바르지 않습니다.");
        }
        return realTarget;
    }

    /** 아직 정상본에서 가리키지 않는 캡처 디렉터리를 조용히 지운다. */
    public void deleteQuietly(String capturePath) {
        if (capturePath == null || !capturePath.matches(
                "[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) return;
        Path target = root.resolve(capturePath).normalize();
        if (target.startsWith(root) && !target.equals(root)) {
            FileSystemUtils.deleteRecursively(target.toFile());
        }
    }

    private static String safePart(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("사용자 매뉴얼 캡처 저장 식별자가 올바르지 않습니다.");
        }
        return value;
    }

    private static String safeCapturePath(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) {
            throw new IOException("사용자 매뉴얼 캡처 저장 경로가 올바르지 않습니다.");
        }
        return value;
    }
}
