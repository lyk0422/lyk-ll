package com.example.starter.blood;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 主流程集成测试：入库 → 乱序双检回传 → 放行发血 → 同键重放/异参 409 → 状态查询。
 */
@AutoConfigureMockMvc
class BloodApiIntegrationTest extends BloodServiceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        // 入库
        mockMvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content("""
                {"barcode":"API-001","componentType":"红细胞","expireDate":"2026-10-01"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expireAt").value("2026-10-01T23:59:59Z"));

        // 乱序回传：复检先合格 → 隔离
        mockMvc.perform(post("/api/units/API-001/test-results").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testType":"RECHECK","result":"QUALIFIED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISOLATED"))
                .andExpect(jsonPath("$.late").value(false));

        // 初检后合格 → 可放行
        mockMvc.perform(post("/api/units/API-001/test-results").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testType":"INITIAL","result":"QUALIFIED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASABLE"));

        // 放行发血
        mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"REQ-API-1","barcode":"API-001",
                 "windowStart":"2026-09-24T08:00:00Z","windowEnd":"2026-09-24T12:00:00Z"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.unitStatus").value("RELEASED"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"REQ-API-1","barcode":"API-001",
                 "windowStart":"2026-09-24T08:00:00Z","windowEnd":"2026-09-24T12:00:00Z"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.replayed").value(true));

        // 同键异参：409
        mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"REQ-API-1","barcode":"API-001",
                 "windowStart":"2026-09-24T08:00:00Z","windowEnd":"2026-09-24T18:00:00Z"}
                """))
                .andExpect(status().isConflict());

        // 状态查询：已放行
        mockMvc.perform(get("/api/units/API-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void unknownBarcodeReturns404() throws Exception {
        mockMvc.perform(get("/api/units/NOPE-404"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"REQ-API-404","barcode":"NOPE-404",
                 "windowStart":"2026-09-24T08:00:00Z","windowEnd":"2026-09-24T12:00:00Z"}
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content("""
                {"barcode":"","componentType":"红细胞","expireDate":"2026-10-01"}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/units/API-400/test-results").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testType":"UNKNOWN","result":"QUALIFIED"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
