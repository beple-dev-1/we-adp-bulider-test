package com.bizplay.builder.screendesign;

import com.bizplay.builder.config.BuilderProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 화면설계서 캡처 번들을 데이터 루트 안의 불변 경로에 게시하고 안전하게 읽는다. */
@Component
public class ScreenDesignBundleStore {

    private final Path root;

    public ScreenDesignBundleStore(BuilderProperties properties) {
        this.root = properties.dataRoot().toAbsolutePath().normalize().resolve("screen-designs");
    }

    public String publish(Path prepared, String projectId, String revisionId) throws IOException {
        String project = safePart(projectId);
        String revision = safePart(revisionId);
        Path target = root.resolve(project).resolve(revision).normalize();
        if (!target.startsWith(root) || Files.exists(target)) {
            throw new IOException("화면설계서 캡처를 저장할 경로를 만들 수 없습니다.");
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(prepared, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(prepared, target);
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    public Path file(String bundlePath, String fileName) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("화면설계서 캡처 파일 이름이 올바르지 않습니다.");
        }
        Path bundle = root.resolve(bundlePath == null ? "" : bundlePath).normalize();
        Path target = bundle.resolve(fileName).normalize();
        if (!bundle.startsWith(root) || !target.startsWith(bundle) || !Files.isRegularFile(target)) {
            throw new IOException("화면설계서 캡처 파일을 찾을 수 없습니다.");
        }
        Path realRoot = root.toRealPath();
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(realRoot)) {
            throw new IOException("화면설계서 캡처 파일 경로가 올바르지 않습니다.");
        }
        return realTarget;
    }

    public void deleteQuietly(String bundlePath) {
        if (bundlePath == null || bundlePath.isBlank()) return;
        Path target = root.resolve(bundlePath).normalize();
        if (target.startsWith(root) && !target.equals(root)) {
            FileSystemUtils.deleteRecursively(target.toFile());
        }
    }

    private static String safePart(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("화면설계서 저장 식별자가 올바르지 않습니다.");
        }
        return value;
    }
}
