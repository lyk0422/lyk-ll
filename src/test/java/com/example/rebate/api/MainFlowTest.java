package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 主流程：采集 → 关联校验（换汇成本含恰端点）→ 正式申报 → 审核 → 退库 → 结案，并复算。
 */
class MainFlowTest extends BaseApiTest {

    @Test
    void fullFlowWithExchangeCostAtBothEndpoints() throws Exception {
        seedLibrary();

        // 申报日 2026-02-01 命中 V2026，退税率 13%
        // CD-MID: 1000*13%=130，成本 130/20=6.5
        postJson("/api/collect/customs",
                TestFixtures.customs("c-mid", "CD-MID", TestFixtures.P1, "1000", "20"), 200);
        // CD-LOW: 500*13%=65，成本恰 5.0
        postJson("/api/collect/customs",
                TestFixtures.customs("c-low", "CD-LOW", TestFixtures.P1, "500", "13"), 200);
        // CD-HIGH: 800*13%=104，成本恰 8.0
        postJson("/api/collect/customs",
                TestFixtures.customs("c-high", "CD-HIGH", TestFixtures.P1, "800", "13"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i-mid", "INV-MID", "CD-MID", TestFixtures.P1, "900"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i-low", "INV-LOW", "CD-LOW", TestFixtures.P1, "450"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i-high", "INV-HIGH", "CD-HIGH", TestFixtures.P1, "720"), 200);

        JsonNode check = postJson("/api/batches/check", TestFixtures.check(
                "chk-main", "B-MAIN", "2026-02-01", "CD-MID", "CD-LOW", "CD-HIGH"), 200);
        assertEquals("CHECKED", check.get("status").asText());
        assertEquals(0, check.get("doubts").size());
        assertEquals(3, check.get("details").size());
        assertEquals(0, new java.math.BigDecimal("299.0000")
                .compareTo(new java.math.BigDecimal(check.get("totalDeclaredTax").asText())));

        JsonNode costs = check.get("details");
        for (int i = 0; i < costs.size(); i++) {
            String cost = costs.get(i).get("exchangeCost").asText();
            assertTrue(new java.math.BigDecimal(cost)
                    .compareTo(new java.math.BigDecimal("5.0")) >= 0);
            assertTrue(new java.math.BigDecimal(cost)
                    .compareTo(new java.math.BigDecimal("8.0")) <= 0);
            assertEquals("PASS", costs.get(i).get("result").asText());
            assertEquals("V2026", costs.get(i).get("versionNo").asText());
        }

        JsonNode declared = postJson("/api/batches/B-MAIN/declare",
                TestFixtures.declare("d-main", false), 200);
        assertEquals("DECLARED", declared.get("status").asText());

        JsonNode approved = postJson("/api/batches/B-MAIN/approve",
                TestFixtures.requestId("a-main"), 200);
        assertEquals("APPROVED", approved.get("status").asText());

        JsonNode writeoff = postJson("/api/batches/B-MAIN/treasury",
                TestFixtures.treasury("t-main", "GK-0001", "299.00"), 200);
        assertEquals("TREASURED", writeoff.get("batchStatus").asText());
        assertTrue(writeoff.get("consistent").asBoolean());

        JsonNode closed = postJson("/api/batches/B-MAIN/close",
                TestFixtures.requestId("cl-main"), 200);
        assertEquals("CLOSED", closed.get("status").asText());

        JsonNode recalcCheck = getJson("/api/recalc/check/B-MAIN", 200);
        assertTrue(recalcCheck.get("consistent").asBoolean());
        JsonNode recalcTreasury = getJson("/api/recalc/treasury/B-MAIN", 200);
        assertTrue(recalcTreasury.get("consistent").asBoolean());
    }
}
