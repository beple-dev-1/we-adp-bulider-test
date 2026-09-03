package com.bizplay.builder.claude;

import java.util.Optional;

public interface ClaudeAuthGateway {

    /**
     * 승인 주소를 얻는다.
     *
     * <p>⚠ 이 순간 <b>살아 있는 로그인 자식 프로세스가 하나 뜬다.</b> `handle` 이 그것을 가리킨다.
     * 코드를 넣을 때 <b>같은 프로세스</b>여야 하기 때문이다 — PKCE 를 그 프로세스가 쥔다.
     */
    Authorization begin();

    /**
     * 승인이 끝났는지 보고, 끝났으면 자격을 내준다.
     *
     * <p>⚠ <b>2026-08-14 실측으로 길이 둘이 됐다.</b> 요즘 {@code claude auth login --claudeai} 는
     * <b>브라우저 콜백으로 스스로 끝내고 자식이 종료한다</b> — 그때는 넣을 코드가 아예 없다.
     * 코드를 물어보는 판본은 <b>대체 길</b>로만 남는다(콜백이 못 닿는 환경).
     * 어느 길이든 <b>판정은 자격 파일이 앉았나</b> 하나로 한다.
     *
     * @param code 돌아온 코드. <b>비어 있어도 된다</b> — 콜백으로 끝난 경우가 그렇다
     * @return 봉인해 둘 문자열과 로그인 계정 식별정보. 문자열은
     *         <b>`claudeAiOauth` 키 하나만 든 JSON 문서</b>이며 자격 파일 전체가 아니다
     *         (MCP 토큰이 딸려오면 안 된다).
     *         <p><b>비어 있으면 「아직 안 끝났다」</b>이고 <b>실패가 아니다</b> —
     *         ⛔ 부르는 쪽은 이때 로그인을 버리면 안 된다. 진행 중인 승인이 죽는다
     */
    Optional<AuthenticatedCredential> complete(Authorization authorization, String code);

    /**
     * 승인을 안 끝내고 떠난 로그인을 버린다 — 프로세스를 죽이고 그 자리를 지운다.
     * <b>여러 번 불러도 된다.</b>
     */
    void discard(String handle);

    record Authorization(String handle, String url) {
    }

    record AuthenticatedCredential(String oauthOnlyJson, ClaudeAccountIdentity identity) {
    }
}
