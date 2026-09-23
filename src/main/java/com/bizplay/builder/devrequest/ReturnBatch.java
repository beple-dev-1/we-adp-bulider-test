package com.bizplay.builder.devrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 개발이 돌려보낸 배치가 <b>계약에 맞나</b>를 판정한다 — 받는 자리의 문 지킴이.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md}
 * 「⛔ ② 는 파일 하나씩 받지 않는다 — 배치로 받는다」.
 *
 * <p>⭐ <b>배치가 다 차고 전부 규격을 지나야 놓는다.</b> 하나라도 떨어지면 <b>통째로 거절하고
 * 아무것도 안 놓는다</b>. 파일 하나씩 즉시 반영하면 세 가지가 동시에 깨진다 —
 * 「누락」과 「안 바뀜」을 못 가르고, 기본 브랜치가 시점 혼합물이 되고, 무엇이 필수인지 정할
 * 자리가 없어진다.
 * ⛔ <b>「일단 놓고 나중에 맞추자」로 되돌리지 마라</b> — 반쪽 상태를 다음 FRD 가 as-is 로 읽는다.
 *
 * <p>⭐ <b>{@code unchanged} 는 배치가 찼는지 세는 값</b>이지 실제로 안 바뀌었다는 증명이 아니다.
 * ⛔ 이 둘을 뭉치지 마라 — 완결성 계산과 내용의 참거짓은 다른 물음이다.
 *
 * <p>⚠ <b>회신서의 이름과 꼴은 설계에 없다</b>(2026-09-23). 설계는 개발 API 로 받는 그림이라
 * 배치가 <b>요청 본문</b>이었다. git 통로에서는 파일이어야 해서 {@code DR-nnn/return.json} 을
 * 새로 정했다 — 바꾸려면 개발에 고지해야 한다.
 *
 * <p>⭐ <b>기본 브랜치가 그 사이 움직였어도 거절하지 않는다</b> (2026-09-23 사용자 확정).
 * 설계는 「받을 때 HEAD 와 다르면 거절」이었으나, 개발은 며칠 뒤에 돌려주고 그 사이 {@code main} 은
 * 바뀔 수 있다 — 판이 같은지로 거절하면 멀쩡한 결과가 매번 떨어진다. 대신 <b>갈라 온 뒤 기본
 * 브랜치에서 바뀐 파일과 이 배치가 다루는 경로가 겹칠 때만</b> 거절한다 — 설계가 미리 적어 둔
 * 「HEAD 가 아니라 그 배치가 다루는 경로의 기준만 보는 쪽으로 좁힌다」가 이것이다. {@code unchanged}
 * 로 적은 것도 다루는 경로다. ⚠ 색인만은 받을 때만 본다(아래 판정 자리의 까닭).
 * ⭐ 내용은 여전히 대조하지 않는다 — 파일 이름만 본다(설계의 「무대조」는 그대로다).
 * ⛔ 「HEAD 와 같아야 받는다」로 되돌리지 마라.
 *
 * <p>⛔ <b>DB 도 git 도 만지지 않는다</b> — 값을 받아 판정만 한다. 기본 브랜치의 사정은
 * {@link MainHistory} 로 받는다. 그래서 시험이 가볍다.
 */
public final class ReturnBatch {

    /** ⛔ 이 파일 이름을 다른 곳에 다시 적지 마라 — 흩어지면 한쪽만 고쳐진다. */
    public static final String FILE = "return.json";

    /**
     * 테스트 결과 md 둘의 자리 — 개발요청서 이름 밑이다({@code DR-009/return/unit-tests.md}).
     * ⛔ 이것도 여기에만 적는다 — {@code expected-back.md} 가 개발에게 알리는 경로와
     * 받는 자리가 읽는 경로가 같은 글자여야 한다.
     */
    public static final String UNIT_TESTS = "return/unit-tests.md";
    public static final String INTEGRATION_TESTS = "return/integration-tests.md";

    private static final String CHANGED = "changed";
    private static final String UNCHANGED = "unchanged";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReturnBatch() {
    }

    /**
     * 판정에 필요한 <b>기본 브랜치의 사정</b> 둘 — 받는 자리가 git 으로 재서 건넨다.
     */
    public interface MainHistory {

        /** 이 커밋이 기본 브랜치 이력에 있나. ⚠ 전달 브랜치 위에서 갈라 오면 여기서 떨어진다. */
        boolean contains(String commit);

        /** 이 커밋 이후 지금까지 기본 브랜치에서 바뀐 파일들. */
        Set<String> changedSince(String commit);
    }

    /**
     * 판정 결과.
     *
     * @param filesToTake ⭐ {@code changed} 인 것만 담는다 — {@code unchanged} 는 파일이 아니다
     */
    public record Verdict(boolean accepted, List<String> rejections, List<String> filesToTake) {

        /** 다른 판정의 거절을 더한다. ⭐ 하나라도 있으면 받을 파일은 빈 목록이 된다. */
        public Verdict rejectAlso(List<String> more) {
            if (more.isEmpty()) {
                return this;
            }
            List<String> all = new ArrayList<>(rejections);
            all.addAll(more);
            return new Verdict(false, List.copyOf(all), List.of());
        }
    }

    /**
     * @param main 지금 기본 브랜치의 사정. 갈라 온 기준이 그 이력에 있어야 하고, 그 뒤 바뀐 파일과
     *             받을 파일이 겹치면 거절한다
     */
    public static Verdict judge(ExpectedBack expected, String returnJson, MainHistory main) {
        JsonNode returned;
        try {
            returned = JSON.readTree(returnJson);
        } catch (JsonProcessingException broken) {
            // ⛔ 못 읽은 것을 통과로 세면 반쪽이 들어온다. 거절이지 무시가 아니다.
            return new Verdict(false, List.of("회신서를 읽지 못했습니다 — " + FILE), List.of());
        }

        List<String> rejections = new ArrayList<>();
        String declaredBase = returned.path("base").asText(null);
        boolean baseKnown = false;
        if (declaredBase == null || declaredBase.isBlank()) {
            rejections.add("회신서에 갈라 온 기준(base)이 없습니다 — 기본 브랜치의 어느 커밋에서"
                    + " 갈라 왔는지 적어 주십시오.");
        } else if (!main.contains(declaredBase)) {
            // ⛔ 전달 브랜치 위에서 갈라 오면 여기서 떨어진다 — 기획의 to-be 가 사실인 척 섞인다.
            rejections.add("갈라 온 기준이 기본 브랜치 이력에 없습니다: " + declaredBase
                    + " — 기본 브랜치에서 갈라 주십시오. 전달 브랜치 위에서 갈라 오면 이렇게 됩니다.");
        } else {
            baseKnown = true;
        }

        Set<String> known = new LinkedHashSet<>();
        expected.screens().forEach(screen -> known.add(screen.screenId()));
        List<String> take = new ArrayList<>();
        // ⭐ 이 배치가 다루는 경로 — 겹침을 여기서 본다(설계 「그 배치가 다루는 경로의 기준만」).
        Set<String> covered = new LinkedHashSet<>();

        for (JsonNode row : returned.path("screens")) {
            String screenId = row.path("screenId").asText(null);
            if (screenId == null || !known.contains(screenId)) {
                // ⛔ 목록 밖은 받지 않는다 — 대조 없이 자리를 지키는 유일한 문 지킴이다.
                rejections.add("돌려받을 목록에 없는 화면입니다: " + screenId);
                continue;
            }
            ExpectedBack.Screen screen = expected.screens().stream()
                    .filter(candidate -> candidate.screenId().equals(screenId)).findFirst().orElseThrow();
            for (String part : screen.required()) {
                String state = row.path(part).asText(null);
                if (state == null) {
                    rejections.add("필수 구성요소가 안 왔습니다: " + screenId + " 의 " + part);
                    continue;
                }
                if (!CHANGED.equals(state) && !UNCHANGED.equals(state)) {
                    rejections.add("모르는 상태값입니다: " + screenId + " 의 " + part + " = " + state);
                    continue;
                }
                if (CHANGED.equals(state)) {
                    take.add(pathOf(screen, part));
                }
                /*
                 * ⭐ unchanged 로 적은 것도 다루는 경로다 — html 만 받고 그 사이 바뀐 md 를 두면
                 *   개발의 옛 기준 html 과 남의 새 md 가 한 화면에 섞인다(시점 혼합물).
                 * ⚠ 색인만은 받을 때만 본다. 모든 화면의 필수라 늘 다루는 경로에 들고, IA 확정 게시가
                 *   게시할 때마다 다시 만든다 — 다루기만 해도 거절하면 IA 한 번에 회신이 전부 떨어진다.
                 */
                if (!ExpectedBack.INDEX.equals(part) || CHANGED.equals(state)) {
                    covered.add(pathOf(screen, part));
                }
            }
        }
        for (String screenId : known) {
            boolean came = false;
            for (JsonNode row : returned.path("screens")) {
                came |= screenId.equals(row.path("screenId").asText(null));
            }
            if (!came) {
                rejections.add("돌려받아야 할 화면이 안 왔습니다: " + screenId);
            }
        }

        if (baseKnown && rejections.isEmpty()) {
            /*
             * ⭐ 갈라 온 뒤 기본 브랜치에서 바뀐 파일과 이 배치가 다루는 경로가 겹치면 거절한다.
             *   겹치지 않으면 지금 기본 브랜치 위에 얹어도 덮을 것도, 섞일 것도 없다.
             */
            Set<String> moved = main.changedSince(declaredBase);
            covered.stream().filter(moved::contains).forEach(path -> rejections.add(
                    "갈라 온 뒤 기본 브랜치에서 이 파일이 바뀌었습니다: " + path
                            + " — 지금 기본 브랜치의 것을 보고 합쳐서 다시 보내 주십시오."
                            + " 그대로 받으면 그 변경이 덮입니다."));
        }

        boolean accepted = rejections.isEmpty();
        // ⭐ 하나라도 떨어지면 아무것도 안 놓는다.
        return new Verdict(accepted, List.copyOf(rejections), accepted ? List.copyOf(take) : List.of());
    }

    /**
     * 테스트 결과 md 둘이 <b>보낸 TC 목록 안에 있나</b>를 본다.
     *
     * <p>⛔ <b>보낸 적 없는 TC 가 오면 거절한다</b> (2026-09-23 사용자 확정). 담으면 테스트 화면이
     * 부푼 숫자를 세고, 그 줄은 어느 완료 조건에도 짝이 없다. 화면의 「목록 밖은 받지 않는다」와
     * 같은 규율이다. ⚠ 단위 쪽 번호를 통합 표에 적어도 목록 밖이다 — 갈래를 섞지 않는다.
     *
     * <p>⭐ <b>그 갈래에 TC 를 하나도 안 보냈으면 다 받는다.</b> 설계가 「시나리오가 없어도 계약은
     * 성립한다」고 했고, {@code expected-back.md} 가 그때 「검증한 항목을 같은 서식으로 적어 달라」고 한다.
     *
     * <p>⚠ <b>보낸 것이 덜 온 것은 여기서 따지지 않는다</b> — 이번에 정한 것은 「목록 밖」뿐이다.
     *
     * @param unitMarkdown        없으면 {@code null}
     * @param integrationMarkdown 없으면 {@code null}
     */
    public static List<String> judgeTests(ExpectedBack expected, String unitMarkdown,
                                          String integrationMarkdown) {
        List<String> rejections = new ArrayList<>();
        outside(expected.unitTests(), unitMarkdown, "UNIT", "단위테스트", rejections);
        outside(expected.integrationTests(), integrationMarkdown, "INTEGRATION", "통합테스트", rejections);
        return List.copyOf(rejections);
    }

    private static void outside(List<String> sent, String markdown, String kind, String name,
                                List<String> rejections) {
        if (sent == null || sent.isEmpty()) {
            return;   // 안 보냈으면 개발이 적은 것을 다 받는다
        }
        TestResultReader.read(markdown, kind).stream()
                .map(TestResultReader.Result::tcId)
                .filter(tcId -> !sent.contains(tcId))
                .distinct()
                .forEach(tcId -> rejections.add("보낸 적 없는 " + name + " TC 입니다: " + tcId
                        + " — 우리가 적어 보낸 번호만 채워 주십시오. 보낸 목록: "
                        + String.join(" · ", sent)));
    }

    /** 구성요소가 기획 저장소의 어느 자리에 앉나. ⚠ {@code expected-back.md} 도 이것으로 개발에게 알린다. */
    public static String pathOf(ExpectedBack.Screen screen, String part) {
        return switch (part) {
            case ExpectedBack.PAGES -> "core/" + screen.systemCode() + "/pages/" + screen.screenId() + ".html";
            case ExpectedBack.SCREEN_MD -> "core/" + screen.systemCode() + "/pages/" + screen.screenId() + ".md";
            /*
             * ⚠ 색인은 화면마다 한 조각이지만 파일은 저장소에 하나다 — 같은 경로가 여러 번 담길 수
             *   있고, 놓는 쪽에서 한 번만 꺼내면 된다.
             */
            case ExpectedBack.INDEX -> "index.json";
            default -> throw new IllegalStateException("모르는 구성요소입니다: " + part);
        };
    }
}
