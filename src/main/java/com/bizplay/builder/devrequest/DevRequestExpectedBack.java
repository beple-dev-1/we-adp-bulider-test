package com.bizplay.builder.devrequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이 개발요청서로 <b>돌려받을 것</b> — 현재 운영 화면 재동기의 대상 표.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-22-dev-request-package-design.md} 「돌려받을 것」 칸 ·
 * {@code docs/superpowers/specs/2026-08-07-dev-feedback-design.md}.
 *
 * <p>⭐ <b>{@code manifest.json} 의 {@code expectedBack} 과 {@code expected-back.md}가 둘 다
 * 이 하나에서 난다.</b> 사람용 상세 계약과 기계용 계약이 같은 대상에서 갈라져야 한다.
 *
 * <p><b>목록이 아니라 표다.</b> 화면ID 하나만 담으면 수신 쪽이 판정을 못 한다 —
 * 놓을 경로에 시스템 마디가 필요하고(신규 화면은 색인에 아직 없다),
 * 「회신이 끝났다」를 셀 근거로 필수 구성요소가 필요하다.
 *
 * <p>⛔ <b>규약(주소·상태코드·재시도)과 실제 수신 처리는 여기 없다.</b>
 * 이 값은 이 DR에서 개발 완료 뒤 돌려줄 대상만 나타낸다.
 *
 * @param screens 화면 축. 8절과 같은 화면 목록이다
 * @param domains 경로가 특정된 도메인 축 — 7절 백엔드 항목의 {@code target} 에서 뽑는다
 * @param backendChanges 경로 특정 여부와 무관하게 실제로 구현할 화면 외 항목 전체
 */
public record DevRequestExpectedBack(List<ScreenRow> screens, List<DomainRow> domains,
                                     List<BackendRow> backendChanges) {

    /** 회신 구성요소 이름. ⚠ 수신 API 의 갈래 이름과 같은 글자다 — 바꾸면 계약이 갈린다. */
    public static final String PAGES = "pages";
    public static final String SCREEN_MD = "screen-md";
    public static final String INDEX = "index";

    /**
     * 화면 한 장.
     *
     * @param systemCode         시스템. 놓을 경로 {@code core/<시스템>/pages/} 의 마디다
     * @param screenId           화면ID
     * @param requiredComponents 배치가 찼다고 셀 때 하나씩 {@code changed}·{@code unchanged} 가 와야 하는 것
     * @param acceptScreenMd     화면 md 를 받나. ⛔ {@code false} 면 {@code requiredComponents} 에
     *                           {@code screen-md} 가 없다 — 안 빼면 개발이 보낼 수 없는 것을 필수로 요구해
     *                           배치가 영원히 안 찬다
     */
    public record ScreenRow(String systemCode, String screenId, List<String> requiredComponents,
                            boolean acceptScreenMd) {
        public ScreenRow {
            requiredComponents = requiredComponents == null ? List.of() : List.copyOf(requiredComponents);
        }
    }

    /** 도메인 모듈 하나 — {@code domains/<도메인>/<모듈>.md} 에 앉는다. */
    public record DomainRow(String domain, String module) {}

    /** 화면 외 구현 한 항목 — 도메인 경로를 특정하지 못해도 회신 계약에서 사라지지 않는다. */
    public record BackendRow(String category, String target, String changeDetail,
                             String verification) {}

    /**
     * 백엔드 {@code target} 에서 도메인 모듈을 읽는 모양 — {@code domains/<도메인>/<모듈>.md} 또는
     * {@code <도메인>/<모듈>} (확장자 있어도 된다).
     *
     * <p>⚠ {@code target} 은 자유 글자다(정본 설계 「{@code target} 이 자유 글자인 것은 남는 위험이다」).
     * 「결제 API」처럼 앵커 모양이 아니면 <b>도메인 축에 안 오른다</b> — 지어내지 않는다.
     */
    private static final Pattern DOMAIN_TARGET =
            Pattern.compile("^\\s*(?:domains/)?([a-z0-9-]+)/([a-z0-9-]+)(?:\\.md)?\\s*$");

    public DevRequestExpectedBack {
        screens = screens == null ? List.of() : List.copyOf(screens);
        domains = domains == null ? List.of() : List.copyOf(domains);
        backendChanges = backendChanges == null ? List.of() : List.copyOf(backendChanges);
    }

    /**
     * 스냅샷에서 표를 만든다.
     *
     * <p>⚠ <b>{@code acceptScreenMd} 는 지금 전부 {@code true} 다.</b> 「{@code ⚑ 보정됨}·{@code ⬛ 네이티브}」
     * 표시를 빌더 DB 가 아직 안 쥐고 있다(2026-08-25 실측 — 그 칸이 없다). 그 표시가 DB 에 생기는 순간
     * 여기서 읽어 {@code false} 로 내리고 {@code screen-md} 를 필수에서 뺀다 — 계획 10 Task 4 의 자리다.
     */
    public static DevRequestExpectedBack of(DevelopmentRequestContent content) {
        List<ScreenRow> screens = new ArrayList<>();
        for (var screen : content.screens()) {
            boolean acceptScreenMd = true;
            List<String> required = acceptScreenMd
                    ? List.of(PAGES, SCREEN_MD, INDEX) : List.of(PAGES, INDEX);
            screens.add(new ScreenRow(systemOf(screen), screen.deliveryScreenId(), required, acceptScreenMd));
        }
        // 같은 모듈이 여러 항목에 걸리면 한 줄이다 — 수신은 모듈 단위로 센다.
        Set<DomainRow> domains = new LinkedHashSet<>();
        List<BackendRow> backendChanges = new ArrayList<>();
        for (var change : content.requiredChanges()) {
            backendChanges.add(new BackendRow(change.category(), change.target(),
                    change.changeDetail(), change.verification()));
            if (change.target() == null) {
                continue;
            }
            Matcher matcher = DOMAIN_TARGET.matcher(change.target());
            if (matcher.matches()) {
                domains.add(new DomainRow(matcher.group(1), matcher.group(2)));
            }
        }
        return new DevRequestExpectedBack(screens, List.copyOf(domains), backendChanges);
    }

    private static String systemOf(DevelopmentRequestContent.Screen screen) {
        return screen.systemCode() == null || screen.systemCode().isBlank()
                ? "unknown" : screen.systemCode();
    }
}
