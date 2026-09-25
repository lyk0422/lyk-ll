package com.example.starter.ventilation.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP 端到端测试：真实 Spring MVC + H2 事务，验证状态码语义（200/404/409）
 * 与激活→反馈→复位→归档完整链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VentilationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM event_log");
        jdbc.update("DELETE FROM device_feedback");
        jdbc.update("DELETE FROM device_command");
        jdbc.update("DELETE FROM active_zone_lock");
        jdbc.update("DELETE FROM idem_request");
        jdbc.update("DELETE FROM fire_mode");
    }

    private static String rid() {
        return UUID.randomUUID().toString().substring(12);
    }

    private JsonNode activate(String requestId, String zone) throws Exception {
        String body = """
                {"requestId":"%s","zoneCode":"%s","smokeFlowDirection":"TO_EAST",
                 "targetCondition":"PURGE_MAX"}
                """.formatted(requestId, zone);
        MvcResult result = mockMvc.perform(post("/api/ventilation/modes/activate")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode feedback(String uid, String device, boolean inPosition) throws Exception {
        String requestId = rid();
        String body = """
                {"requestId":"%s","commandUid":"%s","deviceCode":"%s",
                 "inPosition":%s,"observedState":null}
                """.formatted(requestId, uid, device, inPosition);
        MvcResult result = mockMvc.perform(post("/api/ventilation/feedback")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String commandUid(JsonNode mode, String device) {
        for (JsonNode c : mode.get("commands")) {
            if (device.equals(c.get("deviceCode").asText())) {
                return c.get("commandUid").asText();
            }
        }
        throw new IllegalStateException("未找到设备指令: " + device);
    }

    @Test
    void full_lifecycle_over_http_with_reverse_reset_and_archive() throws Exception {
        String reqId = rid();
        JsonNode mode = activate(reqId, "Z3");

        // 激活瞬间仅第一道风阀 ISSUED（先阀后机）
        String damperUid = commandUid(mode, "D-Z3-1");
        String fanUid = commandUid(mode, "F-Z3-1");
        mockMvc.perform(get("/api/ventilation/modes/" + reqId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands[0].deviceCode").value("D-Z3-1"))
                .andExpect(jsonPath("$.commands[0].status").value("ISSUED"));

        // 同键同参重放：200 且同一模式 id
        mockMvc.perform(post("/api/ventilation/modes/activate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","zoneCode":"Z3","smokeFlowDirection":"TO_EAST",
                                 "targetCondition":"PURGE_MAX"}
                                """.formatted(reqId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mode.get("id").asLong()));

        // 同键异参：409
        mockMvc.perform(post("/api/ventilation/modes/activate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","zoneCode":"Z1","smokeFlowDirection":"TO_EAST",
                                 "targetCondition":"PURGE_MAX"}
                                """.formatted(reqId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        // 到位反馈：阀→机顺序推进
        feedback(damperUid, "D-Z3-1", true);
        feedback(fanUid, "F-Z3-1", true);

        // 复位：逆序（风机先停）
        String resetId = rid();
        mockMvc.perform(post("/api/ventilation/modes/reset")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","modeRequestId":"%s"}
                                """.formatted(resetId, reqId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESETTING"));

        JsonNode resetting = objectMapper.readTree(mockMvc.perform(
                get("/api/ventilation/modes/" + reqId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String fanResetUid = null;
        String damperResetUid = null;
        for (JsonNode c : resetting.get("commands")) {
            if ("RESET".equals(c.get("phase").asText())) {
                if ("F-Z3-1".equals(c.get("deviceCode").asText())) {
                    fanResetUid = c.get("commandUid").asText();
                }
                if ("D-Z3-1".equals(c.get("deviceCode").asText())) {
                    damperResetUid = c.get("commandUid").asText();
                }
            }
        }
        // 先停风机到位，风阀才下发
        feedback(fanResetUid, "F-Z3-1", true);
        feedback(damperResetUid, "D-Z3-1", true);

        mockMvc.perform(get("/api/ventilation/modes/" + reqId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESET_COMPLETE"));

        // 归档终态
        mockMvc.perform(post("/api/ventilation/modes/archive")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","modeRequestId":"%s"}
                                """.formatted(rid(), reqId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 事件流按序可查
        mockMvc.perform(get("/api/ventilation/events").param("requestId", reqId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType=='MODE_ACTIVATED')]").exists())
                .andExpect(jsonPath("$[?(@.eventType=='RESET_COMPLETED')]").exists())
                .andExpect(jsonPath("$[?(@.eventType=='MODE_ARCHIVED')]").exists());
    }

    @Test
    void unknown_mode_and_validation_errors_have_proper_status() throws Exception {
        mockMvc.perform(get("/api/ventilation/modes/missing-mode"))
                .andExpect(status().isNotFound());

        // 缺必填字段：400
        mockMvc.perform(post("/api/ventilation/modes/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"zoneCode\":\"Z3\"}"))
                .andExpect(status().isBadRequest());
    }
}
