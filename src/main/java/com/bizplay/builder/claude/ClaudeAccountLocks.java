package com.bizplay.builder.claude;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 한 계정의 <b>Claude 실행</b>을 <b>계정당 동시 N개</b>로 묶는다 — 요구사항 분석·채팅·목업·정의서 재작성이
 * 전부 이 잠금을 지난다.
 *
 * <p>⭐ <b>2026-08-26 에 「한 줄 세우기」에서 「동시 N개」로 바꿨다.</b> 종전 잠금은 같은 계정의 실행을
 * 전부 직렬화해서 FRD 완료 때 화면 N장의 정의서가 N배 걸렸고, 목업 일괄 만들기도 한 장씩 돌았다.
 * 잠금이 막으려던 사고(뒷 실행이 앞 실행의 갱신 자격을 못 보고 덮는 것)는 그 뒤
 * {@code ClaudeCredentialRunner#persistRefreshedCredential} 이 <b>저장 직전에 DB 를 다시 읽어</b>
 * 막게 됐으므로, 여기 남은 몫은 「한 계정이 너무 많이 동시에 돌지 않게」 하나다.
 *
 * <p>⚠ <b>남은 위험 하나</b>: 같은 계정의 두 프로세스가 <b>같은 순간</b>에 OAuth 갱신을 하면 한쪽이
 * 실패할 수 있다(갱신 토큰은 한 번만 쓰인다). 그 경우 그 실행 하나가 실패로 끝나고 DB 자격은
 * 위 되쓰기 규칙이 지킨다 — 조용히 죽는 사고는 아니다. 그래도 걸리면
 * {@code builder.ai-account-concurrency: 1} 로 종전 직렬화로 되돌릴 수 있다.
 *
 * <p>⛔ <b>계정 재연결은 이 잠금을 쓰지 않는다 (2026-08-17).</b> 그 길은 사람이 누른 요청 스레드이고,
 * 이 잠금은 {@code claude} 가 도는 <b>몇 분 내내</b> 잡혀 있다 — 기다리면 화면이 그만큼 멈춘다.
 * 대신 실행 쪽이 저장 직전에 DB 를 다시 읽어 양보한다
 * ({@link ClaudeCredentialService#store} 와 {@code ClaudeCredentialRunner} 에 사유가 있다).
 * ⛔ 편의로 {@code store()} 에 다시 이것을 두르지 마라 — 그 멈춤이 되살아난다.
 */
@Component
public class ClaudeAccountLocks {

    private final int permits;
    private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    /**
     * @param permits 한 계정이 동시에 돌릴 수 있는 {@code claude} 수. 1 이면 종전과 같은 직렬화다.
     *                ⚠ 서버 전체의 상한은 {@code builder.ai-concurrency}(실행기 크기)가 따로 잡는다.
     */
    public ClaudeAccountLocks(@Value("${builder.ai-account-concurrency:3}") int permits) {
        this.permits = Math.max(1, permits);
    }

    public int permits() {
        return permits;
    }

    public Guard acquire(String accountId) {
        Semaphore semaphore = locks.computeIfAbsent(accountId, ignored -> new Semaphore(permits, true));
        semaphore.acquireUninterruptibly();
        return semaphore::release;
    }

    /**
     * 자격 <b>되쓰기</b>(DB 다시 읽기 → 비교 → 저장)만 감싸는 <b>짧은</b> 계정 잠금.
     *
     * <p>⛔ <b>이것을 빼지 마라 (2026-08-26 코덱스 적대 검증).</b> 동시 실행 둘이 <b>같은 원본 자격</b>을 들고
     * 시작하면, 끝날 때 둘 다 「DB 가 아직 원본이다」를 읽고 <b>둘 다 저장</b>할 수 있다 — 읽기·비교·쓰기가
     * 한 덩어리가 아니면 뒤에 쓴 낡은 갱신 토큰이 앞의 것을 덮는다. 이 잠금 안에서 비교하고 저장하면
     * 두 번째는 DB 가 이미 바뀐 것을 보고 물러난다. 실행 내내 잡는 위 {@link #acquire} 와 달리
     * 밀리초 단위다.
     */
    public Guard persistGuard(String accountId) {
        ReentrantLock lock = persistLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
        lock.lock();
        return lock::unlock;
    }

    private final ConcurrentHashMap<String, ReentrantLock> persistLocks = new ConcurrentHashMap<>();

    /** try-with-resources에서 쓰되 닫을 때 검사 예외를 만들지 않는 손잡이. */
    @FunctionalInterface
    public interface Guard extends AutoCloseable {
        @Override
        void close();
    }
}
