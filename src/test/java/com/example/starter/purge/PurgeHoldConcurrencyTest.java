package com.example.starter.purge;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.LegalHold;
import com.example.starter.domain.PurgeBatch;
import com.example.starter.service.ApiException;
import com.example.starter.service.HoldService;
import com.example.starter.service.PurgeService;
import com.example.starter.web.dto.CreateHoldRequest;
import com.example.starter.web.dto.MarkPurgeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终确认与新保全并发的真实 H2 行锁串行化测试。
 *
 * <p>允许的两种结局：保全先提交则证据救回不清除；清除先提交则保全整笔失败。
 * 任一结局下都不得出现“保全成功且证据已清除”。</p>
 */
class PurgeHoldConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private PurgeService purgeService;

    @Autowired
    private HoldService holdService;

    private ExecutorService pool;

    /** 一轮竞态结果：holdCreated 为 true 时 hold 必 ACTIVE；holdFailed 时必为 409。 */
    private record RaceOutcome(boolean holdCreated, boolean holdFailed, boolean evidencePurged,
                               boolean manifestContains, long holdId) {
    }

    @Test
    void raceHoldFirstGuaranteesEvidenceNotPurged() throws Exception {
        // 交错窗口：保全先获取序列锁提交，最终确认在锁上等待后救回证据。
        for (int round = 0; round < 5; round++) {
            String eid = evidenceId("HF", round);
            RaceOutcome outcome = runRace("HF-" + round, eid, true);
            assertTrue(outcome.holdCreated(), "第 " + round + " 轮：保全应成功");
            assertTrue(!outcome.evidencePurged(), "第 " + round + " 轮：保全成功则证据不得清除");
            assertTrue(!outcome.manifestContains(), "第 " + round + " 轮：被救回证据不得进入清单");
            assertEquals("ACTIVE", holdService.get(outcome.holdId()).status().name());
            assertEquals("RETAINED", evidenceService.get(eid).status().name());
        }
    }

    @Test
    void raceConfirmFirstGuaranteesWholeHoldFails() throws Exception {
        for (int round = 0; round < 5; round++) {
            String eid = evidenceId("CF", round);
            RaceOutcome outcome = runRace("CF-" + round, eid, false);
            assertTrue(outcome.holdFailed(), "第 " + round + " 轮：清除先提交后保全必须整笔失败");
            assertTrue(outcome.evidencePurged(), "第 " + round + " 轮：先提交的清除应已完成");
            assertTrue(outcome.manifestContains(), "第 " + round + " 轮：证据应在冻结清单中");
            assertEquals(0L,
                    jdbc.queryForObject("SELECT COUNT(*) FROM legal_hold", Long.class),
                    "失败保全不得留下任何保全单");
        }
    }

    @Test
    void simultaneousRaceNeverEndsInHeldAndPurged() throws Exception {
        // 双方严格同时起跑，由 H2 行锁裁决顺序，仅校验不变量。
        for (int round = 0; round < 10; round++) {
            String eid = "SIM-" + round;
            register(eid, "body-" + round, T0.plusSeconds(100));
            PurgeBatch batch = purgeService.mark(
                    new MarkPurgeRequest("MK-SIM-" + round, T0.plusSeconds(600), null));
            final long expectedVersion = jdbc.queryForObject(
                    "SELECT hold_version FROM seq_holder WHERE id=0", Long.class);

            CyclicBarrier barrier = new CyclicBarrier(2);
            pool = Executors.newFixedThreadPool(2);
            final int idx = round;
            AtomicReference<Object> holdResult = new AtomicReference<>();
            Future<?>[] futures = new Future<?>[2];
            futures[0] = pool.submit(() -> {
                await(barrier);
                try {
                    LegalHold hold = holdService.create(
                            new CreateHoldRequest("CASE-SIM-" + idx, java.util.Set.of(eid),
                                    "HLD-SIM-" + idx, expectedVersion));
                    holdResult.set(hold);
                } catch (ApiException ex) {
                    holdResult.set(ex);
                }
            });
            futures[1] = pool.submit(() -> {
                await(barrier);
                purgeService.confirm(batch.batchId());
            });
            allDone(futures);

            boolean purged = "PURGED".equals(evidenceService.get(eid).status().name());
            boolean inManifest = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM purge_manifest WHERE batch_id=? AND evidence_id=?",
                    Long.class, batch.batchId(), eid) > 0;
            Object result = holdResult.get();
            if (result instanceof LegalHold hold) {
                assertTrue(!purged && !inManifest, "保全成功与证据清除不得同时发生（第 " + round + " 轮）");
                assertEquals("ACTIVE", hold.status().name());
            } else {
                assertTrue(purged && inManifest, "保全失败时清除必须已提交（第 " + round + " 轮）");
                assertTrue(result instanceof ApiException api && api.getStatus().value() == 409);
            }
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameMarkRequestProducesSingleBatch() throws Exception {
        register("E1", "c1", T0.plusSeconds(100));
        MarkPurgeRequest request = new MarkPurgeRequest("MK-CONCURRENT", T0.plusSeconds(600), null);
        CyclicBarrier barrier = new CyclicBarrier(2);
        pool = Executors.newFixedThreadPool(2);

        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        Future<?>[] futures = new Future<?>[2];
        futures[0] = pool.submit(() -> {
            await(barrier);
            first.set(purgeService.mark(request).batchId());
        });
        futures[1] = pool.submit(() -> {
            await(barrier);
            second.set(purgeService.mark(request).batchId());
        });
        allDone(futures);

        assertEquals(first.get(), second.get(), "同键同参并发标记必须返回同一批次");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Long.class));
        pool.shutdownNow();
    }

    @Test
    void concurrentHoldsForPurgedEvidenceBothFailAfterConfirm() throws Exception {
        register("E1", "c1", T0.plusSeconds(100));
        PurgeBatch batch = purgeService.mark(new MarkPurgeRequest("MK1", T0.plusSeconds(600), null));
        purgeService.confirm(batch.batchId());

        // 清除提交后并发创建两笔保全：都必须整笔失败，不得有任何保全单落库。
        CyclicBarrier barrier = new CyclicBarrier(2);
        pool = Executors.newFixedThreadPool(2);
        AtomicReference<Boolean> f1 = new AtomicReference<>(false);
        AtomicReference<Boolean> f2 = new AtomicReference<>(false);
        Future<?>[] futures = new Future<?>[2];
        futures[0] = pool.submit(() -> {
            await(barrier);
            try {
                holdService.create(new CreateHoldRequest("C1", java.util.Set.of("E1"), "H1", 0L));
            } catch (ApiException ex) {
                f1.set(true);
            }
        });
        futures[1] = pool.submit(() -> {
            await(barrier);
            try {
                holdService.create(new CreateHoldRequest("C2", java.util.Set.of("E1"), "H2", 0L));
            } catch (ApiException ex) {
                f2.set(true);
            }
        });
        allDone(futures);

        assertTrue(f1.get() && f2.get());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM legal_hold", Long.class));
        pool.shutdownNow();
    }

    // ---- 内部辅助 ----

    private String evidenceId(String prefix, int round) {
        return "E-RACE-" + prefix + "-" + round;
    }

    /**
     * 执行一轮竞态。holdFirst=true 时保全线程先启动并抢先获取序列锁，否则确认线程抢先。
     */
    private RaceOutcome runRace(String tag, String eid, boolean holdFirst) throws Exception {
        register(eid, "body-" + tag, T0.plusSeconds(100));
        PurgeBatch batch = purgeService.mark(
                new MarkPurgeRequest("MK-" + tag, T0.plusSeconds(600), null));
        long expectedVersion = jdbc.queryForObject(
                "SELECT hold_version FROM seq_holder WHERE id=0", Long.class);

        pool = Executors.newFixedThreadPool(2);
        AtomicReference<Object> holdResult = new AtomicReference<>();

        Runnable holdTask = () -> {
            try {
                LegalHold hold = holdService.create(
                        new CreateHoldRequest("CASE-" + tag, java.util.Set.of(eid),
                                "HLD-" + tag, expectedVersion));
                holdResult.set(hold);
            } catch (ApiException ex) {
                holdResult.set(ex);
            }
        };
        Runnable confirmTask = () -> purgeService.confirm(batch.batchId());

        Future<?> firstFuture;
        Future<?> secondFuture;
        if (holdFirst) {
            firstFuture = pool.submit(holdTask);
            Thread.sleep(150);
            secondFuture = pool.submit(confirmTask);
        } else {
            firstFuture = pool.submit(confirmTask);
            Thread.sleep(150);
            secondFuture = pool.submit(holdTask);
        }
        firstFuture.get(15, TimeUnit.SECONDS);
        secondFuture.get(15, TimeUnit.SECONDS);

        boolean purged = "PURGED".equals(evidenceService.get(eid).status().name());
        boolean inManifest = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purge_manifest WHERE batch_id=? AND evidence_id=?",
                Long.class, batch.batchId(), eid) > 0;
        Object result = holdResult.get();
        boolean created = result instanceof LegalHold;
        long holdId = created ? ((LegalHold) result).holdId() : -1L;
        pool.shutdownNow();
        return new RaceOutcome(created, result instanceof ApiException, purged, inManifest, holdId);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void allDone(Future<?>[] futures) throws Exception {
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
    }
}
