package com.example.starter;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 减压方案变更：旧方案与已生成记录在同一事务内冻结，台账清退；变更失败则整体不动。
 */
class PlanChangeFreezeTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    private void postJson(String uri, String json) throws Exception {
        mockMvc.perform(post(uri).contentType("application/json").content(json)).andReturn();
    }

    @Test
    void planChangeFreezesOldPlanAndRecordsAtomically_newRecordsHangOffNewVersion() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "DIVE-PF", "D001", "D002",
                18, 50));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "PLAN-PF-1", "DIVE-PF", 18, 50));

        // v1 成对下潜与水底：记录生效、台账入账
        postJson("/api/dives/records", Requests.record(objectMapper, "PF-D1", "DIVE-PF", "D001",
                "DESCENT", 0, 18, 1, T0));
        postJson("/api/dives/records", Requests.record(objectMapper, "PF-D2", "DIVE-PF", "D002",
                "DESCENT", 0, 18, 1, T0));
        postJson("/api/dives/records", Requests.record(objectMapper, "PF-B1", "DIVE-PF", "D001",
                "BOTTOM", 18, 18, 40, T0.plusSeconds(60)));
        postJson("/api/dives/records", Requests.record(objectMapper, "PF-B2", "DIVE-PF", "D002",
                "BOTTOM", 18, 18, 40, T0.plusSeconds(60)));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM bottom_time_ledger "
                        + "WHERE dive_request_id='DIVE-PF'", Integer.class).intValue());

        // 变更为 24 米 30 分钟（NDL=30，恰极限合法）
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "PLAN-PF-2", "DIVE-PF", 24, 30)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.frozenOldVersions").value(1));

        // 原子冻结：v1 方案冻结、v1 全部记录冻结且不再生效、台账清退
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,
                jdbc.queryForObject("SELECT frozen FROM dive_plan WHERE dive_request_id='DIVE-PF' "
                        + "AND version=1", Boolean.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM dive_record "
                        + "WHERE dive_request_id='DIVE-PF' AND (frozen=FALSE OR effective=TRUE)",
                        Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM bottom_time_ledger "
                        + "WHERE dive_request_id='DIVE-PF'", Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2, diveRepository.get("DIVE-PF").currentPlanVersion());
        org.junit.jupiter.api.Assertions.assertEquals("PLANNED", diveRepository.get("DIVE-PF").status());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                eventRepository.countByType("DIVE-PF", "PLAN_FROZEN"));

        // 旧方案深度形状的记录不再被接受（当前版本深度已是 24）
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "PF-OLD", "DIVE-PF", "D001",
                                "DESCENT", 0, 18, 1, T0.plusSeconds(3600))))
                .andExpect(status().isBadRequest());

        // 新版本成对记录生效并重新入台账
        postJson("/api/dives/records", Requests.record(objectMapper, "PF-N1", "DIVE-PF", "D001",
                "BOTTOM", 24, 24, 30, T0.plusSeconds(3600)));
        mockMvc.perform(post("/api/dives/records").contentType("application/json")
                        .content(Requests.record(objectMapper, "PF-N2", "DIVE-PF", "D002",
                                "BOTTOM", 24, 24, 30, T0.plusSeconds(3600))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planVersion").value(2))
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.cumulativeBottomMin24h").value(30));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM bottom_time_ledger "
                        + "WHERE dive_request_id='DIVE-PF' AND plan_version=2", Integer.class)
                        .intValue());
    }

    /** 变更请求本身非法（超 NDL）→ 422，旧方案与记录保持原样，证明冻结与新建同事务原子。 */
    @Test
    void invalidPlanChangeLeavesOldPlanIntact() throws Exception {
        postJson("/api/dives/apply", Requests.apply(objectMapper, "DIVE-PC", "D003", "D004",
                18, 50));
        postJson("/api/dives/plans", Requests.plan(objectMapper, "PLAN-PC-1", "DIVE-PC", 18, 50));
        postJson("/api/dives/records", Requests.record(objectMapper, "PC-B1", "DIVE-PC", "D003",
                "BOTTOM", 18, 18, 20, T0));
        postJson("/api/dives/records", Requests.record(objectMapper, "PC-B2", "DIVE-PC", "D004",
                "BOTTOM", 18, 18, 20, T0));

        // 24 米 NDL=30，请求 31 分钟 → 422
        mockMvc.perform(post("/api/dives/plans").contentType("application/json")
                        .content(Requests.plan(objectMapper, "PLAN-PC-BAD", "DIVE-PC", 24, 31)))
                .andExpect(status().isUnprocessableEntity());

        // 旧方案未冻结、记录仍生效、台账保留，版本仍为 1
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                jdbc.queryForObject("SELECT frozen FROM dive_plan WHERE dive_request_id='DIVE-PC' "
                        + "AND version=1", Boolean.class));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM dive_record "
                        + "WHERE dive_request_id='DIVE-PC' AND effective=TRUE AND frozen=FALSE",
                        Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(1) FROM bottom_time_ledger "
                        + "WHERE dive_request_id='DIVE-PC'", Integer.class).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, diveRepository.get("DIVE-PC").currentPlanVersion());
    }
}
