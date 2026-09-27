package com.example.starter;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 反复潜水余量积分联锁：主流程、积分消减、三重门禁、来源冻结/重查、
 * 受控出水原子记账、历史更正与查询（H2 真实事务）。
 */
class ResidualWorkflowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private String postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
    }

    /** 受控首潜 18 米计划 40 分钟（NDL=50），执行并受控出水：两人残留积分各 40。 */
    @Test
    void controlledFirstDive_plansWithZeroPoints_surfaceCreditsBothAtomically() throws Exception {
        Instant surfaced = runControlledDive("C1", "CP1", "D001", "D002", 18, 40, T0, 40, 0);

        // 两人积分账户：残留 40、版本 1、来源潜次 C1
        assertEquals(40, accountPoints("D001"));
        assertEquals(40, accountPoints("D002"));
        assertEquals(1, accountVersion("D001"));
        assertEquals("C1", jdbc.queryForObject(
                "SELECT source_dive_request_id FROM residual_point_account WHERE diver_code='D001'",
                String.class));
        // 两条积分流水、出水后占用槽已释放
        assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM residual_point_movement "
                        + "WHERE dive_request_id='C1'", Integer.class).intValue());
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot", Integer.class)
                        .intValue());
        assertEquals("SURFACED", diveRepository.get("C1").status());

        // 账户查询：出水即刻当前积分 40；30 分钟消减 5；120 分钟消减 20
        mockMvc.perform(get("/api/residual/divers/D001/account").param("at", surfaced.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitResidualPoints").value(40))
                .andExpect(jsonPath("$.currentPoints").value(40))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/residual/divers/D001/account")
                        .param("at", surfaced.plus(Duration.ofMinutes(30)).toString()))
                .andExpect(jsonPath("$.currentPoints").value(35));
        mockMvc.perform(get("/api/residual/divers/D001/account")
                        .param("at", surfaced.plus(Duration.ofMinutes(120)).toString()))
                .andExpect(jsonPath("$.currentPoints").value(20));
    }

    /** 第二潜：恰满 2 小时观察（同时满足间隔），水面消减后积分 20，恰用尽允许 30 分钟合法，出水残留 50。 */
    @Test
    void secondDive_decayAndExactAllowedUse_creditsNewResidual() throws Exception {
        Instant s1 = runControlledDive("C1", "CP1", "D001", "D002", 18, 40, T0, 40, 0);
        Instant planAt = s1.plus(Duration.ofHours(2));
        postJson("/api/dives/close", Requests.close(objectMapper, "CC1", "C1", planAt));

        // 第二潜申请
        postJson("/api/dives/apply", Requests.apply(objectMapper, "C2", "D001", "D002", 18, 30));
        // 受控方案：当前积分 40-4*5=20，允许 50-20=30，恰计划 30 合法
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP2", "C2", 18, 30, 0,
                                planAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.teamAllowedMin").value(30))
                .andExpect(jsonPath("$.divers[0].currentPoints").value(20))
                .andExpect(jsonPath("$.divers[0].allowedMinutes").value(30))
                .andExpect(jsonPath("$.divers[0].observationCompleted").value(true))
                .andExpect(jsonPath("$.divers[0].intervalGatePassed").value(true));

        Instant s2 = runControlledDiveAfterPlan("C2", 18, 30, planAt);
        // 出水残留 = 下潜前 20 + 本次 30 = 50，账户版本 2
        assertEquals(50, accountPoints("D001"));
        assertEquals(50, accountPoints("D002"));
        assertEquals(2, accountVersion("D001"));
        assertEquals("C2", jdbc.queryForObject(
                "SELECT source_dive_request_id FROM residual_point_account WHERE diver_code='D001'",
                String.class));
        // 潜次积分视图冻结来源指向 C1
        mockMvc.perform(get("/api/residual/dives/C2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlled").value(true))
                .andExpect(jsonPath("$.snapshots[0].sourceDiveRequestId").value("C1"))
                .andExpect(jsonPath("$.snapshots[0].frozenPoints").value(20))
                .andExpect(jsonPath("$.movements[0].preDivePoints").value(20))
                .andExpect(jsonPath("$.movements[0].residualPoints").value(50));
    }

    /** 间隔与观察门禁：59 分钟间隔拒绝；恰 60 分钟仅过间隔门禁、观察未完成仍拒绝。 */
    @Test
    void intervalAndObservationGates_fiftyNineAndExactlySixtyRejected() throws Exception {
        Instant s1 = runControlledDive("G1", "GP1", "D001", "D002", 18, 40, T0, 40, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "GC1", "G1",
                s1.plus(Duration.ofHours(2))));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "G2", "D001", "D002", 18, 30));

        // 59 分钟：间隔门禁 422
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "GP2-59", "G2", 18, 30, 0,
                                s1.plus(Duration.ofMinutes(59)))))
                .andExpect(status().isUnprocessableEntity());
        // 恰 60 分钟：间隔恰通过，但 2 小时观察未完成，仍 422
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "GP2-60", "G2", 18, 30, 0,
                                s1.plus(Duration.ofMinutes(60)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("观察")));
        // 失败不占键：同一 requestId 改到合法时刻可成功受理
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "GP2-60", "G2", 18, 30, 0,
                                s1.plus(Duration.ofHours(2)))))
                .andExpect(status().isCreated());
    }

    /** 允许分钟数小于等于 0：残留 100、水面 5 小时消减到 50，18 米允许 0，拒绝方案。 */
    @Test
    void noAllowedTime_whenPointsReachNdl_rejected() throws Exception {
        // 12 米 NDL=120，潜 100 分钟 → 残留 100
        Instant s1 = runControlledDive("N1", "NP1", "D001", "D002", 12, 100, T0, 100, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "NC1", "N1",
                s1.plus(Duration.ofHours(2))));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "N2", "D001", "D002", 18, 10));
        // 5 小时 = 10 个 30 分钟块消减 50 → 当前 50，18 米允许 0
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "NP2", "N2", 18, 10, 0,
                                s1.plus(Duration.ofHours(5)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("允许分钟数")));
        // 账户与流水未因失败方案新增
        assertEquals(1, accountVersion("D001"));
    }

    /** 积分上限：当前积分 60 时计划 45 分钟 → 残留将达 105 > 100，方案拒绝（尽管允许分钟 60）。 */
    @Test
    void capExceeded_whenPreDivePlusBottomOver100_rejected() throws Exception {
        // 12 米潜 80 分钟 → 残留 80
        Instant s1 = runControlledDive("P1", "PP1", "D001", "D002", 12, 80, T0, 80, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "PC1", "P1",
                s1.plus(Duration.ofHours(2))));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "P2", "D001", "D002", 12, 45));
        // 2 小时消减 20 → 当前 60；12 米允许 60（>=45），但 60+45=105 超上限
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "PP2", "P2", 12, 45, 0,
                                s1.plus(Duration.ofHours(2)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("100")));
    }

    /** 计划底时间超过全队较短允许时长：两人积分不同，取较小者，422。 */
    @Test
    void teamAllowedTakesShorterDiver_planOverItRejected() throws Exception {
        // 构造两人不同积分：分别用不同搭档各潜一次较难（占用槽按人），直接以真实两潜达成：
        // D001/D002 同潜 40 分钟残留各 40 后，再用 jdbc 仅调整 D002 存储残留为 70，
        // 模拟两人来源积分不同（账户行仍为真实表、版本真实递增）。
        Instant s1 = runControlledDive("T1", "TP1", "D001", "D002", 18, 40, T0, 40, 0);
        jdbc.update("UPDATE residual_point_account SET residual_points=70 WHERE diver_code='D002'");
        postJson("/api/dives/close", Requests.close(objectMapper, "TC1", "T1",
                s1.plus(Duration.ofHours(2))));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "T2", "D001", "D002", 18, 40));
        // 2 小时消减：D001 当前 20（允许 30），D002 当前 50（允许 0）→ 全队允许 0，方案 422
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "TP2", "T2", 18, 1, 0,
                                s1.plus(Duration.ofHours(2)))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 版本与模式互斥：expectedVersion 不符 409；受控/旧入口互相拒绝 409。 */
    @Test
    void expectedVersionMismatch_andModeGuards_return409() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "M1", "D001", "D002", 18, 40));
        // 尚未出方案却传 expectedVersion=1
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "MP1", "M1", 18, 40, 1, T0)))
                .andExpect(status().isConflict());
        // 受控方案 v1 成功后，旧方案入口拒绝
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "MP1", "M1", 18, 40, 0, T0)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "MP-LEGACY", "M1", 18, 40)))
                .andExpect(status().isConflict());
        // 旧潜次（已用旧入口出过方案）拒绝受控方案
        postJson("/api/dives/apply", Requests.apply(objectMapper, "M2", "D003", "D004", 18, 40));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "MP2-LEGACY", "M2", 18, 40));
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "MP2", "M2", 18, 40, 0, T0)))
                .andExpect(status().isConflict());
        // 受控出水期望版本错误 409；旧出水入口对受控潜次 409
        pairDescent("M1", 18, T0, "D001", "D002");
        pairBottom("M1", 18, 40, T0.plusSeconds(60), "D001", "D002");
        pairAscent("M1", 18, T0.plusSeconds(60 * 41), "D001", "D002");
        mockMvc.perform(post("/api/residual/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "MS1", "M1", 2,
                                T0.plusSeconds(60 * 42))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/dives/surface").contentType("application/json")
                        .content(Requests.surface(objectMapper, "MS-LEGACY", "M1",
                                T0.plusSeconds(60 * 42))))
                .andExpect(status().isConflict());
    }

    /** 来源冻结后被新出水改动：下潜前重查失败 409，须重做方案；重做后版本 2 可下潜。 */
    @Test
    void staleFrozenSource_descentRejected_thenReplanSucceeds() throws Exception {
        Instant s1 = runControlledDive("F1", "FP1", "D001", "D002", 18, 40, T0, 40, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "FC1", "F1",
                s1.plus(Duration.ofHours(2))));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "F2", "D001", "D002", 18, 30));
        Instant planAt = s1.plus(Duration.ofHours(2));
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "FP2", "F2", 18, 30, 0,
                                planAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.divers[0].sourceDiveRequestId").value("F1"));

        // 冻结后来源账户被新的确认出水改动：版本 1→2、出水时刻推后（真实 UPDATE 模拟新积分）
        Instant laterSurface = planAt.plus(Duration.ofMinutes(40));
        jdbc.update("UPDATE residual_point_account SET version=2, last_surfaced_at_utc=?, "
                        + "residual_points=45, updated_at_utc=? WHERE diver_code='D001'",
                Timestamp.from(laterSurface), Timestamp.from(laterSurface));

        // 单人下潜记录先行（不触发门禁），成对时重查来源失败 → 409 且成对事务整体回滚
        postJson("/api/dives/records", Requests.record(objectMapper, "FD-STALE-1", "F2",
                "D001", "DESCENT", 0, 18, 1, planAt));
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "FD-STALE-2", "F2", "D002",
                                "DESCENT", 0, 18, 1, planAt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("重做方案")));
        // 败方（D002）记录随事务回滚、未占用槽、潜次仍 PLANNED
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_record WHERE dive_request_id='F2' AND diver_code='D002'",
                Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot",
                Integer.class).intValue());
        assertEquals("PLANNED", diveRepository.get("F2").status());

        // 以 expectedVersion=1 重做受控方案：旧快照清退、版本 2、重新冻结 v2 来源
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "FP2B", "F2", 18, 5, 1,
                                laterSurface.plus(Duration.ofHours(2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(1) FROM residual_plan_snapshot WHERE dive_request_id='F2' "
                        + "AND plan_version=2", Integer.class).intValue());
    }

    /** 旧方案不能消费新出的积分：下潜后账户被改，受控出水原子拒绝且积分账不变。 */
    @Test
    void surfaceDetectsChangedSource_afterDescent_wholeTransactionRollsBack() throws Exception {
        Instant s1 = runControlledDive("U1", "UP1", "D001", "D002", 18, 40, T0, 40, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "UC1", "U1",
                s1.plus(Duration.ofHours(2))));
        Instant planAt = s1.plus(Duration.ofHours(2));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "U2", "D001", "D002", 18, 20));
        postJson("/api/residual/plans", Requests.controlledPlan(objectMapper, "UP2", "U2",
                18, 20, 0, planAt));
        pairDescent("U2", 18, planAt, "D001", "D002");
        pairBottom("U2", 18, 20, planAt.plusSeconds(60), "D001", "D002");
        pairAscent("U2", 18, planAt.plusSeconds(60 * 21), "D001", "D002");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot",
                Integer.class).intValue());

        // 下潜后、出水前，D001 账户被其他确认出水改动（版本推进）
        jdbc.update("UPDATE residual_point_account SET version=version+1, "
                + "last_surfaced_at_utc=? WHERE diver_code='D001'",
                Timestamp.from(planAt.plus(Duration.ofMinutes(30))));

        mockMvc.perform(post("/api/residual/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "US2", "U2", 1,
                                planAt.plus(Duration.ofMinutes(25)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("新出的积分")));
        // 整笔回滚：无流水新增、潜次仍 DIVING、占用仍在（未误放）、D001 账户残留未被本次覆盖
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM residual_point_movement WHERE dive_request_id='U2'",
                Integer.class).intValue());
        assertEquals("DIVING", diveRepository.get("U2").status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(1) FROM diver_active_slot",
                Integer.class).intValue());
        assertEquals(40, jdbc.queryForObject(
                "SELECT residual_points FROM residual_point_account WHERE diver_code='D001' "
                        + "AND source_dive_request_id='U1'", Integer.class).intValue());
    }

    /** 实际水下分钟超过冻结允许分钟：受控出水 422，不记账、状态不变。 */
    @Test
    void surfaceRejectsActualBottomOverAllowedMinutes_noAccountChange() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "O1", "D003", "D004", 18, 20));
        postJson("/api/residual/plans", Requests.controlledPlan(objectMapper, "OP1", "O1",
                18, 20, 0, T0));
        pairDescent("O1", 18, T0, "D003", "D004");
        // 实际 BOTTOM 51 分钟 > 首潜允许 50（NDL）
        pairBottom("O1", 18, 51, T0.plusSeconds(60), "D003", "D004");
        pairAscent("O1", 18, T0.plusSeconds(60 * 52), "D003", "D004");
        mockMvc.perform(post("/api/residual/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "OS1", "O1", 1,
                                T0.plusSeconds(60 * 53))))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM residual_point_account",
                Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM residual_point_movement",
                Integer.class).intValue());
        assertEquals("DIVING", diveRepository.get("O1").status());
    }

    /** 幂等：同键同参重放；同键异参 409 且状态不变；业务失败不占键。 */
    @Test
    void idempotency_replaySameKey_conflictOnDifferentParams_failureDoesNotConsumeKey()
            throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "I1", "D001", "D002", 18, 40));
        String body = Requests.controlledPlan(objectMapper, "IP1", "I1", 18, 40, 0, T0);
        mockMvc.perform(post("/api/residual/plans").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/residual/plans").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_plan WHERE dive_request_id='I1'", Integer.class)
                        .intValue());

        // 同键异参（改底时间）→ 409，版本仍为 1
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "IP1", "I1", 18, 41, 0, T0)))
                .andExpect(status().isConflict());
        assertEquals(1, diveRepository.get("I1").currentPlanVersion());

        // 新键的业务失败（422）不占键：同 requestId 改合法参数后成功
        postJson("/api/dives/apply", Requests.apply(objectMapper, "I2", "D003", "D004", 18, 40));
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "IP2", "I2", 18, 51, 0, T0)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "IP2", "I2", 18, 40, 0, T0)))
                .andExpect(status().isCreated());
    }

    /** 历史更正独立留痕：已关闭潜次原记录/台账/积分不变，差异版本递增，含分隔符理由指纹可区分。 */
    @Test
    void correctionOnClosedDive_recordsIndependentDifference_versionsAndIdempotency()
            throws Exception {
        Instant s1 = runControlledDive("K1", "KP1", "D001", "D002", 18, 40, T0, 40, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "KC1", "K1",
                s1.plus(Duration.ofHours(2))));
        long recordId = jdbc.queryForObject(
                "SELECT id FROM dive_record WHERE dive_request_id='K1' AND phase='BOTTOM' "
                        + "AND diver_code='D001'", Long.class);

        String trickyReason = "a|b:c{d}[e],f=g S|3:abc";
        // 首条更正：expectedVersion=0 → 版本 1
        mockMvc.perform(post("/api/residual/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "KCORR-1", "K1", recordId,
                                38, 0, s1.plus(Duration.ofHours(3)), trickyReason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deltaMin").value(-2))
                .andExpect(jsonPath("$.recordedDurationMin").value(40))
                .andExpect(jsonPath("$.correctedDurationMin").value(38));
        // 原记录、台账、积分均不变
        assertEquals(40, jdbc.queryForObject(
                "SELECT duration_min FROM dive_record WHERE id=?", Integer.class, recordId)
                        .intValue());
        assertEquals(40, jdbc.queryForObject(
                "SELECT bottom_min FROM bottom_time_ledger WHERE dive_request_id='K1' "
                        + "AND diver_code='D001'", Integer.class).intValue());
        assertEquals(40, accountPoints("D001"));

        // 版本不符 409
        mockMvc.perform(post("/api/residual/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "KCORR-BAD", "K1", recordId,
                                39, 0, s1.plus(Duration.ofHours(4)), "x")))
                .andExpect(status().isConflict());

        // 同键异参（分隔符理由改动）409；同键同参重放
        String firstBody = Requests.correction(objectMapper, "KCORR-2", "K1", recordId,
                36, 1, s1.plus(Duration.ofHours(4)), trickyReason);
        mockMvc.perform(post("/api/residual/corrections").contentType("application/json")
                        .content(firstBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/api/residual/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "KCORR-2", "K1", recordId,
                                37, 1, s1.plus(Duration.ofHours(4)), trickyReason + "!")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/residual/corrections").contentType("application/json")
                        .content(firstBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        // 不存在的记录 404
        mockMvc.perform(post("/api/residual/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "KCORR-404", "K1", 999999L,
                                36, 2, s1.plus(Duration.ofHours(5)), "x")))
                .andExpect(status().isNotFound());

        // 潜次积分视图含受控标记、快照、流水与更正差异
        mockMvc.perform(get("/api/residual/dives/K1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlled").value(true))
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.movements.length()").value(2))
                .andExpect(jsonPath("$.corrections.length()").value(2))
                .andExpect(jsonPath("$.activeSlots").value(false));
    }

    /** 400/404：方案时刻早于上次出水 400；潜次/账户不存在 404；深度不在合成表 400。 */
    @Test
    void invalidTimeAndMissingObjects_400And404() throws Exception {
        Instant s1 = runControlledDive("Z1", "ZP1", "D001", "D002", 18, 40, T0, 40, 0);
        postJson("/api/dives/close", Requests.close(objectMapper, "ZC1", "Z1",
                s1.plus(Duration.ofHours(2))));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "Z2", "D001", "D002", 18, 40));
        // 方案时刻早于上次出水
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "ZP2-EARLY", "Z2", 18, 40, 0,
                                s1.minusSeconds(60))))
                .andExpect(status().isBadRequest());
        // 深度不在合成减压表
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "ZP2-DEPTH", "Z2", 19, 40,
                                0, s1.plus(Duration.ofHours(2)))))
                .andExpect(status().isBadRequest());
        // 潜次不存在
        mockMvc.perform(post("/api/residual/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "ZP2-NOPE", "NOPE", 18, 40,
                                0, s1.plus(Duration.ofHours(2)))))
                .andExpect(status().isNotFound());
        // 尚无账户的潜水员查询 404；旧潜次积分视图 controlled=false
        mockMvc.perform(get("/api/residual/divers/D003/account"))
                .andExpect(status().isNotFound());
        postJson("/api/dives/apply", Requests.apply(objectMapper, "Z3", "D003", "D004", 18, 40));
        mockMvc.perform(get("/api/residual/dives/Z3"))
                .andExpect(jsonPath("$.controlled").value(false));
    }

    /** 受控出水后同键重放仍返回首次冻结结果（潜次已 SURFACED），不重复记账。 */
    @Test
    void controlledSurfaceReplay_afterStateAdvanced_returnsFirstResult() throws Exception {
        Instant s1 = runControlledDive("R1", "RP1", "D003", "D004", 18, 40, T0, 40, 0);
        // 已 SURFACED，重放同一次受控出水请求
        mockMvc.perform(post("/api/residual/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "S-R1", "R1", 1, s1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("SURFACED"))
                .andExpect(jsonPath("$.surfacedAtUtc").value(s1.toString()));
        // 不产生第二笔流水、积分账不重复更新
        assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM residual_point_movement "
                        + "WHERE dive_request_id='R1'", Integer.class).intValue());
        assertEquals(1, accountVersion("D003"));
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    /**
     * 跑完一个受控潜次：申请→受控方案→下潜/底/上升成对→受控出水；返回出水时刻。
     *
     * @param planExpectedVersion 受控方案期望的潜次版本：首潜为 0；出水版本恒为该值 +1
     */
    private Instant runControlledDive(String diveId, String planId, String a, String b,
                                      int depth, int bottom, Instant base, int actualBottom,
                                      int planExpectedVersion) throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, diveId, a, b, depth, bottom));
        postJson("/api/residual/plans", Requests.controlledPlan(objectMapper, planId, diveId,
                depth, bottom, planExpectedVersion, base));
        int surfaceVersion = planExpectedVersion + 1;
        int transit = (int) Math.ceil(depth / 18.0);
        pairDescent(diveId, depth, base, a, b);
        pairBottom(diveId, depth, actualBottom, base.plusSeconds(60L * transit), a, b);
        Instant ascentAt = base.plusSeconds(60L * (transit + actualBottom));
        pairAscent(diveId, depth, ascentAt, a, b);
        Instant surfaced = ascentAt.plusSeconds(60L * transit);
        postJson("/api/residual/surface", Requests.controlledSurface(objectMapper,
                "S-" + diveId, diveId, surfaceVersion, surfaced));
        return surfaced;
    }

    /** 在已申请且已出受控方案（v1）后，成对执行下潜/底/上升并受控出水，返回出水时刻。 */
    private Instant runControlledDiveAfterPlan(String diveId, int depth, int bottom,
                                               Instant base) throws Exception {
        int transit = (int) Math.ceil(depth / 18.0);
        pairDescent(diveId, depth, base, aOf(diveId), bOf(diveId));
        pairBottom(diveId, depth, bottom, base.plusSeconds(60L * transit), aOf(diveId), bOf(diveId));
        Instant ascentAt = base.plusSeconds(60L * (transit + bottom));
        pairAscent(diveId, depth, ascentAt, aOf(diveId), bOf(diveId));
        Instant surfaced = ascentAt.plusSeconds(60L * transit);
        postJson("/api/residual/surface", Requests.controlledSurface(objectMapper,
                "S-" + diveId + "-2", diveId, 1, surfaced));
        return surfaced;
    }

    private String aOf(String diveId) {
        return diveRepository.get(diveId).diverACode();
    }

    private String bOf(String diveId) {
        return diveRepository.get(diveId).diverBCode();
    }

    private void pairDescent(String diveId, int depth, Instant at, String a, String b)
            throws Exception {
        int transit = (int) Math.ceil(depth / 18.0);
        postJson("/api/dives/records", Requests.record(objectMapper, "RD-" + diveId + "-" + a,
                diveId, a, "DESCENT", 0, depth, transit, at));
        postJson("/api/dives/records", Requests.record(objectMapper, "RD-" + diveId + "-" + b,
                diveId, b, "DESCENT", 0, depth, transit, at));
    }

    private void pairBottom(String diveId, int depth, int minutes, Instant at, String a, String b)
            throws Exception {
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + diveId + "-" + a,
                diveId, a, "BOTTOM", depth, depth, minutes, at));
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + diveId + "-" + b,
                diveId, b, "BOTTOM", depth, depth, minutes, at));
    }

    private void pairAscent(String diveId, int depth, Instant at, String a, String b)
            throws Exception {
        int transit = (int) Math.ceil(depth / 18.0);
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + diveId + "-" + a,
                diveId, a, "ASCENT", depth, 0, transit, at));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + diveId + "-" + b,
                diveId, b, "ASCENT", depth, 0, transit, at));
    }

    private int accountPoints(String diver) {
        return jdbc.queryForObject(
                "SELECT residual_points FROM residual_point_account WHERE diver_code=?",
                Integer.class, diver);
    }

    private int accountVersion(String diver) {
        return jdbc.queryForObject(
                "SELECT version FROM residual_point_account WHERE diver_code=?",
                Integer.class, diver);
    }
}
