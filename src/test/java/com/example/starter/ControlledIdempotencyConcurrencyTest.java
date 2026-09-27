package com.example.starter;

import com.example.starter.service.IdempotencyService;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ControlledStartRequest;
import com.example.starter.web.dto.ControlledResults.ControlledStartResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受控流程幂等与并发/事务边界的 H2 测试（虚构积分模型）。
 */
class ControlledIdempotencyConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private String postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
    }

    /** 受控申请同键重放首次结果；同键异参 409 且不新增潜次。 */
    @Test
    void controlledApply_replaySameKey_conflictDifferentParams() throws Exception {
        String body = Requests.controlledApply(objectMapper, "CA-IDEM", "D001", "D002", 18, 50);
        mockMvc.perform(post("/api/controlled-dives/apply").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.pointsControlled").value(true));
        mockMvc.perform(post("/api/controlled-dives/apply").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive WHERE request_id='CA-IDEM'", Integer.class).intValue());

        mockMvc.perform(post("/api/controlled-dives/apply").contentType("application/json")
                        .content(Requests.controlledApply(objectMapper, "CA-IDEM", "D001", "D002", 24, 30)))
                .andExpect(status().isConflict());
        assertEquals(18, jdbc.queryForObject(
                "SELECT planned_depth_m FROM dive WHERE request_id='CA-IDEM'", Integer.class).intValue());
    }

    /** 业务失败（422）不占幂等键：同一 requestId 随后用合法参数仍首次受理。 */
    @Test
    void failedPlanDoesNotOccupyKey_thenValidSucceeds() throws Exception {
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-FK", "D001", "D002", 18, 50));
        // 首次出方案就传 51 分钟（超 NDL=50）→ 422
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-FK", "CD-FK", 0, 18, 51, T0)))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotency_record WHERE request_id='CP-FK'", Integer.class).intValue());
        // 复用同键合法参数 → 首次受理成功（非重放）
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-FK", "CD-FK", 0, 18, 50, T0)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false));
    }

    /** 下潜后重放方案请求仍返回首次冻结结果，不因当前阶段误报。 */
    @Test
    void replayPlanAfterStart_returnsFirstFrozenResult() throws Exception {
        preparePlanned("CD-RP", "D003", "D004", 18, 50, T0);
        postJson("/api/controlled-dives/start", Requests.controlledStart(
                objectMapper, "CS-RP", "CD-RP", 1, T0));
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "PLAN-CD-RP", "CD-RP", 0, 18, 50, T0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(1));
    }

    /** 受控出水同键重放不重复更新积分账（账面积分与更新次数不变）。 */
    @Test
    void surfaceReplay_doesNotDoubleSettle() throws Exception {
        prepareStarted("CD-SR", "D003", "D004", 18, 50, T0);
        Instant surfaced = T0.plusSeconds(60L * 55);
        String body = Requests.controlledSurface(objectMapper, "CU-SR", "CD-SR", 1, 50, surfaced);
        mockMvc.perform(post("/api/controlled-dives/surface").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/controlled-dives/surface").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(50, jdbc.queryForObject(
                "SELECT points FROM diver_points_account WHERE diver_code='D003'", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM bottom_time_ledger WHERE diver_code='D003'", Integer.class).intValue());
    }

    /**
     * 真实并发：两个不同潜水组（D001+D002 与 D001+D003）共享 D001 同时实际下潜，最多一组成功。
     */
    @Test
    void twoGroupsSharingDiver_concurrentStart_onlyOneSucceeds() throws Exception {
        preparePlanned("CD-G1", "D001", "D002", 18, 50, T0);
        preparePlanned("CD-G2", "D001", "D003", 18, 50, T0);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String diveId : List.of("CD-G1", "CD-G2")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        IdempotencyService.Outcome<ControlledStartResult> out = controlledDiveService
                                .controlledStart(new ControlledStartRequest(
                                        "START-" + diveId, diveId, 1, T0));
                        return out.statusCode();
                    } catch (ApiException e) {
                        return e.getStatus();
                    }
                }));
            }
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
            assertEquals(1, ok, "共享 D001 的两组并发下潜必须恰一组成功");
            assertEquals(1, conflict, "另一组必须 409");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 胜方占两名潜水员槽（含 D001），败方不残留槽
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(1) FROM diver_dive_slot", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM diver_dive_slot WHERE diver_code='D001'", Integer.class).intValue());
        // 恰一个潜次进入 DIVING，另一个停留 PLANNED
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive WHERE status='DIVING'", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive WHERE status='PLANNED'", Integer.class).intValue());
    }

    /**
     * 出水事务原子性：24h 门禁在出水阶段失败时，两人积分账、台账、潜水员槽与潜次状态整体回滚。
     */
    @Test
    void surfaceRejectRollsBackAccountsLedgerSlotsAndStatus() throws Exception {
        prepareStarted("CD-TX", "D001", "D002", 18, 50, T0);
        // 方案后向 D001 的 24h 窗口注入一笔已入账水下时间（340 分钟），本次 50 -> 390 > 360
        jdbc.update("INSERT INTO bottom_time_ledger "
                        + "(diver_code, dive_request_id, plan_version, bottom_min, started_at_utc) "
                        + "VALUES ('D001', 'SEED-TX', 1, 340, ?)",
                java.sql.Timestamp.from(T0.minusSeconds(60L)));
        Instant surfaced = T0.plusSeconds(60L * 55);
        mockMvc.perform(post("/api/controlled-dives/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "CU-TX", "CD-TX", 1, 50, surfaced)))
                .andExpect(status().isUnprocessableEntity());

        // 整体回滚：仍 DIVING、无积分账、无本次台账、潜水员槽仍在（未释放）、潜次无出水时刻
        assertEquals("DIVING", diveRepository.get("CD-TX").status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM diver_points_account", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM bottom_time_ledger WHERE dive_request_id='CD-TX'", Integer.class).intValue());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(1) FROM diver_dive_slot", Integer.class).intValue());
        // 失败不占幂等键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotency_record WHERE request_id='CU-TX'", Integer.class).intValue());
    }

    private void preparePlanned(String id, String a, String b, int depth, int bottom, Instant descent)
            throws Exception {
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, id, a, b, depth, bottom));
        postJson("/api/controlled-dives/plans", Requests.controlledPlan(
                objectMapper, "PLAN-" + id, id, 0, depth, bottom, descent));
    }

    private void prepareStarted(String id, String a, String b, int depth, int bottom, Instant descent)
            throws Exception {
        preparePlanned(id, a, b, depth, bottom, descent);
        postJson("/api/controlled-dives/start", Requests.controlledStart(
                objectMapper, "START-" + id, id, 1, descent));
    }
}
