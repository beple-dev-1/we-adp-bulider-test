package com.bizplay.builder.devrequest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 꾸러미 한 채를 zip 한 덩어리로 묶는다.
 *
 * <p>⭐ <b>폴더 구조를 그대로 담는다.</b> 개발이 풀면 {@code screens/<시스템>/<화면ID>/to-be.html} 의
 * {@code ../assets/…} 가 그 자리에서 맞는다 — 목업이 혼자 서는 값이 zip 을 지나서도 산다.
 *
 * <p>⭐ <b>꾸러미에 한글 이름이 하나도 없다 (2026-08-25 병주 지시).</b> 종전에는
 * {@code 개발요청서.md}·{@code 변경내용.md} 가 한글이어서 {@code java.util.zip} 의 UTF-8 이름 + 언어
 * 인코딩 비트에 기댔고, <b>옛 도구에서 깨질 수 있다</b>를 알면서 받아들이고 있었다. 이름을
 * {@code dev-request.md}·{@code changes.md} 로 바꿔 <b>그 위험 자체를 없앴다.</b>
 * ⛔ <b>한글 이름을 되살리지 마라</b> — 폴더도 파일도 영문이라 이제 인코딩에 기대는 자리가 없다.
 */
@Component
public class DevRequestPackageZipper {

    public byte[] zip(DevRequestPackage built) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        write(built, out);
        return out.toByteArray();
    }

    /** ZIP을 임시 파일에 완성한 뒤 원본 자리로 옮겨 불완전한 파일이 다운로드되지 않게 한다. */
    public DevRequestPackage store(DevRequestPackage built, Path archive) {
        Path target = archive.toAbsolutePath().normalize();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream sink = Files.newOutputStream(temporary)) {
                write(built, sink);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return built.withArchive(target);
        } catch (IOException failed) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 원래 저장 실패를 보고한다.
            }
            throw new UncheckedIOException("개발요청서 ZIP을 저장하지 못했습니다.", failed);
        }
    }

    /** ⚠ 항목 순서를 <b>경로로</b> 고정한다 — 같은 꾸러미면 같은 zip 이어야 지문과 대조가 된다. */
    void write(DevRequestPackage built, OutputStream sink) throws IOException {
        List<DevRequestPackage.Entry> entries = built.entries().stream()
                .sorted(java.util.Comparator.comparing(DevRequestPackage.Entry::path))
                .toList();
        try (ZipOutputStream zip = new ZipOutputStream(sink, java.nio.charset.StandardCharsets.UTF_8)) {
            for (DevRequestPackage.Entry entry : entries) {
                Path file = built.root().resolve(entry.path());
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                ZipEntry item = new ZipEntry(entry.path());
                // ⛔ 시각을 넣지 않는다 — 같은 꾸러미가 돌릴 때마다 다른 zip 이 되면 대조가 안 된다.
                item.setTime(0L);
                zip.putNextEntry(item);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }
}
