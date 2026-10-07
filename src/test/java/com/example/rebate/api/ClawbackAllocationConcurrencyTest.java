package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 汇总分配 H2 真实并发边界：新旧入口同流水竞争不重复到账；同流水同内容并发只入账一次；
 * 不同流水并发分配同一批订单由版本条件更新串行，不超收、失败整笔回滚且可换版本重试。
 */
class ClawbackAllocationConcurrencyTest extends BaseApiTest {

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

    private void submitAndApprove(String batchNo, String declNo, String correctionNo,
                                  long amountCents, String prefix) throws Exception {
        postJson("/api/batches/" + batchNo + "/chargebacks",
                TestFixtures.chargebackSubmit(prefix + "-s", declNo, correctionNo,
                        "并发测试", amountCents, "alice"), 200);
        postJson("/api/batches/" + batchNo + "/chargebacks/" + correctionNo + "/review",
                TestFixtures.chargebackReview(prefix + "-r", 1, "APPROVE", "bob"), 200);
    }

    private long collectedOf(String batchNo, String correctionNo) throws Exception {
        return getJson("/api/batches/" + batchNo + "/chargebacks/" + correctionNo, 200)
                .get("clawback").get("collectedCents").asLong();
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void concurrentAggregateAndSingleReceiptSameSerialCreditOnce() throws Exception {
        prepareClosedTwo("B-AC1", "CD-AC1A", "CD-AC1B", "ac1");
        submitAndApprove("B-AC1", "CD-AC1A", "COR-A", 10000L, "ac1-a");

        String aggregateBody = TestFixtures.batchReceipt("ac1-agg", "BS-AC1", 6000L,
                "2026-03-05T10:00:00Z", "[" + TestFixtures.allocation("COR-A", 6000L, 1) + "]");
        String singleBody = TestFixtures.receipt("ac1-single", 1, "BS-AC1", 6000L,
                "2026-03-05T10:00:00Z");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-AC1/clawback-receipts", aggregateBody);
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-AC1/chargebacks/COR-A/receipts", singleBody);
            }));
            start.countDown();

            int ok = 0;
            int conflict = 0;
            for (Future<MvcResult> f : futures) {
                int status = f.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    conflict++;
                } else {
                    throw new AssertionError("未预期状态码 " + status);
                }
            }
            assertEquals(1, ok, "同一真实流水只能有一种入口成功");
            assertEquals(1, conflict, "另一入口必须 409");

            assertEquals(6000L, collectedOf("B-AC1", "COR-A"),
                    "新旧入口竞争不得重复到账");
            assertEquals(1, countRows("t_bank_serial_registry"), "同一流水只登记一次");
            assertEquals(1, countRows("t_clawback_receipt") + countRows("t_clawback_batch_receipt"),
                    "单笔回执与母凭证合计只能落一条");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIdenticalAggregatesApplyOnce() throws Exception {
        prepareClosedTwo("B-AC2", "CD-AC2A", "CD-AC2B", "ac2");
        submitAndApprove("B-AC2", "CD-AC2A", "COR-A", 5000L, "ac2-a");
        submitAndApprove("B-AC2", "CD-AC2B", "COR-B", 3000L, "ac2-b");

        String allocations = "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                + TestFixtures.allocation("COR-B", 2000L, 1) + "]";
        String body1 = TestFixtures.batchReceipt("ac2-1", "BS-AC2", 5000L,
                "2026-03-05T10:00:00Z", allocations);
        String body2 = TestFixtures.batchReceipt("ac2-2", "BS-AC2", 5000L,
                "2026-03-05T10:00:00Z", allocations);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-AC2/clawback-receipts", body1);
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-AC2/clawback-receipts", body2);
            }));
            start.countDown();

            long firstId = -1L;
            for (Future<MvcResult> f : futures) {
                MvcResult result = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, result.getResponse().getStatus(),
                        "同流水同内容并发：一笔入账、另一笔自然去重重放");
                long id = objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("id").asLong();
                if (firstId < 0) {
                    firstId = id;
                } else {
                    assertEquals(firstId, id, "重放必须返回首份母凭证");
                }
            }

            assertEquals(3000L, collectedOf("B-AC2", "COR-A"), "并发同内容只入账一次");
            assertEquals(2000L, collectedOf("B-AC2", "COR-B"));
            assertEquals(1, countRows("t_clawback_batch_receipt"));
            assertEquals(2, countRows("t_clawback_allocation"));
            assertEquals(1, countRows("t_bank_serial_registry"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentAggregatesSameOrdersNeverOverCollectAndLoserCanRetry() throws Exception {
        prepareClosedTwo("B-AC3", "CD-AC3A", "CD-AC3B", "ac3");
        submitAndApprove("B-AC3", "CD-AC3A", "COR-A", 10000L, "ac3-a");
        submitAndApprove("B-AC3", "CD-AC3B", "COR-B", 10000L, "ac3-b");

        // 两笔不同流水的汇总分配都基于版本 1，并发只能一笔生效
        String agg1v1 = TestFixtures.batchReceipt("ac3-agg1", "BS-AC3A", 12000L,
                "2026-03-05T10:00:00Z",
                "[" + TestFixtures.allocation("COR-A", 6000L, 1) + ","
                        + TestFixtures.allocation("COR-B", 6000L, 1) + "]");
        String agg2v1 = TestFixtures.batchReceipt("ac3-agg2", "BS-AC3B", 6000L,
                "2026-03-05T10:00:00Z",
                "[" + TestFixtures.allocation("COR-A", 3000L, 1) + ","
                        + TestFixtures.allocation("COR-B", 3000L, 1) + "]");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-AC3/clawback-receipts", agg1v1);
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-AC3/clawback-receipts", agg2v1);
            }));
            start.countDown();

            MvcResult r1 = futures.get(0).get(30, TimeUnit.SECONDS);
            MvcResult r2 = futures.get(1).get(30, TimeUnit.SECONDS);
            int s1 = r1.getResponse().getStatus();
            int s2 = r2.getResponse().getStatus();
            assertTrue((s1 == 200 && s2 == 409) || (s1 == 409 && s2 == 200),
                    "同版本并发分配只能一笔生效，实际 " + s1 + "/" + s2);

            // 失败方整笔回滚：流水与请求键都未占用，换当前版本 2 同键同流水重试成功
            String loserRetry = s1 == 409
                    ? TestFixtures.batchReceipt("ac3-agg1", "BS-AC3A", 12000L,
                            "2026-03-05T10:00:00Z",
                            "[" + TestFixtures.allocation("COR-A", 6000L, 2) + ","
                                    + TestFixtures.allocation("COR-B", 6000L, 2) + "]")
                    : TestFixtures.batchReceipt("ac3-agg2", "BS-AC3B", 6000L,
                            "2026-03-05T10:00:00Z",
                            "[" + TestFixtures.allocation("COR-A", 3000L, 2) + ","
                                    + TestFixtures.allocation("COR-B", 3000L, 2) + "]");
            JsonNode retried = postJson("/api/batches/B-AC3/clawback-receipts", loserRetry, 200);
            assertEquals(2, retried.get("allocations").size());

            // 无论谁先成功，最终两单各收 9000，不超批准 10000
            assertEquals(9000L, collectedOf("B-AC3", "COR-A"));
            assertEquals(9000L, collectedOf("B-AC3", "COR-B"));
            assertEquals(2, countRows("t_clawback_batch_receipt"));
            assertEquals(4, countRows("t_clawback_allocation"));
            assertEquals(2, countRows("t_bank_serial_registry"));
            JsonNode orderA = getJson("/api/batches/B-AC3/chargebacks/COR-A", 200);
            assertEquals(3, orderA.get("clawback").get("version").asInt(),
                    "两笔成功分配各加一版");
        } finally {
            pool.shutdownNow();
        }
    }
}
