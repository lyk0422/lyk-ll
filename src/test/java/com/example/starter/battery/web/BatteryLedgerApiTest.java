package com.example.starter.battery.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.example.starter.battery.TestClockConfig;

/**
 * 真实 REST 入口端到端测试：建档、检测上架、换出、回站、再检上架、调拨签收全链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BatteryLedgerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM alarm");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM battery_event");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM battery");
    }

    @Test
    @DisplayName("HTTP 全链路：建档-检测上架-换出-回站-复检上架-调拨签收-验真")
    void fullFlowOverHttp() throws Exception {
        // 1. 建档
        mockMvc.perform(post("/api/batteries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"BAT-E2E","stationId":"ST-A"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BAT-E2E"))
                .andExpect(jsonPath("$.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.stationId").value("ST-A"));

        // 2. 检测：恰好双阈值达标（SOC=90，健康度=80）→ 上架
        mockMvc.perform(post("/api/batteries/inspection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e1","code":"BAT-E2E","batch":1,"soc":90,"health":80}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("LISTED"));

        // 3. 换出绑定车辆
        mockMvc.perform(post("/api/batteries/swap-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e2","code":"BAT-E2E","vehicleId":"VEH-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_USE"))
                .andExpect(jsonPath("$.vehicleId").value("VEH-1"));

        // 4. 回站入站（链一致校验通过）
        mockMvc.perform(post("/api/batteries/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e3","code":"BAT-E2E","stationId":"ST-A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.stationId").value("ST-A"));

        // 5. 复检新批次后重新上架
        mockMvc.perform(post("/api/batteries/inspection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e4","code":"BAT-E2E","batch":2,"soc":92,"health":85}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LISTED"));

        // 6. 调出方 ST-A 发起站间调拨至 ST-B
        String transferId = mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e5","code":"BAT-E2E","fromStationId":"ST-A","toStationId":"ST-B"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"transferId\":(\\d+).*", "$1");

        // 在途期间换出被拒绝（409）
        mockMvc.perform(post("/api/batteries/swap-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e6","code":"BAT-E2E","vehicleId":"VEH-2"}
                                """))
                .andExpect(status().isConflict());

        // 7. 调入方签收
        mockMvc.perform(post("/api/transfers/" + transferId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e7"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));

        mockMvc.perform(get("/api/batteries/BAT-E2E"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.stationId").value("ST-B"));

        // 8. 事件链验真通过，事件顺序稳定
        mockMvc.perform(get("/api/batteries/BAT-E2E/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.eventCount").value(9));

        // 9. 同键异参返回 409
        mockMvc.perform(post("/api/batteries/inspection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-e1","code":"BAT-E2E","batch":1,"soc":10,"health":10}
                                """))
                .andExpect(status().isConflict());
    }
}
