package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 风速阈值与 10 分钟窗口判定测试。
 */
class WindThresholdTest extends BaseIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 10, 9, 0, 0);

    @Test
    @DisplayName("9 分钟数据不触发：判定须恰满 10 分钟")
    void nineMinutesDoesNotTrigger() throws Exception {
        feedWind(segmentIds.get(0), T0, 9, new BigDecimal("25.00"), "nine-min");
        assertThat(lineStatus()).isEqualTo("NORMAL");
        assertThat(countRows("control_event")).isZero();
    }

    @Test
    @DisplayName("10 分钟平均恰达 17m/s 触发缓停（恰等阈值生效）")
    void exactlySeventeenTriggersSoftHold() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("17.00"), "soft-eq");
        assertThat(lineStatus()).isEqualTo("SOFT_HOLD");
        assertThat(countRows("control_event")).isOne();
        String type = jdbc.queryForObject("SELECT event_type FROM control_event", String.class);
        String avg = jdbc.queryForObject("SELECT trigger_avg_speed FROM control_event", String.class);
        assertThat(type).isEqualTo("SOFT_HOLD");
        assertThat(new BigDecimal(avg)).isEqualByComparingTo("17.00");
    }

    @Test
    @DisplayName("10 分钟平均恰达 20m/s 触发紧急停车（恰等按高档处理）")
    void exactlyTwentyTriggersEmergencyStop() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("20.00"), "emg-eq");
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");
        String type = jdbc.queryForObject("SELECT event_type FROM control_event", String.class);
        assertThat(type).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    @DisplayName("10 分钟平均 16.99m/s 不触发")
    void belowThresholdDoesNotTrigger() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("16.99"), "below");
        assertThat(lineStatus()).isEqualTo("NORMAL");
        assertThat(countRows("control_event")).isZero();
    }

    @Test
    @DisplayName("乱序到达按时刻归位：后到的历史分钟补齐窗口后触发")
    void outOfOrderReadingCompletesWindow() throws Exception {
        // 先到 09:01~09:09 共 9 分钟，窗口缺 09:00，不触发
        feedWind(segmentIds.get(0), T0.plusMinutes(1), 9, new BigDecimal("18.00"), "ooo-head");
        assertThat(lineStatus()).isEqualTo("NORMAL");
        // 乱序补到 09:00，窗口 [09:00,09:09] 恰满 10 分钟，平均 18m/s → 缓停
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windJson("ooo-tail", segmentIds.get(0), T0, "18.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggeredEvent.eventType").value("SOFT_HOLD"))
                .andExpect(jsonPath("$.triggeredEvent.windowStart").value("2026-01-10T09:00"))
                .andExpect(jsonPath("$.triggeredEvent.windowEnd").value("2026-01-10T09:09"));
        assertThat(lineStatus()).isEqualTo("SOFT_HOLD");
    }

    @Test
    @DisplayName("同分段同时刻数据去重：重复上报不产生新记录")
    void duplicateReadingIsDeduplicated() throws Exception {
        String body = windJson("dup-1", segmentIds.get(0), T0, "12.34");
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        // 不同 requestId、同分段同时刻：按站点与时刻去重
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windJson("dup-2", segmentIds.get(0), T0, "12.34")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.accepted").value(false));
        assertThat(countRows("wind_reading")).isOne();
    }

    @Test
    @DisplayName("管制事件归档后，窗口内迟到数据只记迟到事件")
    void lateReadingOnlyRecordedAsLateEvent() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("20.00"), "late-trigger");
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");
        // 测量时刻落在已判定窗口 [T0, T0+9min] 内（避开已上报的整点时刻，防止命中去重分支）
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windJson("late-1", segmentIds.get(0), T0.plusMinutes(5).plusSeconds(30), "30.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.accepted").value(false));
        assertThat(countRows("late_wind_event")).isOne();
        // 迟到数据不进入正常风速表
        assertThat(countRows("wind_reading")).isEqualTo(10);
    }

    @Test
    @DisplayName("管制期间新风速照常入库去重，但不产生新管制事件")
    void readingsDuringControlDoNotTriggerNewEvent() throws Exception {
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("18.00"), "during-trigger");
        assertThat(lineStatus()).isEqualTo("SOFT_HOLD");
        // 管制期间另一分段也上报超阈值数据：入库但不触发新事件
        feedWind(segmentIds.get(1), T0.plusMinutes(10), 10, new BigDecimal("25.00"), "during-seg2");
        assertThat(countRows("control_event")).isOne();
        assertThat(countRows("wind_reading")).isEqualTo(20);
        assertThat(lineStatus()).isEqualTo("SOFT_HOLD");
    }
}
