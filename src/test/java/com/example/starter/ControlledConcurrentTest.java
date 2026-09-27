package com.example.starter;

import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ControlledPlanRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反复潜水积分控制的真实并发测试（H2）：
 * 两组共享同一潜水员并发出受控方案时最多一组成功；同 requestId 并发唯一受理、余者重放。
 */
class ControlledConcurrentTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private void apply(String id, String a, String b) {
        diveService.apply(new com.example.starter.web.dto.ApplyRequest(id, a, b, 12, 30));
    }

    /** 两组 (D001,D002) 与 (D003,D001) 并发受控方案：恰一组 201，另一组 409，槽位互斥且只一版方案。 */
    @Test
    void sharedDiverConcurrentControlledPlans_onlyOneSucceeds() throws Exception {
        apply("GX-1", "D001", "D002");
        apply("GX-2", "D003", "D001");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> submitPlan(start, "GXP-1", "GX-1",
                    Map.of("D001", 1, "D002", 1))));
            futures.add(pool.submit(() -> submitPlan(start, "GXP-2", "GX-2",
                    Map.of("D003", 1, "D001", 1))));
            start.countDown();

            int created = 0;
            int conflict = 0;
            for (Future<Integer> f : futures) {
                int code = f.get(30, TimeUnit.SECONDS);
                if (code == 201) {
                    created++;
                } else if (code == 409) {
                    conflict++;
                }
            }
            assertEquals(1, created, "共享潜水员并发受控方案必须恰一组成功");
            assertEquals(1, conflict, "另一组必须 409");
            // D001 只有一个槽，且只属于胜方潜次
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(1) FROM diver_dive_slot WHERE diver_code='D001'", Integer.class)
                    .intValue());
            // 败方潜次没有任何受控方案/快照/账户写残留
            int controlledPlans = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM controlled_plan", Integer.class).intValue();
            assertEquals(1, controlledPlans);
            int activeAccounts = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM diver_score_account WHERE active_dive_request_id IS NOT NULL",
                    Integer.class).intValue();
            assertEquals(2, activeAccounts, "胜方两名组员账户处于占用态");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 同 requestId、同参数并发受控方案：唯一受理，败方重放首次结果（均 201），只落一版方案。 */
    @Test
    void sameRequestIdConcurrentControlledPlans_singleAcceptOthersReplay() throws Exception {
        apply("SI-1", "D003", "D004");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var out = controlledPlan("SIP-1", "SI-1", Map.of("D003", 1, "D004", 1));
                    // 重放分支 body 为通用 Map（幂等模板原样反序列化），这里只判定状态与重放标记
                    return out.statusCode() + ":" + out.replayed();
                }));
            }
            start.countDown();
            int first = 0;
            int replay = 0;
            for (Future<String> f : futures) {
                String r = f.get(30, TimeUnit.SECONDS);
                if ("201:false".equals(r)) {
                    first++;
                } else if ("201:true".equals(r)) {
                    replay++;
                }
            }
            assertEquals(1, first, "同 requestId 并发必须恰一个首次受理");
            assertEquals(threads - 1, replay, "其余请求重放首次冻结结果");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(1) FROM controlled_plan WHERE dive_request_id='SI-1'",
                    Integer.class).intValue());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(1) FROM idempotency_record WHERE request_id='SIP-1'",
                    Integer.class).intValue());
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(1) FROM diver_dive_slot", Integer.class).intValue());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 旧方案不能消费新出的积分：账户被并发结算推进版本后，成对出水必须整组回滚（两账户均不变）。 */
    @Test
    void concurrentSurfaceSettlement_versionGuarded_atomicRollback() throws Exception {
        apply("CS-1", "D001", "D002");
        controlledPlan("CSP-1", "CS-1", Map.of("D001", 1, "D002", 1));
        pairAll("CS-1", T0.plusSeconds(60), 30);
        Instant surfacedAt = T0.plusSeconds(60L * 33);

        // 在出水事务前并发把 D002 账户版本先推进（模拟另一结算消费了新版本）
        jdbc.update("UPDATE diver_score_account SET version=version+1, residual_score=70 "
                + "WHERE diver_code='D002'");

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () ->
                diveService.surface(new com.example.starter.web.dto.SurfaceRequest(
                        "CSS-1", "CS-1", surfacedAt)));
        assertEquals(409, ex.getStatus());
        // 出水整组回滚：潜次仍停留在成对下潜后的 DIVING、两账户均无本次结算、占用槽未释放
        assertEquals("DIVING", diveRepository.get("CS-1").status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM score_settlement WHERE dive_request_id='CS-1'",
                Integer.class).intValue());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(1) FROM diver_dive_slot WHERE dive_request_id='CS-1'",
                Integer.class).intValue());
        // D001 未被部分结算：版本仍为方案冻结的 1
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM diver_score_account WHERE diver_code='D001'",
                Integer.class).intValue());
    }

    private Integer submitPlan(CountDownLatch start, String requestId, String diveId,
                               Map<String, Integer> versions) {
        try {
            start.await();
            return controlledPlan(requestId, diveId, versions).statusCode();
        } catch (ApiException e) {
            return e.getStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private com.example.starter.service.IdempotencyService.Outcome<
            com.example.starter.web.dto.ControlledPlanResult> controlledPlan(
            String requestId, String diveId, Map<String, Integer> versions) {
        return controlledDiveService.controlledPlan(
                new ControlledPlanRequest(requestId, diveId, 12, 30, T0, versions));
    }

    private void pairAll(String diveId, Instant descentAt, int bottom) throws Exception {
        post(diveId, "D001", "DESCENT", 0, 12, 1, descentAt);
        post(diveId, "D002", "DESCENT", 0, 12, 1, descentAt);
        post(diveId, "D001", "BOTTOM", 12, 12, bottom, descentAt.plusSeconds(60));
        post(diveId, "D002", "BOTTOM", 12, 12, bottom, descentAt.plusSeconds(60));
        Instant ascentAt = descentAt.plusSeconds(60L * (bottom + 1));
        post(diveId, "D001", "ASCENT", 12, 0, 1, ascentAt);
        post(diveId, "D002", "ASCENT", 12, 0, 1, ascentAt);
    }

    private void post(String diveId, String diver, String phase, int from, int to, int duration,
                      Instant at) {
        diveService.record(new com.example.starter.web.dto.RecordRequest(
                "R-" + diveId + "-" + diver + "-" + phase, diveId, diver, phase, from, to,
                duration, at));
    }
}
