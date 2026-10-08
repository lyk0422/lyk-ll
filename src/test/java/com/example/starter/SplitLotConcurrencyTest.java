package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
 * 分缸 H2 真实并发（多线程 + CountDownLatch 同时发起，结果与最终数据双断言）：
 * 1) 两次拆同源并发：最多一次成功，败方 409 整单回滚，目标缸占用/子缸不重复；
 * 2) 来源判色与分缸竞争：行锁互斥，恰一方成交——判色先成则分缸 409，分缸先成则判色 409。
 */
class SplitLotConcurrencyTest extends BaseIntegrationTest {

    private Map<String, Object> splitBody(String requestId, String childPrefix, String vatPrefix) {
        return Map.of(
                "requestId", requestId,
                "sourceLotNo", "LS",
                "expectedVersion", 1,
                "targets", List.of(
                        Map.of("lotNo", childPrefix + "1", "vatNo", vatPrefix + "1",
                                "fabricWeightKg", new BigDecimal("40")),
                        Map.of("lotNo", childPrefix + "2", "vatNo", vatPrefix + "2",
                                "fabricWeightKg", new BigDecimal("60"))));
    }

    private void prepareFed100() {
        schedule("sc-sched", "LS", "VS", "R-BASE", 1, "100");
        postOk("/api/feedings", Map.of(
                "requestId", "sc-feed", "lotNo", "LS",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));
    }

    @Test
    void concurrentSplit_sameSource_exactlyOneSucceeds() throws Exception {
        prepareFed100();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/lots/split",
                        splitBody("sc-split-" + idx, "LC" + idx + "-", "VC" + idx + "-"));
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

        assertEquals(1, ok, "同一来源最多成功分缸一次");
        assertEquals(1, conflict, "败方分缸并发拒绝");
        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot_split", Long.class));
        assertEquals(Long.valueOf(2),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot_split_item", Long.class));
        assertEquals(Long.valueOf(2),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot WHERE status='FED' AND split_parent_lot_id IS NOT NULL",
                        Long.class),
                "败方整单回滚，只残留胜方两个 FED 子缸");
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LS'", String.class));
        // 来源原缸释放，胜方两个目标缸各占一行，败方目标缸不得被占用。
        assertEquals(Long.valueOf(0),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active WHERE vat_no='VS'", Long.class));
        assertEquals(Long.valueOf(2),
                jdbc.queryForObject("SELECT COUNT(*) FROM vat_active", Long.class));
    }

    @Test
    void concurrentSplitSameRequestId_sameParams_appliedExactlyOnce() throws Exception {
        prepareFed100();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return post("/api/lots/split", splitBody("sc-same", "LC", "VC")).getStatusCode().value();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS), "同键同参并发首次成功其余重放");
        }
        pool.shutdown();
        assertEquals(Long.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot_split", Long.class));
        assertEquals(Long.valueOf(2),
                jdbc.queryForObject("SELECT COUNT(*) FROM lot WHERE split_parent_lot_id IS NOT NULL", Long.class));
    }

    @Test
    void splitAndJudgeRace_exactlyOneWins_loserRejected() throws Exception {
        prepareFed100();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();

        // 线程 A：分缸；线程 B：来源出缸判色。二者争抢来源 lot 行锁。
        results.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return post("/api/lots/split", splitBody("race-split", "LC", "VC")).getStatusCode().value();
        }));
        results.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return post("/api/judgments", Map.of(
                    "requestId", "race-judge", "lotNo", "LS", "deltaE", new BigDecimal("0.30")))
                    .getStatusCode().value();
        }));

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int splitCode = results.get(0).get(30, TimeUnit.SECONDS);
        int judgeCode = results.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        String sourceStatus = jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LS'", String.class);
        long splits = jdbc.queryForObject("SELECT COUNT(*) FROM lot_split", Long.class);
        long judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_judgment WHERE lot_id=(SELECT id FROM lot WHERE lot_no='LS')",
                Long.class);

        if ("SPLIT".equals(sourceStatus)) {
            // 分缸先成：分缸 200，来源判色 409。
            assertEquals(200, splitCode, "分缸先成应成功");
            assertEquals(409, judgeCode, "分缸先成后来源判色必须拒绝");
            assertEquals(1L, splits);
            assertEquals(0L, judgments);
        } else {
            // 判色先成：判色 200（PASSED），分缸 409。
            assertEquals("PASSED", sourceStatus, "判色先成来源应为 PASSED，实际 " + sourceStatus);
            assertEquals(200, judgeCode, "判色先成应成功");
            assertEquals(409, splitCode, "判色先成后分缸必须拒绝");
            assertEquals(0L, splits, "败方分缸整单回滚，无子缸谱系");
            assertEquals(1L, judgments);
            assertEquals(Long.valueOf(0),
                    jdbc.queryForObject("SELECT COUNT(*) FROM lot WHERE split_parent_lot_id IS NOT NULL",
                            Long.class));
            // 判色先成，来源缸占用仍在（未分缸不释放）。
            assertEquals(Long.valueOf(1),
                    jdbc.queryForObject("SELECT COUNT(*) FROM vat_active WHERE vat_no='VS'", Long.class));
        }
    }
}
