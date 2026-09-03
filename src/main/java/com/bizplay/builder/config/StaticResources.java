package com.bizplay.builder.config;

import java.util.List;

/**
 * 정적 자원 경로의 정본. <b>{@code SecurityConfig} 와 {@code FirstLoginFilter} 가 이것 하나를 쓴다.</b>
 *
 * <p>⚠ <b>왜 한 곳에 모았나 — 같은 결함을 두 번 겪었다.</b>
 *
 * <ol>
 *   <li>스프링은 클래스패스 {@code static/} 을 <b>뿌리 경로</b>로 내보내는데, 두 곳이 모두 죽은 경로
 *       {@code /static/} 을 적고 있었다. 그래서 로그인 화면과 비밀번호 화면이 스타일 없이 뜨게 되어 있었다.</li>
 *   <li>목록이 둘로 갈려 있으면 <b>새 자원을 열 때 한쪽만 고쳐서</b> 같은 결함이 되살아난다.
 *       HTMX 를 넣을 때 {@code /js/} 가 그 자리다 — 폐기된 계획 2 는 {@code SecurityConfig} 에만
 *       {@code /js/**} 를 열라고 적어 놨고, 그대로 하면 관문에 걸린 사람의 JS 요청이 되튕긴다.</li>
 * </ol>
 *
 * <p>그래서 <b>새 정적 자원을 열 때 아래 접두 목록에만 한 줄 더한다.</b> 두 곳이 같이 열린다.
 * 그리고 {@code ShellTest} 에 그 자원 파일 하나를 더해 <b>익명과 관문에 걸린 사람 둘 다</b> 200 을 받는지 재라.
 *
 * <p>(둘 다 2026-08-09 코덱스 적대검증이 잡았다. 첫째는 실제 결함이었고 둘째는 올 결함이었다.)
 */
public final class StaticResources {

    /** 경로 접두. ⚠ 새 정적 자원은 여기에만 더한다. */
    private static final List<String> PREFIXES = List.of("/css/", "/fonts/", "/js/");

    private StaticResources() {
    }

    /** Spring Security 의 {@code requestMatchers} 에 넣을 무늬. */
    public static String[] patterns() {
        return PREFIXES.stream().map(prefix -> prefix + "**").toArray(String[]::new);
    }

    /** 이 경로가 정적 자원이냐 — 최초 로그인 관문 필터가 쓴다. */
    public static boolean matches(String path) {
        return PREFIXES.stream().anyMatch(path::startsWith);
    }
}
