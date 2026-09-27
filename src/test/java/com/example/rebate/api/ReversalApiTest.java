package com.example.rebate.api;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;

/**
 * 冲回测试公共支撑：准备一个已退库结案（CLOSED）批次。
 */
abstract class ReversalApiTest extends BaseApiTest {

    /**
     * 录入文库、报关单/发票，完成 校验→申报→审核→退库→结案，申报日命中 V2026(13%)。
     *
     * @param batchNo 批次号
     * @param declNo 报关单号（发票号固定为 INV- 前缀）
     * @param fob FOB 价（计税依据=FOB×0.13）
     * @param fobUsd FOB 美元价（保证换汇成本落在 [5,8]）
     */
    protected void seedClosedBatch(String batchNo, String declNo, String fob, String fobUsd)
            throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("c-" + declNo, declNo, TestFixtures.P1, fob, fobUsd), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i-" + declNo, "INV-" + declNo, declNo,
                        TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("chk-" + batchNo, batchNo, "2026-02-01", declNo), 200);
        postJson("/api/batches/" + batchNo + "/declare",
                TestFixtures.declare("decl-" + batchNo, false), 200);
        postJson("/api/batches/" + batchNo + "/approve",
                TestFixtures.requestId("appr-" + batchNo), 200);
        // 退库与申报税额一致（13%），金额由测试按 frozen 推导时直接用固定一致值。
        String tax = new java.math.BigDecimal(fob)
                .multiply(new java.math.BigDecimal("0.13"))
                .setScale(4, java.math.RoundingMode.HALF_UP)
                .toPlainString();
        postJson("/api/batches/" + batchNo + "/treasury",
                TestFixtures.treasury("tr-" + batchNo, "GK-" + batchNo, tax), 200);
        postJson("/api/batches/" + batchNo + "/close",
                TestFixtures.requestId("close-" + batchNo), 200);
    }

    protected String reversals(String batchNo) {
        return "/api/batches/" + batchNo + "/reversals";
    }

    protected String review(String batchNo, String correctionNo) {
        return "/api/batches/" + batchNo + "/reversals/" + correctionNo + "/review";
    }

    protected String receipts(String batchNo, String correctionNo) {
        return "/api/batches/" + batchNo + "/reversals/" + correctionNo + "/receipts";
    }

    protected String detail(String batchNo, String correctionNo) {
        return "/api/batches/" + batchNo + "/reversals/" + correctionNo;
    }

    protected String balance(String batchNo, String declNo) {
        return "/api/batches/" + batchNo + "/reversals/balance/" + declNo;
    }
}
