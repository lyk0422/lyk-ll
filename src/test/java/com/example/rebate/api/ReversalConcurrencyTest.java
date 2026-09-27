package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * H2 真实并发边界：
 * <ul>
 *   <li>多笔冲回与审批并发，累计占用绝不能超过冻结退税额；</li>
 *   <li>同一已批准冲回单并发到账不能超收；</li>
 *   <li>同 requestId 并发只执行一次，结果冻结、额度只占用/到账一次。</li>
 * </ul>
 */
class ReversalConcurrencyTest extends BaseApiTest {

    private static final String BATCH = "B-RC";
    private static final String DECL = "CD-RC";
    private static final long FROZEN = 13000L;

    private String submit(long idx, long amountCents) {
        return "/api/batches/" + BATCH + "/reversals";
    }

    /** 提交+批准多线程并发：任何交错下台账占用都不得超过冻结额。 */
    @Test
    void concurrentSubmitsAndApprovalsNeverExceedFrozen() throws Exception {
        closedSingleBatch(BATCH, DECL);

        int threads = 6;
        long amountEach = 4000L;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // 并发提交：预检窗口内可能多张 PENDING 同时存在，最终由批准时条件 UPDATE 兜底。
            List<Future<MvcResult>> submitFutures = new ArrayList<>();
            for (int n = 0; n < threads; n++) {
                final int idx = n;
                submitFutures.add(pool.submit(() -> {
                    start.await();
                    return postRaw(submit(idx, amountEach), TestFixtures.reversalSubmit(
                            "rc-sub-" + idx, DECL, "COR-C" + idx,
                            "并发冲回" + idx, amountEach, "alice", 1,
                            "2026-03-10T10:0" + idx + ":00Z"));
                }));
            }
            start.countDown();

            List<Long> pendingIds = new ArrayList<>();
            for (int i = 0; i < submitFutures.size(); i++) {
                MvcResult r = submitFutures.get(i).get(30, TimeUnit.SECONDS);
                int status = r.getResponse().getStatus();
                assertTrue(status == 200 || status == 422,
                        "提交只允许成功或业务拒绝，实际 " + status);
                if (status == 200) {
                    pendingIds.add(objectMapper
                            .readTree(r.getResponse().getContentAsString()).get("id").asLong());
                }
            }
            assertTrue(pendingIds.size() >= 1, "至少应有一张冲回单提交成功");

            // 并发批准全部 PENDING：批准占用的条件 UPDATE 是最终硬边界。
            CountDownLatch approveStart = new CountDownLatch(1);
            List<Future<MvcResult>> approveFutures = new ArrayList<>();
            for (int i = 0; i < pendingIds.size(); i++) {
                final long reversalId = pendingIds.get(i);
                approveFutures.add(pool.submit(() -> {
                    approveStart.await();
                    return postRaw(
                            "/api/batches/" + BATCH + "/reversals/" + reversalId + "/review",
                            TestFixtures.reversalReview(
                                    "rc-app-" + reversalId, "bob", "APPROVE", 1,
                                    "2026-03-10T11:00:00Z"));
                }));
            }
            approveStart.countDown();

            long approvedSum = 0;
            for (Future<MvcResult> f : approveFutures) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                int status = r.getResponse().getStatus();
                assertTrue(status == 200 || status == 422 || status == 409,
                        "审批只允许成功/业务拒绝/版本冲突，实际 " + status);
                if (status == 200) {
                    approvedSum += amountEach;
                }
            }

            // 数据库事实校验：占用 ≤ 冻结，且占用额恰好等于各已批准/完成单金额之和。
            Long occupied = jdbc.queryForObject("""
                    SELECT occupied_cents FROM t_reversal_ledger
                    WHERE batch_id = (SELECT id FROM t_declaration_batch WHERE batch_no = 'B-RC')
                      AND declaration_no = 'CD-RC'
                    """, Long.class);
            assertNotNull(occupied);
            assertTrue(occupied <= FROZEN, "并发审批超冲: occupied=" + occupied);
            assertEquals(approvedSum, occupied, "台账占用必须与批准金额逐分一致");

            Long approvedInDb = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount_cents), 0) FROM t_reversal_order
                    WHERE status IN ('APPROVED', 'COMPLETED')
                    """, Long.class);
            assertEquals(occupied, approvedInDb);

            Long detailCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_detail", Long.class);
            Long approvedCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_reversal_order WHERE status IN ('APPROVED','COMPLETED')",
                    Long.class);
            assertEquals(approvedCount, detailCount, "每张已批准单恰好一条独立追缴明细");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 同一已批准冲回单并发登记两笔超额到账：只能一笔成功，不能超收。 */
    @Test
    void concurrentReceiptsNeverExceedApprovedAmount() throws Exception {
        closedSingleBatch(BATCH, DECL);
        JsonNode order = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rcr-sub", DECL, "COR-R",
                        "并发到账", 5000L, "alice", 1, "2026-03-10T10:00:00Z"), 200);
        long id = order.get("id").asLong();
        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/review",
                TestFixtures.reversalReview("rcr-app", "bob", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 200);

        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int n = 0; n < threads; n++) {
                final int idx = n;
                futures.add(pool.submit(() -> {
                    start.await();
                    // 两笔均按批准后版本 2 并发提交，各 3000 分，累计将达 6000 > 5000
                    return postRaw(
                            "/api/batches/" + BATCH + "/reversals/" + id + "/receipts",
                            TestFixtures.clawbackReceipt("rcr-rc-" + idx, "RC-R" + idx,
                                    3000L, 2, "2026-03-11T10:0" + idx + ":00Z"));
                }));
            }
            start.countDown();
            int ok = 0;
            int rejected = 0;
            for (Future<MvcResult> f : futures) {
                int status = f.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                if (status == 200) {
                    ok++;
                } else if (status == 422 || status == 409) {
                    // 422：条件更新判出超收；409：行锁串行后读到已递增版本。两者都安全阻止超收。
                    rejected++;
                } else {
                    throw new AssertionError("未预期状态码 " + status);
                }
            }
            assertEquals(1, ok, "只能有一笔到账成功");
            assertEquals(1, rejected, "超额的一笔必须被拒绝或版本冲突");

            JsonNode fresh = getJson("/api/batches/" + BATCH + "/reversals/" + id, 200);
            assertEquals(3000L, fresh.get("receivedCents").asLong());
            assertEquals("APPROVED", fresh.get("status").asText());
            Integer receiptRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_receipt WHERE reversal_id = " + id,
                    Integer.class);
            assertEquals(1, receiptRows, "被拒事务不得残留回执");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 同 requestId 并发批准：全部返回首次冻结结果，额度只占用一次、追缴明细只有一条。 */
    @Test
    void concurrentSameRequestIdApprovesOnce() throws Exception {
        closedSingleBatch(BATCH, DECL);
        JsonNode order = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rid-sub", DECL, "COR-I",
                        "同键审批", 5000L, "alice", 1, "2026-03-10T10:00:00Z"), 200);
        long id = order.get("id").asLong();

        String body = TestFixtures.reversalReview("rid-app", "bob", "APPROVE", 1,
                "2026-03-10T11:00:00Z");
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int n = 0; n < threads; n++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postRaw(
                            "/api/batches/" + BATCH + "/reversals/" + id + "/review", body);
                }));
            }
            start.countDown();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, r.getResponse().getStatus());
                JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
                assertEquals("APPROVED", node.get("status").asText());
                assertEquals(2, node.get("versionNo").asInt(), "重放不产生新的版本变更");
            }
            Long occupied = jdbc.queryForObject("SELECT occupied_cents FROM t_reversal_ledger",
                    Long.class);
            assertEquals(5000L, occupied);
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM t_clawback_detail",
                    Long.class));
            Integer idemRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_idempotent_record WHERE request_id = 'rid-app'",
                    Integer.class);
            assertEquals(1, idemRows);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 同 requestId 并发到账：全部 200，回执只有一条、到账额只增加一次。 */
    @Test
    void concurrentSameRequestIdReceiptOnce() throws Exception {
        closedSingleBatch(BATCH, DECL);
        JsonNode order = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("ridr-sub", DECL, "COR-J",
                        "同键到账", 5000L, "alice", 1, "2026-03-10T10:00:00Z"), 200);
        long id = order.get("id").asLong();
        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/review",
                TestFixtures.reversalReview("ridr-app", "bob", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 200);

        String body = TestFixtures.clawbackReceipt("ridr-rc", "RC-RID", 3000L, 2,
                "2026-03-11T10:00:00Z");
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int n = 0; n < threads; n++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postRaw(
                            "/api/batches/" + BATCH + "/reversals/" + id + "/receipts", body);
                }));
            }
            start.countDown();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, r.getResponse().getStatus());
                JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
                assertEquals(3000L, node.get("receivedCents").asLong());
                assertEquals(3, node.get("versionNo").asInt());
            }
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_clawback_receipt WHERE receipt_serial = 'RC-RID'",
                    Long.class));
            Long received = jdbc.queryForObject("SELECT received_cents FROM t_reversal_ledger",
                    Long.class);
            assertEquals(3000L, received);
        } finally {
            pool.shutdownNow();
        }
    }
}
