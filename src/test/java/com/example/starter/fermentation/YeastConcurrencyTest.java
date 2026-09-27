package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CoolingRequest;
import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest;
import com.example.starter.fermentation.api.YeastDtos.ControlledStartRequest;
import com.example.starter.fermentation.api.YeastDtos.RecoverYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastUnitRequest;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.service.BatchService;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.YeastService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酵母投酵/回收并发测试：两批并发投同一酵母最多一方成功；
 * 并发回收仅受理一次；回收与新投酵按提交顺序检查最新代次与状态（乐观锁版本）。
 */
@SpringBootTest
class YeastConcurrencyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private BatchService batchService;

    @Autowired
    private YeastService yeastService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM yeast_recovery");
        jdbc.update("DELETE FROM yeast_unit");
        jdbc.update("DELETE FROM fermentation_batch");
    }

    private String reqId() {
        return "req-" + UUID.randomUUID();
    }

    private long createBatch(String batchNo, String tankCode) throws Exception {
        StoredResponse response = batchService.createBatch(new CreateBatchRequest(
                reqId(), batchNo, tankCode, "LAGER-CURVE", T0));
        assertEquals(201, response.status());
        return objectMapper.readTree(response.body()).get("id").asLong();
    }

    private void registerYeast(String unitCode) {
        StoredResponse response = yeastService.register(
                new RegisterYeastUnitRequest(reqId(), unitCode));
        assertEquals(201, response.status());
    }

    /** 受控投酵并驱动批次完成主酵（进入降温），酵母此时版本为 2。 */
    private long driveToCoolingWithYeast(String batchNo, String tankCode, String unitCode)
            throws Exception {
        registerYeast(unitCode);
        long batchId = createBatch(batchNo, tankCode);
        assertEquals(200, batchService.startMainFermentControlled(batchId,
                new ControlledStartRequest(reqId(), T0, unitCode, 1)).status());
        assertEquals(201, batchService.addDiacetylTest(batchId, new DiacetylTestRequest(
                reqId(), T0.plusHours(1), new BigDecimal("0.09"))).status());
        assertEquals(201, batchService.addDiacetylTest(batchId, new DiacetylTestRequest(
                reqId(), T0.plusHours(14), new BigDecimal("0.08"))).status());
        assertEquals(200, batchService.cooling(batchId,
                new CoolingRequest(reqId(), T0.plusHours(14))).status());
        return batchId;
    }

    @Test
    @DisplayName("两批并发投同一酵母：仅一方成功，酵母仅绑定一个批次且版本只 +1 一次")
    void concurrentPitchOnlyOneSucceeds() throws Exception {
        registerYeast("YEAST-CONC-1");
        long batch1 = createBatch("B-CONC-Y1", "TANK-1");
        long batch2 = createBatch("B-CONC-Y2", "TANK-2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (long batchId : new long[]{batch1, batch2}) {
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
                try {
                    StoredResponse response = batchService.startMainFermentControlled(batchId,
                            new ControlledStartRequest(reqId(), T0, "YEAST-CONC-1", 1));
                    if (response.status() == 200) {
                        succeeded.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (ApiException e) {
                    // 乐观锁冲突 409 或状态已变 422 均为合法拒绝
                    if (e.status().value() == 409 || e.status().value() == 422) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");

        assertEquals(1, succeeded.get(), "并发投同一酵母应仅一方成功");
        assertEquals(1, rejected.get(), "另一方应被 409/422 拒绝");
        assertEquals(0, unexpected.get(), "不应出现其他结果");

        // 酵母仅绑定一个批次，版本只递增一次
        var unit = yeastService.getUnit("YEAST-CONC-1");
        assertEquals("IN_USE", unit.status().name());
        assertEquals(2, unit.version(), "并发下版本应只 +1 一次");
        assertNotNull(unit.activeBatchId());
        assertTrue(unit.activeBatchId() == batch1 || unit.activeBatchId() == batch2);
        Integer mainFermentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status = 'MAIN_FERMENT'",
                Integer.class);
        assertEquals(1, mainFermentCount, "应仅一个批次进入主酵");
    }

    @Test
    @DisplayName("并发回收同一酵母：仅受理一次，代次只 +1 一次，回收记录仅一条")
    void concurrentRecoveryOnlyOnce() throws Exception {
        long batchId = driveToCoolingWithYeast("B-CONC-Y3", "TANK-1", "YEAST-CONC-2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
                try {
                    StoredResponse response = yeastService.recover("YEAST-CONC-2",
                            new RecoverYeastRequest(reqId(), T0.plusHours(24),
                                    new BigDecimal("95.00"), false, 2));
                    if (response.status() == 200) {
                        succeeded.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (ApiException e) {
                    if (e.status().value() == 409 || e.status().value() == 422) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");

        assertEquals(1, succeeded.get(), "并发回收应仅受理一次");
        assertEquals(1, rejected.get(), "另一方应被 409/422 拒绝");
        assertEquals(0, unexpected.get(), "不应出现其他结果");

        var unit = yeastService.getUnit("YEAST-CONC-2");
        assertEquals("AVAILABLE", unit.status().name());
        assertEquals(1, unit.generation(), "代次应只 +1 一次");
        assertEquals(3, unit.version(), "版本应只 +1 一次");
        assertNull(unit.activeBatchId(), "回收应解除批次占用");
        Integer recoveryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recovery WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, recoveryCount, "失败批次也只能回收一次，回收记录应仅一条");
    }

    @Test
    @DisplayName("回收与新投酵按提交顺序检查最新代次与状态：回收后旧版本投酵 409，新版本可投")
    void recoveryThenPitchChecksLatestVersion() throws Exception {
        driveToCoolingWithYeast("B-CONC-Y4", "TANK-1", "YEAST-CONC-3");
        // 回收提交后：代次 1、可用、版本 3
        assertEquals(200, yeastService.recover("YEAST-CONC-3",
                new RecoverYeastRequest(reqId(), T0.plusHours(24),
                        new BigDecimal("95.00"), false, 2)).status());

        long batch2 = createBatch("B-CONC-Y5", "TANK-2");
        // 按回收前的旧版本（2）投酵 → 409 版本冲突
        ApiException stale = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> batchService.startMainFermentControlled(batch2,
                        new ControlledStartRequest(reqId(), T0.plusHours(25),
                                "YEAST-CONC-3", 2)));
        assertEquals(409, stale.status().value(), "旧版本投酵应返回 409");
        assertEquals("FILLED", batchService.getBatch(batch2).status().name(),
                "版本冲突不得改变批次状态");

        // 按最新版本（3）投酵 → 成功，代次 1 仍 ≤5 可用
        assertEquals(200, batchService.startMainFermentControlled(batch2,
                new ControlledStartRequest(reqId(), T0.plusHours(25), "YEAST-CONC-3", 3))
                .status());
        var unit = yeastService.getUnit("YEAST-CONC-3");
        assertEquals("IN_USE", unit.status().name());
        assertEquals(4, unit.version());
        assertEquals(batch2, unit.activeBatchId());
    }
}
