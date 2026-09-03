package com.bizplay.builder.intake;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 올린 파일을 <b>어떻게 읽을 것인가</b>를 가른다.
 *
 * <p>⛔ <b>「읽었나」로 판정하지 않는다. 「글자가 실제로 나왔나」로 판정한다.</b>
 * 2026-08-09 실측(이슈 {@code #7})이 근거다 — <b>한컴 문서는 에러도 없이 압축 바이트를 글자로
 * 되돌려준다</b>. 그것이 무서운 쪽이다: 예외가 안 나므로 <b>AI 가 쓰레기를 읽고 그럴듯한
 * 요구사항을 지어내도 아무도 못 알아챈다.</b>
 *
 * <p><b>2026-08-15 에 판정이 둘에서 셋이 됐다.</b> 그전에는 「읽힌다 / 못 읽는다」였고
 * 못 읽으면 그것으로 끝이었다. 지금은 <b>못 읽는 것 중에 「사람 눈으로는 읽히는 것」</b>을
 * 따로 뽑아 멀티모달 AI 에게 넘긴다 — 스캔 PDF 와 그림 파일이 그것이다.
 * ⛔ 한컴·오피스 압축 문서는 여기 <b>안</b> 들어간다: 멀티모달도 zip 안의 XML 을 못 읽는다.
 *
 * <p>⛔ <b>이 판정은 올리기를 막지 않는다.</b> 받은 문서 원본은 그대로 보존하기로 정해져 있다
 * (→ {@code intake}). 막는 것은 <b>다음 걸음</b>이다.
 *
 * <p>⚠ <b>확장자를 믿지 않는다.</b> 이름을 {@code .txt} 로 바꾼 한컴 파일이 온다 — 앞 바이트가 정본이다.
 *
 * <p>⚠ 뽑는 기계는 {@link DocumentTextExtractor} 에 있다. 판정하려면 어차피 뽑아 봐야 해서
 * 같은 코드가 양쪽에 필요했다 — <b>PDF 도구를 아는 자리를 둘로 두면 갈린다.</b>
 */
@Component
public class DocumentReadCheck {

    /**
     * 이 파일을 어떻게 읽나.
     *
     * <p>⚠ <b>{@link #NEEDS_UNDERSTANDING} 은 실패가 아니다</b> — 「서버로는 못 읽으니
     * 멀티모달에게 넘긴다」는 뜻이다. 화면에서 오류로 칠하지 마라.
     */
    public enum Readability {
        /** 서버가 글자를 뽑을 수 있다. AI 를 안 부른다. */
        READABLE,
        /** 서버로는 글자가 안 나오지만 <b>사람 눈에는 보이는</b> 문서다 — 멀티모달이 읽는다. */
        NEEDS_UNDERSTANDING,
        /** 어느 쪽으로도 못 읽는다. 원본은 보존하되 다음 걸음이 닫힌다. */
        UNREADABLE
    }

    /**
     * @param value  셋 중 하나
     * @param reason 사람이 읽는 한 줄. 화면이 그대로 보여준다
     */
    public record ReadVerdict(Readability value, String reason) {

        /** ⚠ 이 이름을 바꾸면 화면 조각과 테스트도 같이 고쳐야 한다. */
        public boolean readable() {
            return value == Readability.READABLE;
        }

        public boolean needsUnderstanding() {
            return value == Readability.NEEDS_UNDERSTANDING;
        }
    }

    /** 그럴듯한 UTF-8 이라고 볼 하한. 이보다 낮으면 읽을 수 있는 문서가 아니다. */
    private static final double MIN_TEXT_RATIO = 0.9;

    private final DocumentTextExtractor extractor;

    public DocumentReadCheck(DocumentTextExtractor extractor) {
        this.extractor = extractor;
    }

    public ReadVerdict inspect(Path file) throws IOException {
        byte[] head = extractor.readHead(file);

        if (head.length == 0) {
            return new ReadVerdict(Readability.UNREADABLE, "빈 파일이라 읽을 글자가 없다");
        }
        if (extractor.isZip(head)) {
            // ⛔ 멀티모달로 넘기지 마라 — 저쪽도 zip 안의 XML 을 못 읽는다. 변환기가 먼저다.
            return new ReadVerdict(Readability.UNREADABLE,
                    "한컴·오피스 압축 문서라 글자가 그대로 안 나온다. 변환기를 먼저 붙여야 한다");
        }
        if (extractor.isImage(head)) {
            return new ReadVerdict(Readability.NEEDS_UNDERSTANDING,
                    "그림 파일이라 내용 분석이 필요하다");
        }
        if (extractor.isPdf(head)) {
            // ⛔ 도구가 있다는 것은 「읽었다」이지 「글자가 나왔다」가 아니다.
            //    그림으로 스캔한 PDF 는 도구가 멀쩡히 돌고 글자가 0자로 나온다.
            //    ⚠ 뽑기가 「0자면 던진다」를 이미 지키므로 그 판정을 여기서 되풀이하지 않는다.
            //    ⚠ 도구가 아예 없는 서버도 이 갈래다 — 멀티모달이 그 자리를 메운다.
            try {
                extractor.extract(file);
                return new ReadVerdict(Readability.READABLE, "PDF 에서 글자가 나왔다");
            } catch (IOException noText) {
                return new ReadVerdict(Readability.NEEDS_UNDERSTANDING,
                        "PDF 에서 글자가 안 나와 내용 분석이 필요하다");
            }
        }
        if (extractor.textRatio(head) < MIN_TEXT_RATIO) {
            return new ReadVerdict(Readability.UNREADABLE,
                    "글자가 아닌 바이트가 많다 — 읽을 수 있는 문서가 아니다");
        }
        return new ReadVerdict(Readability.READABLE, "글자로 읽힌다");
    }
}
