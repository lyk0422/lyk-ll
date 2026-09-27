package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.TestFixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * H2 真实并发：
 * <ul>
 *   <li>两笔冲回并发批准，累计不得超过冻结退税额（超额一方回滚，单据留待复核）；</li>
 *   <li>并发批准恰可分尽冻结额时两笔均成功；</li>
 *   <li>同键同参并发回执只有一笔实际登记，其余回放首次结果，总额不变。</li>
 * </ul>
 */
class ReversalConcurrencyTest extends ReversalApiTest {

    private static final long FROZEN_CENTS = 13000L;

    @Test
    void concurrentApprovalsCannotExceedFrozenQuota() throws Exception {
        seedClosedBatch("B-CONC", "CD-CONC", "1000", "20");
        // 两张待复核单各 8000，合计 16000 > 冻结 13000
        postJson(reversals("B-CONC"),
                TestFixtures.reversalSubmit("ca", "CD-CONC", "CA", "x", 8000, "clerk-a"), 200);
        postJson(reversals("B-CONC"),
                TestFixtures.reversalSubmit("cb", "CD-CONC", "CB", "x", 8000, "clerk-a"), 200);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> approve(start, "B-CONC", "CA", "ra")));
            futures.add(pool.submit(() -> approve(start, "B-CONC", "CB", "rb")));
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
            assertEquals(1, ok, "只能有一笔批准成功");
            assertEquals(1, rejected, "超额一笔必须业务拒绝");

            Long occupied = jdbc.queryForObject(
                    "SELECT occupied_cents FROM t_reversal_quota "
                            + "WHERE batch_id=(SELECT id FROM t_declaration_batch WHERE batch_no='B-CONC')",
                    Long.class);
            assertEquals(8000L, occupied, "台账占用不得超过实际批准额");
            assertEquals(FROZEN_CENTS - 8000, FROZEN_CENTS - occupied);

            // 被拒的一笔保持 PENDING（未占用、可后续处理），批准的一笔 APPROVED
            Integer approved = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_reversal WHERE status='APPROVED'", Integer.class);
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_reversal WHERE status='PENDING'", Integer.class);
            assertEquals(1, approved);
            assertEquals(1, pending);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentApprovalsExactlySplittingFrozenBothSucceed() throws Exception {
        seedClosedBatch("B-SPLIT", "CD-SPLIT", "1000", "20");
        postJson(reversals("B-SPLIT"),
                TestFixtures.reversalSubmit("sa", "CD-SPLIT", "SA", "x", 8000, "clerk-a"), 200);
        postJson(reversals("B-SPLIT"),
                TestFixtures.reversalSubmit("sb", "CD-SPLIT", "SB", "x", 5000, "clerk-a"), 200);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> approve(start, "B-SPLIT", "SA", "rs1")));
            futures.add(pool.submit(() -> approve(start, "B-SPLIT", "SB", "rs2")));
            start.countDown();
            for (Future<Integer> f : futures) {
                assertEquals(200, f.get(30, TimeUnit.SECONDS), "恰分尽冻结额两笔均应成功");
            }
            Long occupied = jdbc.queryForObject(
                    "SELECT occupied_cents FROM t_reversal_quota "
                            + "WHERE batch_id=(SELECT id FROM t_declaration_batch WHERE batch_no='B-SPLIT')",
                    Long.class);
            assertEquals(FROZEN_CENTS, occupied, "恰归零合法");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyReceiptRegistersOnce() throws Exception {
        seedClosedBatch("B-RCP", "CD-RCP", "1000", "20");
        postJson(reversals("B-RCP"),
                TestFixtures.reversalSubmit("rx", "CD-RCP", "CX", "x", 5000, "clerk-a"), 200);
        postJson(review("B-RCP", "CX"),
                TestFixtures.reversalReview("rv", 1, "reviewer-b", true, null), 200);

        String body = TestFixtures.reversalReceipt("same-rcp", 2, "RCV-X", 1000,
                "2026-03-01T00:00:00Z");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
            for (int n = 0; n < threads; n++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postRaw(receipts("B-RCP", "CX"), body);
                }));
            }
            start.countDown();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, r.getResponse().getStatus());
                JsonNodeLite node = new JsonNodeLite(r.getResponse().getContentAsString());
                assertEquals(1000L, node.collected, "全部回放首次冻结结果，累计恒为 1000");
            }
            Integer receiptRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_reversal_receipt", Integer.class);
            assertEquals(1, receiptRows, "同键并发只能写入一条回执明细");
            Long collected = jdbc.queryForObject(
                    "SELECT collected_cents FROM t_reversal WHERE correction_no='CX'", Long.class);
            assertEquals(1000L, collected);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 并发任务：批准指定批次内指定更正编号的冲回单（不同 requestId 与复核人）。 */
    private int approve(CountDownLatch start, String batchNo, String correctionNo, String requestId) {
        try {
            start.await();
            return postRaw(review(batchNo, correctionNo),
                    TestFixtures.reversalReview(requestId, 1,
                            "reviewer-" + requestId, true, null)).getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 轻量响应解析。 */
    private final class JsonNodeLite {
        final long collected;

        JsonNodeLite(String json) throws Exception {
            this.collected = objectMapper.readTree(json).get("collectedCents").asLong();
        }
    }
}
