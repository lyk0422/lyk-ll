package com.example.starter.weigh;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.starter.BaseIntegrationTest;

/**
 * 过磅检测记录接口测试：超限率边界、去重口径、静态复检优先与幂等重放。
 */
class WeighApiTest extends BaseIntegrationTest {

    private static final String WEIGHED_AT = "2026-09-20T08:00:00Z";

    private String weighJson(String requestId, String plateNo, String weighType, int totalWeightKg) {
        return """
                {"requestId":"%s","plateNo":"%s","stationId":"ST01","weighedAt":"%s",
                 "axleType":"AXLE_2","weighType":"%s","totalWeightKg":%d}
                """.formatted(requestId, plateNo, WEIGHED_AT, weighType, totalWeightKg);
    }

    @Test
    @DisplayName("超限率恰为 0 判不超限")
    void rateExactlyZeroIsNotOverLimit() throws Exception {
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-zero-1", "苏A00001", "DYNAMIC", 18000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overRate", is(0.00)))
                .andExpect(jsonPath("$.overLimit", is(false)))
                .andExpect(jsonPath("$.limitWeightKg", is(18000)));
    }

    @Test
    @DisplayName("超限率大于 0 判超限，保留两位小数")
    void positiveRateIsOverLimit() throws Exception {
        // (19800-18000)/18000 = 0.10
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-over-1", "苏A00002", "DYNAMIC", 19800)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overRate", is(0.10)))
                .andExpect(jsonPath("$.overLimit", is(true)));
    }

    @Test
    @DisplayName("同一车牌+检测站+过磅时刻+称重方式去重，返回首次记录")
    void dedupByPlateStationAndTime() throws Exception {
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-dup-1", "苏A00003", "DYNAMIC", 23400)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.deduplicated", is(false)));

        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-dup-2", "苏A00003", "DYNAMIC", 25000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.deduplicated", is(true)))
                .andExpect(jsonPath("$.totalWeightKg", is(23400)));

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(1L, count);
    }

    @Test
    @DisplayName("静态复检优先：静态到达后动态记录保留可查但不再作为判定依据")
    void staticRecheckPrevailsOverDynamic() throws Exception {
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-st-1", "苏A00004", "DYNAMIC", 23400)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authoritative", is(true)));

        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-st-2", "苏A00004", "STATIC", 17000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authoritative", is(true)))
                .andExpect(jsonPath("$.overLimit", is(false)));

        // 动态记录保留可查，但已被静态复检取代
        mockMvc.perform(get("/api/weigh-records/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weighType", is("DYNAMIC")))
                .andExpect(jsonPath("$.authoritative", is(false)));
    }

    @Test
    @DisplayName("同 requestId 同参重放返回首次结果，同键异参返回 409")
    void idempotentReplayAndConflict() throws Exception {
        String body = weighJson("w-idem-1", "苏A00005", "DYNAMIC", 23400);
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(1)));

        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.idempotentReplay", is(true)));

        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weighJson("w-idem-1", "苏A00005", "DYNAMIC", 25000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_CONFLICT")));

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM weigh_record", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(1L, count);
    }

    @Test
    @DisplayName("未知轴型返回 422")
    void unknownAxleTypeRejected() throws Exception {
        String body = """
                {"requestId":"w-axle-1","plateNo":"苏A00006","stationId":"ST01",
                 "weighedAt":"2026-09-20T08:00:00Z","axleType":"AXLE_99",
                 "weighType":"DYNAMIC","totalWeightKg":20000}
                """;
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("UNKNOWN_AXLE_TYPE")));
    }
}
