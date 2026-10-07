package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 汇总分配主流程：一笔真实到账（母凭证只记一次）分给同一结案批次内多张已批准冲回单；
 * 结构/业务失败整笔拒绝且不占流水与请求键；同流水同内容重放首份分配；与旧单笔入口全局互斥。
 */
class MasterAllocationFlowTest extends BaseApiTest {

    /** 1000×13%=130.00 → 冻结 13000 分；批准 COR-1 5000 分、COR-2 4000 分（追缴明细版本均 1）。 */
    private void prepareTwoApproved(String batchNo, String declNo, String prefix) throws Exception {
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
        postJson("/api/batches/" + batchNo + "/chargebacks",
                TestFixtures.chargebackSubmit(prefix + "-s1", declNo, "COR-1", "汇总一",
                        5000L, "s1"), 200);
        postJson("/api/batches/" + batchNo + "/chargebacks/COR-1/review",
                TestFixtures.chargebackReview(prefix + "-a1", 1, "APPROVE", "r1"), 200);
        postJson("/api/batches/" + batchNo + "/chargebacks",
                TestFixtures.chargebackSubmit(prefix + "-s2", declNo, "COR-2", "汇总二",
                        4000L, "s1"), 200);
        postJson("/api/batches/" + batchNo + "/chargebacks/COR-2/review",
                TestFixtures.chargebackReview(prefix + "-a2", 1, "APPROVE", "r1"), 200);
    }

    @Test
    void allocateFiveThousandAcrossTwoOrders() throws Exception {
        prepareTwoApproved("B-MA1", "CD-MA1", "ma1");

        // 5000 分分给两单 3000 和 2000 分
        JsonNode master = postJson("/api/batches/B-MA1/clawback-allocations",
                TestFixtures.masterAllocation("ma1-m", "BANK-MA1", 5000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 3000L, 1),
                        TestFixtures.allocItem("COR-2", 2000L, 1)), 200);
        assertEquals("B-MA1", master.get("batchNo").asText());
        assertEquals("BANK-MA1", master.get("masterSerial").asText());
        assertEquals(5000L, master.get("totalCents").asLong());
        assertEquals(1, master.get("version").asInt());
        assertEquals(2, master.get("allocations").size());
        JsonNode a1 = master.get("allocations").get(0);
        JsonNode a2 = master.get("allocations").get(1);
        assertTrue(a1.get("allocationNo").asText().startsWith("ALC-"));
        assertTrue(a2.get("allocationNo").asText().startsWith("ALC-"));
        assertEquals("COR-1", a1.get("correctionNo").asText());
        assertEquals(3000L, a1.get("amountCents").asLong());
        assertEquals(3000L, a1.get("collectedCents").asLong());
        assertEquals(2000L, a1.get("remainingCents").asLong());
        assertEquals("COLLECTING", a1.get("status").asText());
        assertEquals(2, a1.get("detailVersion").asInt());
        assertEquals("COR-2", a2.get("correctionNo").asText());
        assertEquals(2000L, a2.get("amountCents").asLong());
        assertEquals(2000L, a2.get("collectedCents").asLong());
        assertEquals(2000L, a2.get("remainingCents").asLong());
        assertEquals(2, a2.get("detailVersion").asInt());

        // 预期余额：COR-1 已收 3000 未收 2000；COR-2 已收 2000 未收 2000（旧查询入口仍可用）
        JsonNode cor1 = getJson("/api/batches/B-MA1/chargebacks/COR-1", 200);
        assertEquals(3000L, cor1.get("clawback").get("collectedCents").asLong());
        assertEquals(2000L, cor1.get("clawback").get("remainingCents").asLong());
        assertEquals(2, cor1.get("clawback").get("version").asInt());
        JsonNode cor2 = getJson("/api/batches/B-MA1/chargebacks/COR-2", 200);
        assertEquals(2000L, cor2.get("clawback").get("collectedCents").asLong());
        assertEquals(2000L, cor2.get("clawback").get("remainingCents").asLong());

        // 勾稽：母凭证总额 = 各分配行合计 = 子单已收增量；占用余额不受到账影响
        JsonNode view = getJson("/api/batches/B-MA1/clawback-allocations/BANK-MA1", 200);
        long allocated = 0L;
        for (JsonNode item : view.get("allocations")) {
            allocated += item.get("amountCents").asLong();
        }
        assertEquals(5000L, allocated);
        assertEquals(view.get("totalCents").asLong(), allocated);
        JsonNode balances = getJson("/api/batches/B-MA1/chargebacks", 200);
        assertEquals(9000L, balances.get(0).get("occupiedCents").asLong());
        assertEquals(4000L, balances.get(0).get("remainingCents").asLong());

        // 母凭证只记一次，分配行不冒充银行流水：无单笔回执行
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_allocation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_receipt", Integer.class));
        assertEquals("MASTER", jdbc.queryForObject(
                "SELECT kind FROM t_bank_serial WHERE serial = 'BANK-MA1'", String.class));

        // 原退库批次始终结案，结案总额未回改
        JsonNode batch = getJson("/api/batches/B-MA1", 200);
        assertEquals("CLOSED", batch.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("130.0000")
                .compareTo(batch.get("totalDeclaredTax").decimalValue()));
    }

    @Test
    void exactAllocationCompletesOrderAndKeepsOthersCollecting() throws Exception {
        prepareTwoApproved("B-MA2", "CD-MA2", "ma2");

        // COR-1 恰收齐 5000 → COMPLETED；COR-2 不在本次分配，仍在途
        JsonNode master = postJson("/api/batches/B-MA2/clawback-allocations",
                TestFixtures.masterAllocation("ma2-m", "BANK-MA2", 5000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 5000L, 1)), 200);
        JsonNode a1 = master.get("allocations").get(0);
        assertEquals("COMPLETED", a1.get("status").asText());
        assertEquals(0L, a1.get("remainingCents").asLong());
        assertEquals(2, a1.get("detailVersion").asInt());

        JsonNode cor1 = getJson("/api/batches/B-MA2/chargebacks/COR-1", 200);
        assertEquals("COMPLETED", cor1.get("clawback").get("status").asText());
        assertNotNull(cor1.get("clawback").get("completedAt"));
        JsonNode cor2 = getJson("/api/batches/B-MA2/chargebacks/COR-2", 200);
        assertEquals("COLLECTING", cor2.get("clawback").get("status").asText());
        assertEquals(0L, cor2.get("clawback").get("collectedCents").asLong());

        // 完成后旧单笔入口只读；旧版本 409
        postJson("/api/batches/B-MA2/chargebacks/COR-1/receipts",
                TestFixtures.receipt("ma2-r1", 2, "RCV-MA2", 1L, "2026-03-06T10:00:00Z"), 422);
        postJson("/api/batches/B-MA2/clawback-allocations",
                TestFixtures.masterAllocation("ma2-m2", "BANK-MA2B", 1000L,
                        "2026-03-06T10:00:00Z",
                        TestFixtures.allocItem("COR-2", 1000L, 9)), 409);
    }

    @Test
    void allocationFailuresRejectWholeAndKeepNoState() throws Exception {
        prepareTwoApproved("B-MA3", "CD-MA3", "ma3");
        // 另一结案批次及其已批准单，用于跨批校验
        prepareTwoApproved("B-MA3B", "CD-MA3B", "ma3b");
        // 待复核与已驳回单
        postJson("/api/batches/B-MA3/chargebacks",
                TestFixtures.chargebackSubmit("ma3-sp", "CD-MA3", "COR-P", "待复核",
                        1000L, "s1"), 200);
        postJson("/api/batches/B-MA3/chargebacks",
                TestFixtures.chargebackSubmit("ma3-sr", "CD-MA3", "COR-R", "将驳回",
                        1000L, "s1"), 200);
        postJson("/api/batches/B-MA3/chargebacks/COR-R/review",
                TestFixtures.chargebackReview("ma3-rj", 1, "REJECT", "r1"), 200);

        // 合计不等于总额
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f1", "BANK-F1", 5000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 3000L, 1),
                        TestFixtures.allocItem("COR-2", 1000L, 1)), 422);
        // 分配对象重复
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f2", "BANK-F2", 4000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 2000L, 1),
                        TestFixtures.allocItem("COR-1", 2000L, 1)), 422);
        // 跨批冲回单
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f3", "BANK-F3", 1000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-X", 1000L, 1)), 404);
        // 待复核单
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f4", "BANK-F4", 1000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-P", 1000L, 1)), 422);
        // 已驳回单
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f5", "BANK-F5", 1000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-R", 1000L, 1)), 422);
        // 任一单超额（COR-2 批准 4000，未收 4000）
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f6", "BANK-F6", 5001L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-2", 4001L, 1),
                        TestFixtures.allocItem("COR-1", 1000L, 1)), 422);
        // 旧版本
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f7", "BANK-F7", 1000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 1000L, 2)), 409);
        // 超过 10 张 / 空分配 / 非正金额：Bean 校验 400
        String[] eleven = new String[11];
        java.util.Arrays.fill(eleven, TestFixtures.allocItem("COR-1", 100L, 1));
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f8", "BANK-F8", 1100L,
                        "2026-03-05T10:00:00Z", eleven), 400);
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f9", "BANK-F9", 0L,
                        "2026-03-05T10:00:00Z"), 400);
        postJson("/api/batches/B-MA3/clawback-allocations",
                TestFixtures.masterAllocation("ma3-f10", "BANK-F10", 100L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 0L, 1)), 400);

        // 全部失败整笔回滚：无母凭证、无分配行、不占真实流水，子单余额不变
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_allocation", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_bank_serial", Integer.class));
        JsonNode cor1 = getJson("/api/batches/B-MA3/chargebacks/COR-1", 200);
        assertEquals(0L, cor1.get("clawback").get("collectedCents").asLong());
        assertEquals(1, cor1.get("clawback").get("version").asInt());
    }

    @Test
    void sameSerialSameContentReplaysAndFreezesNewRequestKey() throws Exception {
        prepareTwoApproved("B-MA4", "CD-MA4", "ma4");

        JsonNode first = postJson("/api/batches/B-MA4/clawback-allocations",
                TestFixtures.masterAllocation("ma4-m1", "BANK-MA4", 5000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 3000L, 1),
                        TestFixtures.allocItem("COR-2", 2000L, 1)), 200);

        // 同流水同内容换新 requestId 重放：返回首份分配（冻结快照），不重复到账
        JsonNode replay = postJson("/api/batches/B-MA4/clawback-allocations",
                TestFixtures.masterAllocation("ma4-m2", "BANK-MA4", 5000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 3000L, 1),
                        TestFixtures.allocItem("COR-2", 2000L, 1)), 200);
        assertEquals(first.get("masterId").asLong(), replay.get("masterId").asLong());
        assertEquals(first.get("allocations").get(0).get("allocationNo").asText(),
                replay.get("allocations").get(0).get("allocationNo").asText());
        JsonNode cor1 = getJson("/api/batches/B-MA4/chargebacks/COR-1", 200);
        assertEquals(3000L, cor1.get("clawback").get("collectedCents").asLong(),
                "重放不得重复累加已收额");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));

        // 新请求键已被冻结：同键异参 409
        postJson("/api/batches/B-MA4/clawback-allocations",
                TestFixtures.masterAllocation("ma4-m2", "BANK-OTHER", 1000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 1000L, 2)), 409);

        // 同流水改金额 / 改时刻 / 改分配对象均 409
        postJson("/api/batches/B-MA4/clawback-allocations",
                TestFixtures.masterAllocation("ma4-m3", "BANK-MA4", 5001L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 3001L, 2),
                        TestFixtures.allocItem("COR-2", 2000L, 2)), 409);
        postJson("/api/batches/B-MA4/clawback-allocations",
                TestFixtures.masterAllocation("ma4-m4", "BANK-MA4", 5000L,
                        "2026-03-05T11:00:00Z",
                        TestFixtures.allocItem("COR-1", 3000L, 2),
                        TestFixtures.allocItem("COR-2", 2000L, 2)), 409);
        postJson("/api/batches/B-MA4/clawback-allocations",
                TestFixtures.masterAllocation("ma4-m5", "BANK-MA4", 5000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 5000L, 2)), 409);

        // 失败重放请求不占键、不产生新母凭证
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));
    }

    @Test
    void serialMutualExclusionBetweenMasterAndSingleEntry() throws Exception {
        prepareTwoApproved("B-MA5", "CD-MA5", "ma5");

        // 旧单笔入口先占流水 RCV-MA5
        postJson("/api/batches/B-MA5/chargebacks/COR-1/receipts",
                TestFixtures.receipt("ma5-r1", 1, "RCV-MA5", 1000L, "2026-03-01T10:00:00Z"),
                200);
        // 汇总入口不得借用单笔流水
        postJson("/api/batches/B-MA5/clawback-allocations",
                TestFixtures.masterAllocation("ma5-m1", "RCV-MA5", 1000L,
                        "2026-03-01T10:00:00Z",
                        TestFixtures.allocItem("COR-2", 1000L, 1)), 409);

        // 汇总入口先占母流水 BANK-MA5
        postJson("/api/batches/B-MA5/clawback-allocations",
                TestFixtures.masterAllocation("ma5-m2", "BANK-MA5", 2000L,
                        "2026-03-02T10:00:00Z",
                        TestFixtures.allocItem("COR-2", 2000L, 1)), 200);
        // 旧单笔入口不得借用已分配母流水
        postJson("/api/batches/B-MA5/chargebacks/COR-1/receipts",
                TestFixtures.receipt("ma5-r2", 2, "BANK-MA5", 500L, "2026-03-02T10:00:00Z"),
                409);

        assertEquals("SINGLE", jdbc.queryForObject(
                "SELECT kind FROM t_bank_serial WHERE serial = 'RCV-MA5'", String.class));
        assertEquals("MASTER", jdbc.queryForObject(
                "SELECT kind FROM t_bank_serial WHERE serial = 'BANK-MA5'", String.class));
        // 各入口只生效一次：COR-1 已收 1000（单笔），COR-2 已收 2000（汇总）
        JsonNode cor1 = getJson("/api/batches/B-MA5/chargebacks/COR-1", 200);
        assertEquals(1000L, cor1.get("clawback").get("collectedCents").asLong());
        JsonNode cor2 = getJson("/api/batches/B-MA5/chargebacks/COR-2", 200);
        assertEquals(2000L, cor2.get("clawback").get("collectedCents").asLong());
    }

    @Test
    void requestIdIdempotencyRules() throws Exception {
        prepareTwoApproved("B-MA6", "CD-MA6", "ma6");

        JsonNode first = postJson("/api/batches/B-MA6/clawback-allocations",
                TestFixtures.masterAllocation("ma6-k1", "BANK-MA6", 2000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 2000L, 1)), 200);
        // 同键同参重放首次结果
        JsonNode replay = postJson("/api/batches/B-MA6/clawback-allocations",
                TestFixtures.masterAllocation("ma6-k1", "BANK-MA6", 2000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 2000L, 1)), 200);
        assertEquals(first.get("masterId").asLong(), replay.get("masterId").asLong());
        // 同键异参 409
        postJson("/api/batches/B-MA6/clawback-allocations",
                TestFixtures.masterAllocation("ma6-k1", "BANK-MA6", 3000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-1", 3000L, 1)), 409);
        // 失败不占键：先 422，再同键换合法参数成功
        postJson("/api/batches/B-MA6/clawback-allocations",
                TestFixtures.masterAllocation("ma6-k2", "BANK-MA6B", 9999L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-2", 1000L, 1)), 422);
        JsonNode retried = postJson("/api/batches/B-MA6/clawback-allocations",
                TestFixtures.masterAllocation("ma6-k2", "BANK-MA6B", 1000L,
                        "2026-03-05T10:00:00Z",
                        TestFixtures.allocItem("COR-2", 1000L, 1)), 200);
        assertNotNull(retried.get("masterId"));

        JsonNode cor1 = getJson("/api/batches/B-MA6/chargebacks/COR-1", 200);
        assertEquals(2000L, cor1.get("clawback").get("collectedCents").asLong(),
                "同键重放不得重复到账");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));
    }
}
