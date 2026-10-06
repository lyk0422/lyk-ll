package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层全流程：巡检登记 → 结构级缺陷 → 限功率 → 维修 → 复检合格闭环撤销限功率；
 * 以及 404 / 409 错误语义。
 */
class WebApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 缺陷登记到维修闭环全流程() throws Exception {
        long blade = bladeId("BLADE-B01");

        // 1. 巡检登记
        MvcResult inspection = mockMvc.perform(post("/api/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-insp-1","bladeId":%d,"position":"叶中10-15m","inspector":"inspector-li"}
                                """.formatted(blade)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bladeId").value(blade))
                .andReturn();
        long inspectionId = objectMapper.readTree(
                inspection.getResponse().getContentAsString()).get("id").asLong();

        // 2. 结构级缺陷登记 → 立即限功率
        MvcResult defect = mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-defect-1","inspectionId":%d,"bladeId":%d,"position":"叶中10-15m",
                                 "defectType":"CRACK","lengthMm":220.5,"depthRatio":0.55}
                                """.formatted(inspectionId, blade)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("STRUCTURAL"))
                .andExpect(jsonPath("$.curtailmentId").isNumber())
                .andReturn();
        long defectId = objectMapper.readTree(
                defect.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/blades/{bladeId}/status", blade))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curtailed").value(true));

        // 3. 建维修单 → 登记维修记录
        MvcResult order = mockMvc.perform(post("/api/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-order-1","defectId":%d}
                                """.formatted(defectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn();
        long orderId = objectMapper.readTree(
                order.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/repair-orders/{orderId}/repairs", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-repair-1","technician":"tech-wang","description":"裂纹打磨后结构胶灌注"}
                                """))
                .andExpect(status().isOk());

        // 4. 复检合格 → 同事务结案、闭环、撤销限功率
        mockMvc.perform(post("/api/repair-orders/{orderId}/reinspections", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-reinspect-1","result":"PASS","inspector":"inspector-li"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("CLOSED"))
                .andExpect(jsonPath("$.defectStatus").value("CLOSED"))
                .andExpect(jsonPath("$.curtailmentLifted").value(true))
                .andExpect(jsonPath("$.escalated").value(false));

        mockMvc.perform(get("/api/blades/{bladeId}/status", blade))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curtailed").value(false));

        // 5. 台账可复算
        mockMvc.perform(get("/api/blades/{bladeId}/repairs", blade))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CLOSED"))
                .andExpect(jsonPath("$[0].repairRecords.length()").value(1))
                .andExpect(jsonPath("$[0].reinspections.length()").value(1));
    }

    @Test
    void 同键异参返回409() throws Exception {
        long blade = bladeId("BLADE-B01");
        String body1 = """
                {"requestId":"web-idem-1","bladeId":%d,"position":"叶根0-5m",
                 "defectType":"EROSION","lengthMm":50.0,"depthRatio":0.05}
                """.formatted(blade);
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());

        String body2 = """
                {"requestId":"web-idem-1","bladeId":%d,"position":"叶尖30-35m",
                 "defectType":"EROSION","lengthMm":50.0,"depthRatio":0.05}
                """.formatted(blade);
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        // 同键同参重放返回首次结果
        MvcResult replay = mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayJson.get("position").asText()).isEqualTo("叶根0-5m");
    }

    @Test
    void 不存在资源返回404() throws Exception {
        mockMvc.perform(get("/api/defects/{defectId}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(post("/api/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-order-404","defectId":999999}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 参数非法返回400() throws Exception {
        long blade = bladeId("BLADE-B01");
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-bad-1","bladeId":%d,"position":"叶根0-5m",
                                 "defectType":"CRACK","depthRatio":1.5}
                                """.formatted(blade)))
                .andExpect(status().isBadRequest());
    }
}
