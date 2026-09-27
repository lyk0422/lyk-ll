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
 * 受控投料并发/幂等边界（真实多线程 + CountDownLatch 同时发起，结果与最终数据双断言）：
 * 1) 两缸争同批余量不得超耗，恰成功一单；
 * 2) 同一 requestId 并发同参受控投料只生效一次（其余重放）；
 * 3) 同一受控缸次并发投料（不同 requestId）串行化，恰一单生效。
 */
class CompensationConcurrencyTest extends BaseIntegrationTest {

    private static final int THREADS = 8;

    @Test
    void concurrentControlledFeed_sameBatchLimitedBalance_exactlyOneSucceeds_noOverConsumption() throws Exception {
        // B-RED-1 余量仅够一单（补偿目标 800.000）
        jdbc.update("UPDATE dye_batch SET available_g=800 WHERE batch_no='B-RED-1'");
        schedule("cb-s1", "CB1", "CBV1", "R-BASE", 1, "50");
        schedule("cb-s2", "CB2", "CBV2", "R-BASE", 1, "50");
        approveDefault("cb-a1", "CB1");
        approveDefault("cb-a2", "CB2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/controlled-feedings", Map.of(
                        "requestId", "cb-f" + idx,
                        "lotNo", "CB" + idx,
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
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

        assertEquals(1, ok, "同批余量只够一单：恰成功一单");
        assertEquals(1, rejected, "另一单余量不足整单拒绝");
        assertEquals(0, new BigDecimal("0.000").compareTo(batchAvailable("B-RED-1")), "不得超耗");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='FED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_approval WHERE status='FED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_approval WHERE status='VALID'", Long.class),
                "败单批准保持有效，可重新投料");
    }

    @Test
    void concurrentControlledFeed_sameRequestSameParams_appliedExactlyOnce() throws Exception {
        schedule("cc-s1", "CC1", "CCV1", "R-BASE", 1, "50");
        approveDefault("cc-a1", "CC1");
        BigDecimal redBefore = batchAvailable("B-RED-1");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/controlled-feedings", Map.of(
                        "requestId", "cc-feed-same",
                        "lotNo", "CC1",
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
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
                "SELECT COUNT(*) FROM feeding WHERE request_id='cc-feed-same'", Long.class));
        assertEquals(Long.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing w JOIN feeding f ON f.id=w.feeding_id "
                        + "WHERE f.request_id='cc-feed-same'", Long.class));
        assertEquals(0, redBefore.subtract(new BigDecimal("800.000")).compareTo(batchAvailable("B-RED-1")),
                "并发同键重放不得重复扣减批次余量");
        assertEquals("FED", jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='CC1'", String.class));
    }

    @Test
    void concurrentControlledFeed_sameLotDifferentKeys_exactlyOneFeeding() throws Exception {
        schedule("cl-s1", "CL9", "CLV9", "R-BASE", 1, "50");
        approveDefault("cl-a1", "CL9");
        BigDecimal redBefore = batchAvailable("B-RED-1");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/controlled-feedings", Map.of(
                        "requestId", "cl-feed-" + idx,
                        "lotNo", "CL9",
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
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

        assertEquals(1, ok, "同一缸次并发投料串行化：恰一单生效");
        assertEquals(THREADS - 1, rejected, "其余看到已投料状态整单拒绝");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding f JOIN lot l ON l.id=f.lot_id WHERE l.lot_no='CL9'",
                Long.class));
        assertEquals(0, redBefore.subtract(new BigDecimal("800.000")).compareTo(batchAvailable("B-RED-1")),
                "只扣减一次批次余量");
    }
}
