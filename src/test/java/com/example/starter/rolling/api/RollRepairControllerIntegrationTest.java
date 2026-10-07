package com.example.starter.rolling.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.rolling.AbstractRollingIntegrationTest;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返修放行流程 HTTP 公开契约：主流程两人放行、代次链查询、直径字段校验 400、
 * 成功后继再建 409、迟到旧实绩证据。
 */
@AutoConfigureMockMvc
class RollRepairControllerIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 返修主流程两人放行并查询代次链() throws Exception {
        // 首代上机、跑满 10 公里到期、日冻结。
        mockMvc.perform(post("/api/rolling/rolls/mount").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"h-mount","rollNo":"RH-1","cycleLimitKm":10,
                 "mountedAt":"2026-10-08T00:00:00"}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/rolling/rolls/actuals").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"h-wear","rollNo":"RH-1","rollingKm":10,
                 "recordedAt":"2026-10-08T01:00:00"}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.banned").value(true));
        mockMvc.perform(post("/api/rolling/rolls/RH-1/freeze")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-freeze"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozenAt").isNotEmpty());

        // 返修申请：直径 560.00、新辊号 RH-2、新上限 9、expectedVersion 1。
        MvcResult applyResult = mockMvc.perform(post("/api/rolling/rolls/repairs")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rp1","oldRollNo":"RH-1","groundDiameterMm":560.00,
                                 "newRollNo":"RH-2","newCycleLimitKm":9,
                                 "inspectionCert":"CERT-H-1","expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.aggregateVersion").value(1))
                .andExpect(jsonPath("$.groundDiameterMm").value(560.00))
                .andReturn();
        long applicationId = ((Number) JsonPath.read(
                applyResult.getResponse().getContentAsString(), "$.applicationId")).longValue();

        // 两名不同检验员确认合格。
        mockMvc.perform(post("/api/rolling/rolls/repairs/" + applicationId + "/inspections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-insp-a","inspectorId":"INS-A","approved":true,
                                 "certNo":"CERT-A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvals").value(1));
        mockMvc.perform(post("/api/rolling/rolls/repairs/" + applicationId + "/inspections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-insp-b","inspectorId":"INS-B","approved":true,
                                 "certNo":"CERT-B"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.successorRollNo").value("RH-2"))
                .andExpect(jsonPath("$.aggregateVersion").value(2));

        // 新代独立台账：里程 0、新上限 9。
        mockMvc.perform(get("/api/rolling/rolls/RH-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKm").value(0))
                .andExpect(jsonPath("$.cycleLimitKm").value(9))
                .andExpect(jsonPath("$.status").value("IN_SERVICE"));

        // 旧代保留禁排与冻结结论。
        mockMvc.perform(get("/api/rolling/rolls/RH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BANNED"))
                .andExpect(jsonPath("$.totalKm").value(10))
                .andExpect(jsonPath("$.frozenAt").isNotEmpty());

        // 代次链同时呈现链与各代台账。
        mockMvc.perform(get("/api/rolling/rolls/repairs/by-roll/RH-2/chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRollNo").value("RH-2"))
                .andExpect(jsonPath("$.currentGenerationNo").value(2))
                .andExpect(jsonPath("$.repairCount").value(1))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.generations.length()").value(2))
                .andExpect(jsonPath("$.generations[0].rollNo").value("RH-1"))
                .andExpect(jsonPath("$.generations[0].status").value("BANNED"))
                .andExpect(jsonPath("$.generations[1].rollNo").value("RH-2"))
                .andExpect(jsonPath("$.generations[1].totalKm").value(0));

        // 成功后继不能再建：对旧代再申请 409。
        mockMvc.perform(post("/api/rolling/rolls/repairs")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-rp2","oldRollNo":"RH-1","groundDiameterMm":540.00,
                                 "newRollNo":"RH-3","newCycleLimitKm":8,
                                 "inspectionCert":"CERT-H-2","expectedVersion":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("成功后继")));

        // 建代后迟到旧实绩只落证据。
        mockMvc.perform(post("/api/rolling/rolls/actuals").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"h-late","rollNo":"RH-1","rollingKm":3,
                 "recordedAt":"2026-10-08T02:00:00"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.totalKm").value(10));
        mockMvc.perform(get("/api/rolling/rolls/repairs/by-roll/RH-1/chain"))
                .andExpect(jsonPath("$.generations[0].lateEvidenceCount").value(1))
                .andExpect(jsonPath("$.generations[1].lateEvidenceCount").value(0));
    }

    @Test
    void 直径低于500毫米请求体校验400() throws Exception {
        mockMvc.perform(post("/api/rolling/rolls/repairs")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"h-bad","oldRollNo":"RH-X","groundDiameterMm":499.99,
                                 "newRollNo":"RH-Y","newCycleLimitKm":9,
                                 "inspectionCert":"CERT","expectedVersion":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 不存在返修申请查询404() throws Exception {
        mockMvc.perform(get("/api/rolling/rolls/repairs/999999"))
                .andExpect(status().isNotFound());
    }
}
