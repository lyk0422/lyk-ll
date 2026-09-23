package com.example.starter.dr.web;

import com.example.starter.dr.DrTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求响应 API 的 HTTP 语义测试：状态码、错误码、幂等重放与证据/调整查询。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:dr_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(DrTestConfiguration.class)
class DrControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dr_adjustment");
        jdbc.update("DELETE FROM dr_freeze_interval");
        jdbc.update("DELETE FROM dr_settlement_freeze");
        jdbc.update("DELETE FROM dr_curve_point");
        jdbc.update("DELETE FROM dr_enrollment");
        jdbc.update("DELETE FROM dr_event");
    }

    private String createEvent(String ref) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventRef", ref);
        body.put("startUtc", "2026-09-23T01:00:00Z");
        body.put("endUtc", "2026-09-23T02:00:00Z");
        body.put("intervalMinutes", 60);
        mockMvc.perform(post("/api/dr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventRef").value(ref))
                .andExpect(jsonPath("$.intervalCount").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.frozen").value(false));
        return ref;
    }

    private void enroll(String ref, String meter) throws Exception {
        mockMvc.perform(post("/api/dr/events/{ref}/enrollments", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"meterId":"%s","committedValue":5,"baselineVersion":"BL-1"}
                                """.formatted(meter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meterId").value(meter));
    }

    private void writePoint(String ref, String meter, String type, int idx,
                            String value, Integer expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataType", type);
        body.put("intervalIdx", idx);
        body.put("value", new BigDecimal(value));
        body.put("expectedVersion", expectedVersion);
        mockMvc.perform(post("/api/dr/events/{ref}/meters/{meter}/points", ref, meter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private String freezeBody(String requestId, int expectedVersion, String meter,
                              int baselineVersion, int actualVersion) {
        return """
                {"requestId":"%s","expectedVersion":%d,"meters":[
                  {"meterId":"%s","intervals":[
                    {"intervalIdx":0,"baselineVersion":%d,"actualVersion":%d}
                  ]}
                ]}
                """.formatted(requestId, expectedVersion, meter, baselineVersion, actualVersion);
    }

    @Test
    @DisplayName("完整主流程：建事件-登记-写曲线-冻结-查证据-重放；404/400/409 语义正确")
    void fullFlowOverHttp() throws Exception {
        String ref = "EVT-" + UUID.randomUUID();
        createEvent(ref);
        enroll(ref, "M1");

        // 未登记电表写点 -> 400
        mockMvc.perform(post("/api/dr/events/{ref}/meters/M9/points", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataType":"BASELINE","intervalIdx":0,"value":10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METER_NOT_ENROLLED"));

        writePoint(ref, "M1", "BASELINE", 0, "10", null);
        writePoint(ref, "M1", "ACTUAL", 0, "4", null);

        // 事件不存在 -> 404
        mockMvc.perform(get("/api/dr/events/NO-SUCH/freeze"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 请求体缺字段 -> 400 校验错误
        mockMvc.perform(post("/api/dr/events/{ref}/freeze", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"BAD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 首次冻结成功：交付 min(5,10-4)=5
        mockMvc.perform(post("/api/dr/events/{ref}/freeze", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("REQ-1", 2, "M1", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.expectedVersion").value(2))
                .andExpect(jsonPath("$.totalDelivered").value(5.000000))
                .andExpect(jsonPath("$.intervals[0].baselineVersion").value(1))
                .andExpect(jsonPath("$.intervals[0].actualVersion").value(1))
                .andExpect(jsonPath("$.intervals[0].deliveredValue").value(5.000000));

        // 查询冻结证据
        mockMvc.perform(get("/api/dr/events/{ref}/freeze", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDelivered").value(5.000000))
                .andExpect(jsonPath("$.intervals[0].meterId").value("M1"));

        // 同键同参重放：成功返回同一指纹
        mockMvc.perform(post("/api/dr/events/{ref}/freeze", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("REQ-1", 2, "M1", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-1"));

        // 冻结后更正实测并产生调整建议，GET 可查
        writePoint(ref, "M1", "ACTUAL", 0, "0", 1);
        mockMvc.perform(get("/api/dr/events/{ref}/adjustments", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dataType").value("ACTUAL"))
                .andExpect(jsonPath("$[0].frozenVersion").value(1))
                .andExpect(jsonPath("$[0].correctedVersion").value(2))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].suggestedDelta").value(0.000000));

        // 冻结证据仍为首次快照
        mockMvc.perform(get("/api/dr/events/{ref}/freeze", ref))
                .andExpect(jsonPath("$.intervals[0].actualVersion").value(1))
                .andExpect(jsonPath("$.intervals[0].actualValue").value(4.000000));

        // 同键异参（证据版本变化） -> 409
        mockMvc.perform(post("/api/dr/events/{ref}/freeze", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("REQ-1", 2, "M1", 1, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("事件编码重复返回 409；时间非法返回 400")
    void duplicateEventAndInvalidRange() throws Exception {
        String ref = "EVT-DUP-" + UUID.randomUUID();
        createEvent(ref);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventRef", ref);
        body.put("startUtc", "2026-09-23T01:00:00Z");
        body.put("endUtc", "2026-09-23T02:00:00Z");
        body.put("intervalMinutes", 60);
        mockMvc.perform(post("/api/dr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_REF_EXISTS"));

        body.put("eventRef", "EVT-BAD-" + UUID.randomUUID());
        body.put("endUtc", "2026-09-23T00:00:00Z");
        mockMvc.perform(post("/api/dr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }
}
