package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * H2 真实并发：同一报关单只允许进入一个有效批次；同 requestId 并发只有一个请求实际执行。
 */
class ConcurrencyTest extends BaseApiTest {

    @Test
    void concurrentChecksForSameDeclarationOnlyOneWins() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("cc-c", "CD-CONC", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("cc-i", "INV-CONC", "CD-CONC", TestFixtures.P1, "900"), 200);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int n = 0; n < threads; n++) {
                final int idx = n;
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult r = postRaw("/api/batches/check",
                            TestFixtures.check("conc-chk-" + idx, "B-CONC-" + idx,
                                    "2026-02-01", "CD-CONC"));
                    return r.getResponse().getStatus();
                }));
            }
            start.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<Integer> f : futures) {
                int status = f.get(30, TimeUnit.SECONDS);
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    conflict++;
                } else {
                    throw new AssertionError("未预期的状态码 " + status);
                }
            }
            assertEquals(1, ok, "并发下只能有一个批次占用该报关单");
            assertEquals(threads - 1, conflict);

            Integer activeBatches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_declaration_batch WHERE status <> 'VOID'", Integer.class);
            assertEquals(1, activeBatches, "失败事务必须回滚，不得残留批次");
            Integer registrations = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_declaration_registry", Integer.class);
            assertEquals(1, registrations);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdExecutesBusinessOnce() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("cr-c", "CD-RID", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("cr-i", "INV-RID", "CD-RID", TestFixtures.P1, "900"), 200);

        String body = TestFixtures.check("same-rid", "B-RID", "2026-02-01", "CD-RID");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
            for (int n = 0; n < threads; n++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postRaw("/api/batches/check", body);
                }));
            }
            start.countDown();
            int okCount = 0;
            String firstTax = null;
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, r.getResponse().getStatus());
                JsonNodeOk okNode = new JsonNodeOk(r.getResponse().getContentAsString());
                okCount++;
                if (firstTax == null) {
                    firstTax = okNode.tax;
                } else {
                    assertEquals(firstTax, okNode.tax, "同键并发必须返回同一首次结果");
                }
            }
            assertEquals(threads, okCount);
            Integer batches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_declaration_batch WHERE batch_no = 'B-RID'", Integer.class);
            assertEquals(1, batches, "同键并发只能创建一个批次");
            assertTrue(true);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 极简响应解析，避免在 lambda 里抛检查异常。 */
    private final class JsonNodeOk {
        final String tax;

        JsonNodeOk(String json) throws Exception {
            this.tax = objectMapper.readTree(json).get("totalDeclaredTax").asText();
        }
    }
}
