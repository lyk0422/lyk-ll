package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 缓停全流程测试：禁止上客、逐段放行、复开 15 分钟规则与双岗确认。
 */
class SoftHoldFlowTest extends BaseIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 2, 1, 10, 0, 0);

    private long eventId;

    @BeforeEach
    void triggerSoftHold() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("17.50"), "soft-flow");
        assertThat(lineStatus()).isEqualTo("SOFT_HOLD");
        eventId = jdbc.queryForObject("SELECT id FROM control_event", Long.class);
    }

    @Test
    @DisplayName("缓停期间线路禁止上客")
    void boardingForbiddenDuringSoftHold() throws Exception {
        mockMvc.perform(get("/api/lines/{lineId}", lineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOFT_HOLD"))
                .andExpect(jsonPath("$.boardingAllowed").value(false));
    }

    @Test
    @DisplayName("在途吊厢须按站点逐段放行：跳段放行被拒绝")
    void releaseMustFollowStationOrder() throws Exception {
        // 直接放行第 2 段：违反顺序
        mockMvc.perform(post("/api/events/{eventId}/segment-releases", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rel-o1\",\"segmentId\":" + segmentIds.get(1) + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASE_OUT_OF_ORDER"));
        // 按序放行 1、2、3 段
        for (int i = 0; i < segmentIds.size(); i++) {
            var result = mockMvc.perform(post("/api/events/{eventId}/segment-releases", eventId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"rel-" + i + "\",\"segmentId\":" + segmentIds.get(i) + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.releasedSegmentSeq").value(i + 1));
            if (i == segmentIds.size() - 1) {
                result.andExpect(jsonPath("$.allReleased").value(true));
            }
        }
        assertThat(countRows("segment_release")).isEqualTo(3);
    }

    @Test
    @DisplayName("低风速持续 14 分钟申请复开被拒，恰满 15 分钟允许申请")
    void reopenRequiresFullFifteenMinutes() throws Exception {
        LocalDateTime calm = T0.plusMinutes(10);
        // 仅 14 分钟低于 15m/s：不满足
        for (long seg : segmentIds) {
            feedWind(seg, calm, 14, new BigDecimal("10.00"), "calm14-" + seg);
        }
        mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-early\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WIND_NOT_RECOVERED"));
        // 补足第 15 分钟（恰满 15 分钟允许申请）
        for (long seg : segmentIds) {
            feedWind(seg, calm.plusMinutes(14), 1, new BigDecimal("10.00"), "calm15-" + seg);
        }
        mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("复开须运营与机务双岗确认：单岗确认不复开，同岗重复确认被拒")
    void reopenRequiresDualRoleConfirmation() throws Exception {
        feedCalmFifteen();
        String reopenBody = mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-dual\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reopenId = ((Number) com.jayway.jsonpath.JsonPath.read(reopenBody, "$.reopenRequestId")).longValue();

        // 运营岗确认：仍未复开
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"conf-ops\",\"role\":\"OPERATIONS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));
        assertThat(lineStatus()).isEqualTo("SOFT_HOLD");
        // 同岗重复确认：422
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"conf-ops-2\",\"role\":\"OPERATIONS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONFIRMATION"));
        // 机务岗确认：复开，事件归档为终态
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"conf-mnt\",\"role\":\"MAINTENANCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.eventStatus").value("CLOSED"));
        assertThat(lineStatus()).isEqualTo("NORMAL");
        String eventStatus = jdbc.queryForObject("SELECT status FROM control_event WHERE id = ?",
                String.class, eventId);
        assertThat(eventStatus).isEqualTo("CLOSED");
        // 归档后窗口内迟到数据只记迟到事件（避开已上报的整点时刻，防止命中去重分支）
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windJson("late-after-close", segmentIds.get(0),
                                T0.plusMinutes(3).plusSeconds(30), "40.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        assertThat(countRows("late_wind_event")).isOne();
    }

    @Test
    @DisplayName("复开后线路恢复允许上客")
    void boardingAllowedAfterReopen() throws Exception {
        feedCalmFifteen();
        String reopenBody = mockMvc.perform(post("/api/events/{eventId}/reopen-requests", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"reopen-board\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reopenId = ((Number) com.jayway.jsonpath.JsonPath.read(reopenBody, "$.reopenRequestId")).longValue();
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"cb-1\",\"role\":\"OPERATIONS\"}"));
        mockMvc.perform(post("/api/reopen-requests/{id}/confirmations", reopenId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"cb-2\",\"role\":\"MAINTENANCE\"}"));
        mockMvc.perform(get("/api/lines/{lineId}", lineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.boardingAllowed").value(true));
    }

    /** 为所有分段补足恰满 15 分钟低于 15m/s 的风速数据。 */
    private void feedCalmFifteen() throws Exception {
        LocalDateTime calm = T0.plusMinutes(10);
        for (long seg : segmentIds) {
            feedWind(seg, calm, 15, new BigDecimal("10.00"), "calm15full-" + seg);
        }
    }
}
