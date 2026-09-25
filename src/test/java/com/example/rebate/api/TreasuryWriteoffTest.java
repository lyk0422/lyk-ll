package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 退库核销：恰 0.5% 视为一致；超出登记差异并转人工；批次＋国库流水号唯一。
 */
class TreasuryWriteoffTest extends BaseApiTest {

    private void prepareApprovedBatch(String batchNo, String declNo, String prefix) throws Exception {
        seedLibrary();
        // 1000*13%=130，成本 130/20=6.5
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
    }

    @Test
    void diffExactlyHalfPercentIsConsistent() throws Exception {
        prepareApprovedBatch("B-T1", "CD-T1", "t1");
        // 申报税额 130；到账 129.35 -> 差 0.65，恰 0.5%
        JsonNode w = postJson("/api/batches/B-T1/treasury",
                TestFixtures.treasury("t1-w", "GK-T1", "129.35"), 200);
        assertEquals(0, new java.math.BigDecimal("0.00500000")
                .compareTo(new java.math.BigDecimal(w.get("diffRatio").asText())));
        assertTrue(w.get("consistent").asBoolean());
        assertFalse(w.get("manualFlag").asBoolean());
        assertEquals("TREASURED", w.get("batchStatus").asText());
    }

    @Test
    void diffOverHalfPercentRegistersAndGoesManual() throws Exception {
        prepareApprovedBatch("B-T2", "CD-T2", "t2");
        // 到账 129.34 -> 差 0.66，比例 0.00507692 > 0.5%
        JsonNode w = postJson("/api/batches/B-T2/treasury",
                TestFixtures.treasury("t2-w", "GK-T2", "129.34"), 200);
        assertFalse(w.get("consistent").asBoolean());
        assertTrue(w.get("manualFlag").asBoolean(), "超差须登记差异并转人工");
        assertEquals("DIFF_MANUAL", w.get("batchStatus").asText());

        // 核销差异可复算：复算值与落库值逐项一致；该核销项本身金额判定为不一致
        JsonNode recalc = getJson("/api/recalc/treasury/B-T2", 200);
        assertTrue(recalc.get("consistent").asBoolean(), "复算结果须与落库值一致");
        assertFalse(recalc.get("items").get(0).get("storedConsistent").asBoolean());
        assertEquals(0, new java.math.BigDecimal("0.6600")
                .compareTo(new java.math.BigDecimal(
                        recalc.get("items").get(0).get("storedDiffAmount").asText())));
    }

    @Test
    void batchAndSerialUniqueAndReplayReturnsFirstRecord() throws Exception {
        prepareApprovedBatch("B-T3", "CD-T3", "t3");
        JsonNode first = postJson("/api/batches/B-T3/treasury",
                TestFixtures.treasury("t3-w", "GK-T3", "130.00"), 200);
        assertTrue(first.get("consistent").asBoolean());

        // 同键重放：返回首次登记
        JsonNode replay = postJson("/api/batches/B-T3/treasury",
                TestFixtures.treasury("t3-w", "GK-T3", "130.00"), 200);
        assertEquals(first.get("id").asLong(), replay.get("id").asLong());

        // 同国库流水号不同金额（不同 requestId）仍命中唯一登记，返回首次结果，不重复落库
        JsonNode dupSerial = postJson("/api/batches/B-T3/treasury",
                TestFixtures.treasury("t3-w2", "GK-T3", "999.00"), 200);
        assertEquals(first.get("id").asLong(), dupSerial.get("id").asLong());
        assertEquals(0, new java.math.BigDecimal("130.00")
                .compareTo(new java.math.BigDecimal(dupSerial.get("receivedAmount").asText())));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_treasury_writeoff WHERE batch_id = "
                        + "(SELECT id FROM t_declaration_batch WHERE batch_no = 'B-T3')",
                Integer.class);
        assertEquals(1, rows, "批次+国库流水号唯一，不得重复登记");
    }
}
