package com.example.starter.battery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.battery.web.ConflictException;
import com.example.starter.battery.web.Requests.InspectionRequest;
import com.example.starter.battery.web.Requests.RegisterBatteryRequest;
import com.example.starter.battery.web.Requests.SwapOutRequest;
import com.example.starter.battery.web.Responses.SwapOutResponse;

/**
 * 并发边界测试：同一电池并发换出唯一成功、同键并发重放结果一致。
 */
class ConcurrentSwapOutTest extends AbstractLedgerTest {

    private static final int THREADS = 8;

    @Test
    @DisplayName("并发换出同一电池仅一笔成功，其余冲突，最终仅一条换出事件")
    void concurrentSwapOutOnlyOneSucceeds() throws Exception {
        service.register(new RegisterBatteryRequest("BAT-P1", "ST-A"));
        service.inspect(new InspectionRequest("req-p0", "BAT-P1", 1L, 95, 90));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<SwapOutResponse> successes = new CopyOnWriteArrayList<>();
        List<ConflictException> conflicts = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        successes.add(service.swapOut(new SwapOutRequest(
                                "req-p" + index, "BAT-P1", "VEH-" + index)));
                    } catch (ConflictException e) {
                        conflicts.add(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        unexpected.add(e);
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就绪");
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未在超时内结束");
        }

        assertTrue(unexpected.isEmpty(), "出现非预期异常: " + unexpected);
        assertEquals(1, successes.size(), "并发换出必须唯一成功");
        assertEquals(THREADS - 1, conflicts.size());
        assertEquals(BatteryStatus.IN_USE.name(), batteryStatus("BAT-P1"));
        assertEquals(successes.get(0).vehicleId(), service.getBattery("BAT-P1").vehicleId());

        Integer swapOutEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM battery_event WHERE battery_code = ? AND event_type = 'SWAP_OUT'",
                Integer.class, "BAT-P1");
        assertEquals(1, swapOutEvents == null ? 0 : swapOutEvents);
        assertTrue(service.verify("BAT-P1").valid());
    }

    @Test
    @DisplayName("同 requestId 同参数并发重放结果一致且不产生重复事件")
    void concurrentSameRequestReplay() throws Exception {
        service.register(new RegisterBatteryRequest("BAT-P2", "ST-A"));
        service.inspect(new InspectionRequest("req-q0", "BAT-P2", 1L, 95, 90));
        int eventsBefore = eventCount("BAT-P2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SwapOutResponse> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.swapOut(new SwapOutRequest("req-q1", "BAT-P2", "VEH-1"));
            });
            Future<SwapOutResponse> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.swapOut(new SwapOutRequest("req-q1", "BAT-P2", "VEH-1"));
            });
            start.countDown();
            SwapOutResponse r1 = first.get(30, TimeUnit.SECONDS);
            SwapOutResponse r2 = second.get(30, TimeUnit.SECONDS);
            assertEquals(r1, r2);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未在超时内结束");
        }

        assertEquals(eventsBefore + 1, eventCount("BAT-P2"));
        assertEquals(BatteryStatus.IN_USE.name(), batteryStatus("BAT-P2"));
    }
}
