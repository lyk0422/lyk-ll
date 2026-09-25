package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 全流程：建约→保单→立案预提→结案冲销→出账→异议→（另一账单）双方确认封存→调整单。
 */
@AutoConfigureMockMvc
class ApiFlowTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 出账异议封存与调整单全流程() throws Exception {
        mockMvc.perform(post("/api/treaties").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"treatyNo\":\"T-API\",\"name\":\"比例合约\","
                                + "\"cessionRate\":0.8,\"limitPerPolicy\":1000.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/treaties/T-API/policies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyNo\":\"P-API\",\"cededPremium\":1000.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/policies/P-API/claims").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r1\",\"claimNo\":\"C-API\","
                                + "\"estimateAmount\":500.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisionAmount").value(400.00));
        mockMvc.perform(post("/api/claims/C-API/close").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r2\",\"finalPaidAmount\":400.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversalAmount").value(-80.00))
                .andExpect(jsonPath("$.balanceAfter").value(320.00));

        // 出账：保费分保 800 + 赔款摊回 320 = 1120，超限额 1000 按限额计入
        MvcResult billResult = mockMvc.perform(post("/api/bills/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r3\",\"treatyNo\":\"T-API\","
                                + "\"quarter\":\"2026Q3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.status").value("PENDING"))
                .andExpect(jsonPath("$.bill.totalAmount").value(1000.00))
                .andExpect(jsonPath("$.items[0].retainedAmount").value(120.00))
                .andReturn();
        String body = billResult.getResponse().getContentAsString();
        String billNo = com.jayway.jsonpath.JsonPath.read(body, "$.bill.billNo");
        Integer itemId = com.jayway.jsonpath.JsonPath.read(body, "$.items[0].id");

        // 异议：逐项列明差异原因
        mockMvc.perform(post("/api/bills/" + billNo + "/disputes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r4\",\"items\":[{\"billItemId\":" + itemId
                                + ",\"reason\":\"限额适用口径待核对\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPUTED"));
        // 异议后确认被拒绝
        mockMvc.perform(post("/api/bills/" + billNo + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r5\",\"party\":\"CEDANT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_DISPUTED"));

        // 另一季度账单走双方确认→封存
        String bill2 = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(
                        post("/api/bills/generate").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"api-r6\",\"treatyNo\":\"T-API\","
                                        + "\"quarter\":\"2026Q4\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.bill.billNo");
        mockMvc.perform(post("/api/bills/" + bill2 + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r7\",\"party\":\"CEDANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.status").value("PENDING"));
        mockMvc.perform(post("/api/bills/" + bill2 + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r8\",\"party\":\"REINSURER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.status").value("SEALED"));
        // 封存后重复确认幂等成功、异议拒绝
        mockMvc.perform(post("/api/bills/" + bill2 + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r9\",\"party\":\"CEDANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.status").value("SEALED"));
        mockMvc.perform(post("/api/bills/" + bill2 + "/disputes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r10\",\"items\":[{\"billItemId\":1,"
                                + "\"reason\":\"封存后异议\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILL_SEALED"));

        // 差错走下一期调整单并引用原账单号
        mockMvc.perform(post("/api/bills/" + bill2 + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-r11\",\"quarter\":\"2027Q1\","
                                + "\"premiumAdjustment\":-10.00,\"claimRecoveryAdjustment\":0.00,"
                                + "\"reason\":\"保费分保差错更正\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.billType").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.bill.originalBillNo").value(bill2))
                .andExpect(jsonPath("$.bill.totalAmount").value(-10.00));

        // 查询复算
        mockMvc.perform(get("/api/bills/" + billNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.billNo").value(billNo));
        // 账户余额
        mockMvc.perform(get("/api/treaties/T-API/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.balance").value(320.00));
    }

    @Test
    void 同键异参HTTP语义为409() throws Exception {
        mockMvc.perform(post("/api/treaties").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"treatyNo\":\"T-409\",\"name\":\"比例合约\","
                                + "\"cessionRate\":0.5,\"limitPerPolicy\":100.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/bills/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-k1\",\"treatyNo\":\"T-409\","
                                + "\"quarter\":\"2026Q3\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/bills/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-k1\",\"treatyNo\":\"T-409\","
                                + "\"quarter\":\"2026Q4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
