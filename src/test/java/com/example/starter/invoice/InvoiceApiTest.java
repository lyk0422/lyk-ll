package com.example.starter.invoice;

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
 * HTTP 接口语义测试：状态码与统一错误体。
 */
@AutoConfigureMockMvc
class InvoiceApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 开票接口主流程与幂等冲突语义() throws Exception {
        createAccount(SELLER, "1000.00");
        String body = """
                {"requestId":"REQ-API-1","sellerTaxNo":"%s","buyerTaxNo":"%s",
                 "lines":[{"itemName":"商品","quantity":1,"unitPrice":100.00,"amount":100.00}]}
                """.formatted(SELLER, BUYER);

        mockMvc.perform(post("/api/invoices/issue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.totalAmount").value(100.00));

        // 同键同参：幂等返回首次结果
        mockMvc.perform(post("/api/invoices/issue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 同键异参：409
        String conflict = body.replace("100.00", "200.00");
        mockMvc.perform(post("/api/invoices/issue").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void 超额开票返回422且额度不变() throws Exception {
        createAccount(SELLER, "100.00");
        String body = """
                {"requestId":"REQ-API-2","sellerTaxNo":"%s","buyerTaxNo":"%s",
                 "lines":[{"itemName":"商品","quantity":1,"unitPrice":200.00,"amount":200.00}]}
                """.formatted(SELLER, BUYER);
        mockMvc.perform(post("/api/invoices/issue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        mockMvc.perform(get("/api/credit-accounts/" + SELLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value(100.00));
    }

    @Test
    void 查询不存在资源返回404() throws Exception {
        mockMvc.perform(get("/api/invoices/NO-SUCH-INVOICE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 参数校验失败返回400() throws Exception {
        String body = """
                {"requestId":"","sellerTaxNo":"","buyerTaxNo":"","lines":[]}
                """;
        mockMvc.perform(post("/api/invoices/issue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 红冲全流程接口() throws Exception {
        createAccount(SELLER, "1000.00");
        String issue = """
                {"requestId":"REQ-API-3","sellerTaxNo":"%s","buyerTaxNo":"%s",
                 "lines":[{"itemName":"商品","quantity":1,"unitPrice":500.00,"amount":500.00}]}
                """.formatted(SELLER, BUYER);
        String invoiceNo = mockMvc.perform(post("/api/invoices/issue")
                        .contentType(MediaType.APPLICATION_JSON).content(issue))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"invoiceNo\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/invoices/" + invoiceNo + "/deliver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        String createInfo = """
                {"requestId":"REQ-API-4","originalInvoiceNo":"%s","redType":"FULL"}
                """.formatted(invoiceNo);
        String infoNo = mockMvc.perform(post("/api/red-info-tables")
                        .contentType(MediaType.APPLICATION_JSON).content(createInfo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"infoNo\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/red-info-tables/" + infoNo + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"party\":\"SELLER\",\"opinion\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/red-info-tables/" + infoNo + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"party\":\"BUYER\",\"opinion\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.redInvoiceNo").isNotEmpty());

        mockMvc.perform(get("/api/credit-accounts/" + SELLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value(1000.00));
    }
}
