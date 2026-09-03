package com.bizplay.builder.shell;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 껍데기 조각({@code fragments/shell :: layout})의 <b>인자 계약</b>을 한 자리에서 지킨다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-09-screen-shell-design.md}
 * 이름 규약: {@code docs/coding-conventions.md}
 *
 * <p>⛔ <b>왜 있나.</b> 조각 인자는 문자열이라 오타가 나도 Thymeleaf 는 아무 말도 안 한다.
 * 그대로 두면 {@code shape} 가 틀릴 때 전부 {@code '꽉'} 으로 흐르고 {@code current} 가 틀릴 때
 * 메뉴는 그리되 현재 위치 표시만 사라진다 — 즉 <b>500 이 아니라 「메뉴와 프로젝트 이름이
 * 사라진 정상 화면」</b>으로 보인다. 사람이 가장 눈치채기 어려운 실패 방식이다.
 * 그래서 <b>모르는 값이면 렌더를 실패시킨다.</b> 조용히 틀린 화면보다 시끄럽게 깨지는 편이 싸다.
 *
 * <p>부르는 자리는 {@code fragments/shell.html} 한 곳뿐이다 — 화면이 직접 부르지 않는다.
 *
 * <p>⚠ <b>값은 한글 그대로다.</b> {@code shape} 가 받는 {@code '산출물'}·{@code '관리'}·
 * {@code '카드'}·{@code '꽉'} 은 사람이 읽는 말이라 규약의 「화면에 뜨는 글」쪽이다.
 * 영문으로 바뀐 것은 <b>부르는 이름</b>뿐이다.
 *
 * <p>⚠ 화면을 새로 만들 때: 메뉴에 자리가 없는 화면이면 {@code current} 를 억지로 채우지 말고
 * {@code shape} 를 {@code '꽉'} 이나 {@code '카드'} 로 골라라. 메뉴 항목을 늘리는 것이 맞다면
 * <b>여기 목록과 {@code fragments/parts.html} 을 같이 고친다</b> — 둘이 갈라지면
 * {@code ShellContractTest} 가 빨개진다.
 */
@Component
public class ShellContract {

    /** 왼쪽 메뉴 열이 붙는 둘 · 안 붙는 둘. */
    public static final Set<String> SHAPES = Set.of("산출물", "관리", "카드", "꽉");

    /** 산출물 화면에서 사용할 수 있는 전체 열쇠. 숨긴 메뉴의 화면도 직접 열 수 있으므로 여기에 남긴다. */
    public static final List<String> ARTIFACT_KEYS = List.of(
            "received-docs", "requirements", "definitions",
            "brd", "frds", "srts", "dev-requests", "menu-tree", "design-guide", "business-language", "solution-mockups",
            "functional-specs", "screen-designs", "unit-tests", "integration-tests", "user-manual");

    /** 왼쪽 메뉴에 표시하는 산출물 열쇠. 순서는 {@code parts.html} 의 링크 순서와 같게 둔다. */
    public static final List<String> ARTIFACT_MENU_KEYS = List.of(
            "frds", "srts", "dev-requests", "menu-tree", "design-guide", "business-language", "solution-mockups",
            "functional-specs", "screen-designs", "unit-tests", "integration-tests", "user-manual");

    /**
     * 그린존 다섯 — 2026-08-27 에 열렸다(병주 확정). 화면이 서기 전까지 {@code list.html} 이 「준비 중」을 띄운다.
     * ⛔ 「요구사항 추적 매트릭스」는 같은 날 삭제됐다 — 열쇠 {@code matrix} 를 되살리지 마라.
     */
    public static final Set<String> GREEN_ZONE_KEYS = Set.of(
            "functional-specs", "screen-designs", "unit-tests", "integration-tests", "user-manual");

    /** 관리 메뉴 둘. */
    public static final List<String> ADMIN_KEYS = List.of("projects", "accounts");

    /**
     * 산출물 화면 제목. 열쇠 순서를 그대로 따른다.
     *
     * <p>왜 여기 있나 — 컨트롤러가 화면 제목으로 쓰고 {@code parts.html} 이 메뉴 글자로 쓴다.
     * 둘이 갈라지면 「메뉴에 적힌 이름과 들어간 화면의 제목이 다른」 자리가 난다.
     * {@code ShellContractTest} 가 이 표와 메뉴에 그려진 글자를 대조한다.
     */
    public static final Map<String, String> ARTIFACT_NAMES;

    static {
        var m = new LinkedHashMap<String, String>();
        m.put("received-docs", "받은 문서");
        m.put("requirements", "요구사항");
        m.put("definitions", "요구사항정의서");
        m.put("brd", "BRD");
        m.put("frds", "FRD 작업");
        m.put("srts", "SRT");
        m.put("dev-requests", "개발요청서");
        m.put("menu-tree", "IA");
        m.put("design-guide", "디자인가이드");
        m.put("business-language", "정책·표준용어");
        m.put("solution-mockups", "솔루션 템플릿");
        m.put("functional-specs", "기능명세서");
        m.put("screen-designs", "화면설계서");
        m.put("unit-tests", "단위테스트");
        m.put("integration-tests", "통합테스트");
        m.put("user-manual", "사용자 매뉴얼");
        ARTIFACT_NAMES = Collections.unmodifiableMap(m);
    }

    /**
     * 프로젝트를 고른 뒤에만 뜨는 모양들. 이 모양인데 프로젝트 이름이 없으면 계약 위반이다.
     *
     * <p>{@code '관리'} 와 {@code '카드'} 는 프로젝트 밖이라 여기 없다 — 관리는 슈퍼계정이 프로젝트
     * 여럿을 보는 자리이고, 카드는 로그인 계열이라 아직 고른 프로젝트가 없다.
     */
    private static final Set<String> INSIDE_PROJECT = Set.of("산출물", "꽉");

    /**
     * 조각이 렌더를 시작하기 전에 부른다. 계약을 어기면 여기서 던져서 <b>화면이 깨진다.</b>
     *
     * <p>⚠ 예외 메시지는 한글 문장이되 <b>가리키는 이름은 실제 식별자를 그대로 쓴다</b> —
     * 메시지가 코드에 없는 이름을 부르면 읽은 사람이 그 이름을 찾다가 헛짚는다.
     *
     * @param projectName 머리에 뜨는 이름. 조각 인자가 아니라 <b>모델에서 따로 읽는 값</b>이라
     *                    처음에는 이 검사 밖에 있었다 — 그래서 산출물 화면이 프로젝트 이름 없이
     *                    조용히 떴다(2026-08-09 코덱스 적대검증이 짚었다).
     * @param projectId   메뉴 링크가 쓰는 값. 없으면 링크가 {@code /projects//artifacts/brd} 로
     *                    나가서 <b>누르면 고르기로 튕기는 메뉴</b>가 된다 — 이름과 같은 조용한 실패다
     * @return 언제나 {@code true} — Thymeleaf 의 {@code th:with} 가 값을 하나 받아야 해서다
     */
    public boolean check(String shape, String current, String projectName, String projectId) {
        if (shape == null || !SHAPES.contains(shape)) {
            throw new IllegalArgumentException(
                    "껍데기 조각의 shape 가 %s 중 하나가 아니다: '%s'".formatted(SHAPES, shape));
        }

        if (INSIDE_PROJECT.contains(shape)) {
            if (projectName == null || projectName.isBlank()) {
                throw new IllegalArgumentException(
                        "shape '%s' 는 프로젝트를 고른 뒤에만 뜨는데 projectName 이 비어 있다".formatted(shape));
            }
            // ⚠ null 만 재던 자리다. 번호가 글자가 되면서 빈 글자가 들어올 수 있게 됐는데,
            //    그것이 바로 이 검사가 막으려던 `/projects//artifacts/brd` 를 만든다.
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException(
                        "shape '%s' 는 프로젝트를 고른 뒤에만 뜨는데 projectId 가 없다".formatted(shape));
            }
        }

        List<String> allowed = switch (shape) {
            case "산출물" -> ARTIFACT_KEYS;
            case "관리" -> ADMIN_KEYS;
            default -> null;   // 카드 · 꽉 — 메뉴가 없다
        };

        if (allowed == null) {
            if (current != null) {
                throw new IllegalArgumentException(
                        "shape '%s' 에는 메뉴가 없는데 current 가 들어왔다: '%s'".formatted(shape, current));
            }
            return true;
        }

        if (current == null || !allowed.contains(current)) {
            throw new IllegalArgumentException(
                    "shape '%s' 의 current 가 %s 중 하나가 아니다: '%s'".formatted(shape, allowed, current));
        }
        return true;
    }
}
