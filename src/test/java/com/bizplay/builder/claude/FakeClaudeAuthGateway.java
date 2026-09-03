package com.bizplay.builder.claude;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실물 CLI 를 부르지 않는 대역. 겉은 실물과 같은 계약이다 —
 * 로그인마다 <b>다른 손잡이</b>를 주고, 성공하면 <b>claudeAiOauth 한 칸짜리 문서</b>를 내주고,
 * 버려진 손잡이로는 코드를 못 넣는다.
 */
public class FakeClaudeAuthGateway implements ClaudeAuthGateway {

    private final AtomicInteger id = new AtomicInteger();
    final List<String> discarded = new CopyOnWriteArrayList<>();

    @Override
    public Authorization begin() {
        return new Authorization("가짜손잡이-" + id.incrementAndGet(),
                "https://claude.com/cai/oauth/authorize?fake=1");
    }

    /**
     * 브라우저 콜백이 이미 끝냈나. 실물에서는 <b>자격 파일이 앉았나</b>가 이 값이다
     * (2026-08-14 실측 — 요즘은 이쪽이 보통 길이다).
     */
    private volatile boolean finishedByCallback;

    /** 사람이 새 창에서 승인을 마친 것으로 친다. */
    void finishByCallback() {
        finishedByCallback = true;
    }

    int beginCount() {
        return id.get();
    }

    @Override
    public Optional<AuthenticatedCredential> complete(Authorization authorization, String code) {
        if (discarded.contains(authorization.handle())) {
            // 실물에서는 자식 프로세스가 상한에 걸려 죽은 자리다.
            throw new IllegalStateException("로그인이 살아 있지 않다.");
        }
        // ① 콜백으로 끝났으면 코드가 없어도 끝난다.
        if (finishedByCallback) {
            return Optional.of(credential());
        }
        // ② 대체 길 — 코드를 물어보는 판본.
        if (code == null || code.isBlank()) {
            return Optional.empty();     // 아직이다. ⛔ 실패가 아니라서 로그인을 안 버린다
        }
        if (!"맞는코드".equals(code)) {
            throw new IllegalArgumentException("코드가 맞지 않는다.");
        }
        return Optional.of(credential());
    }

    private AuthenticatedCredential credential() {
        return new AuthenticatedCredential(OAUTH_ONLY,
                new ClaudeAccountIdentity("planner@claude.example", "org-planning", "기획팀", "team"));
    }

    /** ★ 실물이 내주는 것과 같은 모양이다 — 자격 파일 전체가 아니라 claudeAiOauth 한 칸뿐이다. */
    private static final String OAUTH_ONLY =
            "{\"claudeAiOauth\":{\"accessToken\":\"가짜토큰\",\"refreshToken\":\"가짜갱신\"}}";

    @Override
    public void discard(String handle) {
        discarded.add(handle);
    }

    void clear() {
        id.set(0);
        discarded.clear();
        finishedByCallback = false;
    }

    @TestConfiguration
    public static class Wiring {
        @Bean @Primary
        public ClaudeAuthGateway fakeClaudeAuthGateway() {
            return new FakeClaudeAuthGateway();
        }
    }
}
