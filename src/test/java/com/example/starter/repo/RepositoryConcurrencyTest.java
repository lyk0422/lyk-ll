package com.example.starter.repo;

import com.example.starter.domain.MessageStatus;
import com.example.starter.po.DeviceMessage;
import com.example.starter.testsupport.ForwarderTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库并发边界（真实 H2，非 mock）：
 * <ul>
 *   <li>同一条 PENDING 消息被多线程同时抢占为 in-flight，只有一个线程成功；</li>
 *   <li>相同 dedupKey 并发插入由唯一约束兜底，只落一条。</li>
 * </ul>
 */
class RepositoryConcurrencyTest extends ForwarderTestBase {

    @Test
    void concurrentMarkInFlightClaimsExactlyOnce() throws Exception {
        DeviceMessage saved = messageRepository.insert("dev-1", "/t", 1, 5,
                "claim-1", null, Instant.now());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (messageRepository.markInFlight(saved.id(), Instant.now())) {
                            success.incrementAndGet();
                        } else {
                            failure.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(threads - 1);
        DeviceMessage reloaded = messageRepository.findByDedupKey("claim-1").orElseThrow();
        assertThat(reloaded.status()).isEqualTo(MessageStatus.IN_FLIGHT.name());
        assertThat(reloaded.attempts()).isEqualTo(1);
    }

    @Test
    void concurrentInsertWithSameDedupKeyIsRejectedByUniqueConstraint() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        messageRepository.insert("dev-1", "/t", 1, 5, "same-key",
                                null, Instant.now());
                        inserted.incrementAndGet();
                    } catch (DuplicateKeyException e) {
                        duplicated.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(inserted.get()).isEqualTo(1);
        assertThat(duplicated.get()).isEqualTo(threads - 1);
        assertThat(messageRepository.findByDedupKey("same-key")).isPresent();
    }

    @Test
    void duplicateDedupKeyInsertDirectlyViolatesConstraint() {
        messageRepository.insert("dev-1", "/t", 1, 5, "dup", null, Instant.now());
        assertThatThrownBy(() ->
                messageRepository.insert("dev-2", "/t", 9, 1, "dup", null, Instant.now()))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
