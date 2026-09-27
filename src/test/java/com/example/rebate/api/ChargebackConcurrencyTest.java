package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * H2 真实并发边界：
 * 审批与两笔冲回并发不得超冲（余额条件占用串行化）；同版本并发回执只有一笔生效；
 * 结构化指纹下含分隔符文本仍可区分；乱序到账不改变总额。
 */
class ChargebackConcurrencyTest extends BaseApiTest {

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

    @Test
    void concurrentApprovalsNeverExceedFrozen() throws Exception {
        prepareClosed("B-CC1", "CD-CC1", "cc1");
        // 两张待复核单各 7000，合计 14000 > 冻结 13000
        postJson("/api/batches/B-CC1/chargebacks",
                TestFixtures.chargebackSubmit("cc1-s1", "CD-CC1", "C-X", "x", 7000L, "a"), 200);
        postJson("/api/batches/B-CC1/chargebacks",
                TestFixtures.chargebackSubmit("cc1-s2", "CD-CC1", "C-Y", "x", 7000L, "a"), 200);

        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-CC1/chargebacks/C-X/review",
                        TestFixtures.chargebackReview("cc1-rx", 1, "APPROVE", "bob"))
                        .getResponse().getStatus();
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-CC1/chargebacks/C-Y/review",
                        TestFixtures.chargebackReview("cc1-ry", 1, "APPROVE", "bob"))
                        .getResponse().getStatus();
            }));
            start.countDown();

            int ok = 0;
            int rejected = 0;
            for (Future<Integer> f : futures) {
                int status = f.get(30, TimeUnit.SECONDS);
                if (status == 200) {
                    ok++;
                } else if (status == 422) {
                    rejected++;
                } else {
                    throw new AssertionError("未预期状态码 " + status);
                }
            }
            assertEquals(1, ok, "并发两笔批准只能有一笔占用成功");
            assertEquals(1, rejected, "超冲的一笔必须被业务拒绝");

            JsonNode balances = getJson("/api/batches/B-CC1/chargebacks", 200);
            assertEquals(13000L, balances.get(0).get("frozenCents").asLong());
            assertEquals(7000L, balances.get(0).get("occupiedCents").asLong(),
                    "累计占用不得超过冻结额");
            assertEquals(6000L, balances.get(0).get("remainingCents").asLong());

            // 恰好一张冲回单 APPROVED 并有追缴明细，另一张回滚为 PENDING 且无追缴明细
            JsonNode x = getJson("/api/batches/B-CC1/chargebacks/C-X", 200);
            JsonNode y = getJson("/api/batches/B-CC1/chargebacks/C-Y", 200);
            int approved = 0;
            int pending = 0;
            for (JsonNode o : java.util.List.of(x, y)) {
                if ("APPROVED".equals(o.get("status").asText())) {
                    approved++;
                    assertEquals("COLLECTING", o.get("clawback").get("status").asText());
                } else {
                    pending++;
                    assertEquals("PENDING", o.get("status").asText());
                }
            }
            assertEquals(1, approved);
            assertEquals(1, pending);

            Integer clawbacks = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_detail", Integer.class);
            assertEquals(1, clawbacks, "失败批准事务回滚，不得产生追缴明细");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameVersionReceiptsOnlyOneApplies() throws Exception {
        prepareClosed("B-CC2", "CD-CC2", "cc2");
        postJson("/api/batches/B-CC2/chargebacks",
                TestFixtures.chargebackSubmit("cc2-s", "CD-CC2", "C-1", "x", 10000L, "a"), 200);
        postJson("/api/batches/B-CC2/chargebacks/C-1/review",
                TestFixtures.chargebackReview("cc2-r", 1, "APPROVE", "bob"), 200);

        // 两笔回执都基于 version=1，各 6000（单独看都不超批准 10000），并发只能一笔生效
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-CC2/chargebacks/C-1/receipts",
                        TestFixtures.receipt("cc2-a", 1, "RA", 6000L, "2026-03-01T10:00:00Z"));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/B-CC2/chargebacks/C-1/receipts",
                        TestFixtures.receipt("cc2-b", 1, "RB", 6000L, "2026-03-02T10:00:00Z"));
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
            assertEquals(1, ok);
            assertEquals(1, conflict, "同版本并发回执，落后一笔必须版本冲突 409");

            JsonNode order = getJson("/api/batches/B-CC2/chargebacks/C-1", 200);
            JsonNode cb = order.get("clawback");
            assertEquals(6000L, cb.get("collectedCents").asLong(), "只登记一笔到账");
            assertEquals(2, cb.get("version").asInt());
            assertEquals(1, cb.get("receipts").size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void delimiterContainingTextIsDistinguishedByStructuredFingerprint() throws Exception {
        prepareClosed("B-CC3", "CD-CC3", "cc3");
        // 朴素拼接会令 correctionNo/reason 边界混淆；结构化 JSON 指纹必须区分。
        String body1 = TestFixtures.chargebackSubmit(
                "cc3-key", "CD-CC3", "C-1|EXTRA", "ab", 100L, "a");
        JsonNode first = postJson("/api/batches/B-CC3/chargebacks", body1, 200);
        String body2 = TestFixtures.chargebackSubmit(
                "cc3-key", "CD-CC3", "C-1", "a|EXTRAb", 100L, "a");
        // 同键但字段语义不同 → 409，而不是被当成同参重放
        postJson("/api/batches/B-CC3/chargebacks", body2, 409);
        JsonNode replay = postJson("/api/batches/B-CC3/chargebacks", body1, 200);
        assertEquals(first.get("id").asLong(), replay.get("id").asLong());
        assertEquals("C-1|EXTRA", replay.get("correctionNo").asText());
        assertNotEquals("C-1", replay.get("correctionNo").asText());
    }

    @Test
    void outOfOrderAndDuplicateReceiptsKeepTotalStable() throws Exception {
        prepareClosed("B-CC4", "CD-CC4", "cc4");
        postJson("/api/batches/B-CC4/chargebacks",
                TestFixtures.chargebackSubmit("cc4-s", "CD-CC4", "C-1", "x", 5000L, "a"), 200);
        postJson("/api/batches/B-CC4/chargebacks/C-1/review",
                TestFixtures.chargebackReview("cc4-r", 1, "APPROVE", "bob"), 200);

        // 乱序：先登记编号靠后的 R2，再登记 R1
        JsonNode afterR2 = postJson("/api/batches/B-CC4/chargebacks/C-1/receipts",
                TestFixtures.receipt("cc4-r2", 1, "R2", 3000L, "2026-03-02T10:00:00Z"), 200);
        assertEquals(3000L, afterR2.get("clawback").get("collectedCents").asLong());
        JsonNode afterR1 = postJson("/api/batches/B-CC4/chargebacks/C-1/receipts",
                TestFixtures.receipt("cc4-r1", 2, "R1", 2000L, "2026-03-01T09:00:00Z"), 200);
        JsonNode cb = afterR1.get("clawback");
        assertEquals(5000L, cb.get("collectedCents").asLong(), "乱序到账不改变总额");
        assertEquals("COMPLETED", cb.get("status").asText());

        // 同流水同金额用不同 requestId 重放（走业务流水幂等，而非 requestId 重放）不重复累加
        JsonNode replay = postJson("/api/batches/B-CC4/chargebacks/C-1/receipts",
                TestFixtures.receipt("cc4-r2-dup", 3, "R2", 3000L, "2026-03-02T10:00:00Z"), 200);
        assertEquals(5000L, replay.get("clawback").get("collectedCents").asLong());
        assertEquals(2, replay.get("clawback").get("receipts").size());
    }
}
