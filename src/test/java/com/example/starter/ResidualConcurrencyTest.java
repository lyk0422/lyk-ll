package com.example.starter;

import com.example.starter.service.DiveService;
import com.example.starter.service.ResidualService;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.RecordRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 真实并发测试：两组共享同一潜水员 D001 并发完成受控下潜成对时，
 * 占用槽唯一索引保证最多一组成功，败方整笔成对事务回滚。
 */
class ResidualConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void twoTeamsSharingOneDiver_concurrentDescent_onlyOneGroupSucceeds() throws Exception {
        // 组X：D001+D002；组Y：D001+D003，分别申请并出受控方案（首潜，积分0，不同 team_key）
        preparePlannedControlled("X", "D001", "D002");
        preparePlannedControlled("Y", "D001", "D003");

        // 两组各自先提交非共享组员的单人下潜记录（已提交、不成对）
        diveService.record(new RecordRequest("X-SOLO", "X", "D002", "DESCENT", 0, 18, 1, T0));
        diveService.record(new RecordRequest("Y-SOLO", "Y", "D003", "DESCENT", 0, 18, 1, T0));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> pairD001("X", "X-PAIR", start)));
            futures.add(pool.submit(() -> pairD001("Y", "Y-PAIR", start)));
            start.countDown();

            int ok = 0;
            int conflict = 0;
            for (Future<Integer> f : futures) {
                int code = f.get(30, TimeUnit.SECONDS);
                if (code == 200) {
                    ok++;
                } else if (code == 409) {
                    conflict++;
                }
            }
            assertEquals(1, ok, "共享潜水员并发下潜必须恰一组成对成功");
            assertEquals(1, conflict, "败方必须 409（占用槽唯一受理）");

            // 最终只有胜方占用两名潜水员（2 个槽），败方未留下占用
            assertEquals(2,
                    jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot", Integer.class)
                            .intValue());
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot WHERE diver_code='D001'",
                            Integer.class).intValue());
            // 恰一个潜次进入 DIVING，另一个保持 PLANNED
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM dive WHERE status='DIVING'",
                            Integer.class).intValue());
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM dive WHERE status='PLANNED'",
                            Integer.class).intValue());
            // D001 只有一条生效下潜记录（败方记录随事务回滚）
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM dive_record WHERE diver_code='D001' "
                            + "AND phase='DESCENT' AND effective=TRUE AND frozen=FALSE",
                            Integer.class).intValue());
            // 败方未消费任何积分账户（尚未出水），且没有半成对的占用
            assertEquals(0,
                    jdbc.queryForObject("SELECT COUNT(1) FROM residual_point_account",
                            Integer.class).intValue());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 胜方后续受控出水成功；败方重做方案前不能复用旧冻结直接下潜（其潜次仍 PLANNED）。 */
    @Test
    void loserRemainsPlanned_andCanReattemptAfterWinnerSurfaces() throws Exception {
        preparePlannedControlled("X2", "D001", "D002");
        preparePlannedControlled("Y2", "D001", "D003");
        diveService.record(new RecordRequest("X2-SOLO", "X2", "D002", "DESCENT", 0, 18, 1, T0));
        diveService.record(new RecordRequest("Y2-SOLO", "Y2", "D003", "DESCENT", 0, 18, 1, T0));

        // 顺序复现竞争：X2 先成对占用 D001
        diveService.record(new RecordRequest("X2-PAIR", "X2", "D001", "DESCENT", 0, 18, 1, T0));
        // Y2 在 X2 未出水前成对 → 409，记录回滚
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> diveService.record(new RecordRequest("Y2-PAIR", "Y2", "D001",
                        "DESCENT", 0, 18, 1, T0)));
        assertEquals(409, ex.getStatus());
        assertEquals("PLANNED", diveRepository.get("Y2").status());

        // X2 完成水底/上升并受控出水后释放占用
        diveService.record(new RecordRequest("X2-B1", "X2", "D001", "BOTTOM", 18, 18, 40,
                T0.plusSeconds(60)));
        diveService.record(new RecordRequest("X2-B2", "X2", "D002", "BOTTOM", 18, 18, 40,
                T0.plusSeconds(60)));
        diveService.record(new RecordRequest("X2-A1", "X2", "D001", "ASCENT", 18, 0, 1,
                T0.plusSeconds(60 * 41)));
        diveService.record(new RecordRequest("X2-A2", "X2", "D002", "ASCENT", 18, 0, 1,
                T0.plusSeconds(60 * 41)));
        residualService.controlledSurface(new com.example.starter.web.dto.ControlledSurfaceRequest(
                "X2-S", "X2", 1, T0.plusSeconds(60 * 42)));
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot", Integer.class)
                        .intValue());

        // Y2 距 D001 出水不足 60 分钟即重做下潜：其旧方案冻结于首潜（无来源），
        // 而 D001 账户已存在 → 来源已变，成对被拒 409，须重做方案
        ApiException stale = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> diveService.record(new RecordRequest("Y2-PAIR2", "Y2", "D001",
                        "DESCENT", 0, 18, 1, T0.plusSeconds(60 * 43))));
        assertEquals(409, stale.getStatus());
    }

    private void preparePlannedControlled(String diveId, String a, String b) {
        diveService.apply(new ApplyRequest(diveId, a, b, 18, 40));
        residualService.controlledPlan(new ControlledPlanRequest("P-" + diveId, diveId,
                18, 40, 0, T0));
    }

    /** 在栅栏放开后提交共享潜水员 D001 的下潜成对记录，返回 HTTP 状态码。 */
    private Integer pairD001(String diveId, String requestId, CountDownLatch start) {
        try {
            start.await();
            diveService.record(new RecordRequest(requestId, diveId, "D001", "DESCENT",
                    0, 18, 1, T0));
            return 200;
        } catch (ApiException e) {
            return e.getStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
