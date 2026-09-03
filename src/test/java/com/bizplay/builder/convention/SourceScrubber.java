package com.bizplay.builder.convention;

/**
 * 자바 소스에서 주석과 문자열 리터럴을 걷어낸다.
 *
 * <p>규약이 한글을 허용하는 자리(주석·예외 메시지·enum 표시 이름·테스트 데이터)는 전부 이 둘 안에 있다.
 * 그러므로 걷어낸 뒤에도 남은 한글은 곧 식별자다.
 *
 * <p>지운 자리는 공백으로 채우고 줄바꿈은 그대로 둔다 — 위반을 알릴 때 줄 번호가 어긋나면 안 된다.
 */
final class SourceScrubber {

    private SourceScrubber() {
    }

    static String scrub(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = blankUntilNewline(source, out, i);
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = blankBlockComment(source, out, i);
            } else if (source.startsWith("\"\"\"", i)) {
                i = blankTextBlock(source, out, i);
            } else if (c == '"' || c == '\'') {
                i = blankQuoted(source, out, i, c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int blankUntilNewline(String source, StringBuilder out, int from) {
        int i = from;
        while (i < source.length() && source.charAt(i) != '\n') {
            out.append(' ');
            i++;
        }
        return i;
    }

    private static int blankBlockComment(String source, StringBuilder out, int from) {
        out.append("  ");
        int i = from + 2;
        while (i < source.length()) {
            if (source.startsWith("*/", i)) {
                out.append("  ");
                return i + 2;
            }
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            i++;
        }
        return i;
    }

    private static int blankTextBlock(String source, StringBuilder out, int from) {
        out.append("   ");
        int i = from + 3;
        while (i < source.length()) {
            if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                out.append("  ");
                i += 2;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                out.append("   ");
                return i + 3;
            }
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            i++;
        }
        return i;
    }

    /** 닫히지 않은 채 줄이 끝나면 거기서 그만둔다 — 한 줄의 오타가 파일 나머지를 통째로 못 보게 만들면 안 된다. */
    private static int blankQuoted(String source, StringBuilder out, int from, char quote) {
        out.append(' ');
        int i = from + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (c == '\\' && i + 1 < source.length()) {
                out.append("  ");
                i += 2;
                continue;
            }
            out.append(' ');
            i++;
            if (c == quote) {
                return i;
            }
        }
        return i;
    }
}
