package com.example.starter;

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
 * HTTP 边界测试：验证状态码语义与写操作幂等重放。
 */
@AutoConfigureMockMvc
class ApiWebTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void defectToRepairClosureFlowOverHttp() throws Exception {
        // 登记结构级缺陷 → 立即限功率
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-defect-1","bladeId":1,"bladePosition":"叶根",
                                 "defectLevel":"STRUCTURAL","defectType":"CRACK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.powerLimitActive").value(true));

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-defect-1","bladeId":1,"bladePosition":"叶根",
                                 "defectLevel":"STRUCTURAL","defectType":"CRACK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        // 同键异参 → 409
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-defect-1","bladeId":2,"bladePosition":"叶尖",
                                 "defectLevel":"SURFACE","defectType":"EROSION"}
                                """))
                .andExpect(status().isConflict());

        // 创建维修单 → 恰第 7 日复检合格闭环 → 限功率撤销
        mockMvc.perform(post("/api/repair-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-order-1","defectId":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.deadlineAt").value("2026-01-08T00:00:00"));

        clock.advance(java.time.Duration.ofDays(7));
        mockMvc.perform(post("/api/repair-orders/1/reinspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-reinspect-1","result":"PASS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("CLOSED"))
                .andExpect(jsonPath("$.powerLimitActive").value(false));

        // 限功率链查询：一段已撤销区间
        mockMvc.perform(get("/api/blades/1/power-limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("STRUCTURAL_DEFECT"))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[0].revokedAt").value("2026-01-08T00:00:00"));

        // 维修台账查询
        mockMvc.perform(get("/api/blades/1/repair-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CLOSED"))
                .andExpect(jsonPath("$[0].closeResult").value("ON_TIME"));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","bladeId":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownBladeReturns404() throws Exception {
        mockMvc.perform(post("/api/defects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-404","bladeId":999,"bladePosition":"叶尖",
                                 "defectLevel":"SURFACE","defectType":"EROSION"}
                                """))
                .andExpect(status().isNotFound());
    }
}
