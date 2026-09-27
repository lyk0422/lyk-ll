package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.service.BatchService;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.YeastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 酵母受控投酵并发互斥与回收/投酵提交顺序测试（H2 MySQL 兼容模式真实并发事务）。
 */
@SpringBootTest
class YeastConcurrencyTest {

    @Autowired
    private YeastService yeastService;

    @Autowired
    private BatchService batchService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM yeast_recovery");
        jdbc.update("DELETE FROM yeast_unit");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM fermentation_batch");
        for (int i = 1; i <= 8; i++) {
            jdbc.update("INSERT INTO fermentation_tank (tank_code, capacity_liters)"
                    + " SELECT 'CTANK-" + i + "', 5000 WHERE NOT EXISTS ("
                    + "SELECT 1 FROM fermentation_tank WHERE tank_code = 'CTANK-" + i + "')");
        }
    }

    @Test
    @DisplayName("8 批并发投同一酵母：最多一方投酵成功，其余冲突回滚，单元仅绑定获胜批次")
    void concurrentPitchSameYeastOnlyOneSucceeds() throws Exception {
        yeastService.register(new com.example.starter.fermentation.api.YeastDtos
                .RegisterYeastRequest(uuid(), "Y-CONC-1"));

        int threads = 8;
        long[] batchIds = new long[threads];
        for (int i = 0; i < threads; i++) {
            StoredResponse created = batchService.createBatch(new CreateBatchRequest(
                    uuid(), "B-CONC-Y-" + i, "CTANK-" + (i + 1), "LAGER-CURVE",
                    BaseFermentationTest.T0));
            assertEquals(201, created.status());
            batchIds[i] = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(created.body()).get("id").asLong();
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long batchId = batchIds[i];
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                try {
                    StoredResponse r = yeastService.pitch(batchId, new PitchYeastRequest(
                            uuid(), "Y-CONC-1", 1, BaseFermentationTest.T0));
                    if (r.status() == 200) {
                        succeeded.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (ApiException e) {
                    if (e.status().value() == 409) {
                        conflicted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, succeeded.get(), "并发投同一酵母仅一方成功");
        assertEquals(0, rejected.get(), "失败方应全部为 409 冲突而非其他错误");
        assertEquals(threads - 1, conflicted.get(), "其余并发请求应全部返回 409");

        Integer inUseBatches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status = 'MAIN_FERMENT'",
                Integer.class);
        assertEquals(1, inUseBatches, "仅获胜批次进入主酵");
        assertEquals(1, inUseBatches, "仅获胜批次进入主酵");
        assertEquals("IN_USE", jdbc.queryForObject(
                "SELECT status FROM yeast_unit WHERE unit_code = 'Y-CONC-1'", String.class),
                "单元应保持被获胜批次在用");
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM yeast_unit WHERE unit_code = 'Y-CONC-1'", Integer.class),
                "单元版本只应增加一次（1 → 2）");
        assertEquals(0, jdbc.queryForObject(
                "SELECT generation FROM yeast_unit WHERE unit_code = 'Y-CONC-1'", Integer.class),
                "投酵不改变代次");
    }

    @Test
    @DisplayName("回收与新投酵并发：按提交顺序仅一方在版本 2 成功，另一方失败回滚")
    void concurrentRecoveryAndPitchRespectCommitOrder() throws Exception {
        yeastService.register(new com.example.starter.fermentation.api.YeastDtos
                .RegisterYeastRequest(uuid(), "Y-RACE-1"));
        // 批次 A 投酵并完成主酵（进入降温），单元 IN_USE 版本 2 被 A 占用
        StoredResponse createdA = batchService.createBatch(new CreateBatchRequest(
                uuid(), "B-RACE-A", "CTANK-1", "LAGER-CURVE", BaseFermentationTest.T0));
        long batchA = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createdA.body()).get("id").asLong();
        assertEquals(200, yeastService.pitch(batchA, new PitchYeastRequest(
                uuid(), "Y-RACE-1", 1, BaseFermentationTest.T0)).status());
        batchService.addDiacetylTest(batchA,
                new com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest(
                        uuid(), BaseFermentationTest.T0.plusHours(1), new BigDecimal("0.09")));
        batchService.addDiacetylTest(batchA,
                new com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest(
                        uuid(), BaseFermentationTest.T0.plusHours(13), new BigDecimal("0.08")));
        assertEquals(200, batchService.cooling(batchA,
                new com.example.starter.fermentation.api.BatchDtos.CoolingRequest(
                        uuid(), BaseFermentationTest.T0.plusHours(13))).status());

        StoredResponse createdB = batchService.createBatch(new CreateBatchRequest(
                uuid(), "B-RACE-B", "CTANK-2", "LAGER-CURVE", BaseFermentationTest.T0));
        long batchB = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createdB.body()).get("id").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        Callable<Void> recoverTask = () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS));
            try {
                StoredResponse r = yeastService.recover(batchA, new RecoverYeastRequest(
                        uuid(), 2, BaseFermentationTest.T0.plusHours(14),
                        new BigDecimal("95.00"), false));
                if (r.status() == 200) {
                    success.incrementAndGet();
                } else {
                    failure.incrementAndGet();
                }
            } catch (ApiException e) {
                failure.incrementAndGet();
            }
            return null;
        };
        Callable<Void> pitchTask = () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS));
            try {
                StoredResponse r = yeastService.pitch(batchB, new PitchYeastRequest(
                        uuid(), "Y-RACE-1", 2, BaseFermentationTest.T0.plusHours(14)));
                if (r.status() == 200) {
                    success.incrementAndGet();
                } else {
                    failure.incrementAndGet();
                }
            } catch (ApiException e) {
                failure.incrementAndGet();
            }
            return null;
        };

        Future<?> f1 = pool.submit(recoverTask);
        Future<?> f2 = pool.submit(pitchTask);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "回收与投酵竞争同一版本仅一方成功");
        assertEquals(1, failure.get(), "另一方必须失败");
        assertEquals(3, jdbc.queryForObject(
                "SELECT version FROM yeast_unit WHERE unit_code = 'Y-RACE-1'", Integer.class),
                "单元版本只允许增加一次");

        String yeastStatus = jdbc.queryForObject(
                "SELECT status FROM yeast_unit WHERE unit_code = 'Y-RACE-1'", String.class);
        boolean recoveryWon = "AVAILABLE".equals(yeastStatus);
        if (recoveryWon) {
            // 回收先提交：批次 B 回滚保持 FILLED，留下一条回收记录；
            // 此后新请求持最新版本 3 与状态 AVAILABLE 投酵应成功（检查的是提交后的最新值）
            assertEquals("FILLED", jdbc.queryForObject(
                    "SELECT status FROM fermentation_batch WHERE id = ?", String.class, batchB),
                    "投酵失败方批次必须随事务回滚，保持满罐");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchA),
                    "获胜回收应留下一条追溯记录");
            StoredResponse nextPitch = yeastService.pitch(batchB, new PitchYeastRequest(
                    uuid(), "Y-RACE-1", 3, BaseFermentationTest.T0.plusHours(15)));
            assertEquals(200, nextPitch.status(), "回收提交后持最新版本 3 的新投酵应成功");
            assertEquals(4, jdbc.queryForObject(
                    "SELECT version FROM yeast_unit WHERE unit_code = 'Y-RACE-1'", Integer.class));
        } else {
            // 投酵先提交：单元被批次 B 占用，回收未发生
            assertEquals("IN_USE", yeastStatus);
            assertEquals(batchB, jdbc.queryForObject(
                    "SELECT occupied_by_batch_id FROM yeast_unit WHERE unit_code = 'Y-RACE-1'",
                    Long.class));
            assertEquals("MAIN_FERMENT", jdbc.queryForObject(
                    "SELECT status FROM fermentation_batch WHERE id = ?", String.class, batchB));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchA),
                    "回收失败方不得写入追溯记录");
        }
    }

    private static String uuid() {
        return "req-" + UUID.randomUUID();
    }
}
