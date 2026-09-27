package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CoolingRequest;
import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest;
import com.example.starter.fermentation.api.YeastDtos.ControlledStartRequest;
import com.example.starter.fermentation.api.YeastDtos.RecycleRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.domain.YeastUnit;
import com.example.starter.fermentation.domain.YeastUnitStatus;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.repository.YeastRepository;
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
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酵母投酵/回收并发测试：两批并发投同一酵母最多一方成功；
 * 并发回收仅受理一次；回收与新投酵按提交顺序以最新代次与状态裁决。
 */
@SpringBootTest
class YeastConcurrencyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private BatchService batchService;

    @Autowired
    private YeastService yeastService;

    @Autowired
    private YeastRepository yeastRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM yeast_recycle_record");
        jdbc.update("DELETE FROM yeast_unit");
        jdbc.update("DELETE FROM fermentation_batch");
    }

    @Test
    @DisplayName("3 批次并发投同一酵母：最多一方成功，酵母仅绑定一个批次，版本仅加 1")
    void concurrentPitchOnlyOneSucceeds() throws Exception {
        yeastService.register(new RegisterYeastRequest("req-reg", "YEAST-CONC"));
        int threads = 3;
        List<Long> batchIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            StoredResponse created = batchService.createBatch(new CreateBatchRequest(
                    "req-create-" + i, "B-CONC-" + i, "TANK-" + (i + 1), "LAGER-CURVE", T0));
            assertEquals(201, created.status());
            batchIds.add(Long.parseLong(
                    created.body().replaceAll(".*\"id\":(\\d+).*", "$1")));
        }

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(threads, i -> {
            try {
                StoredResponse response = batchService.startMainFermentControlled(
                        batchIds.get(i), new ControlledStartRequest(
                                "req-pitch-" + i, "YEAST-CONC", 1, T0));
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
        });

        assertEquals(1, succeeded.get(), "并发投同一酵母最多一方成功");
        assertEquals(threads - 1, rejected.get(), "其余并发投酵应被 409/422 拒绝");
        assertEquals(0, unexpected.get(), "不应出现其他结果");
        YeastUnit unit = yeastRepository.findByCode("YEAST-CONC").orElseThrow();
        assertEquals(YeastUnitStatus.IN_USE, unit.status());
        assertEquals(2, unit.version(), "仅一次成功绑定，版本只加 1");
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_unit WHERE active_batch_id IS NOT NULL",
                Integer.class);
        assertEquals(1, bound, "酵母应仅绑定一个批次");
        Integer mainFermentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status = 'MAIN_FERMENT'",
                Integer.class);
        assertEquals(1, mainFermentCount, "仅一个批次进入主酵");
    }

    @Test
    @DisplayName("4 线程并发回收同一绑定：仅受理一次，代次只加 1，回收记录仅一条")
    void concurrentRecycleOnlyOneSucceeds() throws Exception {
        yeastService.register(new RegisterYeastRequest("req-reg", "YEAST-CONC"));
        long batchId = createPitchedBatchInCooling("B-CONC", "TANK-1", "YEAST-CONC");

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(4, i -> {
            try {
                StoredResponse response = yeastService.recycle("YEAST-CONC",
                        new RecycleRequest("req-recycle-" + i, batchId, 2,
                                new BigDecimal("95.00"), false, T0.plusHours(20)));
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
        });

        assertEquals(1, succeeded.get(), "并发回收应仅受理一次");
        assertEquals(3, rejected.get(), "其余并发回收应被 409/422 拒绝");
        assertEquals(0, unexpected.get(), "不应出现其他结果");
        YeastUnit unit = yeastRepository.findByCode("YEAST-CONC").orElseThrow();
        assertEquals(YeastUnitStatus.AVAILABLE, unit.status());
        assertEquals(1, unit.generation(), "代次只应加 1");
        assertEquals(3, unit.version(), "绑定与回收各成功一次，版本应为 3");
        assertNull(unit.activeBatchId(), "回收后应解除批次占用");
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yeast_recycle_record WHERE batch_id = ?",
                Integer.class, batchId);
        assertEquals(1, records, "回收记录应仅一条");
    }

    @Test
    @DisplayName("回收与新投酵并发：按提交顺序以最新代次与状态裁决，回收成功且新投酵不受理")
    void concurrentRecycleVersusPitch() throws Exception {
        yeastService.register(new RegisterYeastRequest("req-reg", "YEAST-CONC"));
        long batchA = createPitchedBatchInCooling("B-CONC-A", "TANK-1", "YEAST-CONC");
        StoredResponse createdB = batchService.createBatch(new CreateBatchRequest(
                "req-create-b", "B-CONC-B", "TANK-2", "LAGER-CURVE", T0));
        assertEquals(201, createdB.status());
        long batchB = Long.parseLong(createdB.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        AtomicInteger recycleSucceeded = new AtomicInteger();
        AtomicInteger pitchRejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(2, i -> {
            try {
                if (i == 0) {
                    StoredResponse response = yeastService.recycle("YEAST-CONC",
                            new RecycleRequest("req-recycle", batchA, 2,
                                    new BigDecimal("95.00"), false, T0.plusHours(20)));
                    if (response.status() == 200) {
                        recycleSucceeded.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } else {
                    batchService.startMainFermentControlled(batchB,
                            new ControlledStartRequest("req-pitch-b", "YEAST-CONC", 2, T0));
                    unexpected.incrementAndGet();
                }
            } catch (ApiException e) {
                if (i == 1 && (e.status().value() == 409 || e.status().value() == 422)) {
                    pitchRejected.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
            }
        });

        assertEquals(1, recycleSucceeded.get(), "回收应成功受理");
        assertEquals(1, pitchRejected.get(), "新投酵应按最新状态被拒绝（409 或 422）");
        assertEquals(0, unexpected.get(), "不应出现其他结果");
        YeastUnit unit = yeastRepository.findByCode("YEAST-CONC").orElseThrow();
        assertEquals(YeastUnitStatus.AVAILABLE, unit.status(), "回收后应转可用");
        assertEquals(1, unit.generation(), "代次应加 1");
        assertNull(unit.activeBatchId(), "不应绑定新批次");
        String batchBStatus = jdbc.queryForObject(
                "SELECT status FROM fermentation_batch WHERE id = ?", String.class, batchB);
        assertEquals("FILLED", batchBStatus, "新批次不应进入主酵");
    }

    /** 创建批次、受控投酵并驱动至降温（完成主酵），返回批次 ID。 */
    private long createPitchedBatchInCooling(String batchNo, String tankCode, String yeastCode) {
        StoredResponse created = batchService.createBatch(new CreateBatchRequest(
                "req-create-" + batchNo, batchNo, tankCode, "LAGER-CURVE", T0));
        assertEquals(201, created.status());
        long batchId = Long.parseLong(created.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        assertEquals(200, batchService.startMainFermentControlled(batchId,
                new ControlledStartRequest("req-pitch-" + batchNo, yeastCode, 1, T0)).status());
        assertEquals(201, batchService.addDiacetylTest(batchId, new DiacetylTestRequest(
                "req-d1-" + batchNo, T0.plusHours(1), new BigDecimal("0.09"))).status());
        assertEquals(201, batchService.addDiacetylTest(batchId, new DiacetylTestRequest(
                "req-d2-" + batchNo, T0.plusHours(14), new BigDecimal("0.08"))).status());
        assertEquals(200, batchService.cooling(batchId,
                new CoolingRequest("req-cool-" + batchNo, T0.plusHours(14))).status());
        return batchId;
    }

    /** 并发执行 threads 个任务：全部就绪后同时放行，等待完成并设超时。 */
    private void runConcurrently(int threads, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
                task.run(n);
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
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run(int index) throws Exception;
    }
}
