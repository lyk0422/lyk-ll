package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.YeastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一酵母单元被两个发酵批次并发选中时的投酵互斥测试（真实并发 + H2 条件更新/行锁验证）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class YeastConcurrencyTest {

    @Autowired
    private YeastService yeastService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("两批并发投同一酵母：恰好一方成功(200)，另一方409，占用只属于胜方且败方批次仍满罐")
    void concurrentPitchSameYeastOnlyOneSucceeds() throws Exception {
        clean();
        jdbc.update("INSERT INTO yeast_unit (unit_code, generation, status, version, created_at)"
                + " VALUES ('Y-CONC-1', 0, 'AVAILABLE', 1, ?)", java.sql.Timestamp.valueOf(BaseFermentationTest.T0));
        long batchA = createBatchDirect("B-CONC-A", "TANK-1");
        long batchB = createBatchDirect("B-CONC-B", "TANK-2");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        List<Long> winners = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(pitchTask(batchA, "req-pitch-A", ready, go,
                success, conflicted, unexpected, winners)));
        futures.add(pool.submit(pitchTask(batchB, "req-pitch-B", ready, go,
                success, conflicted, unexpected, winners)));

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");

        assertEquals(1, success.get(), "并发投酵应仅一方成功");
        assertEquals(1, conflicted.get(), "另一方应返回 409");
        assertEquals(0, unexpected.get(), "不应出现其他结果");
        assertEquals(1, winners.size());

        // 单元最终 IN_USE，占用批次唯一且为胜方，版本只增一次
        Integer statusCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_unit WHERE unit_code = 'Y-CONC-1' AND status = 'IN_USE'",
                Integer.class);
        assertEquals(1, statusCount);
        Long occupant = jdbc.queryForObject(
                "SELECT occupied_by_batch_id FROM yeast_unit WHERE unit_code = 'Y-CONC-1'",
                Long.class);
        assertEquals(winners.get(0), occupant, "占用方必须是投酵胜方");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM yeast_unit WHERE unit_code = 'Y-CONC-1'", Integer.class);
        assertEquals(2, version, "仅成功一方使版本 +1");

        // 恰好一个批次进入主酵，败方仍为满罐
        Integer mainCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status = 'MAIN_FERMENT'", Integer.class);
        assertEquals(1, mainCount);
        Integer filledCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status = 'FILLED'", Integer.class);
        assertEquals(1, filledCount);
    }

    private Callable<Void> pitchTask(long batchId, String requestId, CountDownLatch ready,
                                     CountDownLatch go, AtomicInteger success,
                                     AtomicInteger conflicted, AtomicInteger unexpected,
                                     List<Long> winners) {
        return () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
            try {
                StoredResponse response = yeastService.pitch(batchId, new PitchYeastRequest(
                        requestId, "Y-CONC-1", 1, BaseFermentationTest.T0));
                if (response.status() == 200) {
                    success.incrementAndGet();
                    winners.add(batchId);
                } else {
                    unexpected.incrementAndGet();
                }
            } catch (ApiException e) {
                if (e.status().value() == 409) {
                    conflicted.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
            }
            return null;
        };
    }

    private long createBatchDirect(String batchNo, String tankCode) {
        Long tankId = jdbc.queryForObject(
                "SELECT id FROM fermentation_tank WHERE tank_code = ?", Long.class, tankCode);
        jdbc.update("INSERT INTO fermentation_batch (batch_no, tank_id, curve_code, curve_version,"
                        + " status, filled_at, maturation_extended, active_tank_key)"
                        + " VALUES (?, ?, 'LAGER-CURVE', 1, 'FILLED', ?, 0, ?)",
                batchNo, tankId, java.sql.Timestamp.valueOf(BaseFermentationTest.T0), tankCode);
        return jdbc.queryForObject(
                "SELECT id FROM fermentation_batch WHERE batch_no = ?", Long.class, batchNo);
    }

    private void clean() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM yeast_recovery_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM fermentation_batch");
        jdbc.update("DELETE FROM yeast_unit");
    }
}
