package com.example.starter.bonded.web;

import com.example.starter.bonded.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层语义：申请 201、重放 200、异参 409、核注/离港/分批核销/结案链路与查询留痕。
 */
class TransferControllerTest extends BaseIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext wac;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    @DisplayName("完整 HTTP 链路：申请201 -> 重放200 -> 异参409 -> 核注 -> 离港 -> 分批核销 -> 结案查询")
    void fullHttpFlow() throws Exception {
        String body = """
                {
                  "requestId": "W-1",
                  "enterpriseId": "ENT-001",
                  "fromWarehouse": "WH-A",
                  "toWarehouse": "WH-B",
                  "items": [
                    {"hsCode": "HS-1001", "quantity": 100, "unitPrice": 10.50},
                    {"hsCode": "HS-1002", "quantity": 50, "unitPrice": 20.00}
                  ]
                }
                """;
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.requestId").value("W-1"))
                .andExpect(jsonPath("$.items.length()").value(2));

        // 同参重放 200
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        // 异参 409
        String conflictBody = body.replace("\"quantity\": 100", "\"quantity\": 101");
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 参数校验失败 400
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // 核注 -> 离港
        mockMvc.perform(post("/api/transfers/W-1/endorse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDORSED"))
                .andExpect(jsonPath("$.deadline").exists());
        mockMvc.perform(post("/api/transfers/W-1/depart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 第一批部分核销
        mockMvc.perform(post("/api/transfers/W-1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchRequestId":"WB-1","items":[{"hsCode":"HS-1001","quantity":40}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 超量批次整批拒绝 409
        mockMvc.perform(post("/api/transfers/W-1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchRequestId":"WB-BAD","items":[{"hsCode":"HS-1002","quantity":999}]}
                                """))
                .andExpect(status().isConflict());

        // 核满结案
        mockMvc.perform(post("/api/transfers/W-1/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchRequestId":"WB-2","items":[
                                  {"hsCode":"HS-1001","quantity":60},
                                  {"hsCode":"HS-1002","quantity":50}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 查询：状态与留痕按序可查
        mockMvc.perform(get("/api/transfers/W-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].toStatus").value("APPLIED"))
                .andExpect(jsonPath("$.history[1].toStatus").value("ENDORSED"))
                .andExpect(jsonPath("$.history[2].toStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.history[3].toStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.history[4].toStatus").value("CLOSED"));

        // 不存在单据 404
        mockMvc.perform(get("/api/transfers/NOPE"))
                .andExpect(status().isNotFound());
    }
}
