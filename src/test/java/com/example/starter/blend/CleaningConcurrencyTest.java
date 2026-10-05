package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗并发边界测试：同一调合罐并发发起清洗唯一成功（真实 H2 库，MySQL 兼容模式）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:blend_test_cleaning_concurrent;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CleaningConcurrencyTest extends BlendServiceTestBase {

    private static CreateCleaningRequest cleaning(String requestId, long blendTankId) {
        return new CreateCleaningRequest(requestId, blendTankId, "GAS95",
                LocalDateTime.parse("2026-09-25T19:00:00"), LocalDateTime.parse("2026-09-25T20:00:00"));
    }

    @Test
    void concurrentCleaningOnSameTankOnlyOneSucceeds() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await(5, TimeUnit.SECONDS);
                            cleaningService.createCleaning(cleaning("R-CLC-" + i, 1));
                            success.incrementAndGet();
                        } catch (BlendException e) {
                            if (e.status() == 409) {
                                conflict.incrementAndGet();
                            } else {
                                other.incrementAndGet();
                            }
                        } catch (Exception e) {
                            other.incrementAndGet();
                        }
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // 同罐并发清洗唯一成功，其余均为409执行中清洗冲突
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(0, other.get());
        // 最终数据一致：仅一张清洗单、一条清洗锁，罐状态清洗中
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_lock"));
        assertEquals(CleaningService.TANK_CLEANING, jdbc.queryForObject(
                "SELECT status FROM blend_tank_state WHERE blend_tank_id = 1", String.class));
    }
}
