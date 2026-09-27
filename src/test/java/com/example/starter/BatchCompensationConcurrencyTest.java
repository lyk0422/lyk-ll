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
 * 受控投料并发/幂等边界（真实多线程 + CountDownLatch 同时发起）：
 * 1) 两个受控缸争同一批次余量（恰够一单）：只成功一单，不超耗、无部分扣减；
 * 2) 同一 requestId 并发同参受控投料：只生效一次，其余重放，批次余量只扣一次；
 * 3) 受控投料与批次系数修订并发：未投料批准要么按旧版本成功、要么整单回滚，不出现超耗/半扣。
 */
class BatchCompensationConcurrencyTest extends BaseIntegrationTest {

    private List<Map<String, Object>> redYellowBlue(String red, String yellow, String blue) {
        return List.of(
                Map.of("dyeCode", "D-RED", "actualG", new BigDecimal(red)),
                Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal(yellow)),
                Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal(blue)));
    }

    @Test
    void twoControlledLots_contendSameLimitedBatch_exactlyOneSucceedsNoOverConsumption() throws Exception {
        // 两个 50kg 受控缸，RED 各需补偿目标 1000g；批次余量恰为 1000g。
        jdbc.update("UPDATE dye_batch SET available_g = 1000 WHERE batch_no = 'B-RED-001' AND factor_version = 1");
        scheduleControlled("cc-s1", "LN1", "VN1", "R-BASE", 1, "50");
        scheduleControlled("cc-s2", "LN2", "VN2", "R-BASE", 1, "50");
        approveSeedCompensation("cc-a1", "LN1");
        approveSeedCompensation("cc-a2", "LN2");

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
                        "requestId", "cc-f" + idx, "lotNo", "LN" + idx, "expectedVersion", 1,
                        "weighings", redYellowBlue("1000", "750", "250")));
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

        assertEquals(1, ok, "同批余量恰够一单：仅一缸投料成功");
        assertEquals(1, rejected, "另一缸因余量不足/并发冲突整单回滚");
        assertEquals(new BigDecimal("0.000"), batchAvailable("B-RED-001"), "批次不得超耗");
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='FED'", Long.class));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE status='CREATED'", Long.class), "败缸保持待投料");
        // 败缸补偿批准仍 FROZEN，未被标记 FED
        assertEquals("FROZEN", jdbc.queryForObject(
                "SELECT c.status FROM compensation c JOIN lot l ON l.id=c.lot_id "
                        + "WHERE l.status='CREATED'", String.class));
    }

    @Test
    void sameRequestConcurrentControlledFeed_appliedOnce_batchDeductedOnce() throws Exception {
        scheduleControlled("cs-s", "LS", "VS", "R-BASE", 1, "50");
        approveSeedCompensation("cs-a", "LS");
        BigDecimal redBefore = batchAvailable("B-RED-001");
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<Map> resp = post("/api/feedings", Map.of(
                        "requestId", "cs-f-same", "lotNo", "LS", "expectedVersion", 1,
                        "weighings", redYellowBlue("1000", "750", "250")));
                return resp.getStatusCode().value();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS), "同键同参并发：首次与重放均 200");
        }
        pool.shutdown();

        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding WHERE request_id='cs-f-same'", Long.class));
        assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(batchAvailable("B-RED-001")),
                "并发重放不得重复扣批次余量");
        assertEquals("FED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LS'", String.class));
        // 补偿批准 FED 且版本只加一次（1 → 2）
        assertEquals(2, ((Number) compensationRow("LS").get("version")).intValue());
    }

    @Test
    void controlledFeedConcurrentWithFactorRevision_eitherFedOrRejectedNoPartialDeduction() throws Exception {
        scheduleControlled("cr-s", "LR", "VR", "R-BASE", 1, "50");
        approveSeedCompensation("cr-a", "LR");
        BigDecimal redBefore = batchAvailable("B-RED-001");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        // 线程 1：按冻结 v1 投料
        results.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return post("/api/feedings", Map.of(
                    "requestId", "cr-f", "lotNo", "LR", "expectedVersion", 1,
                    "weighings", redYellowBlue("1000", "750", "250"))).getStatusCode().value();
        }));
        // 线程 2：并发把系数修订为 1.05（应使未投料批准失效；若投料已先提交则历史不追改）
        results.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return post("/api/batches/B-RED-001/revise", Map.of(
                    "requestId", "cr-r", "batchNo", "B-RED-001",
                    "potencyFactor", new BigDecimal("1.05"),
                    "availableG", new BigDecimal("100000"),
                    "expireAt", "2030-12-31T00:00:00+08:00",
                    "expectedVersion", 1)).getStatusCode().value();
        }));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int feedCode = results.get(0).get(30, TimeUnit.SECONDS);
        int reviseCode = results.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(feedCode == 200 || feedCode == 409 || feedCode == 422,
                "投料结果只能是成功或整单拒绝: " + feedCode);
        assertEquals(200, reviseCode, "系数修订本身应成功提交");

        String lotStatus = jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LR'", String.class);
        String compStatus = compensationRow("LR").get("status").toString();
        if (feedCode == 200) {
            // 投料先提交：缸次 FED、补偿 FED 历史，v1 批次余量扣 1000（修订另插入 active=FALSE 的 v2 行），历史不追改
            assertEquals("FED", lotStatus);
            assertEquals("FED", compStatus);
            BigDecimal v1Available = jdbc.queryForObject(
                    "SELECT available_g FROM dye_batch WHERE batch_no='B-RED-001' AND factor_version=1",
                    BigDecimal.class);
            assertEquals(0, redBefore.subtract(new BigDecimal("1000")).compareTo(v1Available));
        } else {
            // 修订先生效：缸次仍 CREATED、补偿 INVALID，批次余量不扣
            assertEquals("CREATED", lotStatus);
            assertEquals("INVALID", compStatus);
            BigDecimal v1Available = jdbc.queryForObject(
                    "SELECT available_g FROM dye_batch WHERE batch_no='B-RED-001' AND factor_version=1",
                    BigDecimal.class);
            assertEquals(0, redBefore.compareTo(v1Available), "失败投料不得扣余量");
        }
        // 无论先后：批次当前系数版本为 v2、v1 已失效
        assertEquals(2, jdbc.queryForObject(
                "SELECT factor_version FROM dye_batch WHERE batch_no='B-RED-001' AND active=TRUE",
                Integer.class).intValue());
    }

    @Test
    void controlledMerge_identicalTarget_producesConsistentReFrozenTargetsAndFeed() {
        // 非并发的端到端一致性：两缸同补偿并缸后目标翻倍，投料后重算报告受控称量一致。
        scheduleControlled("me-s1", "M1", "VM1", "R-BASE", 1, "50");
        scheduleControlled("me-s2", "M2", "VM2", "R-BASE", 1, "50");
        approveSeedCompensation("me-a1", "M1");
        approveSeedCompensation("me-a2", "M2");
        postOk("/api/lots/merge", Map.of(
                "requestId", "me-m", "lotNo", "M1", "targetLotNo", "M2", "expectedVersion", 1));
        postOk("/api/feedings", Map.of(
                "requestId", "me-f", "lotNo", "M2", "expectedVersion", 2,
                "weighings", redYellowBlue("2000", "1500", "500")));

        // 并缸后目标缸补偿重冻为版本 2，投料 FROZEN→FED 再加 1，最终版本 3；RED 补偿目标 2000g
        assertEquals(3, ((Number) compensationRow("M2").get("version")).intValue());
        assertEquals("FED", compensationRow("M2").get("status"));
        assertEquals(0, new BigDecimal("2000.000").compareTo(jdbc.queryForObject(
                "SELECT ci.compensated_target_g FROM compensation_item ci "
                        + "JOIN compensation c ON c.id=ci.compensation_id JOIN lot l ON l.id=c.lot_id "
                        + "WHERE l.lot_no='M2' AND ci.dye_code='D-RED'", BigDecimal.class)));

        Map<String, Object> report = rest.getForObject(
                url("/api/recipes/R-BASE/recalculate"), Map.class);
        assertEquals(true, report.get("consistent"));
        assertEquals(0, ((List<?>) report.get("weighingMismatches")).size(),
                "受控缸称量按冻结补偿目标重算应一致");
    }
}
