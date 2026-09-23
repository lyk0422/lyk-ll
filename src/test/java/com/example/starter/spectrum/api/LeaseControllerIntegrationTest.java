package com.example.starter.spectrum.api;

import com.example.starter.spectrum.AbstractSpectrumIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租约 REST API 的 HTTP 入口集成测试：创建、冲突解释、快照、校验与幂等 409。
 */
@AutoConfigureMockMvc
@Import(AbstractSpectrumIntegrationTest.FixedClockConfiguration.class)
class LeaseControllerIntegrationTest extends AbstractSpectrumIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String SHARED_A = """
            {
              "requestId": "http-1",
              "regions": ["CELL-A"],
              "start": "2030-01-01T10:00:00Z",
              "end": "2030-01-01T12:00:00Z",
              "freqLowMHz": 100,
              "freqHighMHz": 200,
              "mode": "SHARED",
              "eirpMw": 500
            }
            """;

    @Test
    void createThenExplainConflictThenSnapshotOverHttp() throws Exception {
        // 1) 创建共享租约
        String response = mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(SHARED_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.regions[0]").value("CELL-A"))
                .andExpect(jsonPath("$.events[0].eventType").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        String leaseId = com.jayway.jsonpath.JsonPath.read(response, "$.leaseId");

        // 2) 冲突解释：同区域同时段再申请独占 => compatible=false，原因 EXCLUSIVE_OVERLAP
        String exclusiveProposal = """
                {
                  "regions": ["CELL-A"],
                  "start": "2030-01-01T10:00:00Z",
                  "end": "2030-01-01T12:00:00Z",
                  "freqLowMHz": 150,
                  "freqHighMHz": 180,
                  "mode": "EXCLUSIVE",
                  "eirpMw": 10
                }
                """;
        mockMvc.perform(post("/api/leases/explain")
                        .contentType(MediaType.APPLICATION_JSON).content(exclusiveProposal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.details[0].reason").value("EXCLUSIVE_OVERLAP"))
                .andExpect(jsonPath("$.details[0].regionCell").value("CELL-A"));

        // 3) 直接创建该独占租约 => 409
        String exclusiveCreate = """
                {
                  "requestId": "http-2",
                  "regions": ["CELL-A"],
                  "start": "2030-01-01T10:00:00Z",
                  "end": "2030-01-01T12:00:00Z",
                  "freqLowMHz": 150,
                  "freqHighMHz": 180,
                  "mode": "EXCLUSIVE",
                  "eirpMw": 10
                }
                """;
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(exclusiveCreate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_CONFLICT"));

        // 4) 活动快照：窗口内包含该租约
        mockMvc.perform(get("/api/leases/active")
                        .param("at", "2030-01-01T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].leaseId").value(leaseId));

        // 5) 取消
        mockMvc.perform(post("/api/leases/" + leaseId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"http-3","leaseId":"%s","expectedVersion":1}
                                """.formatted(leaseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(SHARED_A))
                .andExpect(status().isCreated());

        String changed = SHARED_A.replace("\"eirpMw\": 500", "\"eirpMw\": 501");
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_MISMATCH"));
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        String missingRegion = """
                {
                  "requestId": "http-bad",
                  "regions": [],
                  "start": "2030-01-01T10:00:00Z",
                  "end": "2030-01-01T12:00:00Z",
                  "freqLowMHz": 100,
                  "freqHighMHz": 200,
                  "mode": "SHARED",
                  "eirpMw": 500
                }
                """;
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(missingRegion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void occupancyEndpointFiltersByQuery() throws Exception {
        mockMvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON).content(SHARED_A))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/leases/occupancy")
                        .param("at", "2030-01-01T11:00:00Z")
                        .param("region", "CELL-A")
                        .param("freqLowMHz", "150").param("freqHighMHz", "160"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regions[0]").value("CELL-A"));
    }
}
