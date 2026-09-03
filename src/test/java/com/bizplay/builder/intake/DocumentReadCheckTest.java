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

/**
 * 올린 파일에서 <b>글자가 실제로 나오나</b>를 잰다.
 *
 * <p>⛔ <b>「읽었나」로 판정하면 안 된다.</b> 2026-08-09 실측(이슈 {@code #7})에서 둘이 나왔다 —
 * PDF 는 이 기계에서 도구가 없어 거절하고, <b>한컴 문서는 에러도 없이 압축 바이트를 글자로 되돌려준다</b>
 * (fail-open). 그대로 두면 AI 가 쓰레기를 읽고 그럴듯한 요구사항을 지어낸다.
 *
 * <p>⚠ <b>합성 파일로 잰다.</b> 병주의 실제 문서를 프로브에 쓰지 않는다 — 회사 문서다.
 */
class DocumentReadCheckTest {

    @TempDir
    Path tmp;

    private final DocumentReadCheck check = new DocumentReadCheck(new DocumentTextExtractor());

    @Test
    void 한컴_문서는_압축_바이트라_글자가_안_나오므로_못읽는다로_찍는다() throws Exception {
        Path synthesized = fakeHwpx(tmp.resolve("a.hwpx"));

        var verdict = check.inspect(synthesized);

        assertThat(verdict.readable()).isFalse();
        assertThat(verdict.reason()).contains("한컴");
    }

    @Test
    void 그냥_텍스트는_읽힌다() throws Exception {
        Path minutes = Files.writeString(tmp.resolve("m.txt"), "상신할 때 임시저장이 됐으면 좋겠다");

        var verdict = check.inspect(minutes);

        assertThat(verdict.readable()).isTrue();
    }

    @Test
    void PDF_는_이_기계에_읽는_도구가_없으면_모르겠다가_아니라_못읽는다다() throws Exception {
        Path pdf = fakePdf(tmp.resolve("a.pdf"));

        var verdict = check.inspect(pdf);

        assertThat(verdict.readable()).isFalse();
        assertThat(verdict.reason()).contains("PDF");
    }

    /**
     * ⛔ <b>확장자를 믿지 마라.</b> 이름을 {@code .txt} 로 바꾼 한컴 파일이 온다.
     * 앞 바이트가 정본이다 — 이 테스트가 그 규칙을 잡는다.
     */
    @Test
    void 이름만_txt_인_한컴_파일도_앞_바이트를_보고_못읽는다로_찍는다() throws Exception {
        Path disguised = fakeHwpx(tmp.resolve("회의록.txt"));

        assertThat(check.inspect(disguised).readable()).isFalse();
    }

    /**
     * ⚠ {@code textRatio} 은 「그럴듯한 UTF-8 인가」다 — 치환문자와 제어문자를 세어 나눈다.
     * 확장자도 매직 바이트도 없는 그냥 이진 파일이 여기로 떨어진다.
     */
    @Test
    void 매직도_확장자도_없는_이진_파일은_글자_비율로_걸러낸다() throws Exception {
        byte[] noise = new byte[2048];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) (i % 7 == 0 ? 0x00 : 0xF8);
        }
        Path binary = Files.write(tmp.resolve("noise.bin"), noise);

        var verdict = check.inspect(binary);

        assertThat(verdict.readable()).isFalse();
        assertThat(verdict.reason()).contains("글자");
    }

    /** 빈 파일은 뽑을 글자가 0자다 — 「읽혔다」가 아니다. */
    @Test
    void 빈_파일은_읽힌다로_찍지_않는다() throws Exception {
        Path empty = Files.createFile(tmp.resolve("empty.txt"));

        assertThat(check.inspect(empty).readable()).isFalse();
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
