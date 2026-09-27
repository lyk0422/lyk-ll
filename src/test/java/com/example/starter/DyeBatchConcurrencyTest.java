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
 * 2) 同一 requestId 并发同参批准只生效一次；
 * 3) 并发修订同一系数版本，恰成功一次。
 */
class DyeBatchConcurrencyTest extends BaseIntegrationTest {

    @Test
    void concurrentControlledFeed_sameBatch_exactlyOneSucceeds_noOverconsumption() throws Exception {
        // RED 批次余量仅够一单（800g），两缸各需 800g
        jdbc.update("UPDATE dye_batch SET available_g=800 WHERE batch_no='B-RED-01'");
        schedule("cc-sched-1", "LCC-1", "VCC-1", "R-BASE", 1, "50");
        schedule("cc-sched-2", "LCC-2", "VCC-2", "R-BASE", 1, "50");
        approveDefault("cc-ap-1", "AP-CC1", "LCC-1");
        approveDefault("cc-ap-2", "AP-CC2", "LCC-2");

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
                        "requestId", "cc-feed-" + idx,
                        "lotNo", "LCC-" + idx,
                        "approvalNo", "AP-CC" + idx,
                        "weighings", List.of(
                                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
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
            } else if (code == 409 || code == 422) {
                rejected++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        pool.shutdown();

        assertEquals(1, ok, "同批余量只够一单：恰成功一单");
        assertEquals(1, rejected, "另一单整单拒绝");
        assertEquals(0, new BigDecimal("0.000").compareTo(batchAvailable("B-RED-01")),
                "不得超耗：余量恰扣到 0");
        assertEquals(0, new BigDecimal("3250.000").compareTo(batchAvailable("B-YELLOW-01")),
                "败单不得有任何部分扣减");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='FED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE status='CONSUMED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE status='ACTIVE'", Long.class),
                "败单批准保持 ACTIVE 可重试");
    }

    @Test
    void concurrentApprove_sameRequestSameParams_appliedExactlyOnce() throws Exception {
        schedule("ca-sched", "LCA", "VCA", "R-BASE", 1, "50");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/compensations", Map.of(
                        "requestId", "ca-ap", "approvalNo", "AP-CA", "lotNo", "LCA",
                        "items", List.of(
                                Map.of("dyeCode", "D-RED", "batchNo", "B-RED-01", "potencyVersion", 1),
                                Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01",
                                        "potencyVersion", 1),
                                Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01",
                                        "potencyVersion", 1))));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发：首次与重放均返回 201");
        }
        pool.shutdown();

        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation WHERE approval_no='AP-CA'", Long.class));
        assertEquals(Long.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_item ci JOIN compensation c "
                        + "ON c.id=ci.compensation_id WHERE c.approval_no='AP-CA'", Long.class));
    }

    @Test
    void concurrentRevisePotency_sameExpectedVersion_exactlyOneSucceeds() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/dye-batches/B-RED-01/potency", Map.of(
                        "requestId", "cr-revise-" + idx,
                        "potency", new BigDecimal("1.5"),
                        "expectedVersion", 1));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        pool.shutdown();

        assertEquals(1, ok, "同一 expectedVersion 并发修订恰成功一次");
        assertEquals(threads - 1, conflict);
        assertEquals(2, jdbc.queryForObject(
                "SELECT potency_version FROM dye_batch WHERE batch_no='B-RED-01'", Integer.class),
                "版本只从 1 加到 2");
        assertEquals(0, new BigDecimal("1.5").compareTo(jdbc.queryForObject(
                "SELECT potency FROM dye_batch WHERE batch_no='B-RED-01'", BigDecimal.class)));
    }
}
