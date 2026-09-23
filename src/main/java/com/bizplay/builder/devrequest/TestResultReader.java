package com.bizplay.builder.devrequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 개발이 채워 보낸 테스트 결과 md 를 <b>TC 번호별 한 줄</b>로 읽는다.
 *
 * <p>⭐ <b>md 통째로 담으면 화면이 통과/실패를 못 센다.</b> 그린존의 단위·통합테스트 화면이
 * 그 수를 읽으므로 줄 단위로 갈라 담는다.
 *
 * <p>⭐ <b>읽을 수 있는 까닭은 서식을 우리가 정해 보냈기 때문이다</b>({@link ExpectedBackDocument}
 * 가 쓰는 표). 설계가 「빈 양식으로 보내면 돌아오는 것이 기계가 못 읽는 모양이 된다」며
 * <b>TC 를 우리가 먼저 적기로</b> 한 자리다.
 *
 * <p>⚠ <b>안 채운 줄도 읽어 둔다.</b> 「안 왔다」와 「채우다 말았다」는 다른 것이고, 화면이 그 둘을
 * 갈라 보여야 한다.
 * ⛔ <b>모르는 판정 말을 통과로 세지 마라</b> — 지어내는 순간 계약서가 거짓이 된다. 모름으로 세고
 * 원문을 그대로 남긴다.
 *
 * <p>⛔ <b>DB 도 git 도 만지지 않는다</b> — 글자를 받아 줄로 바꾸는 순수한 자리다.
 */
public final class TestResultReader {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    /** ⚠ 「안 채웠다」와 「모르는 말을 적었다」가 둘 다 여기로 온다 — 원문은 {@code rawVerdict} 에 남는다. */
    public static final String UNKNOWN = "UNKNOWN";

    /** ⚠ 우리가 보낸 표의 칸 수. 이보다 적으면 개발이 서식을 고친 것이라 읽지 않는다. */
    private static final int COLUMNS = 9;

    private TestResultReader() {
    }

    public record Result(String kind, String tcId, String title, String actual,
                         String verdict, String rawVerdict, String evidence) {
    }

    public static List<Result> read(String markdown, String kind) {
        if (markdown == null || markdown.isBlank()) {
            // ⚠ 「없다」와 「못 읽었다」를 섞지 않는다 — 비었으면 빈 목록이다.
            return List.of();
        }
        List<Result> results = new ArrayList<>();
        for (String line : markdown.lines().toList()) {
            String row = line.strip();
            if (!row.startsWith("|")) {
                continue;   // 표가 아닌 글 — 개발이 메모를 적어 보낸다
            }
            String[] cells = cells(row);
            if (cells.length < COLUMNS || !cells[0].toUpperCase(Locale.ROOT).startsWith("TC-")) {
                continue;   // 머리줄·구분줄·서식이 다른 줄
            }
            String rawVerdict = blankToNull(cells[7]);
            results.add(new Result(kind, cells[0], blankToNull(cells[1]), blankToNull(cells[6]),
                    verdictOf(rawVerdict), rawVerdict, blankToNull(cells[8])));
        }
        return List.copyOf(results);
    }

    /** ⚠ 줄 양끝의 {@code |} 를 떼고 가른다. 칸 안의 {@code \\|} 는 개발이 이스케이프한 글자다. */
    private static String[] cells(String row) {
        String body = row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length());
        String[] cells = body.split("(?<!\\\\)\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].replace("\\|", "|").strip();
        }
        return cells;
    }

    private static String verdictOf(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (value.equals("통과") || value.equals(PASS) || value.equals("OK")) {
            return PASS;
        }
        if (value.equals("실패") || value.equals(FAIL) || value.equals("NG")) {
            return FAIL;
        }
        // ⛔ 모르는 말은 통과로 세지 않는다 — 원문을 남기고 모름으로 둔다.
        return UNKNOWN;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
