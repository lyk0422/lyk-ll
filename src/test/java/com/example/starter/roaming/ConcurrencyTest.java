package com.example.starter.roaming;

import com.example.starter.roaming.api.AuthorizeRequest;
import com.example.starter.roaming.api.AuthorizeResponse;
import com.example.starter.roaming.api.EndSessionRequest;
import com.example.starter.roaming.api.MeterSnapshotRequest;
import com.example.starter.roaming.api.SealBatchRequest;
import com.example.starter.roaming.api.SettlementBatchResponse;
import com.example.starter.roaming.api.StartSessionRequest;
import com.example.starter.roaming.error.ApiException;
import com.example.starter.roaming.service.AuthorizationService;
import com.example.starter.roaming.service.ChargingSessionService;
import com.example.starter.roaming.service.SettlementService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实线程并发测试（H2 事务边界，非 mock）：
 * <ol>
 *   <li>授权并发：同车同刻仅一个会话成功，竞争方返回同一会话；</li>
 *   <li>直启并发：同车同刻唯一会话，其余 409；</li>
 *   <li>会话结束与封存并发：会话要么计入本批、要么留待本批之外，绝不重复或游离。</li>
 * </ol>
 */
class ConcurrencyTest extends AbstractRoamingTest {

    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private ChargingSessionService sessionService;
    @Autowired
    private SettlementService settlementService;

    @Test
    void concurrentAuthorizationsForSameVehicleAndTimeProduceSingleSession() throws Exception {
        int threads = 8;
        Instant t = clock.instant();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<AuthorizeResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    fire.await();
                    return authorizationService.authorize(
                            new AuthorizeRequest("AUTH-" + idx, "FP-CAR-0001", "PILE-B-001", t));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            fire.countDown();

            String sessionNo = null;
            for (Future<AuthorizeResponse> f : futures) {
                AuthorizeResponse resp = f.get(10, TimeUnit.SECONDS);
                if (sessionNo == null) {
                    sessionNo = resp.sessionNo();
                }
                assertEquals(sessionNo, resp.sessionNo());
            }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDirectStartsForSameVehicleAndTimeLeaveOneWinner() throws Exception {
        int threads = 8;
        Instant t = clock.instant();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    fire.await();
                    sessionService.start(new StartSessionRequest(
                            "START-" + idx, null, "FP-CAR-0002", "PILE-A-001", t));
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            fire.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    assertTrue(cause instanceof ApiException && ((ApiException) cause).getStatus()
                            == HttpStatus.CONFLICT, "期望唯一会话竞争 409，实际：" + cause);
                    conflict++;
                }
            }
            assertEquals(1, success);
            assertEquals(threads - 1, conflict);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void endAndSealRaceAssignsSessionToExactlyOneOutcome() throws Exception {
        // 多轮竞态提高两种先后顺序都被覆盖的概率。
        for (int round = 0; round < 8; round++) {
            singleEndSealRaceRound(round);
        }
    }

    private void singleEndSealRaceRound(int round) throws Exception {
        // 每轮使用独立 UTC 日，避免前一轮已封存批次干扰本轮竞态。
        Instant t = com.example.starter.roaming.testsupport.TestClockConfig.BASE.plus(Duration.ofDays(round));
        clock.setInstant(t);
        AuthorizeResponse auth = authorizationService.authorize(
                new AuthorizeRequest("RA-" + round, "FP-CAR-0001", "PILE-B-001", t));
        String sessionNo = auth.sessionNo();
        sessionService.start(new StartSessionRequest("RS-" + round, sessionNo, null, null, null));
        sessionService.meter(sessionNo, new MeterSnapshotRequest(
                "RM-" + round, 0, new BigDecimal("8.000"), t.plusSeconds(20)));

        LocalDate day = LocalDate.ofInstant(t, java.time.ZoneOffset.UTC);
        Instant endTime = t.plus(Duration.ofMinutes(15));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> endFuture = pool.submit(() -> {
                ready.countDown();
                awaitQuietly(fire);
                sessionService.end(sessionNo, new EndSessionRequest("RE-" + round, endTime));
                return null;
            });
            Future<SettlementBatchResponse> sealFuture = pool.submit(() -> {
                ready.countDown();
                awaitQuietly(fire);
                return settlementService.seal(new SealBatchRequest("RB-" + round, day));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            fire.countDown();

            boolean endedInBatch = false;
            boolean endRejected = false;
            try {
                endFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable cause = e.getCause();
                assertTrue(cause instanceof ApiException && ((ApiException) cause).getStatus()
                        == HttpStatus.CONFLICT, "期望封存竞争 409，实际：" + cause);
                endRejected = true;
            }
            SettlementBatchResponse batch = sealFuture.get(10, TimeUnit.SECONDS);

            String status = jdbc.queryForObject(
                    "SELECT status FROM charging_session WHERE session_no = ?", String.class, sessionNo);
            Long batchId = jdbc.queryForObject(
                    "SELECT batch_id FROM charging_session WHERE session_no = ?", Long.class, sessionNo);
            Integer details = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM settlement_detail WHERE session_no = ?", Integer.class, sessionNo);

            if (endRejected) {
                // 封存先提交：结束回滚，会话未结束、未计入本批，且没有明细。
                assertEquals("CHARGING", status, "第" + round + "轮");
                assertEquals(null, batchId, "第" + round + "轮");
                assertEquals(0, details, "第" + round + "轮");
                assertEquals(0, batch.sessionCount(), "第" + round + "轮");
            } else {
                // 结束先提交：会话已结束、唯一归属本批，且恰好一条明细。
                endedInBatch = true;
                assertEquals("ENDED", status, "第" + round + "轮");
                assertEquals(batch.batchId(), batchId, "第" + round + "轮");
                assertEquals(1, details, "第" + round + "轮");
                assertEquals(1, batch.sessionCount(), "第" + round + "轮");
            }
            // 不变量：明细数恰为 0 或 1，绝不重复；ENDED 会话必有批次。
            assertTrue(details == 0 || details == 1);
            if ("ENDED".equals(status)) {
                assertTrue(batchId != null);
            }
            assertTrue(endedInBatch || endRejected);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
