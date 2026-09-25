package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import com.example.starter.tailings.repo.PlanRepository;
import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * 执行守卫：同一尾矿库同时刻仅允许一个执行中泄流方案（H2 主键约束真实并发验证）。
 */
class ExecutingGuardConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private PlanRepository planRepository;

    @Test
    void concurrentExecutingGuardAllowsOnlyOne() throws Exception {
        long pondId = createPond();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = java.util.stream.IntStream.range(0, threads)
                .<Future<?>>mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        planRepository.insertExecutingGuard(pondId, 1000L + i, 2000L + i);
                        success.incrementAndGet();
                    } catch (DuplicateKeyException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, success.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pond_executing_plan WHERE pond_id = ?", Integer.class, pondId));
    }
}
