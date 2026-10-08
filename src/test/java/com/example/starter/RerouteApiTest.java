package com.example.starter;

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
 * Web 层改投全流程：净重 10000kg 联单在原设施停收后改投另一家设施，
 * 旧设施迟到回执仅存证据，新设施当前版本回执按 2% 规则入账，查询展示完整去向与证据链。
 */
@AutoConfigureMockMvc
class RerouteApiTest extends LedgerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rerouteFlowWithLateReceiptEvidenceOverHttp() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());

        // 1. 进场登记：OTHER 品类，毛重 18000 - 皮重 8000 = 净重 10000，去向 F-INCINERATION
        String entryBody = """
                {
                  "requestId": "REQ-API-RR-ENTRY",
                  "plateNo": "%s",
                  "community": "示例小区",
                  "category": "OTHER",
                  "grossKg": 18000.000,
                  "facilityCode": "F-INCINERATION"
                }
                """.formatted(plateOf(vehicleId));
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

        // 2. 原设施临时停收（设施版本 1 -> 2）
        mockMvc.perform(post("/api/facilities/{code}/revisions", "F-INCINERATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-FAC-REV",
                                  "expectedVersion": 1,
                                  "suspension": {
                                    "startAt": "2026-10-06T00:00:00Z",
                                    "endAt": "2026-10-07T00:00:00Z"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        // 3. 在途改投 F-INCINERATION-2（去向版本 1 -> 2）
        mockMvc.perform(post("/api/manifests/{id}/reroutes", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-RR-1",
                                  "newFacilityCode": "F-INCINERATION-2",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationVersion").value(2))
                .andExpect(jsonPath("$.facilityCode").value("F-INCINERATION-2"));

        // 4. 旧设施的迟到回执（版本 1）：仅存证据，不闭环
        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-LATE-1",
                                  "netKg": 10000.000,
                                  "facilityCode": "F-INCINERATION",
                                  "destinationVersion": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("LATE_EVIDENCE"));

        // 5. 受控联单缺设施/版本的回执：400
        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"REQ-API-NODST\", \"netKg\": 10000.000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_DESTINATION_REQUIRED"));

        // 6. 新设施当前版本回执：偏差 0%，按 2% 规则入账闭环
        mockMvc.perform(post("/api/manifests/{id}/receipts", manifestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-BOOK-1",
                                  "netKg": 10000.000,
                                  "facilityCode": "F-INCINERATION-2",
                                  "destinationVersion": 2
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("MATCH"));

        // 7. 查询：完整去向链 + 迟到证据链
        mockMvc.perform(get("/api/manifests/{id}", manifestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.destinationVersion").value(2))
                .andExpect(jsonPath("$.currentFacilityCode").value("F-INCINERATION-2"))
                .andExpect(jsonPath("$.destinations[0].facilityCode").value("F-INCINERATION"))
                .andExpect(jsonPath("$.destinations[1].facilityCode").value("F-INCINERATION-2"))
                .andExpect(jsonPath("$.lateReceipts[0].requestId").value("REQ-API-LATE-1"))
                .andExpect(jsonPath("$.lateReceipts[0].facilityCode").value("F-INCINERATION"));
    }

    @Test
    void staleRerouteVersionReturns409() throws Exception {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        String entryBody = """
                {
                  "requestId": "REQ-API-RR-ENTRY-2",
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
                                  "requestId": "REQ-API-RR-2",
                                  "newFacilityCode": "F-INCINERATION-2",
                                  "expectedVersion": 9
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESTINATION_VERSION_STALE"));
    }

    @Test
    void staleFacilityRevisionVersionReturns409() throws Exception {
        mockMvc.perform(post("/api/facilities/{code}/revisions", "F-KITCHEN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-API-FAC-409",
                                  "expectedVersion": 7,
                                  "active": false
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FACILITY_VERSION_STALE"));
    }
}
