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
 * 潮汐 HTTP 契约层：真实经过 DispatcherServlet 校验 200/202 受理、
 * 400 参数错误、404 对象不存在、409 版本失配（携带当前版本）、422 业务拒绝。
 */
@AutoConfigureMockMvc
class TideHttpContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String CONFIG_BODY = """
            {"requestId":"tc-h1","zoneCode":"ZN","backwaterThreshold":5.000,
             "expectedVersion":null}
            """;

    @Test
    void createConfig_returns200_andQueryReturnsConfig() throws Exception {
        mockMvc.perform(post("/api/tide-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIG_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.currentMode").value("NORMAL"))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(get("/api/tide-configs/ZN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backwaterThreshold").value(5.0))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void configOnUnknownZone_returns404() throws Exception {
        String body = """
                {"requestId":"tc-h2","zoneCode":"ZZ","backwaterThreshold":5.000}
                """;
        mockMvc.perform(post("/api/tide-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    void negativeThreshold_returns400() throws Exception {
        String body = """
                {"requestId":"tc-h3","zoneCode":"ZN","backwaterThreshold":-0.5}
                """;
        mockMvc.perform(post("/api/tide-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tideLevel_negativeLevel_returns400() throws Exception {
        String body = """
                {"requestId":"tl-h1","zoneCode":"ZN","tideLevel":-1.0,
                 "observedAt":"2026-09-26T00:01:00Z","expectedVersion":1}
                """;
        mockMvc.perform(post("/api/tide-levels").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tideLevel_missingObservedAt_returns400() throws Exception {
        String body = """
                {"requestId":"tl-h2","zoneCode":"ZN","tideLevel":5.0,"expectedVersion":1}
                """;
        mockMvc.perform(post("/api/tide-levels").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tideLevel_unconfiguredZone_returns422() throws Exception {
        String body = """
                {"requestId":"tl-h3","zoneCode":"ZN","tideLevel":5.0,
                 "observedAt":"2026-09-26T00:01:00Z","expectedVersion":1}
                """;
        mockMvc.perform(post("/api/tide-levels").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_NOT_CONFIGURED"));
    }

    @Test
    void tideLevel_versionMismatch_returns409WithCurrentVersion() throws Exception {
        mockMvc.perform(post("/api/tide-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIG_BODY))
                .andExpect(status().isOk());

        String body = """
                {"requestId":"tl-h4","zoneCode":"ZN","tideLevel":5.0,
                 "observedAt":"2026-09-26T00:01:00Z","expectedVersion":9}
                """;
        mockMvc.perform(post("/api/tide-levels").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void fullFlow_tideEnterForced_candidateAndTrailQueryable() throws Exception {
        mockMvc.perform(post("/api/tide-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIG_BODY));
        String tide = """
                {"requestId":"tl-h5","zoneCode":"ZN","tideLevel":5.000,
                 "observedAt":"2026-09-26T00:01:00Z","expectedVersion":1}
                """;
        mockMvc.perform(post("/api/tide-levels").contentType(MediaType.APPLICATION_JSON)
                        .content(tide))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("ENTER_FORCED"))
                .andExpect(jsonPath("$.candidateId").isNumber());

        mockMvc.perform(get("/api/tide-levels").param("zoneCode", "ZN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].observedAt")
                        .value("2026-09-26T00:01:00Z"));

        mockMvc.perform(get("/api/tide-switches/candidates").param("zoneCode", "ZN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].stations.length()").value(2));

        mockMvc.perform(get("/api/tide-switches/trail").param("zoneCode", "ZN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void unknownCandidate_returns404() throws Exception {
        mockMvc.perform(get("/api/tide-switches/candidates/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_FOUND"));
    }

    @Test
    void unconfiguredZoneConfigQuery_returns404() throws Exception {
        mockMvc.perform(get("/api/tide-configs/ZN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIDE_CONFIG_NOT_FOUND"));
    }
}
