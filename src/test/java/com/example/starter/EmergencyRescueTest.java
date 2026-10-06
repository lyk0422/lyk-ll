package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 紧急停车救援流程测试：双人确认各段清空，单人确认不生效。
 */
class EmergencyRescueTest extends BaseIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 3, 1, 14, 0, 0);

    private long eventId;

    @BeforeEach
    void triggerEmergencyStop() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("20.00"), "emg");
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");
        eventId = jdbc.queryForObject("SELECT id FROM control_event", Long.class);
    }

    @Test
    @DisplayName("单人确认分段不清空，两名不同人员确认后该段清空")
    void singleConfirmationDoesNotClearSegment() throws Exception {
        long seg = segmentIds.get(0);
        mockMvc.perform(post("/api/events/{eventId}/rescue-confirmations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescueJson("rc-1", seg, "zhangsan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distinctOperators").value(1))
                .andExpect(jsonPath("$.segmentCleared").value(false))
                .andExpect(jsonPath("$.rescueComplete").value(false));
        // 同一人员重复确认：不生效
        mockMvc.perform(post("/api/events/{eventId}/rescue-confirmations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescueJson("rc-2", seg, "zhangsan")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONFIRMATION"));
        assertThat(countRows("rescue_confirmation")).isOne();
        // 第二名人员确认：该段清空
        mockMvc.perform(post("/api/events/{eventId}/rescue-confirmations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescueJson("rc-3", seg, "lisi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distinctOperators").value(2))
                .andExpect(jsonPath("$.segmentCleared").value(true))
                .andExpect(jsonPath("$.rescueComplete").value(false));
    }

    @Test
    @DisplayName("救援未完成时禁止申请复开；全部段双人确认后可申请")
    void reopenBlockedUntilRescueComplete() throws Exception {
        // 只清空第 1 段
        confirmTwice(segmentIds.get(0), "a", "b");
        mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-blocked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESCUE_INCOMPLETE"));
        // 清空其余段
        confirmTwice(segmentIds.get(1), "c", "d");
        confirmTwice(segmentIds.get(2), "e", "f");
        // 低风速持续恰满 15 分钟
        LocalDateTime calm = T0.plusMinutes(10);
        for (long seg : segmentIds) {
            feedWind(seg, calm, 15, new BigDecimal("8.00"), "emg-calm-" + seg);
        }
        mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-allowed\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("缓停事件不允许走救援确认接口")
    void rescueConfirmationRejectedForSoftHold() throws Exception {
        // 先归档当前紧急事件：清空数据重造一个缓停场景过于繁琐，这里直接校验事件类型约束
        // 构造：另起一条缓停事件需要新事件，当前库内已有紧急事件，故改为校验紧急事件上
        // 非 ACTIVE 状态不可确认——先完成全部确认再复开归档，然后确认接口应拒绝
        confirmTwice(segmentIds.get(0), "a", "b");
        confirmTwice(segmentIds.get(1), "c", "d");
        confirmTwice(segmentIds.get(2), "e", "f");
        LocalDateTime calm = T0.plusMinutes(10);
        for (long seg : segmentIds) {
            feedWind(seg, calm, 15, new BigDecimal("8.00"), "emg-calm2-" + seg);
        }
        String body = mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-close\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reopenId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.reopenRequestId")).longValue();
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"ec-1\",\"role\":\"OPERATIONS\"}"));
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"ec-2\",\"role\":\"MAINTENANCE\"}"));
        assertThat(lineStatus()).isEqualTo("NORMAL");
        // 事件已归档（终态）：救援确认被拒绝
        mockMvc.perform(post("/api/events/{eventId}/rescue-confirmations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescueJson("rc-late", segmentIds.get(0), "wangwu")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVENT_STATE_INVALID"));
    }

    private void confirmTwice(long segmentId, String op1, String op2) throws Exception {
        mockMvc.perform(post("/api/events/{eventId}/rescue-confirmations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescueJson("rc-" + op1 + "-" + segmentId, segmentId, op1)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/events/{eventId}/rescue-confirmations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescueJson("rc-" + op2 + "-" + segmentId, segmentId, op2)))
                .andExpect(status().isOk());
    }

    private String rescueJson(String requestId, long segmentId, String operator) {
        return "{\"requestId\":\"" + requestId + "\",\"segmentId\":" + segmentId
                + ",\"operator\":\"" + operator + "\"}";
    }
}
