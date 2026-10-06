package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web 层全流程：进场称重 -> 回执闭环 -> 日结查询的 HTTP 语义与错误响应体。
 */
@AutoConfigureMockMvc
class LedgerApiTest extends LedgerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void entryToReceiptToSettlementFlowOverHttp() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String plateNo = plateOf(vehicleId);

        // 1. 进场称重登记联单：毛重 13000 - 皮重 8000 = 净重 5000
        String entryBody = """
                {
                  "requestId": "REQ-API-ENTRY-1",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "KITCHEN",
                  "grossKg": 13000.000,
                  "facilityCode": "F-KITCHEN"
                }
                """.formatted(plateNo);
        var entryResult = mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(entryBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.netKg").value(13000.0 - 8000.0))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andReturn();
        String responseJson = entryResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long manifestId = Long.parseLong(
                responseJson.replaceAll(".*\"manifestId\":(\\d+).*", "$1"));

        // 2. 处理设施回执：5100kg，偏差恰 2% 视为一致
        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"REQ-API-RECEIPT-1\", \"netKg\": 5100.000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("MATCH"));

        // 3. 日结查询：KITCHEN 进 1 车 5000kg，出 1 车 5100kg
        mockMvc.perform(get("/api/settlements/daily").param("date", "2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("KITCHEN"))
                .andExpect(jsonPath("$[0].inboundCount").value(1))
                .andExpect(jsonPath("$[0].outboundCount").value(1));
    }

    @Test
    void zeroNetWeightReturns400WithErrorCode() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String body = """
                {
                  "requestId": "REQ-API-ZERO",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "KITCHEN",
                  "grossKg": 8000.000,
                  "facilityCode": "F-KITCHEN"
                }
                """.formatted(plateOf(vehicleId));

        mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NET_WEIGHT_ZERO"));
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String plateNo = plateOf(vehicleId);
        String template = """
                {
                  "requestId": "REQ-API-409",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "KITCHEN",
                  "grossKg": %s,
                  "facilityCode": "F-KITCHEN"
                }
                """;

        mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(plateNo, "12000.000")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(plateNo, "13000.000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"REQ-API-VALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        var result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("UP");
    }
}
