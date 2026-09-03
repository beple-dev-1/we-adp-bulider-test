package com.bizplay.builder.usermanual;

import com.bizplay.builder.config.BuilderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserManualCaptureStoreTest {

    @TempDir Path temporary;
    private UserManualCaptureStore store;

    @BeforeEach
    void 준비한다() {
        BuilderProperties properties = new BuilderProperties("admin", "password", "A".repeat(42) + "g=",
                temporary.resolve("data"), Duration.ofMinutes(10), 2, 10, Duration.ofMinutes(2));
        store = new UserManualCaptureStore(properties);
    }

    @Test
    void 준비된_캡처를_불변_경로에_게시하고_읽는다() throws Exception {
        Path prepared = prepared("첫 화면");

        String capturePath = store.publish(prepared, "0000001", "generation-1");

        assertThat(capturePath).isEqualTo("0000001/generation-1");
        assertThat(prepared).doesNotExist();
        assertThat(Files.readString(store.file(capturePath, "manual-preview.png"))).isEqualTo("첫 화면");
        assertThatThrownBy(() -> store.publish(prepared("다른 화면"), "0000001", "generation-1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("저장할 경로");
    }

    @Test
    void 저장_식별자와_파일_경로로_울타리_밖을_가리킬_수_없다() throws Exception {
        String capturePath = store.publish(prepared("화면"), "0000001", "generation-2");
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "보존");

        assertThatThrownBy(() -> store.publish(prepared("침범"), "../outside", "generation-3"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("식별자");
        assertThatThrownBy(() -> store.file(capturePath, "../outside.txt"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("파일 이름");
        assertThatThrownBy(() -> store.file("../../", "outside.txt"))
                .isInstanceOf(IOException.class);

        store.deleteQuietly("../../");
        assertThat(outside).exists();
    }

    @Test
    void 게시에_채택되지_않은_캡처만_조용히_지운다() throws Exception {
        String capturePath = store.publish(prepared("버릴 화면"), "0000001", "generation-4");
        Path file = store.file(capturePath, "manual-preview.png");

        store.deleteQuietly(capturePath);
        store.deleteQuietly(capturePath);

        assertThat(file).doesNotExist();
    }

    private Path prepared(String body) throws IOException {
        Path prepared = Files.createTempDirectory(temporary, "prepared-");
        Files.writeString(prepared.resolve("manual-preview.png"), body);
        return prepared;
    }
}
