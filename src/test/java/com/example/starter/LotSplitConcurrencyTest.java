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
 * 分缸并发/竞争边界（真实多线程同事务竞争，结果与最终数据双断言）：
 * 1) 同源并发分缸最多一次成功；
 * 2) 来源判色与分缸竞争只有一方成功；
 * 3) 同 requestId 同参并发分缸只生效一次（其余重放）。
 */
class LotSplitConcurrencyTest extends BaseIntegrationTest {

    private void scheduleAndFeed100(String lotNo, String vatNo) {
        schedule("sch-" + lotNo, lotNo, vatNo, "R-BASE", 1, "100");
        postOk("/api/feedings", Map.of(
                "requestId", "feed-" + lotNo,
                "lotNo", lotNo,
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("2000.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("1500.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("500.0000")))));
    }

    private Map<String, Object> splitRequest(String requestId, String sourceLotNo,
                                             String childA, String vatA, String childB, String vatB) {
        return Map.of(
                "requestId", requestId,
                "sourceLotNo", sourceLotNo,
                "expectedVersion", 1,
                "children", List.of(
                        Map.of("lotNo", childA, "vatNo", vatA, "fabricWeightKg", new BigDecimal("40")),
                        Map.of("lotNo", childB, "vatNo", vatB, "fabricWeightKg", new BigDecimal("60"))));
    }

    @Test
    void concurrentSplit_sameSource_exactlyOneSucceeds() throws Exception {
        scheduleAndFeed100("LC1", "VC1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                // 两个请求子缸号与目标缸完全错开，仅同源竞争
                ResponseEntity<Map> resp = post("/api/lots/split", splitRequest(
                        "csp-" + idx, "LC1", "LC1-A" + idx, "VCA" + idx, "LC1-B" + idx, "VCB" + idx));
                return resp.getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int created = 0;
        int conflict = 0;
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

        assertEquals(1, created, "两次拆同源最多一次成功");
        assertEquals(1, conflict);
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split s JOIN lot l ON l.id=s.source_lot_id "
                        + "WHERE l.lot_no='LC1'", Long.class));
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LC1'", String.class));
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE lot_no LIKE 'LC1-%'", Long.class),
                "败方整单回滚，只残留胜方两个子缸");
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM vat_active WHERE vat_no LIKE 'VC%'", Long.class),
                "只有胜方目标占用，原缸已释放");
        // 耗用守恒：仅胜方一套分摊，合计=来源实际耗用
        assertEquals(0, new BigDecimal("2000.0000").compareTo(jdbc.queryForObject(
                "SELECT SUM(allocated_g) FROM lot_split_usage WHERE dye_code='D-RED'",
                BigDecimal.class)));
    }

    @Test
    void concurrentSplitVsJudge_exactlyOneWins() throws Exception {
        scheduleAndFeed100("LC2", "VC2");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        results.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return post("/api/lots/split", splitRequest(
                    "cspj-split", "LC2", "LC2-A", "VJA", "LC2-B", "VJB")).getStatusCode().value();
        }));
        results.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return post("/api/judgments", Map.of(
                    "requestId", "cspj-judge", "lotNo", "LC2",
                    "deltaE", new BigDecimal("0.30"))).getStatusCode().value();
        }));

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 200 || code == 201) {
                success++;
            } else if (code == 409) {
                conflict++;
            } else {
                throw new AssertionError("意外状态码: " + code);
            }
        }
        pool.shutdown();

        assertEquals(1, success, "判色与分缸竞争只能一方成功");
        assertEquals(1, conflict);
        String status = jdbc.queryForObject("SELECT status FROM lot WHERE lot_no='LC2'", String.class);
        long splits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split s JOIN lot l ON l.id=s.source_lot_id "
                        + "WHERE l.lot_no='LC2'", Long.class);
        long judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_judgment j JOIN lot l ON l.id=j.lot_id "
                        + "WHERE l.lot_no='LC2'", Long.class);
        if ("SPLIT".equals(status)) {
            assertEquals(1, splits, "分缸先成：分缸单落库");
            assertEquals(0, judgments, "分缸先成：判色整单回滚无判定记录");
            assertEquals(Long.valueOf(2), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lot WHERE lot_no LIKE 'LC2-%'", Long.class));
        } else {
            assertEquals("PASSED", status, "判色先成：来源转合格");
            assertEquals(0, splits, "判色先成：分缸整单回滚无分缸单");
            assertEquals(1, judgments);
            assertEquals(Long.valueOf(0), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lot WHERE lot_no LIKE 'LC2-%'", Long.class),
                    "判色先成：不得残留子缸");
        }
    }

    @Test
    void concurrentSplit_sameRequestSameParams_appliedExactlyOnce() throws Exception {
        scheduleAndFeed100("LC3", "VC3");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return post("/api/lots/split", splitRequest(
                        "csp-same", "LC3", "LC3-A", "V3A", "LC3-B", "V3B")).getStatusCode().value();
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发：首次与重放均返回 201");
        }
        pool.shutdown();

        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split WHERE request_id='csp-same'", Long.class));
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE lot_no LIKE 'LC3-%'", Long.class), "子缸只建一次");
        assertEquals(Long.valueOf(6), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_split_usage", Long.class), "分摊记录只写一套（2 子缸 × 3 染料）");
        assertEquals(0, new BigDecimal("2000.0000").compareTo(jdbc.queryForObject(
                "SELECT SUM(allocated_g) FROM lot_split_usage WHERE dye_code='D-RED'",
                BigDecimal.class)), "并发重放不得重复分摊");
    }
}
