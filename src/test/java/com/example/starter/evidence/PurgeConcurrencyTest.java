package com.example.starter.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.example.starter.evidence.api.dto.ConfirmPurgeRequest;
import com.example.starter.evidence.api.dto.CreateHoldRequest;
import com.example.starter.evidence.api.dto.HoldView;
import com.example.starter.evidence.api.dto.MarkPurgeRequest;
import com.example.starter.evidence.api.dto.PurgeBatchView;
import com.example.starter.evidence.api.dto.RegisterEvidenceRequest;
import com.example.starter.evidence.error.ApiException;

/**
 * 清除与保全的并发串行化测试：真实并发执行，断言最终状态满足
 * “保全成功则证据不清除”或“清除先提交则保全整笔失败”，且不允许保全成功且证据已清除。
 */
class PurgeConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant COLLECTED = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant EXPIRED = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-09-23T00:00:00Z");

    private String registerAndMark(String evidenceId, String markRequestId) {
        evidenceService.register(new RegisterEvidenceRequest(evidenceId, digestOf(evidenceId), COLLECTED, EXPIRED));
        return purgeService.mark(new MarkPurgeRequest(markRequestId, List.of(evidenceId), CUTOFF, null)).batchId();
    }

    @SuppressWarnings("unchecked")
    @Test
    void holdAndConfirmRaceSerializesToConsistentOutcome() throws Exception {
        for (int round = 0; round < 5; round++) {
            cleanDatabase();
            String evidenceId = "EV-RACE-" + round;
            String batchId = registerAndMark(evidenceId, "m-race-" + round);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            String holdRequestId = "h-race-" + round;
            String confirmRequestId = "c-race-" + round;
            try {
                Future<Object> holdFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return holdService.create(
                                new CreateHoldRequest(holdRequestId, "CASE-RACE", List.of(evidenceId), null));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> confirmFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return purgeService.confirm(batchId, new ConfirmPurgeRequest(confirmRequestId, 1L));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
                start.countDown();
                Object holdResult = holdFuture.get(20, TimeUnit.SECONDS);
                Object confirmResult = confirmFuture.get(20, TimeUnit.SECONDS);

                PurgeBatchView confirmed = assertInstanceOf(PurgeBatchView.class, confirmResult,
                        "第 " + round + " 轮确认应成功");
                Map<String, Object> item = ((List<Map<String, Object>>) confirmed.manifest().get("items")).get(0);
                String evidenceStatus = evidenceService.get(evidenceId).status();

                if (holdResult instanceof HoldView) {
                    // 保全先提交：证据必须被救回，不得被清除。
                    assertEquals("ACTIVE", evidenceStatus, "第 " + round + " 轮：保全成功但证据非可保留状态");
                    assertEquals("RESCUED", item.get("result"), "第 " + round + " 轮：保全成功但清单未排除该证据");
                } else {
                    // 清除先提交：保全必须整笔失败。
                    ApiException holdError = assertInstanceOf(ApiException.class, holdResult);
                    assertEquals("EVIDENCE_ALREADY_PURGED", holdError.getCode());
                    assertEquals("PURGED", evidenceStatus, "第 " + round + " 轮：保全失败但证据未被清除");
                    assertEquals("PURGED", item.get("result"));
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void concurrentDuplicateConfirmReturnsSameFrozenManifest() throws Exception {
        String evidenceId = "EV-DUP-C";
        String batchId = registerAndMark(evidenceId, "m-dup-c");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Runnable await = () -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<PurgeBatchView> first = executor.submit(() -> {
                await.run();
                return purgeService.confirm(batchId, new ConfirmPurgeRequest("c-dup", 1L));
            });
            Future<PurgeBatchView> second = executor.submit(() -> {
                await.run();
                return purgeService.confirm(batchId, new ConfirmPurgeRequest("c-dup", 1L));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            PurgeBatchView one = first.get(20, TimeUnit.SECONDS);
            PurgeBatchView two = second.get(20, TimeUnit.SECONDS);

            assertEquals(one.manifest(), two.manifest(), "并发重复确认必须返回首次冻结的同一清单");
            assertEquals(2, purgeService.get(batchId).version());
            assertEquals("PURGED", evidenceService.get(evidenceId).status());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM purge_batch WHERE batch_id = ?", Integer.class, batchId).intValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateMarkCreatesSingleBatch() throws Exception {
        String evidenceId = "EV-DUP-M";
        evidenceService.register(new RegisterEvidenceRequest(evidenceId, digestOf(evidenceId), COLLECTED, EXPIRED));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Runnable await = () -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<PurgeBatchView> first = executor.submit(() -> {
                await.run();
                return purgeService.mark(new MarkPurgeRequest("m-dup", List.of(evidenceId), CUTOFF, null));
            });
            Future<PurgeBatchView> second = executor.submit(() -> {
                await.run();
                return purgeService.mark(new MarkPurgeRequest("m-dup", List.of(evidenceId), CUTOFF, null));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            PurgeBatchView one = first.get(20, TimeUnit.SECONDS);
            PurgeBatchView two = second.get(20, TimeUnit.SECONDS);

            assertEquals(one.batchId(), two.batchId(), "并发重复标记必须返回同一批次");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch", Integer.class).intValue());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch_item", Integer.class).intValue());
            assertEquals("MARKED", evidenceService.get(evidenceId).status());
        } finally {
            executor.shutdownNow();
        }
    }
}
