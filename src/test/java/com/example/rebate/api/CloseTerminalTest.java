package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 结案终态：重复结案幂等成功；结案后迟到单证只登记事件；结案后不得撤批/再核销。
 */
class CloseTerminalTest extends BaseApiTest {

    private String prepareTreasured(String batchNo, String declNo, String prefix) throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs(prefix + "-c", declNo, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice(prefix + "-i", "INV-" + declNo, declNo,
                        TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check(prefix + "-chk", batchNo, "2026-02-01", declNo), 200);
        postJson("/api/batches/" + batchNo + "/declare",
                TestFixtures.declare(prefix + "-decl", false), 200);
        postJson("/api/batches/" + batchNo + "/approve",
                TestFixtures.requestId(prefix + "-appr"), 200);
        postJson("/api/batches/" + batchNo + "/treasury",
                TestFixtures.treasury(prefix + "-w", "GK-" + prefix, "130.00"), 200);
        return batchNo;
    }

    @Test
    void repeatCloseIdempotentAndLateDocumentsOnlyRecordEvents() throws Exception {
        String b = prepareTreasured("B-CLOSE", "CD-CLOSE", "cl");

        JsonNode closed = postJson("/api/batches/" + b + "/close",
                TestFixtures.requestId("cl-do"), 200);
        assertEquals("CLOSED", closed.get("status").asText());

        // 重复结案（不同 requestId 亦幂等成功）
        JsonNode again = postJson("/api/batches/" + b + "/close",
                TestFixtures.requestId("cl-do-2"), 200);
        assertEquals("CLOSED", again.get("status").asText());

        // 结案后撤批/再核销被拒绝
        postJson("/api/batches/" + b + "/withdraw",
                TestFixtures.requestId("cl-with"), 409);
        postJson("/api/batches/" + b + "/treasury",
                TestFixtures.treasury("cl-w2", "GK-NEW", "130.00"), 409);

        // 迟到单证：只记迟到事件
        JsonNode late = postJson("/api/batches/" + b + "/late-documents",
                TestFixtures.late("cl-late1", "CD-LATE1", "INV-LATE1", "结案后到达的报关单"), 200);
        assertEquals("CLOSED", late.get("batchStatus").asText());
        JsonNode late2 = postJson("/api/batches/" + b + "/late-documents",
                TestFixtures.late("cl-late2", "CD-LATE2", null, "结案后到达的发票"), 200);
        assertEquals("CD-LATE2", late2.get("declarationNo").asText());

        JsonNode events = getJson("/api/batches/" + b + "/late-documents", 200);
        assertEquals(2, events.size());
        // 迟到单证未参与关联：单证关联表无该报关单，占用登记为空
        Integer linked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_batch_document WHERE declaration_no IN ('CD-LATE1','CD-LATE2')",
                Integer.class);
        assertEquals(0, linked, "迟到单证不得建立关联");
        assertTrue(events.get(0).get("note").asText().contains("结案后到达"));
    }
}
