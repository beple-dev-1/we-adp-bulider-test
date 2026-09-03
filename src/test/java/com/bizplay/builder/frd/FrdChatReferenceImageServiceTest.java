package com.bizplay.builder.frd;

import com.bizplay.builder.config.BuilderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrdChatReferenceImageServiceTest {

    @TempDir Path temp;

    @Test
    void PNG_참고_이미지를_임시_저장하고_삭제한다() {
        FrdChatReferenceImageService service = service();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1};

        Path stored = service.store(new MockMultipartFile("referenceImage", "화면.png", "image/png", png));

        assertThat(stored).exists().hasExtension("png");
        service.delete(stored);
        assertThat(stored).doesNotExist();
    }

    @Test
    void 실제_내용이_이미지가_아니면_거부한다() {
        FrdChatReferenceImageService service = service();

        assertThatThrownBy(() -> service.store(new MockMultipartFile(
                "referenceImage", "화면.png", "image/png", "not-image".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PNG, JPEG, WebP");
        assertThat(temp.resolve("frd-chat-reference-images")).doesNotExist();
    }

    private FrdChatReferenceImageService service() {
        BuilderProperties properties = new BuilderProperties("admin", "password",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", temp,
                Duration.ofMinutes(1), 1, 0, Duration.ofSeconds(10));
        return new FrdChatReferenceImageService(properties);
    }
}
