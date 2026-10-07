package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 汇总到账分配主流程与失败分支：一笔真实流水（母凭证）分给同一结案批次多张已批准冲回单；
 * 汇总差额、跨批、待复核/已驳回、任一超额全笔拒绝；自然去重重放与互斥；版本与幂等边界。
 */
class ClawbackAllocationFlowTest extends BaseApiTest {

    /** 两张报关单（各 1000×13%=130.00 → 各冻结 13000 分）的结案批次，退库 260.00。 */
    private void prepareClosedTwo(String batchNo, String d1, String d2, String prefix)
            throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs(prefix + "-c1", d1, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/customs",
                TestFixtures.customs(prefix + "-c2", d2, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice(prefix + "-i1", "INV-" + d1, d1, TestFixtures.P1, "900"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice(prefix + "-i2", "INV-" + d2, d2, TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check(prefix + "-chk", batchNo, "2026-02-01", d1, d2), 200);
        postJson("/api/batches/" + batchNo + "/declare",
                TestFixtures.declare(prefix + "-decl", false), 200);
        postJson("/api/batches/" + batchNo + "/approve",
                TestFixtures.requestId(prefix + "-appr"), 200);
        postJson("/api/batches/" + batchNo + "/treasury",
                TestFixtures.treasury(prefix + "-w", "GK-" + prefix, "260.00"), 200);
        postJson("/api/batches/" + batchNo + "/close",
                TestFixtures.requestId(prefix + "-close"), 200);
    }

    /** 单报关单结案批次（跨批场景用）。 */
    private void prepareClosedOne(String batchNo, String declNo, String prefix) throws Exception {
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
        postJson("/api/batches/" + batchNo + "/close",
                TestFixtures.requestId(prefix + "-close"), 200);
    }

    /** 提交并批准一张冲回单，产生追缴明细（版本 1）。 */
    private void submitAndApprove(String batchNo, String declNo, String correctionNo,
                                  long amountCents, String prefix) throws Exception {
        postJson("/api/batches/" + batchNo + "/chargebacks",
                TestFixtures.chargebackSubmit(prefix + "-s", declNo, correctionNo,
                        "汇总分配测试", amountCents, "alice"), 200);
        postJson("/api/batches/" + batchNo + "/chargebacks/" + correctionNo + "/review",
                TestFixtures.chargebackReview(prefix + "-r", 1, "APPROVE", "bob"), 200);
    }

    private static JsonNode lineOf(JsonNode view, String correctionNo) {
        for (JsonNode line : view.get("allocations")) {
            if (correctionNo.equals(line.get("correctionNo").asText())) {
                return line;
            }
        }
        throw new AssertionError("分配行不存在: " + correctionNo);
    }

    private long collectedOf(String batchNo, String correctionNo) throws Exception {
        return getJson("/api/batches/" + batchNo + "/chargebacks/" + correctionNo, 200)
                .get("clawback").get("collectedCents").asLong();
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void allocateFiveThousandAcrossTwoOrders() throws Exception {
        prepareClosedTwo("B-AG1", "CD-AG1A", "CD-AG1B", "ag1");
        submitAndApprove("B-AG1", "CD-AG1A", "COR-A", 5000L, "ag1-a");
        submitAndApprove("B-AG1", "CD-AG1B", "COR-B", 3000L, "ag1-b");

        // 5000 分分给两单：COR-A 3000、COR-B 2000
        String allocations = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 2000L, 1) + "]";
        JsonNode view = postJson("/api/batches/B-AG1/clawback-receipts",
                TestFixtures.batchReceipt("ag1-br1", "BS-AG1", 5000L,
                        "2026-03-05T10:00:00Z", allocations), 200);

        assertEquals("B-AG1", view.get("batchNo").asText());
        assertEquals("BS-AG1", view.get("bankSerial").asText());
        assertEquals(5000L, view.get("totalCents").asLong());
        assertEquals("2026-03-05T10:00:00Z", view.get("receivedAt").asText());
        assertEquals(1, view.get("version").asInt(), "母凭证版本从 1 开始");
        assertEquals(2, view.get("allocations").size());

        JsonNode la = lineOf(view, "COR-A");
        assertEquals(3000L, la.get("amountCents").asLong());
        assertEquals(3000L, la.get("collectedCents").asLong());
        assertEquals(2000L, la.get("remainingCents").asLong());
        assertEquals("COLLECTING", la.get("detailStatus").asText());
        assertEquals(2, la.get("detailVersion").asInt(), "成功分配后追缴明细版本加 1");
        assertTrue(la.get("clawbackNo").asText().startsWith("CLW-"));
        assertNotNull(la.get("lineId"));

        JsonNode lb = lineOf(view, "COR-B");
        assertEquals(2000L, lb.get("amountCents").asLong());
        assertEquals(2000L, lb.get("collectedCents").asLong());
        assertEquals(1000L, lb.get("remainingCents").asLong());
        assertEquals("COLLECTING", lb.get("detailStatus").asText());
        assertEquals(2, lb.get("detailVersion").asInt());

        // 母凭证查询与入账响应一致
        JsonNode queried = getJson("/api/batches/B-AG1/clawback-receipts/BS-AG1", 200);
        assertEquals(view.get("id").asLong(), queried.get("id").asLong());
        assertEquals(5000L, queried.get("totalCents").asLong());
        assertEquals(2, queried.get("allocations").size());

        // 子单余额勾稽：已收=分配行合计；旧单笔回执为零（未拆伪银行流水）
        JsonNode orderA = getJson("/api/batches/B-AG1/chargebacks/COR-A", 200);
        assertEquals(3000L, orderA.get("clawback").get("collectedCents").asLong());
        assertEquals(0, orderA.get("clawback").get("receipts").size(),
                "汇总分配不得产生伪银行流水回执");
        assertEquals(2, orderA.get("clawback").get("version").asInt());
        assertEquals(2000L, collectedOf("B-AG1", "COR-B"));

        assertEquals(0, countRows("t_clawback_receipt"), "分配行不冒充银行流水");
        assertEquals(1, countRows("t_clawback_batch_receipt"), "母凭证只记一次");
        assertEquals(2, countRows("t_clawback_allocation"));
        assertEquals(1, countRows("t_bank_serial_registry"));
        Long allocSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM t_clawback_allocation", Long.class);
        assertEquals(5000L, allocSum, "分配行合计恒等于母凭证总额");
        String source = jdbc.queryForObject(
                "SELECT source FROM t_bank_serial_registry WHERE serial = 'BS-AG1'", String.class);
        assertEquals("AGGREGATE", source);

        // 原退库批次始终结案，结案总额未被改动
        JsonNode batch = getJson("/api/batches/B-AG1", 200);
        assertEquals("CLOSED", batch.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("260.0000")
                .compareTo(batch.get("totalDeclaredTax").decimalValue()));
    }

    @Test
    void exactFullCollectionCompletesBothOrders() throws Exception {
        prepareClosedTwo("B-AG2", "CD-AG2A", "CD-AG2B", "ag2");
        submitAndApprove("B-AG2", "CD-AG2A", "COR-A", 3000L, "ag2-a");
        submitAndApprove("B-AG2", "CD-AG2B", "COR-B", 2000L, "ag2-b");

        String allocations = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 2000L, 1) + "]";
        JsonNode view = postJson("/api/batches/B-AG2/clawback-receipts",
                TestFixtures.batchReceipt("ag2-br", "BS-AG2", 5000L,
                        "2026-03-05T10:00:00Z", allocations), 200);
        assertEquals("COMPLETED", lineOf(view, "COR-A").get("detailStatus").asText());
        assertEquals("COMPLETED", lineOf(view, "COR-B").get("detailStatus").asText());

        JsonNode orderA = getJson("/api/batches/B-AG2/chargebacks/COR-A", 200);
        assertEquals("COMPLETED", orderA.get("clawback").get("status").asText());
        assertEquals(0L, orderA.get("clawback").get("remainingCents").asLong());
        assertNotNull(orderA.get("clawback").get("completedAt"));

        // 已收齐的单再分配：未收回额为 0，任何正数分配都超额 → 422
        String more = "[" + TestFixtures.allocation("COR-A", 1L, 2) + "]";
        postJson("/api/batches/B-AG2/clawback-receipts",
                TestFixtures.batchReceipt("ag2-br2", "BS-AG2X", 1L,
                        "2026-03-06T10:00:00Z", more), 422);

        JsonNode batch = getJson("/api/batches/B-AG2", 200);
        assertEquals("CLOSED", batch.get("status").asText(), "原退库批次始终结案");
    }

    @Test
    void sumMismatchRejectedAtomicallyAndKeyReleased() throws Exception {
        prepareClosedTwo("B-AG3", "CD-AG3A", "CD-AG3B", "ag3");
        submitAndApprove("B-AG3", "CD-AG3A", "COR-A", 5000L, "ag3-a");
        submitAndApprove("B-AG3", "CD-AG3B", "COR-B", 3000L, "ag3-b");

        // 汇总差额：分配合计 4000 ≠ 总额 5000 → 全笔拒绝
        String mismatched = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 1000L, 1) + "]";
        postJson("/api/batches/B-AG3/clawback-receipts",
                TestFixtures.batchReceipt("ag3-br", "BS-AG3", 5000L,
                        "2026-03-05T10:00:00Z", mismatched), 422);

        assertEquals(0, countRows("t_clawback_batch_receipt"));
        assertEquals(0, countRows("t_clawback_allocation"));
        assertEquals(0, countRows("t_bank_serial_registry"), "失败不得占用流水");
        assertEquals(0L, collectedOf("B-AG3", "COR-A"));
        assertEquals(0L, collectedOf("B-AG3", "COR-B"));

        // 失败不占键：同 requestId 修正合计后成功
        String fixed = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 1000L, 1) + "]";
        JsonNode ok = postJson("/api/batches/B-AG3/clawback-receipts",
                TestFixtures.batchReceipt("ag3-br", "BS-AG3", 4000L,
                        "2026-03-05T10:00:00Z", fixed), 200);
        assertEquals(4000L, ok.get("totalCents").asLong());
        assertEquals(3000L, collectedOf("B-AG3", "COR-A"));
        assertEquals(1000L, collectedOf("B-AG3", "COR-B"));
    }

    @Test
    void crossBatchAndNonApprovedOrdersRejected() throws Exception {
        prepareClosedOne("B-AG4", "CD-AG4", "ag4");
        prepareClosedOne("B-AG4X", "CD-AG4X", "ag4x");
        submitAndApprove("B-AG4", "CD-AG4", "COR-A", 5000L, "ag4-a");
        submitAndApprove("B-AG4X", "CD-AG4X", "COR-X", 5000L, "ag4x-x");
        // 待复核单与已驳回单
        postJson("/api/batches/B-AG4/chargebacks",
                TestFixtures.chargebackSubmit("ag4-sp", "CD-AG4", "COR-P", "待复核", 1000L,
                        "alice"), 200);
        postJson("/api/batches/B-AG4/chargebacks",
                TestFixtures.chargebackSubmit("ag4-sr", "CD-AG4", "COR-R", "将驳回", 1000L,
                        "alice"), 200);
        postJson("/api/batches/B-AG4/chargebacks/COR-R/review",
                TestFixtures.chargebackReview("ag4-rr", 1, "REJECT", "bob"), 200);

        // 跨批：COR-X 属于另一结案批次 → 拒绝
        String cross = "[" + TestFixtures.allocation("COR-X", 1000L, 1) + "]";
        postJson("/api/batches/B-AG4/clawback-receipts",
                TestFixtures.batchReceipt("ag4-cross", "BS-AG4X", 1000L,
                        "2026-03-05T10:00:00Z", cross), 404);

        // 待复核单 → 422
        String pending = "[" + TestFixtures.allocation("COR-P", 1000L, 1) + "]";
        postJson("/api/batches/B-AG4/clawback-receipts",
                TestFixtures.batchReceipt("ag4-pend", "BS-AG4P", 1000L,
                        "2026-03-05T10:00:00Z", pending), 422);

        // 已驳回单 → 422
        String rejected = "[" + TestFixtures.allocation("COR-R", 1000L, 1) + "]";
        postJson("/api/batches/B-AG4/clawback-receipts",
                TestFixtures.batchReceipt("ag4-rej", "BS-AG4R", 1000L,
                        "2026-03-05T10:00:00Z", rejected), 422);

        assertEquals(0, countRows("t_clawback_batch_receipt"));
        assertEquals(0, countRows("t_clawback_allocation"));
        assertEquals(0, countRows("t_bank_serial_registry"));
        assertEquals(0L, collectedOf("B-AG4", "COR-A"));
        assertEquals(0L, collectedOf("B-AG4X", "COR-X"));
    }

    @Test
    void overAllocationRejectedAtomically() throws Exception {
        prepareClosedTwo("B-AG5", "CD-AG5A", "CD-AG5B", "ag5");
        submitAndApprove("B-AG5", "CD-AG5A", "COR-A", 3000L, "ag5-a");
        submitAndApprove("B-AG5", "CD-AG5B", "COR-B", 2000L, "ag5-b");

        // 任一超额（COR-B 未收回 2000，分 2001）→ 全笔拒绝，COR-A 也不提前增加已收额
        String over = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 2001L, 1) + "]";
        postJson("/api/batches/B-AG5/clawback-receipts",
                TestFixtures.batchReceipt("ag5-br", "BS-AG5", 5001L,
                        "2026-03-05T10:00:00Z", over), 422);

        assertEquals(0L, collectedOf("B-AG5", "COR-A"), "全笔拒绝，合法行也不得提前入账");
        assertEquals(0L, collectedOf("B-AG5", "COR-B"));
        assertEquals(0, countRows("t_clawback_batch_receipt"));
        assertEquals(0, countRows("t_clawback_allocation"));
        assertEquals(0, countRows("t_bank_serial_registry"));
    }

    @Test
    void naturalDedupReplayAndContentConflict() throws Exception {
        prepareClosedTwo("B-AG6", "CD-AG6A", "CD-AG6B", "ag6");
        submitAndApprove("B-AG6", "CD-AG6A", "COR-A", 5000L, "ag6-a");
        submitAndApprove("B-AG6", "CD-AG6B", "COR-B", 3000L, "ag6-b");

        String allocations = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 2000L, 1) + "]";
        JsonNode first = postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-1", "BS-AG6", 5000L,
                        "2026-03-05T10:00:00Z", allocations), 200);

        // 同流水同内容换新 requestId → 重放首份分配，不重复到账
        JsonNode replay = postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-2", "BS-AG6", 5000L,
                        "2026-03-05T10:00:00Z", allocations), 200);
        assertEquals(first.get("id").asLong(), replay.get("id").asLong());
        assertEquals(2, replay.get("allocations").size());
        assertEquals(3000L, collectedOf("B-AG6", "COR-A"), "重放不得重复累加");
        assertEquals(2000L, collectedOf("B-AG6", "COR-B"));
        assertEquals(1, countRows("t_clawback_batch_receipt"));
        assertEquals(2, countRows("t_clawback_allocation"));

        // 新请求键已被冻结到首份结果：ag6-2 改参数 → 409
        String changed = "[" + TestFixtures.allocation("COR-A", 1000L, 2) + ","
                + TestFixtures.allocation("COR-B", 4000L, 2) + "]";
        postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-2", "BS-AG6", 5000L,
                        "2026-03-05T10:00:00Z", changed), 409);

        // 同流水改金额 → 409
        String otherAmount = "[" + TestFixtures.allocation("COR-A", 3001L, 2) + ","
                + TestFixtures.allocation("COR-B", 2000L, 2) + "]";
        postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-3", "BS-AG6", 5001L,
                        "2026-03-05T10:00:00Z", otherAmount), 409);

        // 同流水改时刻 → 409
        postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-4", "BS-AG6", 5000L,
                        "2026-03-06T10:00:00Z", allocations), 409);

        // 同流水改分配对象（两行金额互换）→ 409
        String swapped = "[" + TestFixtures.allocation("COR-A", 2000L, 2) + ","
                + TestFixtures.allocation("COR-B", 3000L, 2) + "]";
        postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-5", "BS-AG6", 5000L,
                        "2026-03-05T10:00:00Z", swapped), 409);

        // 同 requestId 同参 → requestId 层重放；同 requestId 异参 → 409
        JsonNode idemReplay = postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-1", "BS-AG6", 5000L,
                        "2026-03-05T10:00:00Z", allocations), 200);
        assertEquals(first.get("id").asLong(), idemReplay.get("id").asLong());
        postJson("/api/batches/B-AG6/clawback-receipts",
                TestFixtures.batchReceipt("ag6-1", "BS-OTHER", 5000L,
                        "2026-03-05T10:00:00Z", allocations), 409);

        assertEquals(3000L, collectedOf("B-AG6", "COR-A"));
        assertEquals(2000L, collectedOf("B-AG6", "COR-B"));
        assertEquals(1, countRows("t_clawback_batch_receipt"));
    }

    @Test
    void staleExpectedVersionRejectedAndSuccessIncrements() throws Exception {
        prepareClosedOne("B-AG7", "CD-AG7", "ag7");
        submitAndApprove("B-AG7", "CD-AG7", "COR-A", 5000L, "ag7-a");

        String first = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + "]";
        JsonNode v1 = postJson("/api/batches/B-AG7/clawback-receipts",
                TestFixtures.batchReceipt("ag7-1", "BS-AG7A", 3000L,
                        "2026-03-05T10:00:00Z", first), 200);
        assertEquals(2, lineOf(v1, "COR-A").get("detailVersion").asInt(), "成功加 1");

        // 旧版 expectedVersion=1 → 409
        String stale = "[" + TestFixtures.allocation("COR-A", 1000L, 1) + "]";
        postJson("/api/batches/B-AG7/clawback-receipts",
                TestFixtures.batchReceipt("ag7-2", "BS-AG7B", 1000L,
                        "2026-03-05T11:00:00Z", stale), 409);
        assertEquals(3000L, collectedOf("B-AG7", "COR-A"), "旧版 409 不得入账");

        // 当前版本 2 → 成功，版本到 3
        String current = "[" + TestFixtures.allocation("COR-A", 1000L, 2) + "]";
        JsonNode v2 = postJson("/api/batches/B-AG7/clawback-receipts",
                TestFixtures.batchReceipt("ag7-2", "BS-AG7B", 1000L,
                        "2026-03-05T11:00:00Z", current), 200);
        assertEquals(3, lineOf(v2, "COR-A").get("detailVersion").asInt());
        assertEquals(4000L, collectedOf("B-AG7", "COR-A"));
    }

    @Test
    void serialMutexBetweenAggregateAndSingleEntries() throws Exception {
        prepareClosedTwo("B-AG8", "CD-AG8A", "CD-AG8B", "ag8");
        submitAndApprove("B-AG8", "CD-AG8A", "COR-A", 5000L, "ag8-a");
        submitAndApprove("B-AG8", "CD-AG8B", "COR-B", 5000L, "ag8-b");

        // 汇总入口占用 BS-AG8
        String allocations = "[" + TestFixtures.allocation("COR-A", 2000L, 1) + "]";
        postJson("/api/batches/B-AG8/clawback-receipts",
                TestFixtures.batchReceipt("ag8-1", "BS-AG8", 2000L,
                        "2026-03-05T10:00:00Z", allocations), 200);

        // 旧单笔入口不得借用已分配母流水 → 409
        postJson("/api/batches/B-AG8/chargebacks/COR-B/receipts",
                TestFixtures.receipt("ag8-borrow", 1, "BS-AG8", 1000L,
                        "2026-03-05T10:00:00Z"), 409);

        // 旧入口正常使用新流水 RS-AG8 → 200
        postJson("/api/batches/B-AG8/chargebacks/COR-B/receipts",
                TestFixtures.receipt("ag8-single", 1, "RS-AG8", 1000L,
                        "2026-03-05T10:00:00Z"), 200);

        // 汇总入口不得使用旧单笔回执流水 → 409
        String borrow = "[" + TestFixtures.allocation("COR-A", 1000L, 2) + "]";
        postJson("/api/batches/B-AG8/clawback-receipts",
                TestFixtures.batchReceipt("ag8-2", "RS-AG8", 1000L,
                        "2026-03-05T10:00:00Z", borrow), 409);

        assertEquals(2000L, collectedOf("B-AG8", "COR-A"));
        assertEquals(1000L, collectedOf("B-AG8", "COR-B"));
        assertEquals(2, countRows("t_bank_serial_registry"), "两种入口各占一条流水");
    }

    @Test
    void structureValidationAndQueryBoundaries() throws Exception {
        prepareClosedOne("B-AG9", "CD-AG9", "ag9");
        prepareClosedOne("B-AG9X", "CD-AG9X", "ag9x");
        submitAndApprove("B-AG9", "CD-AG9", "COR-A", 5000L, "ag9-a");

        // 0 行 / 超 10 行 → 400
        postJson("/api/batches/B-AG9/clawback-receipts",
                TestFixtures.batchReceipt("ag9-zero", "BS-Z", 1000L,
                        "2026-03-05T10:00:00Z", "[]"), 400);
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 1; i <= 11; i++) {
            if (i > 1) {
                eleven.append(",");
            }
            eleven.append(TestFixtures.allocation("COR-" + i, 100L, 1));
        }
        eleven.append("]");
        postJson("/api/batches/B-AG9/clawback-receipts",
                TestFixtures.batchReceipt("ag9-eleven", "BS-E", 1100L,
                        "2026-03-05T10:00:00Z", eleven.toString()), 400);

        // 重复分配对象 → 422
        String dup = "[" + TestFixtures.allocation("COR-A", 1000L, 1) + ","
                + TestFixtures.allocation("COR-A", 1000L, 1) + "]";
        postJson("/api/batches/B-AG9/clawback-receipts",
                TestFixtures.batchReceipt("ag9-dup", "BS-D", 2000L,
                        "2026-03-05T10:00:00Z", dup), 422);

        // 批次不存在 → 404；未结案批次 → 422
        String one = "[" + TestFixtures.allocation("COR-A", 1000L, 1) + "]";
        postJson("/api/batches/NOPE/clawback-receipts",
                TestFixtures.batchReceipt("ag9-nb", "BS-N", 1000L,
                        "2026-03-05T10:00:00Z", one), 404);
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("ag9o-c", "CD-AG9O", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("ag9o-i", "INV-CD-AG9O", "CD-AG9O",
                        TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("ag9o-chk", "B-AG9O", "2026-02-01", "CD-AG9O"), 200);
        postJson("/api/batches/B-AG9O/clawback-receipts",
                TestFixtures.batchReceipt("ag9-open", "BS-O", 1000L,
                        "2026-03-05T10:00:00Z", one), 422);

        // 母凭证查询：未知流水 404；他批流水 404
        postJson("/api/batches/B-AG9/clawback-receipts",
                TestFixtures.batchReceipt("ag9-ok", "BS-AG9", 1000L,
                        "2026-03-05T10:00:00Z", one), 200);
        getJson("/api/batches/B-AG9/clawback-receipts/BS-UNKNOWN", 404);
        getJson("/api/batches/B-AG9X/clawback-receipts/BS-AG9", 404);
        JsonNode ok = getJson("/api/batches/B-AG9/clawback-receipts/BS-AG9", 200);
        assertEquals(1000L, ok.get("totalCents").asLong());
    }
}
