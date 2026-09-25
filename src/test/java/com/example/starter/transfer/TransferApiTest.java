package com.example.starter.transfer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 层语义测试：公开入口的状态码与响应体。
 */
@AutoConfigureMockMvc
class TransferApiTest extends BaseTransferTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applyEndorseVerifyFlowOverHttp() throws Exception {
        seedStock("WH-A", TARIFF_A, 100);
        String applyBody = """
                {"requestId":"HTTP-1","enterpriseId":"ENT-H","fromWarehouse":"WH-A","toWarehouse":"WH-B",
                 "items":[{"tariffCode":"0901.21","quantity":10,"unitPrice":12.50}]}
                """;
        String location = mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.requestId").value("HTTP-1"))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(location, "$.orderId")).longValue();

        // 同键同参重放返回首次结果
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId));

        // 同键异参返回 409
        String conflictBody = applyBody.replace("\"quantity\":10", "\"quantity\":11");
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/transfers/{id}/endorse", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        mockMvc.perform(post("/api/transfers/{id}/verifications", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"HV-1\",\"items\":[{\"tariffCode\":\"0901.21\",\"quantity\":10}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/transfers/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("CLOSED"))
                .andExpect(jsonPath("$.history.length()").value(3));
    }

    @Test
    void invalidRequestReturns400AndUnknownOrderReturns404() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"enterpriseId\":\"ENT-H\",\"fromWarehouse\":\"WH-A\","
                                + "\"toWarehouse\":\"WH-B\",\"items\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/transfers/{id}", 999999))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/transfers/{id}/endorse", 999999))
                .andExpect(status().isNotFound());
    }
}
