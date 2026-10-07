package com.example.starter.rolling.api;

import com.example.starter.rolling.AbstractRollingIntegrationTest;
import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.service.RollLifeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返修放行 HTTP 公开契约：登记/申请/双检验员放行主流程 200、参数校验 400、同键异参与旧版本 409、
 * 代次链组合查询同时返回代次链与各代独立台账。
 */
@AutoConfigureMockMvc
class ReworkControllerIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RollLifeService rollLifeService;

    private void expireAndFreeze(String rollNo) {
        rollLifeService.mount(new RollMountRequest("mount-" + rollNo, rollNo,
                new BigDecimal("10"), T0));
        rollLifeService.reportActual(new RollActualRequest(
                "a1-" + rollNo, rollNo, new BigDecimal("10"), T0.plusHours(1)));
        rollLifeService.freeze(rollNo, new RollFreezeRequest("fz-" + rollNo));
    }

    @Test
    void 返修放行主流程与代次链查询返回200() throws Exception {
        expireAndFreeze("H1");

        mockMvc.perform(post("/api/rolling/physical-rolls")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-reg","physicalNo":"HP1","rollNo":"H1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalNo").value("HP1"))
                .andExpect(jsonPath("$.headGenerationNo").value(1))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/rolling/rework-applications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rw","physicalNo":"HP1","oldRollNo":"H1",
                                 "newRollNo":"H2","diameterAfterGrindMm":510.00,
                                 "cycleLimitKm":8,"inspectionCertNo":"CERT-H",
                                 "mountedAt":"2026-09-26T08:00:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSPECTING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.id").isNumber());

        Long applicationId = jdbc.queryForObject(
                "SELECT id FROM rework_application WHERE request_id = 'h-rw'", Long.class);

        mockMvc.perform(post("/api/rolling/rework-applications/" + applicationId + "/inspections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-in1","inspector":"QA-A","qualified":true,
                                 "expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(post("/api/rolling/rework-applications/" + applicationId + "/inspections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-in2","inspector":"QA-B","qualified":true,
                                 "expectedVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.newGenerationNo").value(2))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(get("/api/rolling/physical-rolls/HP1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physical.headRollNo").value("H2"))
                .andExpect(jsonPath("$.physical.reworkCount").value(1))
                .andExpect(jsonPath("$.generations.length()").value(2))
                .andExpect(jsonPath("$.generations[0].rollNo").value("H1"))
                .andExpect(jsonPath("$.generations[1].rollNo").value("H2"))
                .andExpect(jsonPath("$.generations[1].diameterMm").value(510.00))
                .andExpect(jsonPath("$.ledgers.length()").value(2))
                .andExpect(jsonPath("$.ledgers[0].status").value("BANNED"))
                .andExpect(jsonPath("$.ledgers[0].totalKm").value(10))
                .andExpect(jsonPath("$.ledgers[1].rollNo").value("H2"))
                .andExpect(jsonPath("$.ledgers[1].totalKm").value(0));
    }

    @Test
    void 缺必填字段返回400() throws Exception {
        mockMvc.perform(post("/api/rolling/physical-rolls")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"","physicalNo":"HP-BAD"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 直径小于500业务拒绝400且同键改参可重试() throws Exception {
        expireAndFreeze("H3");
        mockMvc.perform(post("/api/rolling/physical-rolls")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-reg2","physicalNo":"HP2","rollNo":"H3"}
                                """))
                .andExpect(status().isOk());

        // 直径 499.99：业务校验失败（失败不占键）。
        mockMvc.perform(post("/api/rolling/rework-applications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rw2","physicalNo":"HP2","oldRollNo":"H3",
                                 "newRollNo":"H4","diameterAfterGrindMm":499.99,
                                 "cycleLimitKm":8,"inspectionCertNo":"CERT-H2",
                                 "mountedAt":"2026-09-26T08:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("少于 500")));

        // 同一 requestId 改正直径后可成功（失败不占幂等键）。
        mockMvc.perform(post("/api/rolling/rework-applications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rw2","physicalNo":"HP2","oldRollNo":"H3",
                                 "newRollNo":"H4","diameterAfterGrindMm":500.00,
                                 "cycleLimitKm":8,"inspectionCertNo":"CERT-H2",
                                 "mountedAt":"2026-09-26T08:00:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSPECTING"));
    }

    @Test
    void 同键异参返回409() throws Exception {
        expireAndFreeze("H5");
        mockMvc.perform(post("/api/rolling/physical-rolls")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-reg3","physicalNo":"HP3","rollNo":"H5"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rolling/rework-applications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rw3","physicalNo":"HP3","oldRollNo":"H5",
                                 "newRollNo":"H6A","diameterAfterGrindMm":505.00,
                                 "cycleLimitKm":8,"inspectionCertNo":"CERT-H3",
                                 "mountedAt":"2026-09-26T08:00:00"}
                                """))
                .andExpect(status().isOk());
        // 同 requestId 换新辊号：409。
        mockMvc.perform(post("/api/rolling/rework-applications")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rw3","physicalNo":"HP3","oldRollNo":"H5",
                                 "newRollNo":"H6B","diameterAfterGrindMm":505.00,
                                 "cycleLimitKm":8,"inspectionCertNo":"CERT-H3",
                                 "mountedAt":"2026-09-26T08:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
