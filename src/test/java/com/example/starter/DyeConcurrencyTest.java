package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 并发/幂等边界（真实多线程 + CountDownLatch 同时发起，结果与最终数据双断言）：
 * 1) 同一染缸并发排缸唯一受理；
 * 2) 同一 requestId 并发同参投料只生效一次（其余重放）；
 * 3) 库存只够一单时，并并发投料恰成功一单，无超扣/部分扣减。
 */
class DyeConcurrencyTest extends BaseIntegrationTest {

    private static final int THREADS = 8;

    @Test
    void concurrentSchedule_sameVat_onlyOneAccepted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/lots", Map.of(
                        "requestId", "cs-vat-" + idx,
                        "lotNo", "LC-" + idx,
                        "vatNo", "VAT-SHARED",
                        "recipeCode", "R-BASE",
                        "recipeVersion", 1,
                        "fabricWeightKg", new BigDecimal("50")));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        long created = 0;
        long conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                created++;
            } else if (code == 409) {
                conflict++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        pool.shutdown();

        assertEquals(1, created, "同一染缸同时刻只允许一个执行中缸次");
        assertEquals(THREADS - 1, conflict, "其余并发排缸全部冲突拒绝");
        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active WHERE vat_no='VAT-SHARED'", Long.class));
        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot WHERE vat_no='VAT-SHARED'", Long.class),
                "败方事务整体回滚，不得残留缸次");
    }

    @Test
    void concurrentFeed_sameRequestSameParams_appliedExactlyOnce() throws Exception {
        schedule("cf-sched", "LF", "VF", "R-BASE", 1, "50");
        BigDecimal redBefore = stock("D-RED");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                        "requestId", "cf-feed-same",
                        "lotNo", "LF",
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS), "同键同参并发：首次与重放均返回 200");
        }
        pool.shutdown();

        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding WHERE request_id='cf-feed-same'", Long.class));
        assertEquals(Long.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing w JOIN feeding f ON f.id=w.feeding_id "
                        + "WHERE f.request_id='cf-feed-same'", Long.class));
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(stock("D-RED")),
                "并发同键重放不得重复扣减");
        assertEquals("FED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='LF'", String.class));
    }

    @Test
    void concurrentFeed_limitedStock_exactlyOneSucceeds_noOverDeduction() throws Exception {
        // 库存仅够一单：RED 1000g。两个不同缸次并发各需 1000g。
        jdbc.update("UPDATE dye SET stock_g=1000 WHERE code='D-RED'");
        schedule("cl-sched-1", "LN1", "VN1", "R-BASE", 1, "50");
        schedule("cl-sched-2", "LN2", "VN2", "R-BASE", 1, "50");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 1; i <= 2; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                        "requestId", "cl-feed-" + idx,
                        "lotNo", "LN" + idx,
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("1000.0000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.0000")))));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                rejected++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        pool.shutdown();

        assertEquals(1, ok, "库存只够一单：恰成功一单");
        assertEquals(1, rejected, "另一单因库存并发冲突整单回滚");
        assertEquals(new BigDecimal("0.000"), stock("D-RED"), "不得超扣");
        assertEquals(new BigDecimal("4250.000"), stock("D-YELLOW"), "败单不得有任何部分扣减");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='FED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='CREATED'", Long.class),
                "失败缸次保持待投料，可后续重新投料");
    }

    @Test
    void concurrentControlledFeed_sameBatchRemaining_noOverConsumption() throws Exception {
        // 受控场景：批次余量仅够一单（RED 补偿目标 500g，余量 500g），两缸争同批余量不得超耗。
        registerDyeLot("cdl-1", "LOT-XR", "D-RED", "2.0", "500");
        registerDyeLot("cdl-2", "LOT-XY", "D-YELLOW", "1.5", "2000");
        registerDyeLot("cdl-3", "LOT-XB", "D-BLUE", "1.0", "2000");
        schedule("cx-sched-1", "LX1", "VX1", "R-BASE", 1, "50");
        schedule("cx-sched-2", "LX2", "VX2", "R-BASE", 1, "50");
        approve("cap-1", "LX1", List.of(
                List.of("D-RED", "LOT-XR", 1),
                List.of("D-YELLOW", "LOT-XY", 1),
                List.of("D-BLUE", "LOT-XB", 1)));
        approve("cap-2", "LX2", List.of(
                List.of("D-RED", "LOT-XR", 1),
                List.of("D-YELLOW", "LOT-XY", 1),
                List.of("D-BLUE", "LOT-XB", 1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 1; i <= 2; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                        "requestId", "cx-feed-" + idx,
                        "lotNo", "LX" + idx,
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.000")))));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 200) {
                ok++;
            } else if (code == 422) {
                rejected++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        pool.shutdown();

        assertEquals(1, ok, "批次余量只够一单：恰成功一单");
        assertEquals(1, rejected, "另一单余量不足整单回滚");
        assertEquals(0, new BigDecimal("0.000").compareTo(batchAvailable("LOT-XR")),
                "同批余量不得超耗为负");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='FED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM comp_approval WHERE status='CONSUMED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM comp_approval WHERE status='ACTIVE'", Long.class),
                "败单批准保持 ACTIVE，可补足余量后重投");
    }
}
