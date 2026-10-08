package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Web 层改投全流程：10000kg 在途联单 -> 原设施停收 -> 改投 -> 旧设施迟到回执仅存证据
 * -> 新设施按当前版本回执闭环 -> 去向与迟到证据链查询。
 */
@AutoConfigureMockMvc
class RerouteApiTest extends LedgerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rerouteAndControlledReceiptFlowOverHttp() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String plateNo = plateOf(vehicleId);

        // 1. 进场：毛重 18000 - 皮重 8000 = 净重 10000，去向 F-INCINERATION
        String entryBody = """
                {
                  "requestId": "REQ-API-RR-ENTRY",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "OTHER",
                  "grossKg": 18000.000,
                  "facilityCode": "F-INCINERATION"
                }
                """.formatted(plateNo);
        var entryResult = mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(entryBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.netKg").value(10000.0))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andReturn();
        long manifestId = Long.parseLong(entryResult.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll(".*\"manifestId\":(\\d+).*", "$1"));

        // 2. 原设施临时停收：设施版本 1 -> 2，配置停收区间
        mockMvc.perform(put("/api/facilities/F-INCINERATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-FAC-1",
                                  "expectedVersion": 1,
                                  "categories": ["OTHER"],
                                  "suspensions": [
                                    {"startAt": "2026-10-06T00:00:00Z", "endAt": "2026-10-07T00:00:00Z"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 3. 在途改投到 F-INCINERATION-2：去向版本 1 -> 2
        mockMvc.perform(post("/api/manifests/{id}/reroutes", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-RR-1",
                                  "facilityCode": "F-INCINERATION-2",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationVersion").value(2))
                .andExpect(jsonPath("$.facilityCode").value("F-INCINERATION-2"));

        // 4. 旧设施迟到回执（版本 1）：仅存证据，不闭环
        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-RR-LATE",
                                  "netKg": 10000.000,
                                  "facilityCode": "F-INCINERATION",
                                  "destinationVersion": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("LATE_EVIDENCE"));

        // 5. 新设施按当前版本 2 回执：10100kg，偏差 1%，闭环
        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-RR-OK",
                                  "netKg": 10100.000,
                                  "facilityCode": "F-INCINERATION-2",
                                  "destinationVersion": 2
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("MATCH"));

        // 6. 联单详情：完整去向链与迟到证据链
        mockMvc.perform(get("/api/manifests/{id}", manifestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.destinationVersion").value(2))
                .andExpect(jsonPath("$.currentFacilityCode").value("F-INCINERATION-2"))
                .andExpect(jsonPath("$.destinations.length()").value(2))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.lateReceipts.length()").value(1))
                .andExpect(jsonPath("$.lateReceipts[0].facilityCode").value("F-INCINERATION"));

        // 7. 日结：出场只计有效回执 10100kg
        mockMvc.perform(get("/api/settlements/daily").param("date", "2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("OTHER"))
                .andExpect(jsonPath("$[0].inboundCount").value(1))
                .andExpect(jsonPath("$[0].outboundCount").value(1))
                .andExpect(jsonPath("$[0].outboundNetKg").value(10100.0));
    }

    @Test
    void rerouteWithStaleVersionReturns409() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String entryBody = """
                {
                  "requestId": "REQ-API-RR409-ENTRY",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "OTHER",
                  "grossKg": 18000.000,
                  "facilityCode": "F-INCINERATION"
                }
                """.formatted(plateOf(vehicleId));
        var entryResult = mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody))
                .andExpect(status().isCreated())
                .andReturn();
        long manifestId = Long.parseLong(entryResult.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll(".*\"manifestId\":(\\d+).*", "$1"));

        mockMvc.perform(post("/api/manifests/{id}/reroutes", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-RR409",
                                  "facilityCode": "F-INCINERATION-2",
                                  "expectedVersion": 5
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void legacyReceiptOnReroutedManifestReturns400() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String entryBody = """
                {
                  "requestId": "REQ-API-LEG-ENTRY",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "OTHER",
                  "grossKg": 18000.000,
                  "facilityCode": "F-INCINERATION"
                }
                """.formatted(plateOf(vehicleId));
        var entryResult = mockMvc.perform(post("/api/weighings/entry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entryBody))
                .andExpect(status().isCreated())
                .andReturn();
        long manifestId = Long.parseLong(entryResult.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll(".*\"manifestId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/manifests/{id}/reroutes", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-LEG-RR",
                                  "facilityCode": "F-INCINERATION-2",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"REQ-API-LEG-R\", \"netKg\": 10000.000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESTINATION_CONTROL_REQUIRED"));
    }

    @Test
    void facilityQueryShowsVersionAndSuspensions() throws Exception {
        mockMvc.perform(put("/api/facilities/F-KITCHEN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-FAC-Q",
                                  "expectedVersion": 1,
                                  "categories": ["KITCHEN"],
                                  "suspensions": [
                                    {"startAt": "2026-10-06T01:00:00Z", "endAt": "2026-10-06T02:00:00Z"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/facilities/F-KITCHEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.categories[0]").value("KITCHEN"))
                .andExpect(jsonPath("$.suspensions.length()").value(1));
    }
}
