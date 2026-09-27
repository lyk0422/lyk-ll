package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * H2 真实并发：
 * 审批与两笔冲回并发不得超冲（额度行 FOR UPDATE 串行化）；
 * 同一追缴单并发回执不得超收（追缴明细行 FOR UPDATE 串行化）。
 */
class ClawbackConcurrencyTest extends BaseApiTest {

    private static final String BATCH = "B-CB-CONC";
    private static final String DECL = "CD-CC";

    /** CD-CC: 130 元 = 13000 分。 */
    private void prepareClosed() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("ccc-c", DECL, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("ccc-i", "INV-CC", DECL, TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("ccc-chk", BATCH, "2026-02-01", DECL), 200);
        postJson("/api/batches/" + BATCH + "/declare",
                TestFixtures.declare("ccc-decl", false), 200);
        postJson("/api/batches/" + BATCH + "/approve",
                TestFixtures.requestId("ccc-appr"), 200);
        postJson("/api/batches/" + BATCH + "/treasury",
                TestFixtures.treasury("ccc-w", "GK-CC", "130.00"), 200);
        postJson("/api/batches/" + BATCH + "/close",
                TestFixtures.requestId("ccc-close"), 200);
    }

    private long submitPending(String reqId, String corrNo, long cents) throws Exception {
        JsonNode node = postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit(reqId, DECL, corrNo, "并发冲回", cents, "alice"), 200);
        return node.get("clawback").get("id").asLong();
    }

    @Test
    void concurrentApprovalsNeverExceedFrozenQuota() throws Exception {
        prepareClosed();
        // 两笔各 8000 分，合计 16000 > 冻结 13000：并发批准只能成功一笔
        long id1 = submitPending("cc-sub1", "CORR-C1", 8000L);
        long id2 = submitPending("cc-sub2", "CORR-C2", 8000L);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> f1 = pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/" + BATCH + "/clawbacks/" + id1 + "/review",
                        TestFixtures.clawbackReview("cc-rev1", 1, true, "bob"));
            });
            Future<MvcResult> f2 = pool.submit(() -> {
                start.await();
                return postRaw("/api/batches/" + BATCH + "/clawbacks/" + id2 + "/review",
                        TestFixtures.clawbackReview("cc-rev2", 1, true, "carol"));
            });
            start.countDown();

            int ok = 0;
            int rejected = 0;
            for (Future<MvcResult> f : java.util.List.of(f1, f2)) {
                int status = f.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                if (status == 200) {
                    ok++;
                } else if (status == 422) {
                    rejected++;
                } else {
                    throw new AssertionError("未预期的审批状态码 " + status);
                }
            }
            assertEquals(1, ok, "并发审批只能有一笔占用成功");
            assertEquals(1, rejected, "超冲的一笔必须业务拒绝");

            // 最终占用恰为一笔 8000，绝不超过冻结 13000
            JsonNode balance = getJson(
                    "/api/batches/" + BATCH + "/clawbacks/balance/" + DECL, 200);
            assertEquals(8000L, balance.get("occupiedCents").asLong());
            assertEquals(5000L, balance.get("remainingCents").asLong());

            Long occupied = jdbc.queryForObject(
                    "SELECT occupied_cents FROM t_clawback_quota "
                            + "WHERE batch_id = (SELECT id FROM t_declaration_batch WHERE batch_no = ?) "
                            + "AND declaration_no = ?",
                    Long.class, BATCH, DECL);
            assertEquals(8000L, occupied);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReceiptsNeverExceedApprovedAmount() throws Exception {
        prepareClosed();
        long id = submitPending("ccr-sub", "CORR-CR", 1000L);
        postJson("/api/batches/" + BATCH + "/clawbacks/" + id + "/review",
                TestFixtures.clawbackReview("ccr-rev", 1, true, "bob"), 200);

        // 批准 1000 分，两笔回执各 600 分并发：只能有一笔入账，累计 600 而非 1200
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> f1 = pool.submit(() -> {
                start.await();
                return postRaw(
                        "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                        TestFixtures.clawbackReceipt("ccr-1", "CRC-1", 600L,
                                "2026-03-01T10:00:00Z", 2));
            });
            Future<MvcResult> f2 = pool.submit(() -> {
                start.await();
                return postRaw(
                        "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                        TestFixtures.clawbackReceipt("ccr-2", "CRC-2", 600L,
                                "2026-03-01T11:00:00Z", 2));
            });
            start.countDown();

            int ok = 0;
            int rejected = 0;
            for (Future<MvcResult> f : java.util.List.of(f1, f2)) {
                int status = f.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                if (status == 200) {
                    ok++;
                } else if (status == 422) {
                    rejected++;
                } else {
                    throw new AssertionError("未预期的回执状态码 " + status);
                }
            }
            assertEquals(1, ok);
            assertEquals(1, rejected);

            JsonNode detail = getJson(
                    "/api/batches/" + BATCH + "/clawbacks/" + id, 200);
            assertEquals(600L, detail.get("recovery").get("receivedAmountCents").asLong());
            assertEquals("RECOVERING", detail.get("recovery").get("status").asText());
            assertEquals(1, detail.get("recovery").get("receipts").size());
            assertTrue(true);
        } finally {
            pool.shutdownNow();
        }
    }
}
