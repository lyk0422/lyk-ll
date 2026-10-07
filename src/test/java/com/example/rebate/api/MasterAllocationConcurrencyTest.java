package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * 汇总分配 H2 真实并发边界：新旧入口同一真实流水竞争只生效一次；并发汇总分配同一
 * 追缴明细不得重复到账或超过批准金额；同流水同内容并发自然去重（只记一份母凭证）。
 */
class MasterAllocationConcurrencyTest extends BaseApiTest {

    private void prepareClosed(String batchNo, String declNo, String prefix) throws Exception {
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

    private void approve(String batchNo, String declNo, String correctionNo, long amountCents,
                         String prefix) throws Exception {
        postJson("/api/batches/" + batchNo + "/chargebacks",
                TestFixtures.chargebackSubmit(prefix + "-s", declNo, correctionNo, "并发",
                        amountCents, "s1"), 200);
        postJson("/api/batches/" + batchNo + "/chargebacks/" + correctionNo + "/review",
                TestFixtures.chargebackReview(prefix + "-a", 1, "APPROVE", "r1"), 200);
    }

    @Test
    void concurrentMasterAndSingleOnSameSerialOnlyOneWins() throws Exception {
        prepareClosed("B-MC1", "CD-MC1", "mc1");
        approve("B-MC1", "CD-MC1", "COR-1", 10000L, "mc1-s");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> master = pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-MC1/clawback-allocations",
                        TestFixtures.masterAllocation("mc1-m", "BANK-MC1", 5000L,
                                "2026-03-05T10:00:00Z",
                                TestFixtures.allocItem("COR-1", 5000L, 1)));
            });
            Future<MvcResult> single = pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-MC1/chargebacks/COR-1/receipts",
                        TestFixtures.receipt("mc1-r", 1, "BANK-MC1", 5000L,
                                "2026-03-05T10:00:00Z"));
            });
            start.countDown();

            int masterStatus = master.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int singleStatus = single.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            long ok = List.of(masterStatus, singleStatus).stream().filter(s -> s == 200).count();
            long rejected = List.of(masterStatus, singleStatus).stream()
                    .filter(s -> s == 409).count();
            assertEquals(1L, ok, "同一真实流水只能有一个入口成功");
            assertEquals(1L, rejected, "另一入口必须 409 拒绝");

            // 不得重复到账：只生效一笔 5000，版本只加 1
            JsonNode order = getJson("/api/batches/B-MC1/chargebacks/COR-1", 200);
            assertEquals(5000L, order.get("clawback").get("collectedCents").asLong());
            assertEquals(2, order.get("clawback").get("version").asInt());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_bank_serial WHERE serial = 'BANK-MC1'",
                    Integer.class));
            int receipts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_receipt", Integer.class);
            int masters = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class);
            assertEquals(1, receipts + masters, "同一流水只能留下一种凭证");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentMastersOnSameDetailDoNotOverCollect() throws Exception {
        prepareClosed("B-MC2", "CD-MC2", "mc2");
        approve("B-MC2", "CD-MC2", "COR-1", 10000L, "mc2-s");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-MC2/clawback-allocations",
                        TestFixtures.masterAllocation("mc2-a", "BANK-MC2A", 6000L,
                                "2026-03-05T10:00:00Z",
                                TestFixtures.allocItem("COR-1", 6000L, 1)));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-MC2/clawback-allocations",
                        TestFixtures.masterAllocation("mc2-b", "BANK-MC2B", 6000L,
                                "2026-03-05T10:00:00Z",
                                TestFixtures.allocItem("COR-1", 6000L, 1)));
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
            assertEquals(1, ok, "同版本并发汇总分配只能一笔生效");
            assertEquals(1, conflict, "落后一笔必须版本冲突 409 并整笔回滚");

            // 不超过批准金额：只到账一笔 6000，失败方不留母凭证与分配行
            JsonNode order = getJson("/api/batches/B-MC2/chargebacks/COR-1", 200);
            assertEquals(6000L, order.get("clawback").get("collectedCents").asLong());
            assertEquals(2, order.get("clawback").get("version").asInt());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_allocation", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_bank_serial", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameSerialSameContentNaturallyDeduplicates() throws Exception {
        prepareClosed("B-MC3", "CD-MC3", "mc3");
        approve("B-MC3", "CD-MC3", "COR-1", 10000L, "mc3-s");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-MC3/clawback-allocations",
                        TestFixtures.masterAllocation("mc3-a", "BANK-MC3", 3000L,
                                "2026-03-05T10:00:00Z",
                                TestFixtures.allocItem("COR-1", 3000L, 1)));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-MC3/clawback-allocations",
                        TestFixtures.masterAllocation("mc3-b", "BANK-MC3", 3000L,
                                "2026-03-05T10:00:00Z",
                                TestFixtures.allocItem("COR-1", 3000L, 1)));
            }));
            start.countDown();

            long masterId = -1L;
            for (Future<MvcResult> f : futures) {
                MvcResult result = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, result.getResponse().getStatus(),
                        "同流水同内容并发应双双重放成功");
                long id = objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("masterId").asLong();
                if (masterId < 0) {
                    masterId = id;
                } else {
                    assertEquals(masterId, id, "并发同流水同内容只能产生一份母凭证");
                }
            }

            // 自然去重：只记一份母凭证、一条分配行，已收额只累加一次
            JsonNode order = getJson("/api/batches/B-MC3/chargebacks/COR-1", 200);
            assertEquals(3000L, order.get("clawback").get("collectedCents").asLong());
            assertEquals(2, order.get("clawback").get("version").asInt());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_master_receipt", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_allocation", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
