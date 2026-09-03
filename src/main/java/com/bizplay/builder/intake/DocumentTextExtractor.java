package com.bizplay.builder.intake;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 올린 파일에서 <b>실제 글자를 뽑는다</b>.
 *
 * <p>{@link DocumentReadCheck} 와 짝이다 — 그쪽은 <b>「글자가 나오나」를 재고</b>, 이쪽은 <b>뽑는다.</b>
 * 둘로 가른 것이 아니라 <b>PDF 도구를 아는 자리를 하나로 모은 것</b>이다: 판정할 때도 뽑아 봐야 알기 때문에
 * 같은 기계가 양쪽에 필요했다. 판정기가 이 클래스를 물고 쓴다.
 *
 * <p>⛔ <b>못 뽑으면 빈 글자를 돌려주지 않고 던진다.</b> 이 저장소는 여기서 두 번 데었다 —
 * 한컴 문서가 압축 바이트를 글자로 되돌려줬고(예외 없음), 그림으로 스캔한 PDF 가 도구를 멀쩡히 돌리고
 * 0자를 냈다. <b>빈 글자를 성공으로 넘기면 AI 가 그것을 읽고 그럴듯한 요구사항을 지어낸다.</b>
 * 그래서 「모르면 실패」다.
 *
 * <p>⚠ <b>확장자를 믿지 않는다.</b> 이름을 {@code .txt} 로 바꾼 한컴 파일이 온다 — 앞 바이트가 정본이다.
 */
@Component
public class DocumentTextExtractor {

    /** 앞에서 이만큼만 떠서 종류를 가른다. 매직 바이트와 글자 비율을 보는 데 충분하다. */
    static final int HEAD_BYTES = 4096;

    /** 이 아래로 나오면 「글자가 안 나왔다」로 본다. 빈 파일도 여기 걸린다. */
    static final int MIN_TEXT_LENGTH = 1;

    /**
     * ⚠ <b>PDF 도구가 있는지는 한 번만 재서 들고 있는다.</b> 개발 기계에는 없지만
     * <b>운영 리눅스에는 깔면 된다</b>({@code apt-get install poppler-utils}) —
     * 확장자로 무조건 막으면 서버에 도구를 깔아도 문서가 영영 안 열린다.
     * 파일마다 프로세스를 띄우면 올릴 때마다 느려지므로 캐시한다. 서버에 새로 깔면 재시작 때 다시 잰다.
     */
    private volatile Boolean pdfToolPresent = null;

    /**
     * 파일에서 글자를 뽑는다.
     *
     * @throws IOException 뽑을 글자가 없거나 뽑을 수 없는 종류일 때. <b>빈 글자로 돌려주지 않는다.</b>
     */
    public String extract(Path file) throws IOException {
        byte[] head = readHead(file);

        if (isZip(head)) {
            // ⛔ 부르는 쪽이 틀렸다 — DocumentReadCheck 가 이미 «못 읽는다»로 찍었어야 한다.
            throw new IOException("압축 문서라 글자를 뽑을 수 없다 — AI 로 가는 길이 닫혔어야 한다");
        }
        if (isImage(head)) {
            // ⛔ 여기까지 오면 안 된다 — DocumentReadCheck 가 「멀티모달이 읽어야 한다」로 이미 갈랐어야 한다.
            //    ⚠ 그래도 던진다: PNG·JPEG 의 헤더에는 'PNG'·'IHDR' 같은 글자가 섞여 있어
            //      아래 평문 갈래로 흘려보내면 **0자가 아니라서 「글자가 나왔다」로 통과한다.**
            throw new IOException("그림 파일이라 서버가 글자를 뽑을 수 없다 — 멀티모달 AI 가 읽어야 한다");
        }
        if (isPdf(head)) {
            if (!hasPdfTool()) {
                throw new IOException("PDF 는 이 서버에 읽는 도구(poppler-utils)가 있어야 한다");
            }
            return demandText(extractPdfText(file),
                    "PDF 인데 글자가 안 나온다 — 그림으로 스캔한 문서이거나 깨진 파일이다");
        }
        return demandText(Files.readString(file, StandardCharsets.UTF_8),
                "파일에서 나온 글자가 없다");
    }

    /** ⛔ 0자를 성공으로 넘기지 않는 자리. 이 클래스의 존재 이유다. */
    private String demandText(String extracted, String complaint) throws IOException {
        if (textLength(extracted) < MIN_TEXT_LENGTH) {
            throw new IOException(complaint);
        }
        return extracted;
    }

    byte[] readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(HEAD_BYTES);
        }
    }

    /** {@code PK\3\4} — zip 컨테이너. hwpx·docx·xlsx 가 전부 이것이다. */
    boolean isZip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    /** {@code %PDF-} */
    boolean isPdf(byte[] head) {
        return head.length >= 5
                && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-';
    }

    /**
     * 그림 파일인가. <b>서버는 여기서 글자를 못 뽑는다</b> — 읽으려면 멀티모달 AI 가 필요하다
     * ({@link DocumentUnderstandingClient}).
     *
     * <p>⚠ <b>확장자가 아니라 앞 바이트로 가른다</b> — 이 클래스의 다른 판정과 같은 까닭이다.
     * ⛔ 여기에 없는 종류를 「아마 그림일 것」으로 넓히지 마라: 못 알아본 것은
     * 「글자가 아닌 바이트가 많다」로 떨어져 <b>원본은 그대로 보존된 채</b> 다음 걸음만 닫힌다.
     */
    boolean isImage(byte[] head) {
        return isPng(head) || isJpeg(head) || isGif(head) || isBmp(head) || isTiff(head) || isWebp(head);
    }

    /** 그림의 미디어 타입. ⚠ 멀티모달에 넘길 때 필요하다 — 못 알아보면 {@code null} 이다. */
    String imageMediaType(byte[] head) {
        if (isPng(head)) {
            return "image/png";
        }
        if (isJpeg(head)) {
            return "image/jpeg";
        }
        if (isGif(head)) {
            return "image/gif";
        }
        if (isBmp(head)) {
            return "image/bmp";
        }
        if (isTiff(head)) {
            return "image/tiff";
        }
        if (isWebp(head)) {
            return "image/webp";
        }
        return null;
    }

    private boolean isPng(byte[] head) {
        return startsWith(head, new int[] {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    }

    private boolean isJpeg(byte[] head) {
        return startsWith(head, new int[] {0xFF, 0xD8, 0xFF});
    }

    private boolean isGif(byte[] head) {
        return startsWith(head, new int[] {'G', 'I', 'F', '8'});
    }

    private boolean isBmp(byte[] head) {
        return startsWith(head, new int[] {'B', 'M'});
    }

    /** 리틀엔디언({@code II*\0})과 빅엔디언({@code MM\0*}) 둘 다 있다. */
    private boolean isTiff(byte[] head) {
        return startsWith(head, new int[] {'I', 'I', 0x2A, 0x00})
                || startsWith(head, new int[] {'M', 'M', 0x00, 0x2A});
    }

    /** {@code RIFF....WEBP} — 가운데 넷은 길이라 건너뛴다. */
    private boolean isWebp(byte[] head) {
        return startsWith(head, new int[] {'R', 'I', 'F', 'F'})
                && head.length >= 12
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
    }

    /** ⚠ {@code byte} 는 부호가 있다 — {@code 0x89} 같은 값을 그대로 비교하면 늘 거짓이다. */
    private static boolean startsWith(byte[] head, int[] magic) {
        if (head.length < magic.length) {
            return false;
        }
        for (int at = 0; at < magic.length; at++) {
            if ((head[at] & 0xFF) != magic[at]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 「글자로 볼 수 있는 것」의 수. 치환문자({@code U+FFFD})와 제어문자와 공백은 빼고 센다.
     *
     * <p>⚠ 디코딩이 성공했다는 것만으로는 모자란다 — <b>이진 바이트도 치환문자로 바뀌며 「성공」한다.</b>
     */
    int textLength(String text) {
        return (int) text.codePoints()
                .filter(DocumentTextExtractor::isTextChar)
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .count();
    }

    /**
     * 그럴듯한 UTF-8 인지의 비율. {@link DocumentReadCheck} 가 판정에 쓴다.
     *
     * <p>⚠ 앞부분만 떠서 재므로 <b>글자 하나가 경계에서 잘릴 수 있다.</b> 그래서 디코딩 실패를
     * 예외로 받지 않고 치환문자로 흘려보낸 뒤 비율로 판정한다 — 잘린 한 글자가 결론을 못 뒤집는다.
     */
    double textRatio(byte[] head) {
        String text = decodeLeniently(head);
        if (text.isEmpty()) {
            return 0;
        }
        long intact = text.codePoints().filter(DocumentTextExtractor::isTextChar).count();
        return (double) intact / text.codePointCount(0, text.length());
    }

    private String decodeLeniently(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException neverHappens) {
            // REPLACE 라 여기 안 온다. 와도 「글자가 안 나왔다」가 맞는 답이다.
            return "";
        }
    }

    private static boolean isTextChar(int codePoint) {
        if (codePoint == 0xFFFD) {
            return false;   // 치환문자 — 디코딩이 실패한 자리다
        }
        if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
            return true;    // 글에 정상적으로 있는 제어문자 셋
        }
        return !Character.isISOControl(codePoint);
    }

    boolean hasPdfTool() {
        Boolean cached = pdfToolPresent;
        if (cached != null) {
            return cached;
        }
        boolean present = probePdfTool();
        pdfToolPresent = present;
        return present;
    }

    private boolean probePdfTool() {
        try {
            Process probe = new ProcessBuilder("pdftotext", "-v")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor() == 0;
        } catch (IOException | InterruptedException absent) {
            if (absent instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * ⛔ <b>도구가 있을 때만 부른다.</b> 없으면 부르는 쪽에서 이미 끝난다.
     * 뽑은 글이 0자면 그림으로 스캔한 PDF 다 — 그 판정은 {@link #demandText} 가 한다.
     */
    private String extractPdfText(Path file) throws IOException {
        try {
            Process tool = new ProcessBuilder("pdftotext", file.toString(), "-")
                    .redirectErrorStream(true)
                    .start();
            String extracted = new String(tool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return tool.waitFor() == 0 ? extracted : "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
