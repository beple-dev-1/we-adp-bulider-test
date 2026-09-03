package com.bizplay.builder.frd;

import com.bizplay.builder.config.BuilderProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** FRD 화면 대화에 한 번만 쓰는 참고 이미지를 비공개 임시 저장소에 보관한다. */
@Service
class FrdChatReferenceImageService {
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path root;

    FrdChatReferenceImageService(BuilderProperties properties) {
        this.root = properties.dataRoot().resolve("frd-chat-reference-images");
    }

    Path store(MultipartFile image) {
        if (image == null || image.isEmpty()) return null;
        if (image.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("참고 이미지는 10MB 이하로 올려 주세요.");
        }
        try {
            byte[] bytes = image.getBytes();
            String extension = extension(bytes);
            Files.createDirectories(root);
            Path stored = root.resolve(UUID.randomUUID() + "." + extension);
            Files.write(stored, bytes);
            return stored;
        } catch (IOException failure) {
            throw new IllegalStateException("참고 이미지를 저장하지 못했습니다. 다시 시도해 주세요.", failure);
        }
    }

    void delete(Path image) {
        if (image == null) return;
        try {
            Files.deleteIfExists(image);
        } catch (IOException ignored) {
            // 다음 서버 정리 때 제거할 수 있으므로 화면 작업 결과를 실패시키지 않는다.
        }
    }

    private static String extension(byte[] bytes) {
        if (bytes.length >= 8 && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && unsigned(bytes[4]) == 0x0d && unsigned(bytes[5]) == 0x0a
                && unsigned(bytes[6]) == 0x1a && unsigned(bytes[7]) == 0x0a) return "png";
        if (bytes.length >= 3 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff) return "jpg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        throw new IllegalArgumentException("참고 이미지는 PNG, JPEG, WebP 형식만 올릴 수 있습니다.");
    }

    private static int unsigned(byte value) { return value & 0xff; }
}
