package com.example.starter.ride.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.ride.AbstractRideTest;
import com.example.starter.ride.dto.DailyCheckRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通过真实 HTTP 入口（MockMvc）验证状态码、幂等重放与 409 冲突语义。
 */
@AutoConfigureMockMvc
class RideControllerHttpTest extends AbstractRideTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resetAndSeed();
    }

    private String qualifiedDailyCheckJson(String requestId) throws Exception {
        List<DailyCheckRequest.ItemResult> items = checklistRepository.findItemsByRide("R001")
                .keySet().stream()
                .map(code -> new DailyCheckRequest.ItemResult(code, true, "正常"))
                .collect(Collectors.toList());
        return objectMapper.writeValueAsString(new DailyCheckRequest(requestId, "R001", items));
    }

    @Test
    @DisplayName("HTTP 主流程：未日检开放 400，日检后同键重放 200，异参 409")
    void httpStatusAndIdempotencySemantics() throws Exception {
        // 未日检开放：400
        mockMvc.perform(post("/api/rides/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"op-early\",\"rideId\":\"R001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpStatus").value(400));

        // 日检合格：200，状态进入 TRIAL
        mockMvc.perform(post("/api/rides/daily-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualifiedDailyCheckJson("dc-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qualified").value(true))
                .andExpect(jsonPath("$.data.status").value("TRIAL"));

        // 同键同参重放：仍 200 且返回首次消息
        mockMvc.perform(post("/api/rides/daily-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualifiedDailyCheckJson("dc-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qualified").value(true));

        // 同键异参：409
        String conflictBody = qualifiedDailyCheckJson("dc-1")
                .replace("\"qualified\":true", "\"qualified\":false")
                .replaceFirst("\"remark\":\"正常\"", "\"remark\":\"压杠裂纹\"");
        mockMvc.perform(post("/api/rides/daily-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.httpStatus").value(409));

        // 未知设备：404
        mockMvc.perform(post("/api/rides/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"op-x\",\"rideId\":\"NOPE\"}"))
                .andExpect(status().isNotFound());

        // 复算端点可访问
        mockMvc.perform(get("/api/rides/R001/replay-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rideId").value("R001"));
    }
}
