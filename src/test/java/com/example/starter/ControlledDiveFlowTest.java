package com.example.starter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受控反复潜水主流程与门禁的 H2 集成测试（虚构积分模型，不用于实际减压）。
 */
class ControlledDiveFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private String postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
    }

    private int code(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn().getResponse().getStatus();
    }

    /** 走完受控潜次的观察结案（复用旧入口），返回结案时刻。 */
    private Instant closeObservation(String closeReqId, String diveId, Instant surfaced) throws Exception {
        Instant closedAt = surfaced.plus(Duration.ofHours(2));
        mockMvc.perform(post("/api/dives/close").contentType("application/json")
                        .content(Requests.close(objectMapper, closeReqId, diveId, closedAt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        return closedAt;
    }

    /**
     * 首潜两人积分各 0、恰用尽 NDL；出水各 50 分。再潜时：恰 60 分钟仅过间隔门禁但观察未完成拒绝；
     * 结案后于出水 +120 分钟再潜，积分消减到 30，允许 20 分钟，两人分别计账（D001=50、D003=20）。
     */
    @Test
    void firstDiveThenRepeat_intervalObservationDecayAndPerDiverPoints() throws Exception {
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-1", "D001", "D002", 18, 50));
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-1", "CD-1", 0, 18, 50, T0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.teamAllowedBottomMin").value(50))
                .andExpect(jsonPath("$.divers[0].pointsBefore").value(0))
                .andExpect(jsonPath("$.divers[0].allowedBottomMin").value(50))
                .andExpect(jsonPath("$.divers[0].predictedExitPoints").value(50));

        mockMvc.perform(post("/api/controlled-dives/start").contentType("application/json")
                        .content(Requests.controlledStart(objectMapper, "CS-1", "CD-1", 1, T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DIVING"))
                .andExpect(jsonPath("$.divers[0].pointsAtStart").value(0));

        Instant surfaced1 = T0.plusSeconds(60L * 55);
        mockMvc.perform(post("/api/controlled-dives/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "CU-1", "CD-1", 1, 50, surfaced1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SURFACED"))
                .andExpect(jsonPath("$.divers[0].exitPoints").value(50))
                .andExpect(jsonPath("$.divers[1].exitPoints").value(50));
        assertEquals(50, jdbc.queryForObject(
                "SELECT points FROM diver_points_account WHERE diver_code='D001'", Integer.class).intValue());
        // 出水即释放潜水员级槽
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM diver_dive_slot", Integer.class).intValue());

        // 不同潜水组共享 D001（队键不同）可申请：用于验证恰 60 分钟仅过间隔门禁
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-2", "D001", "D003", 18, 20));
        // 距上次出水恰 60 分钟：间隔通过，但上一潜未完成 2 小时观察 → 422
        assertEquals(422, code("/api/controlled-dives/plans", Requests.controlledPlan(
                objectMapper, "CP-2-EARLY", "CD-2", 0, 18, 20, surfaced1.plusSeconds(60L * 60))));

        // 完成首潜 2 小时观察结案（释放队占用）
        closeObservation("CC-1", "CD-1", surfaced1);

        // 出水 +120 分钟：消减 4 步 20 分，D001 当前 30，18 米允许 50-30=20；D003 首次 0 允许 50，取较短 20
        Instant descent2 = surfaced1.plusSeconds(60L * 120);
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-2", "CD-2", 0, 18, 20, descent2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamAllowedBottomMin").value(20))
                .andExpect(jsonPath("$.divers[?(@.diverCode=='D001')].pointsBefore").value(30))
                .andExpect(jsonPath("$.divers[?(@.diverCode=='D003')].pointsBefore").value(0));

        mockMvc.perform(post("/api/controlled-dives/start").contentType("application/json")
                        .content(Requests.controlledStart(objectMapper, "CS-2", "CD-2", 1, descent2)))
                .andExpect(status().isOk());
        Instant surfaced2 = descent2.plusSeconds(60L * 25);
        mockMvc.perform(post("/api/controlled-dives/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "CU-2", "CD-2", 1, 20, surfaced2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.divers[?(@.diverCode=='D001')].exitPoints").value(50))
                .andExpect(jsonPath("$.divers[?(@.diverCode=='D003')].exitPoints").value(20));

        // 查询：潜水员积分账
        mockMvc.perform(get("/api/controlled-dives/divers/D001/points"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.residualPoints").value(50))
                .andExpect(jsonPath("$.lastDiveRequestId").value("CD-2"));
        mockMvc.perform(get("/api/controlled-dives/divers/D003/points"))
                .andExpect(jsonPath("$.residualPoints").value(20));
        // 潜次积分冻结视图可用
        mockMvc.perform(get("/api/controlled-dives/CD-2/points"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsControlled").value(true))
                .andExpect(jsonPath("$.divers.length()").value(2));
    }

    /** 距上次出水不足 60 分钟禁止再潜（恰 60 通过间隔，此处 59 被 422）。 */
    @Test
    void lessThanSixtyMinutesInterval_rejected() throws Exception {
        firstControlledDiveClosed("CD-I", "D001", "D002", 18, 50, T0, T0.plusSeconds(60L * 55));
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-I2", "D001", "D002", 18, 10));
        Instant surfaced = T0.plusSeconds(60L * 55);
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-I", "CD-I2", 0, 18, 10,
                                surfaced.plusSeconds(60L * 59))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 恰 60 分钟通过间隔门禁但当前积分使允许分钟 <=0：40 米 NDL=9，积分 40，允许为负 → 422。 */
    @Test
    void allowedMinutesNonPositive_rejected_evenThoughIntervalPasses() throws Exception {
        Instant surfaced = firstControlledDiveClosed("CD-Z", "D001", "D002", 18, 50, T0,
                T0.plusSeconds(60L * 55));
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-Z2", "D001", "D002", 40, 5));
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-Z", "CD-Z2", 0, 40, 5,
                                surfaced.plusSeconds(60L * 60))))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 出水残留积分上限 100：下潜前 60 + 本次 41 = 101，方案 422 拒绝且不留冻结。 */
    @Test
    void exitPointsAboveOneHundred_rejectedAtPlan() throws Exception {
        // 首潜 12 米 70 分钟（NDL=120，首次积分 0，出水 70）
        Instant s1 = T0.plusSeconds(60L * 75);
        firstControlledDiveClosed("CD-H1", "D001", "D002", 12, 70, T0, s1);
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-H2", "D001", "D002", 12, 41));
        // +60 分钟消减 10：70-10=60；12 米允许 120-60=60（41 未超允许），但出水 60+41=101>100
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-H", "CD-H2", 0, 12, 41,
                                s1.plusSeconds(60L * 60))))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM controlled_plan_freeze WHERE dive_request_id='CD-H2'",
                Integer.class).intValue());
    }

    /** expectedVersion 从 0 起、成功 +1；版本不符 409；变更版本冻结旧冻结快照。 */
    @Test
    void expectedVersionStartsAtZero_conflictMismatch_andChangeRebuildsFreeze() throws Exception {
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-V", "D003", "D004", 18, 50));
        // 无方案时版本为 0，传 1 直接冲突
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-V-BAD", "CD-V", 1, 18, 50, T0)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-V-1", "CD-V", 0, 18, 50, T0)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1));
        // 再次以 0 提交（当前已是 1）→ 409
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-V-STALE", "CD-V", 0, 18, 40, T0)))
                .andExpect(status().isConflict());
        // 以版本 1 变更（未下潜可改）→ 版本 2，旧方案冻结、冻结快照仍为每人一条且挂 v2
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-V-2", "CD-V", 1, 18, 40, T0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.frozenOldVersions").value(1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(1) FROM controlled_plan_freeze WHERE dive_request_id='CD-V' AND version=2",
                Integer.class).intValue());
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT frozen FROM dive_plan WHERE dive_request_id='CD-V' AND version=1", Boolean.class));
    }

    /** 已实际下潜后旧方案不能消费新积分：冻结来源被另一潜次改变，重查拒绝并要求重做方案。 */
    @Test
    void startRecheck_sourceChangedAfterFreeze_rejected() throws Exception {
        // X 潜次 D001+D002 出方案冻结但不实际下潜
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, "CD-X", "D001", "D002", 18, 50));
        postJson("/api/controlled-dives/plans", Requests.controlledPlan(
                objectMapper, "CP-X", "CD-X", 0, 18, 50, T0));

        // 先让 D001 完成一次受控潜（与 D003 组队，队键不同；经过出水/观察结案）
        firstControlledDiveClosed("CD-PRIOR", "D001", "D003", 18, 30,
                T0.plusSeconds(3600 * 24L), T0.plusSeconds(3600L * 24 + 60L * 35));

        // X 的冻结来源已过期（D001 新出积分），实际下潜重查 → 409
        mockMvc.perform(post("/api/controlled-dives/start").contentType("application/json")
                        .content(Requests.controlledStart(objectMapper, "CS-X", "CD-X", 1,
                                T0.plusSeconds(3600 * 48L))))
                .andExpect(status().isConflict());
        // 拒绝未占潜水员槽、未进入 DIVING
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM diver_dive_slot", Integer.class).intValue());
        assertEquals("PLANNED", diveRepository.get("CD-X").status());
    }

    /** 旧语义潜次不受控：受控接口 422；旧申请积分标记为 FALSE。 */
    @Test
    void legacyDiveKeepsOldSemantics_controlledEndpointRejects() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "OLD-1", "D001", "D002", 18, 50));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT points_controlled FROM dive WHERE request_id='OLD-1'", Boolean.class));
        mockMvc.perform(post("/api/controlled-dives/plans").contentType("application/json")
                        .content(Requests.controlledPlan(objectMapper, "CP-OLD", "OLD-1", 0, 18, 50, T0)))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 对象不存在 404、参数非法 400。 */
    @Test
    void notFound_andBadRequest() throws Exception {
        mockMvc.perform(get("/api/controlled-dives/NO-SUCH/points"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/controlled-dives/apply").contentType("application/json")
                        .content(Requests.controlledApply(objectMapper, "BAD", "D001", "D001", 18, 50)))
                .andExpect(status().isBadRequest());
    }

    /**
     * 跑通并结案一次受控潜次（申请→方案→下潜→出水→2 小时观察结案），返回收件时刻。
     */
    private Instant firstControlledDiveClosed(String id, String a, String b, int depth, int bottom,
                                              Instant descent, Instant surfaced) throws Exception {
        postJson("/api/controlled-dives/apply", Requests.controlledApply(
                objectMapper, id, a, b, depth, bottom));
        postJson("/api/controlled-dives/plans", Requests.controlledPlan(
                objectMapper, "PLAN-" + id, id, 0, depth, bottom, descent));
        postJson("/api/controlled-dives/start", Requests.controlledStart(
                objectMapper, "START-" + id, id, 1, descent));
        mockMvc.perform(post("/api/controlled-dives/surface").contentType("application/json")
                        .content(Requests.controlledSurface(objectMapper, "SURF-" + id, id, 1,
                                bottom, surfaced)))
                .andExpect(status().isOk());
        closeObservation("CLOSE-" + id, id, surfaced);
        return surfaced;
    }
}
