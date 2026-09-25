package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web 层端到端测试：入库 → 双检回传 → 放行发血主流程及 HTTP 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BloodFlowApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM issue_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM result_event");
        jdbc.update("DELETE FROM blood_unit");
    }

    @Test
    void 完整主流程入库双检放行发血() throws Exception {
        mvc.perform(post("/api/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"API-001\",\"expiryDate\":\"2026-09-30\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEST"));

        // 乱序回传：复检先到
        mvc.perform(post("/api/units/API-001/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testType\":\"RECHECK\",\"result\":\"PASS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        mvc.perform(post("/api/units/API-001/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testType\":\"INITIAL\",\"result\":\"PASS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASABLE"));

        mvc.perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-API-001\",\"barcode\":\"API-001\","
                                + "\"windowStart\":\"2026-09-25T08:00:00Z\","
                                + "\"windowEnd\":\"2026-09-25T20:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELEASED"))
                .andExpect(jsonPath("$.currentStatus").value("RELEASED"));

        mvc.perform(get("/api/units/API-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void 重复入库返回409() throws Exception {
        String body = "{\"barcode\":\"API-002\",\"expiryDate\":\"2026-09-30\"}";
        mvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/units").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"));
    }

    @Test
    void 未知条码查询返回404() throws Exception {
        mvc.perform(get("/api/units/API-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 缺少必填字段返回400() throws Exception {
        mvc.perform(post("/api/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 同键异参放行返回409() throws Exception {
        mvc.perform(post("/api/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"API-003\",\"expiryDate\":\"2026-09-30\"}"))
                .andExpect(status().isCreated());
        for (String type : new String[] {"INITIAL", "RECHECK"}) {
            mvc.perform(post("/api/units/API-003/results")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"testType\":\"" + type + "\",\"result\":\"PASS\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-API-003\",\"barcode\":\"API-003\","
                                + "\"windowStart\":\"2026-09-25T08:00:00Z\","
                                + "\"windowEnd\":\"2026-09-25T20:00:00Z\"}"))
                .andExpect(status().isOk());

        // 同键异参：409，且单位状态不变
        mvc.perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-API-003\",\"barcode\":\"API-003\","
                                + "\"windowStart\":\"2026-09-25T09:00:00Z\","
                                + "\"windowEnd\":\"2026-09-25T20:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.currentStatus").value("RELEASED"));
    }
}
