package com.example.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.example.starter.TestTimeConfig;
import com.example.starter.port.PortTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 层测试：验证接口契约、错误语义与事件查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestTimeConfig.class)
class PortApiTest extends PortTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        String requestId = nextRequestId();

        // 申请
        String applyBody = """
                {"requestId":"%s","msisdn":"%s","donorOperator":"OP_A","recipientOperator":"OP_B"}
                """.formatted(requestId, msisdn);
        String response = mockMvc.perform(post("/api/port-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();
        long flowId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(response, "$.id").toString());

        // 同键同参重放返回首次结果
        mockMvc.perform(post("/api/port-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(flowId));

        // 同键异参返回 409
        String conflictBody = """
                {"requestId":"%s","msisdn":"%s","donorOperator":"OP_A","recipientOperator":"OP_C"}
                """.formatted(requestId, msisdn);
        mockMvc.perform(post("/api/port-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 签码
        String issued = mockMvc.perform(post("/api/port-requests/{id}/auth-code", flowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CODE_ISSUED"))
                .andReturn().getResponse().getContentAsString();
        String authCode = com.jayway.jsonpath.JsonPath.read(issued, "$.authCode");

        // 携出方确认
        mockMvc.perform(post("/api/port-requests/{id}/confirm", flowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authCode\":\"" + authCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 窗口外广播被拒绝并保留待生效
        mockMvc.perform(post("/api/port-requests/{id}/broadcast", flowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + nextRequestId() + "\",\"channel\":\"CH1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_WINDOW"));

        // 进入窗口后广播生效
        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        String broadcastRequestId = nextRequestId();
        mockMvc.perform(post("/api/port-requests/{id}/broadcast", flowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + broadcastRequestId + "\",\"channel\":\"CH1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portRequestId").value(flowId));

        // 事件按序可查
        mockMvc.perform(get("/api/port-requests/{id}/events", flowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPLIED"))
                .andExpect(jsonPath("$[1].eventType").value("AUTH_CODE_ISSUED"))
                .andExpect(jsonPath("$[2].eventType").value("CONFIRMED"))
                .andExpect(jsonPath("$[3].eventType").value("EFFECTIVE"));
    }

    @Test
    void cancelOverHttp() throws Exception {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        var flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");

        mockMvc.perform(post("/api/port-requests/{id}/cancel", flow.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"用户反悔\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void validationAndNotFoundErrors() throws Exception {
        // 缺少必填字段 → 400
        mockMvc.perform(post("/api/port-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"msisdn\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        // 流程不存在 → 404
        mockMvc.perform(get("/api/port-requests/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLOW_NOT_FOUND"));
    }
}
