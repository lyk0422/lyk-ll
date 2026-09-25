package com.example.starter;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同一潜水员 24 小时（UTC）累计水下时间 6 小时上限：恰 360 分钟合法，361 拒绝且记录不生效。
 */
class CumulativeBottomTimeTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private void postJson(String uri, String json) throws Exception {
        mockMvc.perform(post(uri).contentType("application/json").content(json))
                .andReturn();
    }

    /**
     * 窗口内先完成 120+120+70=310 分钟（三潜），第 4 潜再下 50 分钟恰 360 合法；
     * 第 5 潜 15 分钟使累计 375＞360，成对记录整体回滚不生效、不入台账。
     */
    @Test
    void exactlySixHoursAllowed_sevenMinutesOverRejectedAndIneffective() throws Exception {
        completeDive("DIVE-A", "PA", T0, 12, 120, T0);
        completeDive("DIVE-B", "PB", T0.plusSeconds(3600 * 6L), 12, 120, T0.plusSeconds(3600 * 6L));
        completeDive("DIVE-C", "PC", T0.plusSeconds(3600 * 12L), 12, 70, T0.plusSeconds(3600 * 12L));

        // 第 4 潜：18 米 50 分钟，窗口内 310+50=360 恰上限；完整结案以释放潜水组占用
        Instant dBase = T0.plusSeconds(3600 * 23L);
        completeDive("DIVE-D", "PD", dBase, 18, 50, dBase);

        // 第 5 潜：再 15 分钟 → D001 窗口内 375＞360；成对提交必须整体回滚
        planDive("DIVE-E", "PE", 18, 15);
        Instant eBase = dBase.plusSeconds(1800);
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "RD-E-1", "DIVE-E", "D001",
                                "BOTTOM", 18, 18, 15, eBase)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "RD-E-2", "DIVE-E", "D002",
                                "BOTTOM", 18, 18, 15, eBase)))
                .andExpect(status().isUnprocessableEntity());

        // 第二人配对失败：第二人记录随事务回滚不存在；第一人早先提交的单人记录保留但不生效
        Integer e2Records = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_record WHERE dive_request_id='DIVE-E' AND diver_code='D002'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, e2Records.intValue());
        Integer e1Effective = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_record WHERE dive_request_id='DIVE-E' "
                        + "AND diver_code='D001' AND effective=FALSE AND frozen=FALSE", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, e1Effective.intValue());
        Integer eLedger = jdbc.queryForObject(
                "SELECT COUNT(1) FROM bottom_time_ledger WHERE dive_request_id='DIVE-E'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, eLedger.intValue());
        // 潜次未进入 DIVING（单人不生效）
        org.junit.jupiter.api.Assertions.assertEquals("PLANNED",
                diveRepository.get("DIVE-E").status());
        // D001 窗口台账仍为 360
        Integer total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(bottom_min),0) FROM bottom_time_ledger WHERE diver_code='D001'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(360, total.intValue());
    }

    /** 完成一潜（申请→方案→下潜/底/上升成对→出水→恰满 2 小时结案），释放潜水组占用。 */
    private void completeDive(String diveId, String planId, Instant base, int depth, int bottom,
                              Instant bottomTime) throws Exception {
        int transit = (int) Math.ceil(depth / 18.0);
        postJson("/api/dives/apply", Requests.apply(objectMapper, diveId, "D001", "D002",
                depth, bottom));
        postJson("/api/dives/plans", Requests.plan(objectMapper, planId, diveId, depth, bottom));
        pairPhase(diveId, "DESCENT", depth, transit, base);
        postJson("/api/dives/records", Requests.record(objectMapper, "R-" + diveId + "-B1",
                diveId, "D001", "BOTTOM", depth, depth, bottom, bottomTime));
        postJson("/api/dives/records", Requests.record(objectMapper, "R-" + diveId + "-B2",
                diveId, "D002", "BOTTOM", depth, depth, bottom, bottomTime));
        postJson("/api/dives/records", Requests.record(objectMapper, "R-" + diveId + "-A1",
                diveId, "D001", "ASCENT", depth, 0, transit,
                bottomTime.plusSeconds(60L * bottom)));
        postJson("/api/dives/records", Requests.record(objectMapper, "R-" + diveId + "-A2",
                diveId, "D002", "ASCENT", depth, 0, transit,
                bottomTime.plusSeconds(60L * bottom)));
        Instant surfaced = bottomTime.plusSeconds(60L * (bottom + transit));
        postJson("/api/dives/surface", Requests.surface(objectMapper, "S-" + diveId, diveId,
                surfaced));
        postJson("/api/dives/close", Requests.close(objectMapper, "C-" + diveId, diveId,
                surfaced.plusSeconds(3600 * 2L)));
    }

    private void pairPhase(String diveId, String phase, int depth, int duration, Instant base)
            throws Exception {
        if ("DESCENT".equals(phase)) {
            postJson("/api/dives/records", Requests.record(objectMapper, "R-" + diveId + "-D1",
                    diveId, "D001", phase, 0, depth, duration, base));
            postJson("/api/dives/records", Requests.record(objectMapper, "R-" + diveId + "-D2",
                    diveId, "D002", phase, 0, depth, duration, base));
        }
    }

    private void planDive(String diveId, String planId, int depth, int bottom) throws Exception {
        // 上一潜结案后才能再次申请同组
        postJson("/api/dives/apply", Requests.apply(objectMapper, diveId, "D001", "D002",
                depth, bottom));
        postJson("/api/dives/plans", Requests.plan(objectMapper, planId, diveId, depth, bottom));
    }
}
