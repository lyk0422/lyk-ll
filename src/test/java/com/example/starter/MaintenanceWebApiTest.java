package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 检修封锁 HTTP 语义：登记、窗口生效后即使风速安全未验收仍拒绝上客、双验收解除。
 */
@AutoConfigureMockMvc
class MaintenanceWebApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void windSafeButMaintenanceNotAcceptedStillRejectsBoarding() throws Exception {
        Instant start = Instant.parse("2026-10-08T01:00:00Z");
        Instant end = start.plusSeconds(3600);
        // 固定到窗口起点之前，避免真实时钟越过起点
        clock.setFixed(start.minusSeconds(60));
        // 当前没有任何风控事件，线路风速状态 NORMAL；登记检修单
        String orderBody = """
                {"requestId":"%s","startAt":"%s","endAt":"%s","reason":"抱索器年检","segmentIds":[1,2,3]}
                """.formatted(UUID.randomUUID().toString(), start, end);
        MvcResult created = mockMvc.perform(post("/api/lines/1/maintenance-orders")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();
        long orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();

        // start 之前：风速安全，可上客
        mockMvc.perform(post("/api/lines/1/boardings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"passengerCount\":2}"
                                .formatted(UUID.randomUUID().toString())))
                .andExpect(status().isOk());

        // 生效后：风速仍安全（无风控事件），但检修未验收 → 拒绝上客
        clock.setFixed(start.plusSeconds(300));
        mockMvc.perform(post("/api/lines/1/boardings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"passengerCount\":2}"
                                .formatted(UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("检修封锁中禁止上客: orderId=" + orderId));

        // 线路视图分别展示两类原因
        mockMvc.perform(get("/api/lines/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.maintenanceBlocked").value(true))
                .andExpect(jsonPath("$.activeMaintenanceOrderIds[0]").value(orderId));

        // 恰 end 未清空：验收仍拒绝
        clock.setFixed(end);
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/acceptances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":1,"role":"MECHANICAL",
                                 "operatorId":"mech-1","opinion":"合格"}"""
                                .formatted(UUID.randomUUID().toString())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidSegmentListReturns400AndOldVersionReturns409() throws Exception {
        Instant start = Instant.parse("2026-10-08T05:00:00Z");
        // 空清单：400
        mockMvc.perform(post("/api/lines/1/maintenance-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","startAt":"%s","endAt":"%s","reason":"检修","segmentIds":[]}"""
                                .formatted(UUID.randomUUID().toString(), start, start.plusSeconds(60))))
                .andExpect(status().isBadRequest());

        String body = """
                {"requestId":"%s","startAt":"%s","endAt":"%s","reason":"检修","segmentIds":[1]}"""
                .formatted(UUID.randomUUID().toString(), start, start.plusSeconds(60));
        MvcResult created = mockMvc.perform(post("/api/lines/1/maintenance-orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        long orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asLong();

        // 旧版本修改：409
        mockMvc.perform(put("/api/maintenance-orders/" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":7,"startAt":"%s","endAt":"%s",
                                 "reason":"改期","segmentIds":[2]}"""
                                .formatted(UUID.randomUUID().toString(),
                                        start.plusSeconds(60), start.plusSeconds(120))))
                .andExpect(status().isConflict());
    }
}
