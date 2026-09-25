package com.example.starter.penalty;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.BaseIntegrationTest;

/**
 * 处罚决定接口测试：分档口径（恰 30% 按罚款档）、幂等与静态复检优先。
 */
class PenaltyApiTest extends BaseIntegrationTest {

    private long createWeighRecord(String requestId, String plateNo, String weighType, int weightKg)
            throws Exception {
        String body = """
                {"requestId":"%s","plateNo":"%s","stationId":"ST01","weighedAt":"2026-09-20T08:00:00Z",
                 "axleType":"AXLE_2","weighType":"%s","totalWeightKg":%d}
                """.formatted(requestId, plateNo, weighType, weightKg);
        MvcResult result = mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id").toString());
    }

    private String penaltyJson(String requestId, long weighRecordId) {
        return "{\"requestId\":\"%s\",\"weighRecordId\":%d}".formatted(requestId, weighRecordId);
    }

    @Test
    @DisplayName("超限率恰 30% 按罚款档处理：罚款并记分")
    void rateExactlyThirtyPercentFallsInFineTier() throws Exception {
        long weighId = createWeighRecord("pw-1", "苏B00001", "DYNAMIC", 23400); // 恰 0.30
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-1", weighId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overRate", is(0.30)))
                .andExpect(jsonPath("$.measure", is("FINE_DEMERIT")))
                .andExpect(jsonPath("$.fineAmount", is(2000.00)))
                .andExpect(jsonPath("$.demeritPoints", is(3)))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.caseId", is(1)));

        String caseStatus = jdbc.queryForObject(
                "SELECT status FROM case_record WHERE id = 1", String.class);
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", caseStatus);
    }

    @Test
    @DisplayName("超限率不足 30% 责令卸载放行，不罚款不记分")
    void rateBelowThirtyPercentIsUnloadOnly() throws Exception {
        long weighId = createWeighRecord("pw-2", "苏B00002", "DYNAMIC", 19800); // 0.10
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-2", weighId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measure", is("UNLOAD")))
                .andExpect(jsonPath("$.fineAmount", is(0.00)))
                .andExpect(jsonPath("$.demeritPoints", is(0)));
    }

    @Test
    @DisplayName("超限率 100% 及以上加重档：罚款 6000 记 6 分")
    void rateHundredPercentIsAggravated() throws Exception {
        long weighId = createWeighRecord("pw-3", "苏B00003", "DYNAMIC", 36000); // 1.00
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-3", weighId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measure", is("FINE_DEMERIT")))
                .andExpect(jsonPath("$.fineAmount", is(6000.00)))
                .andExpect(jsonPath("$.demeritPoints", is(6)));
    }

    @Test
    @DisplayName("未超限记录不得作出处罚，返回 422")
    void notOverLimitCannotBePenalized() throws Exception {
        long weighId = createWeighRecord("pw-4", "苏B00004", "DYNAMIC", 18000); // 恰 0
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-4", weighId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("NOT_OVER_LIMIT")));
    }

    @Test
    @DisplayName("动态与静态复检不一致时以静态复检为准作出处罚")
    void penaltyBasedOnStaticWhenPresent() throws Exception {
        long dynamicId = createWeighRecord("pw-5", "苏B00005", "DYNAMIC", 23400); // 动态超限 0.30
        createWeighRecord("pw-6", "苏B00005", "STATIC", 17000); // 静态复检不超限

        // 对动态记录申请处罚：以静态复检为准，静态未超限故不得处罚
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-5", dynamicId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("NOT_OVER_LIMIT")));
    }

    @Test
    @DisplayName("处罚决定幂等：同键同参返回首次结果，同键异参 409 且不产生新文书")
    void penaltyIdempotency() throws Exception {
        long weighId = createWeighRecord("pw-7", "苏B00006", "DYNAMIC", 23400);
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-7", weighId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(1)));

        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-7", weighId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.idempotentReplay", is(true)));

        long otherWeighId = createWeighRecord("pw-8", "苏B00007", "DYNAMIC", 23400);
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(penaltyJson("pp-7", otherWeighId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_CONFLICT")));

        Long penalties = jdbc.queryForObject("SELECT COUNT(*) FROM penalty_decision", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(1L, penalties);
    }
}
