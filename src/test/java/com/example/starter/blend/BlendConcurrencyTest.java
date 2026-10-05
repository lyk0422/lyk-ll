package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.ComponentAmount;
import com.example.starter.blend.BlendDtos.CreateCleaningRequest;
import com.example.starter.blend.BlendDtos.CreateOrderRequest;
import com.example.starter.blend.BlendDtos.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界测试：同一调合罐并发下达唯一成功；同键并发重放只执行一次。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:blend_test_concurrent;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BlendConcurrencyTest extends BlendServiceTestBase {

    private static CreateOrderRequest order(String requestId, long blendTankId, long tankId, String volume) {
        return new CreateOrderRequest(requestId, "GAS92", blendTankId,
                List.of(new ComponentAmount(tankId, new BigDecimal(volume))));
    }

    @Test
    void concurrentOrdersOnSameBlendTankOnlyOneSucceeds() throws Exception {
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
                            service.createOrder(order("R-CC-" + i, 1, 1, "50"));
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
        // 并发下达唯一成功，其余均为409罐占用冲突
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(0, other.get());
        // 最终数据一致：仅一张调合单、一条罐锁、库存只扣减一次
        assertEquals(1, countRows("blend_order"));
        assertEquals(1, countRows("blend_tank_lock"));
        assertEquals(new BigDecimal("950.000"), remaining(1));
    }

    @Test
    void concurrentSameRequestIdExecutesOnlyOnce() throws Exception {
        int threads = 2;
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
                            service.createOrder(order("R-CC-SAME", 1, 1, "50"));
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
        // 同键并发：至多一次业务执行；另一方要么重放成功，要么收到处理中409
        assertEquals(0, other.get());
        assertTrue(success.get() >= 1);
        // 无论重放还是409，最终只创建一张调合单，库存只扣减一次
        assertEquals(1, countRows("blend_order"));
        assertEquals(new BigDecimal("950.000"), remaining(1));
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
                            service.createCleaning(new CreateCleaningRequest("R-CL-CC-" + i, 1L, "GAS95",
                                    "2026-09-25 18:00:00", "2026-09-25 20:00:00"));
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
        // 清洗任务按罐号唯一：并发发起唯一成功，其余均为409冲突
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(0, other.get());
        // 最终数据一致：仅一张执行中清洗单、一条罐级清洗锁
        assertEquals(1, countRows("cleaning_order"));
        assertEquals(1, countRows("cleaning_tank_lock"));
    }
}
