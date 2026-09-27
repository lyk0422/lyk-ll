package com.example.starter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 反复潜水虚构积分受控方案主流程与失败分支（H2 事务测试；模型不用于真实潜水减压）。
 */
class ControlledDiveFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private String postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Integer> versions(int a, int b) {
        return Map.of("D001", a, "D002", b);
    }

    /**
     * 主流程：首潜 12 米 40 分钟（两人首次积分 0）→ 出水原子结算残留 40、账户版本 1→2、释放占用；
     * 2 小时观察结案；二潜恰在出水后 120 分钟（每满 30 减 5 共减 20）当前积分 20、允许 100 分钟。
     */
    @Test
    void repeatedDive_scoreDecay_andAtomicSurfaceSettlement() throws Exception {
        Instant s1 = completeControlledDive("CD-1", "CP-1", T0, 40, 40, versions(1, 1));

        // 出水即结算：两人残留 40、版本 2、占用槽释放
        mockMvc.perform(get("/api/dives/scores/D001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.residualScore").value(40))
                .andExpect(jsonPath("$.accountVersion").value(2))
                .andExpect(jsonPath("$.activeDiveRequestId").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM diver_dive_slot", Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM score_settlement WHERE dive_request_id='CD-1'",
                        Integer.class).intValue());

        // 二潜：恰满 2 小时观察（水面 120 分钟，消减 4 个单元共 20 分），当前 20、允许 100
        Instant plan2At = s1.plusSeconds(3600 * 2L);
        postJson("/api/dives/apply", Requests.apply(objectMapper, "CD-2", "D001", "D002", 12, 80));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-2", "CD-2", 12, 80,
                                plan2At, versions(2, 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersion").value(1))
                .andExpect(jsonPath("$.teamAllowedMin").value(100))
                .andExpect(jsonPath("$.bottomWithinAllowed").value(false))
                .andExpect(jsonPath("$.divers[0].currentScore").value(20))
                .andExpect(jsonPath("$.divers[0].surfaceMinutes").value(120))
                .andExpect(jsonPath("$.divers[0].observationComplete").value(true));

        pairControlledPhases("CD-2", 12, 80, plan2At.plusSeconds(60));
        Instant s2 = plan2At.plusSeconds(60L * (80 + 2));
        postJson("/api/dives/surface", Requests.surface(objectMapper, "CS-2", "CD-2", s2));
        // 残留 = 下潜前 20 + 本次 80 = 恰 100 合法；账户版本 2→3
        mockMvc.perform(get("/api/dives/scores/D001"))
                .andExpect(jsonPath("$.residualScore").value(100))
                .andExpect(jsonPath("$.accountVersion").value(3));
        org.junit.jupiter.api.Assertions.assertEquals(100,
                jdbc.queryForObject("SELECT residual_score FROM diver_score_account WHERE diver_code='D001'",
                        Integer.class).intValue());
    }

    /** 恰用尽两人较短允许时长合法；多 1 分钟拒绝（422）。 */
    @Test
    void exactShorterAllowedIsLegal_oneMoreRejected() throws Exception {
        // D001 首潜 100 分钟：残留 100；与 D002 配对
        Instant s1 = completeControlledDive("EX-1", "EP-1", T0, 100, 100, versions(1, 1));
        // 水面 540 分钟（18 个完整 30 分钟单元，消减 90）：D001 当前 10；新组员 D003 首次积分 0
        Instant plan2At = s1.plusSeconds(60L * 540);
        postJson("/api/dives/apply", Requests.apply(objectMapper, "EX-2", "D001", "D003", 18, 40));
        // 18 米 NDL=50：D001 允许 50-10=40（较短），D003 允许 50；恰用尽 40 合法，残留 10+40=50
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "EP-2-OK", "EX-2", 18, 40,
                                plan2At, Map.of("D001", 2, "D003", 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamAllowedMin").value(40))
                .andExpect(jsonPath("$.bottomWithinAllowed").value(true));
        // 同潜次另一请求 41 分钟：超过较短允许时长 40 → 422，不占键
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "EP-2-BAD", "EX-2", 18, 41,
                                plan2At, Map.of("D001", 2, "D003", 1))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 失败门禁：不足 60 分钟间隔 422；恰 60 过间隔但 2 小时观察未满仍 422。 */
    @Test
    void intervalAndObservationGates() throws Exception {
        Instant s1 = completeControlledDive("GT-1", "GP-1", T0, 40, 40, versions(1, 1));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "GT-2", "D001", "D002", 12, 30));

        // 距上次出水 59 分钟：间隔门禁拒绝
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "GP-59", "GT-2", 12, 30,
                                s1.plusSeconds(60L * 59), versions(2, 2))))
                .andExpect(status().isUnprocessableEntity());
        // 恰 60 分钟：通过间隔门禁，但 2 小时观察未完成 → 仍拒绝
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "GP-60", "GT-2", 12, 30,
                                s1.plusSeconds(60L * 60), versions(2, 2))))
                .andExpect(status().isUnprocessableEntity());
        // 被拒方案不建立新版本/快照、不占槽
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM controlled_plan WHERE dive_request_id='GT-2'",
                        Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM diver_dive_slot WHERE dive_request_id='GT-2'",
                        Integer.class).intValue());
    }

    /** 允许分钟数 <=0 拒绝新方案：残留 100、水面 120 分钟当前 80，40 米 NDL=9 → 允许 -71。 */
    @Test
    void allowedMinutesNonPositive_rejected() throws Exception {
        Instant s1 = completeControlledDive("NZ-1", "NP-1", T0, 100, 100, versions(1, 1));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "NZ-2", "D001", "D002", 40, 5));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "NP-2", "NZ-2", 40, 5,
                                s1.plusSeconds(3600 * 2L), versions(2, 2))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 残留积分超 100：方案预判拒绝（当前 20 + 底 81 = 101）。 */
    @Test
    void residualCapExceededAtPlan_rejected() throws Exception {
        Instant s1 = completeControlledDive("CAP-1", "CPP-1", T0, 40, 40, versions(1, 1));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "CAP-2", "D001", "D002", 12, 81));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CPP-2", "CAP-2", 12, 81,
                                s1.plusSeconds(3600 * 2L), versions(2, 2))))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * 24 小时累计门禁独立于积分：旧流程三潜给 D001 攒 120+120+70=310 分钟台账（均在窗口内），
     * D001 首次参与受控潜次时积分门禁全过，但再潜 51 分钟使 24h 累计 361＞360 拒绝；恰 50（360）合法。
     */
    @Test
    void cumulative24hGateAtControlledPlan_exactly360Legal() throws Exception {
        completeLegacyDive("L24-1", T0, 120);
        completeLegacyDive("L24-2", T0.plusSeconds(60L * 242), 120);
        completeLegacyDive("L24-3", T0.plusSeconds(60L * 483), 70);
        Integer summed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(bottom_min),0) FROM bottom_time_ledger WHERE diver_code='D001'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(310, summed.intValue());

        Instant planAt = T0.plusSeconds(60L * 700);
        // 受控组 D001/D003：D003 无历史；51 分钟 → D001 窗口 361 拒绝
        postJson("/api/dives/apply", Requests.apply(objectMapper, "L24-C", "D001", "D003", 12, 51));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "L24P-BAD", "L24-C", 12, 51,
                                planAt, Map.of("D001", 1, "D003", 1))))
                .andExpect(status().isUnprocessableEntity());
        // 恰 50：310+50=360 合法
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "L24P-OK", "L24-C", 12, 50,
                                planAt, Map.of("D001", 1, "D003", 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamAllowedMin").value(120));
    }

    /** 旧流程完成一潜 D001/D002（12 米），结案释放同组占用。 */
    private void completeLegacyDive(String diveId, Instant base, int bottom) throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, diveId, "D001", "D002",
                12, bottom));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "LP-" + diveId, diveId, 12, bottom));
        postJson("/api/dives/records", Requests.record(objectMapper, "LD-" + diveId + "-1",
                diveId, "D001", "DESCENT", 0, 12, 1, base));
        postJson("/api/dives/records", Requests.record(objectMapper, "LD-" + diveId + "-2",
                diveId, "D002", "DESCENT", 0, 12, 1, base));
        postJson("/api/dives/records", Requests.record(objectMapper, "LB-" + diveId + "-1",
                diveId, "D001", "BOTTOM", 12, 12, bottom, base.plusSeconds(60)));
        postJson("/api/dives/records", Requests.record(objectMapper, "LB-" + diveId + "-2",
                diveId, "D002", "BOTTOM", 12, 12, bottom, base.plusSeconds(60)));
        Instant ascentAt = base.plusSeconds(60L * (bottom + 1));
        postJson("/api/dives/records", Requests.record(objectMapper, "LA-" + diveId + "-1",
                diveId, "D001", "ASCENT", 12, 0, 1, ascentAt));
        postJson("/api/dives/records", Requests.record(objectMapper, "LA-" + diveId + "-2",
                diveId, "D002", "ASCENT", 12, 0, 1, ascentAt));
        Instant surfaced = ascentAt.plusSeconds(60);
        postJson("/api/dives/surface", Requests.surface(objectMapper, "LS-" + diveId, diveId,
                surfaced));
        postJson("/api/dives/close", Requests.close(objectMapper, "LC-" + diveId, diveId,
                surfaced.plusSeconds(3600 * 2L)));
    }

    /** expectedVersion 与账户版本不符 → 409，要求重做方案。 */
    @Test
    void staleExpectedVersion_conflict() throws Exception {
        Instant s1 = completeControlledDive("SV-1", "SVP-1", T0, 40, 40, versions(1, 1));
        postJson("/api/dives/apply", Requests.apply(objectMapper, "SV-2", "D001", "D002", 12, 30));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "SVP-2", "SV-2", 12, 30,
                                s1.plusSeconds(3600 * 2L), versions(1, 1))))
                .andExpect(status().isConflict());
        // 首次潜账户不存在时 expectedVersion 非 1 也 409
        postJson("/api/dives/apply", Requests.apply(objectMapper, "SV-3", "D003", "D004", 12, 30));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "SVP-3", "SV-3", 12, 30, T0,
                                Map.of("D003", 2, "D004", 2))))
                .andExpect(status().isConflict());
    }

    /** 400 参数非法：缺组员版本、深度不在合成表；404：潜次/潜水员不存在。 */
    @Test
    void badRequest400_andNotFound404() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "BR-1", "D001", "D002", 18, 50));
        // 缺少 D002 的版本 → 400
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "BRP-1", "BR-1", 12, 30, T0,
                                Map.of("D001", 1))))
                .andExpect(status().isBadRequest());
        // 13 米不在合成免减压表 → 400
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "BRP-2", "BR-1", 13, 30, T0,
                                versions(1, 1))))
                .andExpect(status().isBadRequest());
        // 潜次不存在 → 404
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "BRP-3", "NO-SUCH", 12, 30,
                                T0, versions(1, 1))))
                .andExpect(status().isNotFound());
        // 名册外潜水员积分查询 → 404
        mockMvc.perform(get("/api/dives/scores/D999")).andExpect(status().isNotFound());
    }

    /** 旧/受控两条方案线互斥，旧潜次保持原语义。 */
    @Test
    void legacyAndControlledPlansAreExclusive_legacyKeepsOldSemantics() throws Exception {        postJson("/api/dives/apply", Requests.apply(objectMapper, "LEG-1", "D003", "D004", 18, 50));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "LGP-1", "LEG-1", 18, 50));
        // 已出旧方案的潜次不能改用受控方案
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "LGC-1", "LEG-1", 18, 30, T0,
                                Map.of("D003", 1, "D004", 1))))
                .andExpect(status().isUnprocessableEntity());

        postJson("/api/dives/apply", Requests.apply(objectMapper, "CTL-1", "D001", "D002", 12, 30));
        postJson("/api/dives/controlled-plans", Requests.controlledPlan(objectMapper, "CLP-1",
                "CTL-1", 12, 30, T0, versions(1, 1)));
        // 已启用积分控制的潜次不能再走旧方案入口
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "CLG-1", "CTL-1", 12, 30)))
                .andExpect(status().isUnprocessableEntity());
        // 旧方案标志仍为 FALSE，受控方案为 TRUE
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                jdbc.queryForObject("SELECT score_controlled FROM dive_plan WHERE dive_request_id='LEG-1'",
                        Boolean.class));
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,
                jdbc.queryForObject("SELECT score_controlled FROM dive_plan WHERE dive_request_id='CTL-1'",
                        Boolean.class));
    }

    /**
     * 跨线占用：受控潜次方案已占用 D001（尚未结案、槽未释放），旧流程另一组 (D003,D001)
     * 成对下潜时必须 409；受控潜次结案释放槽后旧流程可正常成对。
     */
    @Test
    void legacyDiveCannotShareDiverWithActiveControlledDive() throws Exception {
        // 受控潜次 CD-1 (D001,D002) 出方案即占槽，执行成对下潜后进入 DIVING（未出水未释放）
        postJson("/api/dives/apply", Requests.apply(objectMapper, "XCD-1", "D001", "D002", 12, 30));
        postJson("/api/dives/controlled-plans", Requests.controlledPlan(objectMapper, "XCP-1",
                "XCD-1", 12, 30, T0, versions(1, 1)));
        pairDescent("XCD-1", 12, T0.plusSeconds(60));

        // 旧流程潜次 XL-1 (D003,D001)：active_team_slot 与受控槽是两套机制，申请/旧方案均成功
        postJson("/api/dives/apply", Requests.apply(objectMapper, "XL-1", "D003", "D001", 18, 50));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "XLP-1", "XL-1", 18, 50));
        // 单人 D003 下潜不生效；D001 配对应触发跨线占用 409，记录回滚
        postJson("/api/dives/records", Requests.record(objectMapper, "XLD-3", "XL-1", "D003",
                "DESCENT", 0, 18, 1, T0.plusSeconds(120)));
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "XLD-1", "XL-1", "D001",
                                "DESCENT", 0, 18, 1, T0.plusSeconds(120))))
                .andExpect(status().isConflict());
        // 受控潜次完成并结案释放 D001
        postJson("/api/dives/records", Requests.record(objectMapper, "XCB-1", "XCD-1", "D001",
                "BOTTOM", 12, 12, 30, T0.plusSeconds(120)));
        postJson("/api/dives/records", Requests.record(objectMapper, "XCB-2", "XCD-1", "D002",
                "BOTTOM", 12, 12, 30, T0.plusSeconds(120)));
        Instant ascentAt = T0.plusSeconds(60L * 33);
        postJson("/api/dives/records", Requests.record(objectMapper, "XCA-1", "XCD-1", "D001",
                "ASCENT", 12, 0, 1, ascentAt));
        postJson("/api/dives/records", Requests.record(objectMapper, "XCA-2", "XCD-1", "D002",
                "ASCENT", 12, 0, 1, ascentAt));
        Instant surfaced = ascentAt.plusSeconds(60);
        postJson("/api/dives/surface", Requests.surface(objectMapper, "XCS-1", "XCD-1", surfaced));
        postJson("/api/dives/close", Requests.close(objectMapper, "XCC-1", "XCD-1",
                surfaced.plusSeconds(3600 * 2L)));
        // 槽释放后旧流程重新成对 D001 下潜成功
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "XLD-1", "XL-1", "D001",
                                "DESCENT", 0, 18, 1, T0.plusSeconds(3600 * 3L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(true));
    }

    /** 实际下潜前重查：方案后账户版本/积分被并发推进 → 成对下潜 409，旧方案不能消费新积分。 */
    @Test
    void sourceChangedBeforeDescent_oldPlanRejected() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "SRC-1", "D001", "D002", 12, 30));
        postJson("/api/dives/controlled-plans", Requests.controlledPlan(objectMapper, "SRCP-1",
                "SRC-1", 12, 30, T0, versions(1, 1)));
        // 模拟并发结算把账户从 v1 推进到 v2 并写入新残留积分
        jdbc.update("UPDATE diver_score_account SET version=2, residual_score=55, "
                + "last_surfaced_at_utc=?, last_dive_request_id='ELSEWHERE' WHERE diver_code='D001'",
                java.sql.Timestamp.from(T0.plusSeconds(3600)));
        postJson("/api/dives/records", Requests.record(objectMapper, "SRD-1", "SRC-1", "D001",
                "DESCENT", 0, 12, 1, T0.plusSeconds(60)));
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "SRD-2", "SRC-1", "D002",
                                "DESCENT", 0, 12, 1, T0.plusSeconds(60))))
                .andExpect(status().isConflict());
    }

    /** 实际水底分钟超过冻结允许时长：成对记录整体回滚，台账不落地。 */
    @Test
    void actualBottomExceedsFrozenAllowed_pairRollsBack() throws Exception {
        // 40 米 NDL=9：两名首次潜水员冻结允许恰 9 分钟
        postJson("/api/dives/apply", Requests.apply(objectMapper, "OVR-1", "D001", "D002", 40, 9));
        postJson("/api/dives/controlled-plans", Requests.controlledPlan(objectMapper, "OVRP-1",
                "OVR-1", 40, 9, T0, versions(1, 1)));
        pairDescent("OVR-1", 40, T0);
        // 第一人单条 10 分钟：不生效
        postJson("/api/dives/records", Requests.record(objectMapper, "OVB-1", "OVR-1", "D001",
                "BOTTOM", 40, 40, 10, T0.plusSeconds(60 * 3L)));
        // 配对手触发 10 > 冻结允许 9 → 422，第二人记录回滚
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "OVB-2", "OVR-1", "D002",
                                "BOTTOM", 40, 40, 10, T0.plusSeconds(60 * 3L))))
                .andExpect(status().isUnprocessableEntity());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM bottom_time_ledger WHERE dive_request_id='OVR-1'",
                        Integer.class).intValue());
        // 成对下潜已生效使潜次进入 DIVING；失败的水底配对不改状态、不生效
        org.junit.jupiter.api.Assertions.assertEquals("DIVING",
                diveRepository.get("OVR-1").status());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM dive_record WHERE dive_request_id='OVR-1' "
                        + "AND phase='BOTTOM' AND effective=FALSE AND frozen=FALSE", Integer.class)
                        .intValue());
    }

    /** 受控方案幂等：同键同参重放首次冻结结果；异参 409 且失败不占键（可换新键重试成功）。 */
    @Test
    void controlledPlanIdempotency_replayAndConflict() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "IDM-1", "D001", "D002", 12, 30));
        String body = Requests.controlledPlan(objectMapper, "IDMP-1", "IDM-1", 12, 30, T0,
                versions(1, 1));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false));
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.teamAllowedMin").value(120));
        // 同键异参（改底时间）→ 409，方案版本不增加
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "IDMP-1", "IDM-1", 12, 31,
                                T0, versions(1, 1))))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM dive_plan WHERE dive_request_id='IDM-1'",
                        Integer.class).intValue());
        // 失败不占键：新 requestId 重做成功（版本升至 2，旧快照冻结清退）
        mockMvc.perform(post("/api/dives/controlled-plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "IDMP-2", "IDM-1", 12, 31,
                                T0, versions(1, 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersion").value(2))
                .andExpect(jsonPath("$.frozenOldVersions").value(1));
    }

    /** 历史更正：仅已关闭潜次；原值不符拒绝；受理后独立记差异、不追改原记录，并可在复算视图查询。 */
    @Test
    void correctionOnClosedDive_recordsIndependentDelta() throws Exception {
        completeControlledDive("COR-1", "CORP-1", T0, 40, 40, versions(1, 1));
        // 已结案（completeControlledDive 内结案）；原值不符 → 422
        mockMvc.perform(post("/api/dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "CORX-BAD", "COR-1", "D001",
                                41, 45, T0.plusSeconds(3600 * 3L))))
                .andExpect(status().isUnprocessableEntity());
        // 合法更正：40 → 45，Δ=+5
        mockMvc.perform(post("/api/dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "CORX-1", "COR-1", "D001",
                                40, 45, T0.plusSeconds(3600 * 3L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltaMin").value(5))
                .andExpect(jsonPath("$.replayed").value(false));
        // 原水底记录不被追改
        org.junit.jupiter.api.Assertions.assertEquals(40,
                jdbc.queryForObject("SELECT duration_min FROM dive_record WHERE dive_request_id='COR-1' "
                        + "AND diver_code='D001' AND phase='BOTTOM' LIMIT 1", Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM dive_record_correction WHERE request_id='CORX-1'",
                        Integer.class).intValue());
        // 同键重放
        mockMvc.perform(post("/api/dives/corrections").contentType("application/json")
                        .content(Requests.correction(objectMapper, "CORX-1", "COR-1", "D001",
                                40, 45, T0.plusSeconds(3600 * 3L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        // 复算视图含冻结项、结算与差异
        mockMvc.perform(get("/api/dives/COR-1/recompute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlledPlans[0].teamAllowedMin").value(120))
                .andExpect(jsonPath("$.scoreSettlements[0].residualScore").value(40))
                .andExpect(jsonPath("$.corrections[0].deltaMin").value(5));
    }

    /** 完成一整趟受控潜次（申请→受控方案→三段成对→出水→结案），返回出水时刻。 */
    private Instant completeControlledDive(String diveId, String planId, Instant planAt,
                                           int planBottom, int actualBottom,
                                           Map<String, Integer> vers) throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, diveId, "D001", "D002",
                12, planBottom));
        postJson("/api/dives/controlled-plans", Requests.controlledPlan(objectMapper, planId,
                diveId, 12, planBottom, planAt, vers));
        Instant descentAt = planAt.plusSeconds(60);
        pairDescent(diveId, 12, descentAt);
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + diveId + "-1",
                diveId, "D001", "BOTTOM", 12, 12, actualBottom, descentAt.plusSeconds(60)));
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + diveId + "-2",
                diveId, "D002", "BOTTOM", 12, 12, actualBottom, descentAt.plusSeconds(60)));
        Instant ascentAt = descentAt.plusSeconds(60L * (actualBottom + 1));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + diveId + "-1",
                diveId, "D001", "ASCENT", 12, 0, 1, ascentAt));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + diveId + "-2",
                diveId, "D002", "ASCENT", 12, 0, 1, ascentAt));
        Instant surfaced = ascentAt.plusSeconds(60);
        postJson("/api/dives/surface", Requests.surface(objectMapper, "S-" + diveId, diveId,
                surfaced));
        postJson("/api/dives/close", Requests.close(objectMapper, "C-" + diveId, diveId,
                surfaced.plusSeconds(3600 * 2L)));
        return surfaced;
    }

    private void pairControlledPhases(String diveId, int depth, int bottom, Instant descentAt)
            throws Exception {
        pairDescent(diveId, depth, descentAt);
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + diveId + "-1",
                diveId, "D001", "BOTTOM", depth, depth, bottom, descentAt.plusSeconds(60)));
        postJson("/api/dives/records", Requests.record(objectMapper, "RB-" + diveId + "-2",
                diveId, "D002", "BOTTOM", depth, depth, bottom, descentAt.plusSeconds(60)));
        Instant ascentAt = descentAt.plusSeconds(60L * (bottom + 1));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + diveId + "-1",
                diveId, "D001", "ASCENT", depth, 0, 1, ascentAt));
        postJson("/api/dives/records", Requests.record(objectMapper, "RA-" + diveId + "-2",
                diveId, "D002", "ASCENT", depth, 0, 1, ascentAt));
    }

    private void pairDescent(String diveId, int depth, Instant at) throws Exception {
        int transit = (int) Math.ceil(depth / 18.0);
        postJson("/api/dives/records", Requests.record(objectMapper, "RD-" + diveId + "-1",
                diveId, "D001", "DESCENT", 0, depth, transit, at));
        postJson("/api/dives/records", Requests.record(objectMapper, "RD-" + diveId + "-2",
                diveId, "D002", "DESCENT", 0, depth, transit, at));
    }
}
