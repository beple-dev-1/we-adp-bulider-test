package com.bizplay.builder.devrequest;

import java.util.List;

/**
 * 「돌려받을 것」의 <b>계산된 자료</b> — 사람용({@link ExpectedBackDocument})과
 * 기계용({@code manifest.expectedBack})이 <b>둘 다 이것을 그린다.</b>
 *
 * <p>⛔ <b>두 곳에서 따로 계산하지 마라.</b> 설계가 「{@code manifest.expectedBack} 과 같은 대상
 * 자료에서 생성한다」고 못 박은 자리다 — 갈리면 개발이 본 표와 우리가 검사하는 목록이 달라지고,
 * 그때 어느 쪽이 맞는지 아무도 모른다.
 *
 * <p>⭐ <b>필수 구성요소가 계약의 알맹이다.</b> 개발은 대상마다 필수 구성요소 하나하나에
 * {@code changed} 또는 {@code unchanged} 를 <b>정확히 하나</b> 채운다. 그래야 배치가 찼는지
 * 셀 수 있고, 「누락」과 「봤는데 안 바뀜」이 갈린다.
 */
public record ExpectedBack(String returnBranch, String base, List<Screen> screens,
                           List<Domain> domains, List<String> unitTests,
                           List<String> integrationTests) {

    /** 화면 구성요소 — 목업 · 화면 md · 색인 조각. */
    public static final String PAGES = "pages";
    public static final String SCREEN_MD = "screen-md";
    public static final String INDEX = "index";

    public record Screen(String screenId, String systemCode, List<String> required) {

        public boolean expectsScreenMd() {
            return required.contains(SCREEN_MD);
        }
    }

    /** ⚠ {@code target} 이 {@code domains/…} 꼴이 아니어도 담는다 — 꼴로 거르면 목록에서 사라진다. */
    public record Domain(String target, String change) {
    }

    /**
     * 개발요청서 한 건에서 「돌려받을 것」을 계산한다.
     *
     * <p>⚠ <b>보호 화면은 {@code screen-md} 를 필수에서 뺀다.</b> 사람이 손댄 화면이라 받지 않기로
     * 했는데 필수로 두면 <b>배치가 영원히 안 찬다</b> — 개발이 채울 수 없는 칸을 기다리게 된다.
     * ⛔ <b>그 표시를 담는 자리가 아직 빌더에 없다</b>(2026-09-23 실측: 「보정하기」가 잠겨 있고
     * 표시 열도 없다). 그래서 {@code protectedScreens} 는 오늘 늘 비어 온다.
     */
    public static ExpectedBack of(String returnBranch, String base,
                                  DevelopmentRequestContent content,
                                  List<String> protectedScreens) {
        List<String> guarded = protectedScreens == null ? List.of() : protectedScreens;
        List<Screen> screens = content.screens() == null ? List.of() : content.screens().stream()
                .map(screen -> new Screen(screen.deliveryScreenId(), screen.systemCode(),
                        guarded.contains(screen.deliveryScreenId())
                                ? List.of(PAGES, INDEX)
                                : List.of(PAGES, SCREEN_MD, INDEX)))
                .toList();
        List<Domain> domains = content.backendChanges() == null ? List.of()
                : content.backendChanges().stream()
                        .map(change -> new Domain(change.target(), change.changeDetail()))
                        .toList();
        List<String> unit = content.testScenarios().stream()
                .filter(DevelopmentRequestContent.TestScenario::isUnit)
                .map(DevelopmentRequestContent.TestScenario::id).toList();
        List<String> integration = content.testScenarios().stream()
                .filter(scenario -> !scenario.isUnit())
                .map(DevelopmentRequestContent.TestScenario::id).toList();
        return new ExpectedBack(returnBranch, base, screens, domains, unit, integration);
    }
}
