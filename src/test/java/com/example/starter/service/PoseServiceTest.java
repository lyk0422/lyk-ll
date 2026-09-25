package com.example.starter.service;

import com.example.starter.domain.BreachStatus;
import com.example.starter.domain.ZoneAuthorization;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位姿 ingest 与越限处置测试：去重、乱序归位、3 秒制动、制动幂等、
 * 恰结束时刻口径、释放后合法作业不判罚、释放与位姿并发唯一结论。
 */
class PoseServiceTest extends BaseIntegrationTest {

    private static final Instant T0 = TestClockConfig.BASE.plusSeconds(10 * 3600);

    @Autowired
    private PoseService poseService;

    @Autowired
    private AuthorizationService authorizationService;

    /** ZONE-1 圆心 (0,0) 半径 10m，点 (1,1) 在区内，(100,100) 在所有区外。 */
    private PoseService.PoseIngestResult poseInZone(String craneId, Instant ts) {
        return poseService.ingest(craneId, ts, 1.0, 1.0);
    }

    @Test
    void unauthorizedEntryRaisesBreachAndDuplicatePoseNotReprocessed() {
        var first = poseInZone("CRANE-A", T0);
        assertFalse(first.duplicate());
        assertEquals(1, first.breachEvents().size());
        assertEquals(BreachStatus.OPEN, first.breachEvents().get(0).status());

        // 同（塔机，时刻）重复上报：去重，不重复触发预警
        var dup = poseInZone("CRANE-A", T0);
        assertTrue(dup.duplicate());
        assertTrue(dup.breachEvents().isEmpty());
        assertTrue(dup.brakeCommands().isEmpty());
        assertEquals(1, count("pose_reading"));
        assertEquals(1, count("breach_event"));
    }

    @Test
    void outOfOrderReadingsRepositionedByTimestampAndTriggerBrake() {
        // 先到达较晚读数（T+3s），再乱序到达较早读数（T）：按时间戳归位后持续 3 秒，触发制动
        var late = poseInZone("CRANE-A", T0.plusSeconds(3));
        assertEquals(1, late.breachEvents().size());
        assertTrue(late.brakeCommands().isEmpty());

        var early = poseInZone("CRANE-A", T0);
        assertEquals(1, early.brakeCommands().size());
        var breach = early.breachEvents().get(0);
        assertEquals(T0, breach.firstDetectedAtUtc());
        assertEquals(T0.plusSeconds(3), breach.lastDetectedAtUtc());
        assertEquals(BreachStatus.BRAKED, breach.status());
        assertEquals(1, count("brake_command"));
    }

    @Test
    void brakeIssuedOnlyAfterThreeSecondsSustained() {
        assertTrue(poseInZone("CRANE-A", T0).brakeCommands().isEmpty());
        assertTrue(poseInZone("CRANE-A", T0.plusSeconds(1)).brakeCommands().isEmpty());
        assertTrue(poseInZone("CRANE-A", T0.plusSeconds(2)).brakeCommands().isEmpty());
        var at3 = poseInZone("CRANE-A", T0.plusSeconds(3));
        assertEquals(1, at3.brakeCommands().size());
        assertEquals(1, count("brake_command"));
    }

    @Test
    void exitBeforeThreeSecondsResolvesBreachWithoutBrake() {
        poseInZone("CRANE-A", T0);
        poseInZone("CRANE-A", T0.plusSeconds(1));
        // T+2s 吊钩已退出全部重叠区
        var outside = poseService.ingest("CRANE-A", T0.plusSeconds(2), 100.0, 100.0);
        assertTrue(outside.brakeCommands().isEmpty());
        assertEquals(0, count("brake_command"));
        String status = jdbc.queryForObject("SELECT status FROM breach_event", String.class);
        assertEquals("RESOLVED", status);
    }

    @Test
    void brakeCommandIssuedOncePerBreachEvent() {
        poseInZone("CRANE-A", T0);
        var braked = poseInZone("CRANE-A", T0.plusSeconds(3));
        long commandId = braked.brakeCommands().get(0).id();
        // 制动后继续越限：重复触发返回首次指令语义，不产生新指令
        var more = poseInZone("CRANE-A", T0.plusSeconds(4));
        assertTrue(more.brakeCommands().isEmpty());
        poseInZone("CRANE-A", T0.plusSeconds(5));
        assertEquals(1, count("brake_command"));
        Long stored = jdbc.queryForObject("SELECT id FROM brake_command", Long.class);
        assertEquals(commandId, stored);
    }

    @Test
    void poseExactlyAtAuthorizationEndTreatedAsAuthorized() {
        authorizationService.request("ZONE-1", "req-1", "CRANE-A", T0, T0.plusSeconds(3600), new BigDecimal("5"));
        // 恰结束时刻：按授权内处理
        var atEnd = poseInZone("CRANE-A", T0.plusSeconds(3600));
        assertTrue(atEnd.breachEvents().isEmpty());
        assertEquals(0, count("breach_event"));
        // 结束时刻之后：越限
        var after = poseInZone("CRANE-A", T0.plusSeconds(3601));
        assertEquals(1, after.breachEvents().size());
    }

    @Test
    void releasedAuthorizationDoesNotPenalizeFinishedJob() {
        var auth = authorizationService.request("ZONE-1", "req-1", "CRANE-A",
                T0, T0.plusSeconds(3600), new BigDecimal("5"));
        // 作业于 T+1800 完成并释放
        ((MutableClock) clock).set(T0.plusSeconds(1800));
        authorizationService.release(auth.authorization().id(), AuthorizationService.ReleaseAction.COMPLETE);

        // 迟到读数：作业执行期间（释放前）的合法位姿不得判罚
        var lateLegal = poseInZone("CRANE-A", T0.plusSeconds(1000));
        assertTrue(lateLegal.breachEvents().isEmpty());
        assertEquals(0, count("breach_event"));

        // 释放之后的读数：未授权越限
        var afterRelease = poseInZone("CRANE-A", T0.plusSeconds(1801));
        assertEquals(1, afterRelease.breachEvents().size());
    }

    @Test
    void concurrentReleaseAndPoseYieldSingleConsistentConclusion() throws Exception {
        int rounds = 5;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                var auth = authorizationService.request("ZONE-1", "req-r" + i, "CRANE-A",
                        T0, T0.plusSeconds(3600), new BigDecimal("5"));
                long authId = auth.authorization().id();
                ((MutableClock) clock).set(T0.plusSeconds(1800));
                Instant poseTs = T0.plusSeconds(1000 + i); // 释放前的合法作业读数

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                var releaseFuture = pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    authorizationService.release(authId, AuthorizationService.ReleaseAction.COMPLETE);
                    return null;
                });
                var poseFuture = pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    return poseInZone("CRANE-A", poseTs);
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                go.countDown();
                releaseFuture.get(30, TimeUnit.SECONDS);
                var result = poseFuture.get(30, TimeUnit.SECONDS);
                assertFalse(result.duplicate());
                assertTrue(result.breachEvents().isEmpty(),
                        "释放前的合法作业读数不得因并发释放被判越限（第 " + i + " 轮）");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, count("breach_event"));
        assertEquals(rounds, count("pose_reading"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM zone_authorization");
        assertEquals(rounds, rows.size());
        rows.forEach(r -> assertEquals("COMPLETED", r.get("STATUS") == null ? r.get("status") : r.get("STATUS")));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
