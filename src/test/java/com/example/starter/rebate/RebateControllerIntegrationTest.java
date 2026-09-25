package com.example.starter.rebate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路 HTTP 集成测试：采集→校验（疑点）→说明→复核申报→审核→到账核销→结案→迟到单证，
 * 并验证同键异参返回 409 且不改变批次状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RebateControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        for (String table : new String[]{"late_event", "refund_registration", "idempotent_request",
                "declaration_registration", "doubt", "validation_detail",
                "rebate_batch", "rebate_document", "rate_library"}) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        for (String table : new String[]{"rate_library", "rebate_document", "rebate_batch",
                "validation_detail", "doubt", "refund_registration", "late_event"}) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 1");
        }
    }

    @Test
    void fullLifecycleThroughHttp() throws Exception {
        // 1) 文库：旧率 13%（2026-01-01）
        mockMvc.perform(post("/api/rebate/rates").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"rate-1","hsCode":"9403609900","effectiveDate":"2026-01-01","rate":0.13}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.13));

        // 2) 文库版本按日复算：2026-06-30 应命中 13%
        mockMvc.perform(get("/api/rebate/rates/effective")
                        .param("hsCode", "9403609900").param("declareDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveDate").value("2026-01-01"));

        // 3) 采集：FOB 1000 元 / 10 美元 → 成本 13 越界，产生疑点
        String collectBody = """
                {"requestId":"doc-1","declarationNo":"CUSTOMS-001","invoiceNo":"INV-001",
                 "hsCode":"9403609900","fobRmb":1000.0000,"fobUsd":10.0000}
                """;
        mockMvc.perform(post("/api/rebate/documents").contentType(MediaType.APPLICATION_JSON).content(collectBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.replayed").value(false));
        // 同键重放
        mockMvc.perform(post("/api/rebate/documents").contentType(MediaType.APPLICATION_JSON).content(collectBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 4) 建批次并校验：DOUBT
        mockMvc.perform(post("/api/rebate/batches").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"batch-1","batchNo":"BATCH-2026-001","declareDate":"2026-06-30","documentIds":[1]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.taxTotal").value(130.0000))
                .andExpect(jsonPath("$.details[0].result").value("DOUBT"))
                .andExpect(jsonPath("$.doubts[0].status").value("OPEN"));

        // 5) 同键异参 → 409
        mockMvc.perform(post("/api/rebate/batches").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"batch-1","batchNo":"BATCH-2026-OTHER","declareDate":"2026-06-30","documentIds":[1]}
                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/rebate/batches/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value("BATCH-2026-001"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 6) 未解除疑点直接申报 → 422
        mockMvc.perform(post("/api/rebate/batches/1/declare").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"declare-1","reviewDoubtIds":[]}
                """))
                .andExpect(status().isUnprocessableEntity());

        // 7) 说明 + 复核申报原子推进
        mockMvc.perform(post("/api/rebate/doubts/1/explanations").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"explain-1","explanation":"高换汇成本：长期合作客户特价单，附合同备查"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPLAINED"));
        mockMvc.perform(post("/api/rebate/batches/1/declare").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"declare-2","reviewDoubtIds":[1]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLARED"))
                .andExpect(jsonPath("$.doubts[0].status").value("CLEARED"));

        // 8) 冻结后撤批 → 409
        mockMvc.perform(post("/api/rebate/batches/1/void"))
                .andExpect(status().isConflict());

        // 9) 审核 → 到账恰差 0.5% 一致（130.65）→ 退库
        mockMvc.perform(post("/api/rebate/batches/1/approve")).andExpect(status().isOk());
        mockMvc.perform(post("/api/rebate/batches/1/refunds").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"refund-1","treasurySerialNo":"TR-20260925-001","receivedAmount":130.6500}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSISTENT"))
                .andExpect(jsonPath("$.diffRatio").value(0.0050000000));

        // 10) 结案 → 迟到单证只登记事件
        mockMvc.perform(post("/api/rebate/batches/1/close").param("requestId", "close-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/api/rebate/batches/1/late-documents").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"late-1","declarationNo":"CUSTOMS-LATE","invoiceNo":"INV-LATE","payload":"{}"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
        mockMvc.perform(get("/api/rebate/batches/1/late-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // 终态不变
        mockMvc.perform(get("/api/rebate/batches/1"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }
}
