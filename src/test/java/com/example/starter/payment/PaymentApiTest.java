package com.example.starter.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层：入账、封账、对账、差异核销的 HTTP 状态与错误语义。
 */
@AutoConfigureMockMvc
class PaymentApiTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String POST_BODY = """
            {"requestId":"r1","serialNo":"S1","merchantNo":"M0001","amount":10000,
             "txnTime":"2026-09-24T10:00:00Z"}
            """;

    @Test
    void postThenIdempotentReplayThenConflict() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.batchDate").value("2026-09-24"))
                .andExpect(jsonPath("$.fee").value(30))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参：返回首次结果
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 同键异参：409
        String conflict = POST_BODY.replace("\"amount\":10000", "\"amount\":20000");
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict());
    }

    @Test
    void closeReconcileAndResolveFlow() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
                .andExpect(status().isOk());

        // 封账生成清算单；重复封账返回同一清算单
        mvc.perform(post("/api/batches/2026-09-24/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].merchantNo").value("M0001"))
                .andExpect(jsonPath("$[0].netAmount").value(9970));
        mvc.perform(post("/api/batches/2026-09-24/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].netAmount").value(9970));

        // 对账：本地 S1 在渠道文件中缺失 → 本地多账；渠道 C1 本地无 → 渠道多账
        String reconBody = """
                {"entries":[{"serialNo":"C1","amount":5000}]}
                """;
        mvc.perform(post("/api/batches/2026-09-24/reconcile")
                        .contentType(MediaType.APPLICATION_JSON).content(reconBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 差异核销：首次成功，重复核销 409
        Integer diffId = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post("/api/batches/2026-09-24/reconcile")
                                .contentType(MediaType.APPLICATION_JSON).content(reconBody))
                        .andReturn().getResponse().getContentAsString(), "$[0].id");
        String resolveBody = """
                {"resolution":"WRITE_OFF","operator":"op-1"}
                """;
        mvc.perform(post("/api/differences/" + diffId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content(resolveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(post("/api/differences/" + diffId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content(resolveBody))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        String bad = """
                {"requestId":"","serialNo":"S1","merchantNo":"M0001","amount":-5,
                 "txnTime":"2026-09-24T10:00:00Z"}
                """;
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }
}
