package com.example.starter.legality.service;

import com.example.starter.legality.AbstractH2IntegrationTest;
import com.example.starter.legality.api.dto.CheckRequest;
import com.example.starter.legality.api.dto.CheckResponse;
import com.example.starter.legality.api.dto.MemberDutyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一成员同一时段并发值勤提交的唯一受理与幂等测试：
 * 数据库唯一索引 + 事务重试保证恰好一条受理，重复提交按幂等返回首次判定。
 */
class LegalityConcurrencyTest extends AbstractH2IntegrationTest {

    @Autowired
    private LegalityService service;

    @Test
    void concurrentSameSlotDifferentRequestIds_onlyOneAccepted() throws Exception {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CheckResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    MemberDutyRequest d = new MemberDutyRequest(
                            "r-concur-" + idx, "C001", "UT001",
                            Instant.parse("2026-10-01T06:00:00Z"),
                            Instant.parse("2026-10-01T16:00:00Z"));
                    return service.check(new CheckRequest(List.of(d)));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            String winningBatchId = null;
            int newCount = 0;
            int replayCount = 0;
            for (Future<CheckResponse> f : futures) {
                CheckResponse resp = f.get(30, TimeUnit.SECONDS);
                assertNotNull(resp);
                assertEquals("LEGAL", resp.overallResult());
                if (winningBatchId == null) {
                    winningBatchId = resp.batchId();
                }
                assertEquals(winningBatchId, resp.batchId());
                if (resp.results().get(0).idempotentReplay()) {
                    replayCount++;
                } else {
                    newCount++;
                }
            }
            assertEquals(1, newCount, "恰好一条新受理");
            assertEquals(threads - 1, replayCount, "其余全部按幂等重放处理");

            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM duty_record", Integer.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM check_batch", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestId_sameOutcomeReturnedToAll() throws Exception {
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CheckResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    MemberDutyRequest d = new MemberDutyRequest(
                            "r-concur-same-key", "C002", "UT001",
                            Instant.parse("2026-10-02T06:00:00Z"),
                            Instant.parse("2026-10-02T16:00:00Z"));
                    return service.check(new CheckRequest(List.of(d)));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            for (Future<CheckResponse> f : futures) {
                CheckResponse resp = f.get(30, TimeUnit.SECONDS);
                assertEquals("LEGAL", resp.overallResult());
            }
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM duty_record WHERE request_id = 'r-concur-same-key'",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
