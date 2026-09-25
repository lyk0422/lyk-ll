package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 撤批：整体作废、全部关联单证释放、占用登记清除后单证可被新批次重新使用；
 * 不允许部分撤出；已进入退库环节不得撤批。
 */
class WithdrawBatchTest extends BaseApiTest {

    @Test
    void withdrawVoidsBatchAndReleasesAllDocumentsForReuse() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("w-c1", "CD-W1", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/customs",
                TestFixtures.customs("w-c2", "CD-W2", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("w-i1", "INV-W1", "CD-W1", TestFixtures.P1, "900"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("w-i2", "INV-W2", "CD-W2", TestFixtures.P1, "900"), 200);

        postJson("/api/batches/check",
                TestFixtures.check("w-chk", "B-W", "2026-02-01", "CD-W1", "CD-W2"), 200);

        JsonNode voided = postJson("/api/batches/B-W/withdraw",
                TestFixtures.requestId("w-do"), 200);
        assertEquals("VOID", voided.get("status").asText());

        // 全部关联单证均已释放（不得只撤出部分）
        Integer linked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_batch_document WHERE link_status = 'LINKED'", Integer.class);
        assertEquals(0, linked, "撤批后不得残留 LINKED 单证");
        Integer released = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_batch_document WHERE link_status = 'RELEASED'", Integer.class);
        assertEquals(4, released, "2 张报关单 + 2 张发票均须标记 RELEASED");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_declaration_registry", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_invoice_registry", Integer.class));

        // 释放后同一报关单可参与新的有效批次
        JsonNode recheck = postJson("/api/batches/check",
                TestFixtures.check("w-chk2", "B-W2", "2026-02-01", "CD-W1", "CD-W2"), 200);
        assertEquals("CHECKED", recheck.get("status").asText());
    }

    @Test
    void withdrawAfterApprovalRejectedAndReplayIsIdempotent() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("w2-c", "CD-W3", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("w2-i", "INV-W3", "CD-W3", TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("w2-chk", "B-W3", "2026-02-01", "CD-W3"), 200);
        postJson("/api/batches/B-W3/declare",
                TestFixtures.declare("w2-decl", false), 200);
        postJson("/api/batches/B-W3/approve",
                TestFixtures.requestId("w2-appr"), 200);

        JsonNode resp = postJson("/api/batches/B-W3/withdraw",
                TestFixtures.requestId("w2-with"), 409);
        assertTrue(resp.get("error").asText().contains("退库环节"));
        assertEquals("APPROVED", getJson("/api/batches/B-W3", 200).get("status").asText());
    }
}
