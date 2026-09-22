package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.starter.arbitration.api.ApiException;
import com.example.starter.arbitration.dto.ClaimRequest;
import com.example.starter.arbitration.dto.ClaimResponse;

/**
 * 并发仲裁测试：真实并发竞争同一资源，验证任一时刻只有一个有效持有者。
 */
class ArbitrationConcurrencyTest extends AbstractArbitrationTest {

    @Test
    void concurrentClaimsLeaveExactlyOneHolder() throws Exception {
        int contenders = 4;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                String controllerId = "ctrl-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        ClaimResponse res = service.claim(
                                new ClaimRequest(controllerId, "req-" + controllerId,
                                        "joint1", 10, 60_000L));
                        if ("ACTIVE".equals(res.state())) {
                            granted.incrementAndGet();
                        }
                    } catch (ApiException e) {
                        if ("RESOURCE_BUSY".equals(e.getCode())) {
                            busy.incrementAndGet();
                        } else {
                            throw e;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
            }
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(granted.get()).isEqualTo(1);
        assertThat(busy.get()).isEqualTo(contenders - 1);

        Integer activeClaims = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim WHERE resource_id = 'joint1' AND state = 'ACTIVE'",
                Integer.class);
        assertThat(activeClaims).isEqualTo(1);
        assertThat(service.resourceView("joint1").holderControllerId()).isNotNull();
    }

    @Test
    void concurrentSameRequestIdReplayIsIdempotent() throws Exception {
        int contenders = 4;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        service.claim(new ClaimRequest("ctrl-a", "req-shared", "joint2", 10, 60_000L));
                        ok.incrementAndGet();
                    } catch (ApiException e) {
                        if ("REPLAY_CONFLICT".equals(e.getCode())) {
                            conflict.incrementAndGet();
                        } else {
                            throw e;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
            }
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 同 requestId 同参数并发：全部成功（幂等重放）或唯一约束冲突，但租约只有一条
        assertThat(ok.get() + conflict.get()).isEqualTo(contenders);
        Integer claims = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim WHERE request_id = 'req-shared'", Integer.class);
        assertThat(claims).isEqualTo(1);
    }
}
