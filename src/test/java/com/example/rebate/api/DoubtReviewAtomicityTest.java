package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 疑点流程原子性：
 * 说明 -> 复核 -> 解除；未说明不得复核；疑点未解除批次不得正式申报；
 * 复核与申报在同一事务内推进：任一疑点未解除则整笔回滚（已执行的复核也不生效）。
 */
class DoubtReviewAtomicityTest extends BaseApiTest {

    /**
     * 准备一个换汇成本越界（13.0 > 8.0）的疑点批次，返回疑点 ID。
     */
    private long seedDoubtfulBatch(String batchNo, String declNo, String reqPrefix) throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs(reqPrefix + "-c", declNo, TestFixtures.P1, "1000", "10"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice(reqPrefix + "-i", "INV-" + declNo, declNo,
                        TestFixtures.P1, "900"), 200);
        JsonNode check = postJson("/api/batches/check",
                TestFixtures.check(reqPrefix + "-chk", batchNo, "2026-02-01", declNo), 200);
        assertEquals("DOUBTFUL", check.get("status").asText());
        return check.get("doubts").get(0).get("doubtId").asLong();
    }

    @Test
    void reviewBeforeExplanationRejected() throws Exception {
        long doubtId = seedDoubtfulBatch("B-DOUBT", "CD-DOUBT", "d1");
        JsonNode resp = postJson("/api/batches/B-DOUBT/doubts/" + doubtId + "/review",
                TestFixtures.review("rev-early"), 422);
        assertTrue(resp.get("error").asText().contains("尚未提交补充说明"));

        assertEquals("DOUBTFUL", getJson("/api/batches/B-DOUBT", 200).get("status").asText());
        JsonNode check = getJson("/api/batches/B-DOUBT/check", 200);
        assertEquals("OPEN", check.get("doubts").get(0).get("status").asText());
    }

    @Test
    void cannotDeclareWhileDoubtUnresolved() throws Exception {
        long doubtId = seedDoubtfulBatch("B-DOUBT2", "CD-DOUBT2", "d2");

        // OPEN 疑点直接申报 -> 422，批次不得报出
        JsonNode rejected = postJson("/api/batches/B-DOUBT2/declare",
                TestFixtures.declare("decl-reject", false), 422);
        assertTrue(rejected.get("error").asText().contains("未解除疑点"));
        assertEquals("DOUBTFUL", getJson("/api/batches/B-DOUBT2", 200).get("status").asText());

        // 仅提交说明、申报时不复核 -> 仍 422
        postJson("/api/batches/B-DOUBT2/doubts/" + doubtId + "/explain",
                TestFixtures.explain("exp-1", "换汇成本偏高因旺季运费，附情况说明"), 200);
        JsonNode stillRejected = postJson("/api/batches/B-DOUBT2/declare",
                TestFixtures.declare("decl-still", false), 422);
        assertTrue(stillRejected.get("error").asText().contains("未解除疑点"));
        JsonNode check = getJson("/api/batches/B-DOUBT2/check", 200);
        assertEquals("EXPLAINED", check.get("doubts").get(0).get("status").asText());
        assertEquals("DOUBTFUL", getJson("/api/batches/B-DOUBT2", 200).get("status").asText());
    }

    @Test
    void explainThenReviewAndDeclareAtomicallySucceeds() throws Exception {
        long doubtId = seedDoubtfulBatch("B-DOUBT3", "CD-DOUBT3", "d3");

        postJson("/api/batches/B-DOUBT3/doubts/" + doubtId + "/explain",
                TestFixtures.explain("exp-ok", "已补充合同与运费凭证"), 200);

        // 复核与正式申报在同一请求/事务内推进：EXPLAINED -> RESOLVED 后批次立即冻结
        JsonNode declared = postJson("/api/batches/B-DOUBT3/declare",
                TestFixtures.declare("decl-ok", true), 200);
        assertEquals("DECLARED", declared.get("status").asText());
        JsonNode check = getJson("/api/batches/B-DOUBT3/check", 200);
        assertEquals("RESOLVED", check.get("doubts").get(0).get("status").asText());
    }

    @Test
    void declareRollsBackReviewsWhenAnyDoubtStillOpen() throws Exception {
        // 一个批次含两票越界疑点
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("d4-c1", "CD-A", TestFixtures.P1, "1000", "10"), 200);
        postJson("/api/collect/customs",
                TestFixtures.customs("d4-c2", "CD-B", TestFixtures.P1, "1000", "10"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("d4-i1", "INV-A", "CD-A", TestFixtures.P1, "900"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("d4-i2", "INV-B", "CD-B", TestFixtures.P1, "900"), 200);
        JsonNode check = postJson("/api/batches/check",
                TestFixtures.check("d4-chk", "B-DOUBT4", "2026-02-01", "CD-A", "CD-B"), 200);
        assertEquals(2, check.get("doubts").size());
        long doubtA = check.get("doubts").get(0).get("doubtId").asLong();
        long doubtB = check.get("doubts").get(1).get("doubtId").asLong();

        // 只说明第一票
        postJson("/api/batches/B-DOUBT4/doubts/" + doubtA + "/explain",
                TestFixtures.explain("exp-a", "第一票已补凭证"), 200);

        // 同事务复核+申报：第二票仍 OPEN，整笔回滚
        JsonNode rejected = postJson("/api/batches/B-DOUBT4/declare",
                TestFixtures.declare("decl-mixed", true), 422);
        assertTrue(rejected.get("error").asText().contains("未解除疑点"));

        JsonNode after = getJson("/api/batches/B-DOUBT4/check", 200);
        long id0 = after.get("doubts").get(0).get("doubtId").asLong();
        String statusA = id0 == doubtA
                ? after.get("doubts").get(0).get("status").asText()
                : after.get("doubts").get(1).get("status").asText();
        // 第一票的复核必须随事务回滚，仍停留在 EXPLAINED，不得疑点未解除而批次已报出
        assertEquals("EXPLAINED", statusA, "同事务复核须随申报失败一并回滚");
        assertEquals("DOUBTFUL", getJson("/api/batches/B-DOUBT4", 200).get("status").asText());
    }
}
