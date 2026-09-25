package com.example.starter.wbb.web;

import com.example.starter.wbb.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口端到端测试（H2 + MockMvc）：HTTP 状态码语义、幂等重放/409、完整流程与迟到事件。
 */
@AutoConfigureMockMvc
class ManifestControllerH2Test extends AbstractH2IntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;

    private final String flight = "CA3001";
    private final String key = flight + "-2026-09-26";

    private String preplanJson(String requestId, String flightNo, String fuel, String payloadWeight) {
        return """
                {
                  "requestId": "%s",
                  "flightNo": "%s",
                  "flightDate": "2026-09-26",
                  "aircraftType": "A320-SYN",
                  "aircraftRegistration": "B-8888",
                  "fuelKg": %s,
                  "items": [
                    {"category": "PAX", "zoneCode": "CABIN", "pieceCount": 100, "weightKg": 8000, "arm": 16.0},
                    {"category": "CARGO", "zoneCode": "HOLD1", "pieceCount": 20, "weightKg": %s, "arm": 16.0}
                  ]
                }
                """.formatted(requestId, flightNo, fuel, payloadWeight);
    }

    @Test
    void fullFlowOverHttpWithStatusCodesAndIdempotency() throws Exception {
        // 预配 201。
        mockMvc.perform(post("/api/manifests/preplan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preplanJson("http-pre", flight, "5000", "2000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.payloadLimitOk").value(true))
                .andExpect(jsonPath("$.envelopeOk").value(true));

        // 同键同参重放：仍 200（非新建），返回首次结果。
        mockMvc.perform(post("/api/manifests/preplan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preplanJson("http-pre", flight, "5000", "2000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));

        // 同键异参：409。
        mockMvc.perform(post("/api/manifests/preplan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preplanJson("http-pre", flight, "6000", "2000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 值机 → 装载确认。
        mockMvc.perform(post("/api/flights/" + key + "/checkin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-ci","items":[
                                  {"category":"PAX","zoneCode":"CABIN","pieceCount":100,"weightKg":8000,"arm":16.0},
                                  {"category":"CARGO","zoneCode":"HOLD1","pieceCount":20,"weightKg":2000,"arm":16.0}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECK_IN"));
        mockMvc.perform(post("/api/flights/" + key + "/load-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-load"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOADED"));

        // LMC 恰达限额：版本仍为 1。
        mockMvc.perform(post("/api/flights/" + key + "/lmc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-lmc5","deltaItems":[
                                  {"category":"PAX","zoneCode":"CABIN","pieceCount":5,"weightKg":500,"arm":16.0}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.lmcDeltaPax").value(5));

        // 油量变更：起飞重量与重心联动。
        mockMvc.perform(post("/api/flights/" + key + "/fuel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-fuel","fuelKg":7000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuelKg").value(7000.00))
                .andExpect(jsonPath("$.takeoffWeightKg").value(57500.00))
                .andExpect(jsonPath("$.zeroFuelWeightKg").value(50500.00));

        // 双岗复核：WBO 单人签后未冻结，关闭 422；DSP 签后冻结。
        mockMvc.perform(post("/api/flights/" + key + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-rwbo","role":"WBO","reviewer":"zhang.san"}"""))
                .andExpect(jsonPath("$.frozen").value(false));
        mockMvc.perform(post("/api/flights/" + key + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-close-early"}"""))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/flights/" + key + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-rdsp","role":"DSP","reviewer":"li.si"}"""))
                .andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.frozen").value(true));

        // 放行关闭；重复关闭幂等成功。
        mockMvc.perform(post("/api/flights/" + key + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-close"}"""))
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/api/flights/" + key + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 关闭后迟到值机：舱单不变，产生一条 alarmed 迟到事件。
        mockMvc.perform(post("/api/flights/" + key + "/checkin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-late","items":[
                                  {"category":"PAX","zoneCode":"CABIN","pieceCount":1,"weightKg":80,"arm":16.0}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.payloadKg").value(10500.00));
        String body = mockMvc.perform(get("/api/flights/" + key + "/late-events"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode events = om.readTree(body);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("alarmed").asBoolean()).isTrue();
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("CHECK_IN");
    }

    @Test
    void overweightReturns422AndUnknownFlightReturns404() throws Exception {
        // 业载 20000.01kg 超重 → 422。
        mockMvc.perform(post("/api/manifests/preplan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preplanJson("http-over", "CA3002", "0", "12000.01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        // 参数缺失 → 400。
        mockMvc.perform(post("/api/manifests/preplan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNo":"CA3003","flightDate":"2026-09-26",
                                 "aircraftType":"A320-SYN","aircraftRegistration":"B-1","fuelKg":1000}"""))
                .andExpect(status().isBadRequest());

        // 无有效舱单 → 404。
        mockMvc.perform(get("/api/flights/CA9999-" + LocalDate.now() + "/manifests/active"))
                .andExpect(status().isNotFound());
    }
}
