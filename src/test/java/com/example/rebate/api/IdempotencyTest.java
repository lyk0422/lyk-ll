package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * requestId 幂等：同键同参返回首次结果（含失败首次结果）；同键异参 409 且不改变批次状态。
 */
class IdempotencyTest extends BaseApiTest {

    private void seedOne(String declNo, String prefix) throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs(prefix + "-c", declNo, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice(prefix + "-i", "INV-" + declNo, declNo,
                        TestFixtures.P1, "900"), 200);
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() throws Exception {
        seedOne("CD-IDEM", "idm");
        String body = TestFixtures.check("idem-chk", "B-IDEM", "2026-02-01", "CD-IDEM");
        JsonNode first = postJson("/api/batches/check", body, 200);
        JsonNode replay = postJson("/api/batches/check", body, 200);
        assertEquals(first.get("status").asText(), replay.get("status").asText());
        assertEquals(first.get("totalDeclaredTax").asText(), replay.get("totalDeclaredTax").asText());

        // 申报也重放：两次均 DECLARED，批次仅冻结一次
        String declareBody = TestFixtures.declare("idem-decl", false);
        postJson("/api/batches/B-IDEM/declare", declareBody, 200);
        JsonNode replayDecl = postJson("/api/batches/B-IDEM/declare", declareBody, 200);
        assertEquals("DECLARED", replayDecl.get("status").asText());
    }

    @Test
    void sameKeyDifferentParamsConflictsAndStateUnchanged() throws Exception {
        seedOne("CD-C1", "c1");
        seedOne("CD-C2", "c2");
        // 首次：批次 B-A 包含 CD-C1
        postJson("/api/batches/check",
                TestFixtures.check("shared-key", "B-A", "2026-02-01", "CD-C1"), 200);
        // 同 requestId 换成不同批次号/单证 -> 409
        JsonNode conflict = postJson("/api/batches/check",
                TestFixtures.check("shared-key", "B-B", "2026-02-01", "CD-C2"), 409);
        assertTrue(conflict.get("error").asText().contains("不同业务参数"));

        // CD-C2 未被占用：B-B 未创建，CD-C2 仍可独立建批
        JsonNode b2 = postJson("/api/batches/check",
                TestFixtures.check("other-key", "B-B", "2026-02-01", "CD-C2"), 200);
        assertEquals("CHECKED", b2.get("status").asText());
        assertEquals("CHECKED", getJson("/api/batches/B-A", 200).get("status").asText());
    }

    @Test
    void sameKeyReplaysFirstFailureToo() throws Exception {
        // 首次请求即失败（报关单不存在），同键重放得到相同 404 存档响应
        String body = TestFixtures.check("fail-key", "B-FAIL", "2026-02-01", "CD-NONE");
        JsonNode first = postJson("/api/batches/check", body, 404);
        JsonNode replay = postJson("/api/batches/check", body, 404);
        assertEquals(first.get("error").asText(), replay.get("error").asText());
        assertEquals(404, getRawStatus("/api/batches/B-FAIL"));
    }

    private int getRawStatus(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andReturn().getResponse().getStatus();
    }
}
