package com.example.starter.ventilation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层测试：校验 409 同键异参、400 参数错误，以及激活→反馈→复位→归档端到端主流程。
 */
@AutoConfigureMockMvc
class VentilationHttpApiTest extends AbstractVentilationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    private String activateBody(String requestId, String zone, String dir, String cond) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("zoneCode", zone);
        body.put("smokeDirection", dir);
        body.put("targetCondition", cond);
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("同键异参激活返回 HTTP 409，错误码 IDEM_KEY_MISMATCH")
    void sameKeyDifferentParamsReturns409() throws Exception {
        mockMvc.perform(post("/api/ventilation/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody("http-1", "ZONE_C", "FORWARD", "SMOKE_EXHAUST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACTIVATED"));

        mockMvc.perform(post("/api/ventilation/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody("http-1", "ZONE_C", "BACKWARD", "SMOKE_EXHAUST")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEM_KEY_MISMATCH"));
    }

    @Test
    @DisplayName("请求体缺字段返回 400；未知分区返回 400")
    void validationErrors() throws Exception {
        mockMvc.perform(post("/api/ventilation/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"zoneCode\":\"ZONE_C\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/ventilation/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody("http-2", "ZONE_X", "FORWARD", "SMOKE_EXHAUST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    @DisplayName("HTTP 端到端：激活→到位反馈→复位→关阀反馈→归档，事件流水按序可查")
    void endToEndHttpFlow() throws Exception {
        // 激活
        MvcResult actResult = mockMvc.perform(post("/api/ventilation/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activateBody("http-e2e-1", "ZONE_C", "FORWARD", "SMOKE_EXHAUST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACTIVATED"))
                .andReturn();
        JsonNode actJson = json.readTree(actResult.getResponse().getContentAsString());
        long sid = actJson.path("session").path("sessionId").asLong();

        // 风阀到位 → 风机自动启动
        mockMvc.perform(post("/api/ventilation/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody("http-fb-1", sid, "D_C_1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        mockMvc.perform(post("/api/ventilation/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody("http-fb-2", sid, "FAN_C")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        // 复位：先停风机
        mockMvc.perform(post("/api/ventilation/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-reset-1","zoneCode":"ZONE_C"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESET_IN_PROGRESS"));

        // 风机停稳 → 风阀关闭 → 复位完成
        mockMvc.perform(post("/api/ventilation/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody("http-fb-3", sid, "FAN_C")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ventilation/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody("http-fb-4", sid, "D_C_1")))
                .andExpect(status().isOk());

        // 归档
        mockMvc.perform(post("/api/ventilation/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-archive-1","zoneCode":"ZONE_C"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ARCHIVED"));

        // 会话状态
        mockMvc.perform(get("/api/ventilation/sessions/" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 事件流水按序可查，且包含关键迁移事件
        mockMvc.perform(get("/api/ventilation/events?afterId=0&limit=200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.eventType=='MODE_ACTIVATED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType=='RESET_COMPLETE')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType=='MODE_ARCHIVED')]").exists());
    }

    private String feedbackBody(String requestId, long sid, String device) {
        return "{\"requestId\":\"" + requestId + "\",\"sessionId\":" + sid
                + ",\"deviceCode\":\"" + device + "\",\"result\":\"IN_POSITION\"}";
    }
}
