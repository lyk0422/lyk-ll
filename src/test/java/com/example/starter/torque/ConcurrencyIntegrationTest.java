package com.example.starter.torque;

import com.example.starter.torque.dto.CardView;
import com.example.starter.torque.dto.TighteningResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.starter.torque.TestRequests.dispatch;
import static com.example.starter.torque.TestRequests.spec;
import static com.example.starter.torque.TestRequests.tighten;
import static com.example.starter.torque.TestRequests.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真实并发测试（H2 + Hikari 多连接）：
 * 1) 同一工位并发派工唯一受理，败者事务整体回滚；
 * 2) 同一 requestId 并发提交紧固，只落一条测量链，双方均拿到首次结果。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void concurrentDispatchToSameStationAcceptsExactlyOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger occupied = new AtomicInteger();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        CardView view = service.dispatch(dispatch(
                                "req-" + idx, "CARD-" + idx, "STATION-X",
                                List.of(spec("P1", "100", false))));
                        assertNotNull(view);
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if (ErrorCodes.STATION_OCCUPIED.equals(e.getCode())) {
                            occupied.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                    return null;
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, success.get(), "同一工位同时刻只允许一张执行中工序卡");
        assertEquals(threads - 1, occupied.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_card WHERE station = 'STATION-X'", Integer.class),
                "败者派工事务必须整体回滚");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM station_active_card WHERE station = 'STATION-X'", Integer.class));
    }

    @Test
    void concurrentSameRequestIdTightenProducesSingleRecord() throws Exception {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        service.dispatch(dispatch("d-conc", "C-CONC", "STATION-Y",
                List.of(spec("P1", "100", false))));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TighteningResult>> results = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.tighten("C-CONC",
                            tighten("idem-key-1", "P1", "100", "TW-01", "op1"));
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            TighteningResult first = null;
            for (Future<TighteningResult> f : results) {
                TighteningResult r = f.get(15, TimeUnit.SECONDS);
                assertNotNull(r);
                if (first == null) {
                    first = r;
                } else {
                    assertEquals(first.recordedAt(), r.recordedAt());
                    assertEquals(first.attemptNo(), r.attemptNo());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, service.getTighteningChain("C-CONC").size(),
                "同键并发只允许一条紧固记录");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-key-1'", Integer.class));
    }
}
