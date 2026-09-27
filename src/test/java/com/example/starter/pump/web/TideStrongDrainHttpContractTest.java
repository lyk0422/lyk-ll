package com.example.starter.pump.web;

import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 潮汐强排 HTTP 契约：配置/潮位/回执/确认的受理状态、400 参数错误、404、409 携带当前版本。
 */
@AutoConfigureMockMvc
class TideStrongDrainHttpContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullTideSwitchFlow_overHttp() throws Exception {
        // 配置（首次，版本1）
        mockMvc.perform(post("/api/tide/configs").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-cfg","zoneCode":"ZN","thresholdLevel":3.000,"expectedVersion":0}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regimeVersion").value(1))
                .andExpect(jsonPath("$.created").value(true));

        // 潮位恰等门槛 -> 202 + ENTER 候选
        mockMvc.perform(post("/api/tide/readings").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-tr","zoneCode":"ZN",
                 "tideTime":"2026-09-26T00:00:00Z","tideLevel":3.000}
                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.desiredMode").value("STRONG"))
                .andExpect(jsonPath("$.candidateDirection").value("ENTER"))
                .andExpect(jsonPath("$.candidateId").isNumber());

        long candidateId = fetchCandidateId();

        // 两站回执
        receipt("hc-g1", candidateId, "PS-01", 80.00);
        receipt("hc-g2", candidateId, "PS-02", 75.00);

        // 确认 -> 200，两站 START
        mockMvc.perform(post("/api/tide/candidate-confirmations")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"hc-cf","sequenceId":%d,"expectedVersion":1,
                                 "confirmTime":"2026-09-26T00:02:00Z"}
                                """.formatted(candidateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.tasks.length()").value(2));

        // 工况查询
        mockMvc.perform(get("/api/zones/ZN/tide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.currentMode").value("STRONG"));
    }

    @Test
    void invalidTideLevel_returns400() throws Exception {
        mockMvc.perform(post("/api/tide/configs").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-bad","zoneCode":"ZN","thresholdLevel":3.000,"expectedVersion":0}
                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tide/readings").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-bad2","zoneCode":"ZN",
                 "tideTime":"2026-09-26T00:00:00Z","tideLevel":-1.000}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void staleConfigVersion_returns409WithCurrentVersion() throws Exception {
        mockMvc.perform(post("/api/tide/configs").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-v1","zoneCode":"ZN","thresholdLevel":3.000,"expectedVersion":0}
                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tide/configs").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-v2","zoneCode":"ZN","thresholdLevel":3.500,"expectedVersion":9}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_VERSION_MISMATCH"))
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void unknownZoneTideView_is404OnConfig_andViewSaysUnconfigured() throws Exception {
        mockMvc.perform(post("/api/tide/configs").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"hc-nz","zoneCode":"NOPE","thresholdLevel":3.000,"expectedVersion":0}
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        // 查询未配置分区不报错：configured=false
        mockMvc.perform(get("/api/zones/ZB/tide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    private void receipt(String reqId, long sequenceId, String station, double opening)
            throws Exception {
        mockMvc.perform(post("/api/tide/gate-receipts")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","sequenceId":%d,"stationCode":"%s",
                                 "opening":%.2f,"receiptTime":"2026-09-26T00:01:00Z"}
                                """.formatted(reqId, sequenceId, station, opening))
                )
                .andExpect(status().isAccepted());
    }

    private long fetchCandidateId() throws Exception {
        String json = mockMvc.perform(get("/api/zones/ZN/tide"))
                .andReturn().getResponse().getContentAsString();
        int key = json.indexOf("\"openCandidate\"");
        int idKey = json.indexOf("\"id\"", key);
        int colon = json.indexOf(':', idKey);
        int end = json.indexOf(',', colon);
        return Long.parseLong(json.substring(colon + 1, end).trim());
    }
}
