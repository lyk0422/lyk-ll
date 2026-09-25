package com.example.starter.dispatch;

import com.example.starter.StarterApplication;
import com.example.starter.dispatch.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 层测试：经真实 HTTP 序列化/校验/异常处理链路验证状态码与响应体。
 */
@SpringBootTest(classes = StarterApplication.class)
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class DispatchApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM alarm_event");
        jdbc.update("DELETE FROM gate_receipt");
        jdbc.update("DELETE FROM gate_occupancy");
        jdbc.update("DELETE FROM dispatch_order");
    }

    @Test
    void issue_accept_and_receipt_through_http() throws Exception {
        String body = """
                {
                  "requestId": "REQ-API-1",
                  "gates": [
                    {"gateCode": "GATE-A", "targetOpening": 50.00}
                  ],
                  "deadline": "2026-09-25T10:05:00Z"
                }
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("REQ-API-1"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.gates[0].gateCode").value("GATE-A"));

        mockMvc.perform(post("/api/dispatch/orders/REQ-API-1/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(post("/api/dispatch/orders/REQ-API-1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gateCode": "GATE-A", "actualOpening": 51.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.gates[0].inPosition").value(true))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void same_key_different_params_returns_409_and_keeps_state() throws Exception {
        String body = """
                {"requestId":"REQ-API-2","gates":[{"gateCode":"GATE-A","targetOpening":30}],"deadline":"2026-09-25T10:05:00Z"}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String tampered = """
                {"requestId":"REQ-API-2","gates":[{"gateCode":"GATE-A","targetOpening":31}],"deadline":"2026-09-25T10:05:00Z"}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        mockMvc.perform(get("/api/dispatch/orders/REQ-API-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.gates[0].targetOpening").value(30.000));
    }

    @Test
    void gate_conflict_returns_409_and_unknown_order_returns_404() throws Exception {
        String body = """
                {"requestId":"REQ-API-3","gates":[{"gateCode":"GATE-B","targetOpening":20}],"deadline":"2026-09-25T10:05:00Z"}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String second = """
                {"requestId":"REQ-API-4","gates":[{"gateCode":"GATE-B","targetOpening":25}],"deadline":"2026-09-25T10:05:00Z"}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATE_OCCUPIED"));

        mockMvc.perform(get("/api/dispatch/orders/NO-SUCH-ID"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void invalid_body_returns_400() throws Exception {
        String body = """
                {"requestId":"","gates":[],"deadline":"2026-09-25T10:05:00Z"}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
