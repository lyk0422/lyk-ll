package com.example.starter.greenhouse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 端到端 HTTP 语义验证：编制/校验/下发主流程、422 校验失败、409 同键异参。
 */
@AutoConfigureMockMvc
class GreenhouseHttpFlowTest extends BaseGreenhouseTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void 编制校验下发主流程与失败分支的HTTP状态() throws Exception {
        mockMvc.perform(post("/api/greenhouse/crops").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("cropCode", "C1", "name", "番茄", "stageCode", "FRUITING",
                                "dayTempMin", 20, "dayTempMax", 28,
                                "dayHumidityMin", 60, "dayHumidityMax", 80,
                                "nightTempMin", 14, "nightTempMax", 20,
                                "nightHumidityMin", 65, "nightHumidityMax", 85))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/greenhouse/zones").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("zoneCode", "Z1", "name", "一号分区"))))
                .andExpect(status().isCreated());

        // 超窗口策略 → 422。
        mockMvc.perform(post("/api/greenhouse/strategies/draft").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("strategyCode", "S-BAD", "cropCode", "C1", "zoneCode", "Z1",
                                "dayTempTarget", 30, "dayTempTolerance", 0,
                                "dayHumidityTarget", 70, "dayHumidityTolerance", 5,
                                "nightTempTarget", 16, "nightTempTolerance", 0,
                                "nightHumidityTarget", 70, "nightHumidityTolerance", 5))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/greenhouse/strategies/S-BAD/validate"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        // 合法策略编制、校验、下发 200/201。
        mockMvc.perform(post("/api/greenhouse/strategies/draft").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("strategyCode", "S-OK", "cropCode", "C1", "zoneCode", "Z1",
                                "dayTempTarget", 22, "dayTempTolerance", 1,
                                "dayHumidityTarget", 70, "dayHumidityTolerance", 5,
                                "nightTempTarget", 16, "nightTempTolerance", 1,
                                "nightHumidityTarget", 70, "nightHumidityTolerance", 5))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/greenhouse/strategies/S-OK/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        mockMvc.perform(post("/api/greenhouse/strategies/activate").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("strategyCode", "S-OK", "requestId", "REQ-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyCode").value("S-OK"));

        // 同键同参重放 200。
        mockMvc.perform(post("/api/greenhouse/strategies/activate").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("strategyCode", "S-OK", "requestId", "REQ-1"))))
                .andExpect(status().isOk());

        // 缺字段 → 400。
        mockMvc.perform(post("/api/greenhouse/zones").contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("zoneCode", "Z2"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 读数上报与偏差告警HTTP流程() throws Exception {
        mockMvc.perform(post("/api/greenhouse/crops").contentType(MediaType.APPLICATION_JSON)
                .content(json(map("cropCode", "C2", "name", "番茄", "stageCode", "FRUITING",
                        "dayTempMin", 20, "dayTempMax", 28,
                        "dayHumidityMin", 60, "dayHumidityMax", 80,
                        "nightTempMin", 14, "nightTempMax", 20,
                        "nightHumidityMin", 65, "nightHumidityMax", 85)))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/greenhouse/zones").contentType(MediaType.APPLICATION_JSON)
                .content(json(map("zoneCode", "Z2", "name", "二号分区")))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/greenhouse/strategies/draft").contentType(MediaType.APPLICATION_JSON)
                .content(json(map("strategyCode", "S2", "cropCode", "C2", "zoneCode", "Z2",
                        "dayTempTarget", 22, "dayTempTolerance", 1,
                        "dayHumidityTarget", 70, "dayHumidityTolerance", 5,
                        "nightTempTarget", 16, "nightTempTolerance", 1,
                        "nightHumidityTarget", 70, "nightHumidityTolerance", 5))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/greenhouse/strategies/S2/validate")).andExpect(status().isOk());
        mockMvc.perform(post("/api/greenhouse/strategies/activate").contentType(MediaType.APPLICATION_JSON)
                .content(json(map("strategyCode", "S2", "requestId", "REQ-2")))).andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            String time = String.format("2026-09-25T%d:00:00Z", 10 + i);
            mockMvc.perform(post("/api/greenhouse/readings").contentType(MediaType.APPLICATION_JSON)
                            .content(json(map("zoneCode", "Z2", "sampleTime", time,
                                    "temperature", new BigDecimal("30.00"),
                                    "humidity", new BigDecimal("70.00")))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.alertTriggered").value(i == 2));
        }
    }
}
