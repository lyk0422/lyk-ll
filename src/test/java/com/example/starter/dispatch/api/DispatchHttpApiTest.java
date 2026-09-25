package com.example.starter.dispatch.api;

import com.example.starter.dispatch.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调度令 HTTP 接口测试：真实 JSON 序列化与 201/409/404 状态语义。
 */
@SpringBootTest(classes = {
        com.example.starter.StarterApplication.class,
        TestClockConfiguration.class})
@AutoConfigureMockMvc
class DispatchHttpApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM active_gate_lock");
        jdbcTemplate.update("DELETE FROM dispatch_notification");
        jdbcTemplate.update("DELETE FROM gate_step");
        jdbcTemplate.update("DELETE FROM dispatch_order");
    }

    @Test
    void fullHttpFlowIssueAcknowledgeReceipts() throws Exception {
        String body = """
                {
                  "requestId": "HTTP-1",
                  "gates": ["G1", "G2"],
                  "targetOpenings": ["50.000", "40.000"],
                  "deadline": "2026-09-25T01:00:00Z"
                }
                """;
        mockMvc.perform(post("/api/dispatch/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("HTTP-1"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.gates[0].seq").value(1))
                .andExpect(jsonPath("$.gates[1].gateCode").value("G2"));

        // 同键同参重放返回首次结果（状态不变）
        mockMvc.perform(post("/api/dispatch/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        // 同键异参 409 且不改状态
        String changed = body.replace("01:00:00Z", "02:00:00Z");
        mockMvc.perform(post("/api/dispatch/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 未签收先回执 → 409
        mockMvc.perform(post("/api/dispatch/orders/HTTP-1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gateCode":"G1","actualOpening":"50.000",
                                 "completedAt":"2026-09-25T00:10:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ACKNOWLEDGED"));

        mockMvc.perform(post("/api/dispatch/orders/HTTP-1/acknowledgements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        // 跳序回执 G2 → 409
        mockMvc.perform(post("/api/dispatch/orders/HTTP-1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gateCode":"G2","actualOpening":"40.000",
                                 "completedAt":"2026-09-25T00:10:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATE_NOT_IN_SEQUENCE"));

        mockMvc.perform(post("/api/dispatch/orders/HTTP-1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gateCode":"G1","actualOpening":"50.000",
                                 "completedAt":"2026-09-25T00:10:00Z"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/dispatch/orders/HTTP-1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gateCode":"G2","actualOpening":"41.500",
                                 "completedAt":"2026-09-25T00:20:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.gates[1].inPosition").value(false));

        mockMvc.perform(get("/api/dispatch/orders/HTTP-1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("DEVIATION_ALARM"));
    }

    @Test
    void unknownOrderReturns404AndValidationReturns400() throws Exception {
        mockMvc.perform(get("/api/dispatch/orders/NO-SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        mockMvc.perform(post("/api/dispatch/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","gates":["G1"],
                                 "targetOpenings":["50.000"],
                                 "deadline":"2026-09-25T01:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
