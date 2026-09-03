package com.bizplay.builder.intake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 올린 파일에서 <b>실제 글자를 뽑는다</b>. {@link DocumentReadCheck} 가 「나오나」를 재는 자리라면
 * 여기는 「뽑는」 자리다 — PDF 도구를 아는 곳이 하나여야 해서 그 기계를 이쪽이 진다.
 *
 * <p>⛔ <b>못 뽑았을 때 빈 글자를 돌려주지 않는다.</b> 그것이 이 저장소가 두 번 데인 fail-open 이다 —
 * 한컴 문서가 압축 바이트를 글자로 되돌려줬고, 그림 PDF 가 도구를 멀쩡히 돌리고 0자를 냈다.
 * <b>빈 글자를 성공으로 넘기면 AI 가 그것을 읽고 그럴듯한 요구사항을 지어낸다.</b>
 *
 * <p>⚠ <b>합성 파일로 잰다</b> — 병주의 실제 문서를 프로브에 쓰지 않는다(회사 문서다).
 */
class DocumentTextExtractorTest {

    @TempDir
    Path tmp;

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void 평문은_글자가_그대로_나온다() throws Exception {
        Path minutes = Files.writeString(tmp.resolve("m.txt"), "상신할 때 임시저장이 됐으면 좋겠다");

        assertThat(extractor.extract(minutes)).contains("임시저장이 됐으면 좋겠다");
    }

    /**
     * ⛔ <b>이 자리에 압축 문서가 오면 부르는 쪽이 틀린 것이다.</b> {@link DocumentReadCheck} 가
     * 이미 «못 읽는다»로 찍어 AI 로 가는 길을 닫았어야 한다. 조용히 쓰레기를 넘기지 않고 터뜨린다.
     */
    @Test
    void 압축_문서가_여기까지_오면_조용히_넘기지_않고_거절한다() throws Exception {
        Path disguised = fakeHwpx(tmp.resolve("회의록.txt"));

        assertThatThrownBy(() -> extractor.extract(disguised))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("압축");
    }

    /**
     * 도구가 없는 기계에서는 「도구가 없다」로, 있는 기계에서는 「글자가 안 나온다」로 거절한다.
     * <b>어느 쪽이든 빈 글자를 성공으로 넘기지 않는다</b> — 그래서 이 테스트는 기계를 안 탄다.
     */
    @Test
    void 글자가_없는_PDF_는_도구가_있든_없든_빈_글자를_성공으로_넘기지_않는다() throws Exception {
        Path pdf = fakePdf(tmp.resolve("a.pdf"));

        assertThatThrownBy(() -> extractor.extract(pdf)).isInstanceOf(IOException.class);
    }

    /** 빈 파일에서 뽑을 것은 0자다 — 성공이 아니다. */
    @Test
    void 빈_파일은_성공으로_넘기지_않는다() throws Exception {
        Path empty = Files.createFile(tmp.resolve("empty.txt"));

        assertThatThrownBy(() -> extractor.extract(empty)).isInstanceOf(IOException.class);
    }

    /** zip 컨테이너 + {@code mimetype} + 안쪽 xml 한 장이면 실물과 같은 답이 나온다(2026-08-09 실측). */
    private Path fakeHwpx(Path seat) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(seat))) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/hwp+zip".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write("<hp:p>임시저장이 됐으면 좋겠다</hp:p>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return seat;
    }

    private Path fakePdf(Path seat) throws IOException {
        return Files.write(seat, "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII));
    }
}
