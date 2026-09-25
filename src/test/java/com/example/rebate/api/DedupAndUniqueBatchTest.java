package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 单证去重（报关单号唯一、报关单号+发票号唯一）与同一报关单只允许一个有效批次。
 */
class DedupAndUniqueBatchTest extends BaseApiTest {

    @Test
    void duplicateCollectionIsIdempotentAndDoesNotOverwrite() throws Exception {
        seedLibrary();

        JsonNode first = postJson("/api/collect/customs",
                TestFixtures.customs("dup-c-1", "CD-DUP", TestFixtures.P1, "1000", "20"), 200);
        assertTrue(first.get("created").asBoolean());

        // 同 requestId 重放返回首次结果；新 requestId 相同报关单号 -> created=false
        JsonNode replay = postJson("/api/collect/customs",
                TestFixtures.customs("dup-c-1", "CD-DUP", TestFixtures.P1, "1000", "20"), 200);
        assertTrue(replay.get("created").asBoolean(), "同键重放必须返回首次结果");

        JsonNode second = postJson("/api/collect/customs",
                TestFixtures.customs("dup-c-2", "CD-DUP", TestFixtures.P1, "1000", "20"), 200);
        assertFalse(second.get("created").asBoolean(), "重复采集报关单应去重");

        // 不同 FOB 的重复采集也不得覆盖既有数据
        postJson("/api/collect/customs",
                TestFixtures.customs("dup-c-3", "CD-DUP", TestFixtures.P1, "2000", "40"), 200);

        JsonNode inv1 = postJson("/api/collect/invoices",
                TestFixtures.invoice("dup-i-1", "INV-DUP", "CD-DUP", TestFixtures.P1, "900"), 200);
        assertTrue(inv1.get("created").asBoolean());
        JsonNode inv2 = postJson("/api/collect/invoices",
                TestFixtures.invoice("dup-i-2", "INV-DUP", "CD-DUP", TestFixtures.P1, "900"), 200);
        assertFalse(inv2.get("created").asBoolean(), "报关单号+发票号重复应去重");
        // 同一发票号关联到另一报关单是允许的新键
        JsonNode otherDecl = postJson("/api/collect/customs",
                TestFixtures.customs("dup-c-4", "CD-OTHER", TestFixtures.P1, "300", "5"), 200);
        assertTrue(otherDecl.get("created").asBoolean());
    }

    @Test
    void oneDeclarationCanOnlyJoinOneActiveBatch() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("uniq-c", "CD-UNIQ", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("uniq-i", "INV-UNIQ", "CD-UNIQ", TestFixtures.P1, "900"), 200);

        JsonNode first = postJson("/api/batches/check",
                TestFixtures.check("uniq-chk-1", "B-UNIQ-1", "2026-02-01", "CD-UNIQ"), 200);
        assertEquals("CHECKED", first.get("status").asText());

        JsonNode second = postJson("/api/batches/check",
                TestFixtures.check("uniq-chk-2", "B-UNIQ-2", "2026-02-01", "CD-UNIQ"), 409);
        assertTrue(second.get("error").asText().contains("已参与有效批次"));

        // 失败批次不得落库
        assertEquals(404, getRawStatus("/api/batches/B-UNIQ-2"));
    }

    private int getRawStatus(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andReturn().getResponse().getStatus();
    }
}
