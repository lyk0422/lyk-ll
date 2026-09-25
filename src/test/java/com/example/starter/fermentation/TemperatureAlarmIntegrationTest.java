package com.example.starter.fermentation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主酵控温边界：恰 0.5℃ 合格不告警；偏离超过 0.5℃ 恰连续 3 个采样点触发告警；
 * 中间出现合格点后连续计数归零。
 */
class TemperatureAlarmIntegrationTest extends AbstractIntegrationTest {

    @Test
    void exactlyHalfDegreeStaysInRangeAndDoesNotAlarm() throws Exception {
        mockMvc.perform(post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-T1", "TANK-T1", TestData.T0))))
                .andExpect(status().isCreated());

        Instant t1 = TestData.T0.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("req-t1", "B-T1", t1, "10.50"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outOfRange").value(false))
                .andExpect(jsonPath("$.alarmed").value(false))
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(0))
                .andExpect(jsonPath("$.targetTemp").value(10.00));

        Instant t2 = t1.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("req-t2", "B-T1", t2, "9.50"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outOfRange").value(false))
                .andExpect(jsonPath("$.alarmed").value(false));
    }

    @Test
    void exactlyThreeConsecutiveOutOfRangePointsTriggerAlarmOnce() throws Exception {
        mockMvc.perform(post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-T2", "TANK-T2", TestData.T0))))
                .andExpect(status().isCreated());

        Instant base = TestData.T0.plusSeconds(3600);
        // 第 1 点偏离 0.51℃
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("r-a", "B-T2", base, "10.51"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outOfRange").value(true))
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(1))
                .andExpect(jsonPath("$.alarmed").value(false));

        // 第 2 点偏离 -0.60℃
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("r-b", "B-T2", base.plusSeconds(3600), "9.40"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(2))
                .andExpect(jsonPath("$.alarmed").value(false));

        // 恰第 3 点触发告警
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("r-c", "B-T2", base.plusSeconds(7200), "10.80"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(3))
                .andExpect(jsonPath("$.alarmed").value(true));

        // 第 4 点仍偏离，连续 4 点不再重复“恰 3 点”口径告警标记
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("r-d", "B-T2", base.plusSeconds(10800), "10.80"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(4))
                .andExpect(jsonPath("$.alarmed").value(false));
    }

    @Test
    void inRangePointResetsConsecutiveRun() throws Exception {
        mockMvc.perform(post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-T3", "TANK-T3", TestData.T0))))
                .andExpect(status().isCreated());

        Instant base = TestData.T0.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("s-a", "B-T3", base, "10.60"))))
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(1));
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("s-b", "B-T3", base.plusSeconds(3600), "10.60"))))
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(2));
        // 恰 0.5℃ 合格，计数归零
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("s-c", "B-T3", base.plusSeconds(7200), "10.50"))))
                .andExpect(jsonPath("$.outOfRange").value(false))
                .andExpect(jsonPath("$.consecutiveOutOfRange").value(0))
                .andExpect(jsonPath("$.alarmed").value(false));
    }

    @Test
    void sameKeyReplayReturnsFirstResultAndDifferentParamsConflict() throws Exception {
        mockMvc.perform(post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch("B-T4", "TANK-T4", TestData.T0))))
                .andExpect(status().isCreated());

        Instant at = TestData.T0.plusSeconds(3600);
        String request = json(TestData.temp("dup-key", "B-T4", at, "10.10"));
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature").value(10.10));

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temperature").value(10.10));

        // 只落了一条读数
        Integer count = jdbc.sql("SELECT COUNT(*) FROM temperature_reading WHERE batch_id = "
                        + "(SELECT id FROM fermentation_batch WHERE batch_no = 'B-T4')")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);

        // 同键异参：409
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("dup-key", "B-T4", at, "10.99"))))
                .andExpect(status().isConflict());
    }
}
