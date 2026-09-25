package com.example.starter.wb.web;

import com.example.starter.wb.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 契约：预配→值机→装载确认→双岗复核→关闭，含 409/422 语义与迟到告警。
 */
class LoadsheetApiTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc rest() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private static String exactPayloadCreateBody(String requestId) {
        return """
                {
                  "requestId": "%s",
                  "flightId": 1,
                  "fuelKg": 5000,
                  "createdBy": "lc001",
                  "items": [
                    {"category": "PASSENGER", "stationCode": "PAX_F", "pieces": 100, "unitWeightKg": 80},
                    {"category": "CARGO", "stationCode": "CARGO_F", "pieces": 7, "unitWeightKg": 1000}
                  ]
                }
                """.formatted(requestId);
    }

    @Test
    void fullHttpFlowWithBoundaryWeightsAndReviewThenClose() throws Exception {
        String rid = UUID.randomUUID().toString();
        // 预配：业载恰 15000kg、ZFW 重心恰 14%MAC、TOW 重心恰 15%MAC
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content(exactPayloadCreateBody(rid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PRELIMINARY"))
                .andExpect(jsonPath("$.payloadKg").value(15000.00))
                .andExpect(jsonPath("$.cgZfwPct").value(14.0000))
                .andExpect(jsonPath("$.cgTowPct").value(15.0000))
                .andExpect(jsonPath("$.withinLimits").value(true));

        // 同键同参回放首次结果（不产生新版本）
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content(exactPayloadCreateBody(rid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));

        // 同键异参 409
        String tampered = exactPayloadCreateBody(rid).replace("\"fuelKg\": 5000", "\"fuelKg\": 5100");
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        // 值机
        rest().perform(post("/api/flights/1/loadsheet/checkin")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","fuelKg":5000,"operator":"a","items":[
                                  {"category":"PASSENGER","stationCode":"PAX_F","pieces":100,"unitWeightKg":80},
                                  {"category":"CARGO","stationCode":"CARGO_F","pieces":7,"unitWeightKg":1000}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKIN"));

        // 装载确认
        rest().perform(post("/api/flights/1/loadsheet/confirm-load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"loader"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOADED"));

        // 双岗复核
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"LOAD_CONTROLLER","reviewer":"lc001"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOADED"));
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"FLIGHT_DISPATCHER","reviewer":"fd002"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.frozen").value(true));

        // 单人重复岗位 409
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"LOAD_CONTROLLER","reviewer":"lc999"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isConflict());

        // 关闭
        rest().perform(post("/api/flights/1/loadsheet/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","closedBy":"captain"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        // 迟到值机：只登记并告警，舱单数据不变
        rest().perform(post("/api/flights/1/loadsheet/late-checkin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","passengerCount":2,"weightKg":160,"detail":"两名晚到旅客"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.payloadKg").value(15000.00))
                .andExpect(jsonPath("$.lateAlerts[0].passengerCount").value(2))
                .andExpect(jsonPath("$.lateAlerts[0].alerted").value(true));

        rest().perform(get("/api/flights/1/loadsheet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.lateAlerts.length()").value(1));
    }

    @Test
    void overLimitPayloadReturns422() throws Exception {
        String body = exactPayloadCreateBody(UUID.randomUUID().toString())
                .replace("\"pieces\": 7, \"unitWeightKg\": 1000", "\"pieces\": 7, \"unitWeightKg\": 1001");
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("MZFW-OEW")));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
