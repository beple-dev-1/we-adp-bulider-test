package com.bizplay.builder.claude;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계정 잠금은 「직렬화」가 아니라 「계정당 동시 N개」다.
 *
 * <p>종전에는 같은 계정의 실행이 전부 한 줄로 섰다 — FRD 완료 때 화면 N장이 N배 걸렸다.
 * 갱신 자격 덮어쓰기는 {@code ClaudeCredentialRunner#persistRefreshedCredential} 이
 * DB 를 다시 읽어 막으므로, 잠금이 지킬 것은 「너무 많이 동시에」 하나만 남았다.
 */
class ClaudeAccountLocksTest {

    @Test
    void 같은_계정이라도_허용치_안에서는_동시에_돈다() throws Exception {
        ClaudeAccountLocks locks = new ClaudeAccountLocks(2);
        AtomicInteger concurrently = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable job = () -> {
            try (ClaudeAccountLocks.Guard ignored = locks.acquire("A")) {
                peak.accumulateAndGet(concurrently.incrementAndGet(), Math::max);
                bothInside.countDown();
                release.await(5, TimeUnit.SECONDS);
                concurrently.decrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(job);
        Thread second = new Thread(job);
        first.start();
        second.start();

        assertThat(bothInside.await(3, TimeUnit.SECONDS))
                .as("허용치 2 인데 둘이 함께 들어가지 못했다")
                .isTrue();
        release.countDown();
        first.join(3000);
        second.join(3000);
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    void 허용치를_넘는_실행은_앞_것이_끝날_때까지_기다린다() throws Exception {
        ClaudeAccountLocks locks = new ClaudeAccountLocks(1);
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);

        Thread first = new Thread(() -> {
            try (ClaudeAccountLocks.Guard ignored = locks.acquire("A")) {
                firstInside.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        first.start();
        assertThat(firstInside.await(3, TimeUnit.SECONDS)).isTrue();

        Thread second = new Thread(() -> {
            try (ClaudeAccountLocks.Guard ignored = locks.acquire("A")) {
                secondInside.countDown();
            }
        });
        second.start();

        assertThat(secondInside.await(300, TimeUnit.MILLISECONDS))
                .as("허용치 1 인데 두 번째가 기다리지 않고 들어갔다")
                .isFalse();
        release.countDown();
        assertThat(secondInside.await(3, TimeUnit.SECONDS)).isTrue();
        first.join(3000);
        second.join(3000);
    }

    @Test
    void 다른_계정은_서로_기다리지_않는다() throws Exception {
        ClaudeAccountLocks locks = new ClaudeAccountLocks(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherInside = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (ClaudeAccountLocks.Guard ignored = locks.acquire("A")) {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        Thread other = new Thread(() -> {
            try (ClaudeAccountLocks.Guard ignored = locks.acquire("B")) {
                otherInside.countDown();
            }
        });
        other.start();
        assertThat(otherInside.await(3, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        holder.join(3000);
        other.join(3000);
    }

    @Test
    void 자격_되쓰기_잠금은_허용치와_무관하게_계정당_하나씩만_들어간다() throws Exception {
        ClaudeAccountLocks locks = new ClaudeAccountLocks(3);
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try (ClaudeAccountLocks.Guard ignored = locks.persistGuard("A")) {
                firstInside.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        first.start();
        assertThat(firstInside.await(3, TimeUnit.SECONDS)).isTrue();
        Thread second = new Thread(() -> {
            try (ClaudeAccountLocks.Guard ignored = locks.persistGuard("A")) {
                secondInside.countDown();
            }
        });
        second.start();
        assertThat(secondInside.await(300, TimeUnit.MILLISECONDS))
                .as("되쓰기 구간은 동시 실행 허용치(3)와 무관하게 한 번에 하나다")
                .isFalse();
        release.countDown();
        assertThat(secondInside.await(3, TimeUnit.SECONDS)).isTrue();
        first.join(3000);
        second.join(3000);
    }

    @Test
    void 허용치가_1_미만이면_1로_본다() {
        assertThat(new ClaudeAccountLocks(0).permits()).isEqualTo(1);
        assertThat(new ClaudeAccountLocks(-3).permits()).isEqualTo(1);
    }
}
