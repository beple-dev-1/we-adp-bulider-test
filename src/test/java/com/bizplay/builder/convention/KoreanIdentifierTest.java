package com.bizplay.builder.convention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「기계가 부르는 이름은 영문」 규약을 빌드에서 지킨다 — 정본은 {@code docs/coding-conventions.md}.
 *
 * <p>2026-08-14 에 영문화 3단계가 끝나 한글 식별자가 0종이 됐다. 0인 동안 검사를 걸어야 다시 늘지 않는다.
 * 규약이 「필요해지면 그때 세운다」고 미뤄 뒀던 자리가 여기다.
 *
 * <p>스스로를 못 믿는 검사기는 초록이어도 쓸모가 없다. 그래서 아래 절반은 <b>심어 둔 위반을 실제로 잡는지</b>
 * 확인하는 자기 시험이다.
 */
class KoreanIdentifierTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path TEST = Path.of("src/test/java");

    private static final Pattern HANGUL = Pattern.compile("\\p{IsHangul}");

    /** 테스트 메서드 이름은 한글이 규칙이다(규약 「예외로 못 박은 것 셋」 ①) — 이름 자리만 도려낸다. */
    private static final Pattern TEST_METHOD_NAME = Pattern.compile("\\bvoid\\s+[\\p{IsHangul}\\w]+\\s*\\(");

    // ── 진짜 검사 ────────────────────────────────────────────────

    @Test
    void 제품_소스에_한글_식별자가_없다() {
        assertThat(scan(MAIN, false))
                .as("한글 식별자다. 기계가 부르는 이름은 영문으로 쓴다 — docs/coding-conventions.md")
                .isEmpty();
    }

    @Test
    void 테스트_소스에도_한글_식별자가_없다() {
        assertThat(scan(TEST, true))
                .as("한글 식별자다. @Test 메서드 이름 하나만 예외이고 변수·도구 메서드는 영문이다"
                        + " — docs/coding-conventions.md")
                .isEmpty();
    }

    /** 경로가 어긋나 아무 파일도 안 읽으면 위반 0건이 되어 조용히 초록이 된다. 그 실패 방식을 막는다. */
    @Test
    void 검사기가_소스를_실제로_읽는다() {
        assertThat(javaFiles(MAIN)).hasSizeGreaterThan(20);
        assertThat(javaFiles(TEST)).hasSizeGreaterThan(20);
    }

    // ── 자기 시험 — 심어 둔 위반을 잡는가 ──────────────────────────

    @Test
    void 주석과_문자열_안의_한글은_넘어간다() {
        String source = """
                class Sample {
                    // 이건 주석이다
                    private static final String LABEL = "과업요청서";
                    /** 여러 줄 주석도 넘어간다 */
                    void run() { }
                }
                """;
        assertThat(findViolations("Sample.java", source, false)).isEmpty();
    }

    @Test
    void 텍스트_블록_안의_한글은_넘어간다() {
        String source = "class Sample {\n"
                + "    String s = \"\"\"\n"
                + "            받은 문서를 등록한다\n"
                + "            \"\"\";\n"
                + "}\n";
        assertThat(findViolations("Sample.java", source, false)).isEmpty();
    }

    @Test
    void 변수_이름의_한글은_잡힌다() {
        String source = """
                class Sample {
                    void run() { int 개수 = 1; }
                }
                """;
        assertThat(findViolations("Sample.java", source, false))
                .singleElement().asString().contains("개수").contains("Sample.java:2");
    }

    @Test
    void 테스트_메서드_이름은_예외다() {
        String source = """
                class SampleTest {
                    @Test
                    void 무엇을_보장한다() { }
                }
                """;
        assertThat(findViolations("SampleTest.java", source, true)).isEmpty();
    }

    /** {@code CloneWorkerTest} 가 실제로 이 모양이다 — 바로 앞줄만 보던 첫 판이 여기서 오탐을 냈다. */
    @Test
    void 애너테이션이_여럿_붙어도_테스트_이름은_예외다() {
        String source = """
                class SampleTest {
                    @Test
                    @Transactional(propagation = Propagation.NOT_SUPPORTED)
                    void 무엇을_보장한다() { }
                }
                """;
        assertThat(findViolations("SampleTest.java", source, true)).isEmpty();
    }

    @Test
    void 테스트_메서드_이름_옆의_한글_변수는_예외가_아니다() {
        String source = """
                class SampleTest {
                    @Test
                    void 무엇을_보장한다() { int 개수 = 1; }
                }
                """;
        assertThat(findViolations("SampleTest.java", source, true))
                .singleElement().asString().contains("개수");
    }

    @Test
    void 애너테이션이_없는_도구_메서드_이름은_예외가_아니다() {
        String source = """
                class SampleTest {
                    private void 준비한다() { }
                }
                """;
        assertThat(findViolations("SampleTest.java", source, true))
                .singleElement().asString().contains("준비한다");
    }

    @Test
    void 제품_소스에서는_메서드_이름도_예외가_아니다() {
        String source = """
                class Sample {
                    @Test
                    void 무엇을_보장한다() { }
                }
                """;
        assertThat(findViolations("Sample.java", source, false))
                .singleElement().asString().contains("무엇을_보장한다");
    }

    // ── 속 ──────────────────────────────────────────────────────

    private static List<String> scan(Path root, boolean allowTestMethodNames) {
        return javaFiles(root).stream()
                .flatMap(file -> findViolations(label(file), read(file), allowTestMethodNames).stream())
                .toList();
    }

    private static List<String> findViolations(String label, String source, boolean allowTestMethodNames) {
        String[] lines = SourceScrubber.scrub(source).split("\n", -1);
        List<String> found = new ArrayList<>();
        boolean underTestAnnotation = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String inspected = allowTestMethodNames && underTestAnnotation ? blankMethodName(line) : line;
            if (HANGUL.matcher(inspected).find()) {
                found.add("%s:%d: %s".formatted(label, index + 1, line.strip()));
            }
            underTestAnnotation = stillUnderTestAnnotation(underTestAnnotation, line);
        }
        return found;
    }

    /**
     * {@code @Test} 와 메서드 선언 사이에 다른 애너테이션이 끼어드는 자리가 실제로 있다
     * ({@code CloneWorkerTest} 의 {@code @Transactional}). 그래서 바로 앞줄만 보면 안 된다.
     *
     * <p>선언이 끝나는 표시({@code { } ;})를 만날 때까지 상태를 물고 간다 — 애너테이션 줄은 그 셋으로 안 끝난다.
     */
    private static boolean stillUnderTestAnnotation(boolean current, String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return current;
        }
        if (stripped.endsWith("{") || stripped.endsWith("}") || stripped.endsWith(";")) {
            return false;
        }
        return current || stripped.contains("@Test") || stripped.contains("@ParameterizedTest");
    }

    private static String blankMethodName(String line) {
        Matcher matcher = TEST_METHOD_NAME.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        return new StringBuilder(line)
                .replace(matcher.start(), matcher.end(), " ".repeat(matcher.end() - matcher.start()))
                .toString();
    }

    private static List<Path> javaFiles(Path root) {
        assertThat(root).as("검사할 소스 폴더가 없다 — 작업 디렉터리가 저장소 뿌리가 맞나").exists();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String label(Path file) {
        return file.toString().replace('\\', '/');
    }
}
