package com.example.starter.service;

import com.example.starter.StarterApplication;
import com.example.starter.support.AbstractIntegrationTest;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.LotView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实多线程并发下的 H2 事务边界测试：
 * 唯一发证、发证与标签核减原子性、复检/首检结论并发、同键并发幂等。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
class CertificationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private CertificationService service;

    /** 提交合格批次（双检均达原种门槛），待发证。 */
    private void prepareQualifiedLot(String lotNo, String prefix, int labels) {
        service.declare(declareRequest(prefix + "-D", lotNo, labels));
        service.submitField(fieldRequest(prefix + "-F", lotNo, "99.95"));
        service.submitLab(labRequest(prefix + "-L", lotNo, "92.00"));
        LotView lot = service.getLot(lotNo);
        assertEquals("QUALIFIED", lot.status());
    }

    @Test
    @DisplayName("并发对同批次发证：仅一次成功，证书唯一，标签只核减一次")
    void concurrentIssueOnlyOneSucceeds() throws Exception {
        prepareQualifiedLot("LOT-4001", "C1", 3000);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    try {
                        service.issueCertificate("LOT-4001");
                        success.incrementAndGet();
                    } catch (ConflictException ex) {
                        conflict.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, success.get(), "并发发证只允许一次成功");
        assertEquals(threads - 1, conflict.get(), "其余并发发证必须冲突失败");
        assertEquals(1, count("certificate",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-4001')"), "证书必须唯一");
        assertEquals(0, service.getLot("LOT-4001").availableLabels(), "标签只核减一次");
        assertEquals("CERTIFIED", service.getLot("LOT-4001").status());
    }

    @Test
    @DisplayName("发证与注销并发：仅一个终态操作生效；若发证先成功则注销必同步作废证书")
    void issueAndCancelAreSerialized() throws Exception {
        prepareQualifiedLot("LOT-4002", "C2", 1500);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> issue = pool.submit(() -> {
                awaitStart(start);
                try {
                    service.issueCertificate("LOT-4002");
                } catch (RuntimeException ignored) {
                    // 注销先发生时发证应失败，最终状态在主线程统一断言
                }
            });
            Future<?> cancel = pool.submit(() -> {
                awaitStart(start);
                service.cancel(new com.example.starter.web.CancelRequest("LOT-4002"));
            });
            start.countDown();
            issue.get(30, TimeUnit.SECONDS);
            cancel.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        LotView finalLot = service.getLot("LOT-4002");
        assertEquals("CANCELLED", finalLot.status(), "注销最终必须生效（终态）");
        if (finalLot.certificate() != null) {
            // 发证若在注销前成功，其证书必须已被注销事务同步作废。
            assertEquals("VOID", finalLot.certificate().status());
            assertNotNull(finalLot.certificate().voidedAt());
        }
    }

    @Test
    @DisplayName("同键同参并发申报：仅建一个批次，两个调用都返回首次结果")
    void concurrentSameKeyDeclareIsIdempotent() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<LotView>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    return service.declare(declareRequest("CONCURRENT-DECLARE-1", "LOT-4003", 100));
                }));
            }
            start.countDown();
            LotView first = futures.get(0).get(30, TimeUnit.SECONDS);
            for (Future<LotView> future : futures) {
                LotView view = future.get(30, TimeUnit.SECONDS);
                assertEquals(first.createdAt(), view.createdAt());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("seed_lot", "lot_no = ?", "LOT-4003"));
    }

    @Test
    @DisplayName("同键同参并发田间检验：只产生一条检验记录，两次返回相同首次结论")
    void concurrentSameKeyFieldInspectionIsIdempotent() throws Exception {
        service.declare(declareRequest("C4-D", "LOT-4004", 100));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<LotView>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    return service.submitField(fieldRequest("CONCURRENT-FIELD-1", "LOT-4004", "99.95"));
                }));
            }
            start.countDown();
            LotView first = futures.get(0).get(30, TimeUnit.SECONDS);
            for (Future<LotView> future : futures) {
                assertEquals(first.updatedAt(), future.get(30, TimeUnit.SECONDS).updatedAt());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("field_inspection",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-4004')"));
        // 室内检测尚未回，结论保持“等待”（合格项单回不形成越级分级）
        assertEquals("DECLARED", service.getLot("LOT-4004").status());
    }

    /** 等待并发起跑信号；被中断时包装为运行时异常。 */
    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @DisplayName("田间与室内检验并发提交：唯一有效结论一次性形成，记录各一条且批次状态确定")
    void concurrentFieldAndLabFormSingleConclusion() throws Exception {
        service.declare(declareRequest("C5-D", "LOT-4005", 100));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> field = pool.submit(() -> {
                awaitStart(start);
                service.submitField(fieldRequest("C5-F", "LOT-4005", "99.95"));
            });
            Future<?> lab = pool.submit(() -> {
                awaitStart(start);
                service.submitLab(labRequest("C5-L", "LOT-4005", "92.00"));
            });
            start.countDown();
            field.get(30, TimeUnit.SECONDS);
            lab.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        LotView lot = service.getLot("LOT-4005");
        assertEquals("QUALIFIED", lot.status(), "行锁串行化后双检结论必须形成");
        assertEquals("ORIGINAL", lot.determinedGrade());
        assertEquals(1, count("field_inspection",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-4005')"));
        assertEquals(1, count("lab_inspection",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-4005')"));
        assertTrue(lot.availableLabels() == 100, "未发证前标签不得被核减");
    }

    @Test
    @DisplayName("复检申请并发：不同 requestId 同时提交也只受理一次（每批仅限一次复检）")
    void concurrentRecheckAcceptedOnlyOnce() throws Exception {
        // 构造首检不合格批次。
        service.declare(declareRequest("C6-D", "LOT-4006", 100));
        service.submitField(fieldRequest("C6-F", "LOT-4006", "98.00"));
        service.submitLab(labRequest("C6-L", "LOT-4006", "90.00"));
        assertEquals("UNQUALIFIED", service.getLot("LOT-4006").status());
        // 不合格时间为基准日 2026-03-10，推进到第 10 日，仍在 15 日窗口内。
        clock.setInstant(java.time.Instant.parse("2026-03-20T08:00:00Z"));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String requestId = "CONCURRENT-RECHECK-" + i;
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    try {
                        service.applyRecheck(recheckRequest(requestId, "LOT-4006"));
                        accepted.incrementAndGet();
                    } catch (BusinessRuleException | ConflictException ex) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, accepted.get(), "复检只允许受理一次");
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, count("recheck_application",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-4006')"));
        assertEquals(1, service.getLot("LOT-4006").recheckCount());
        assertEquals("RECHECKING", service.getLot("LOT-4006").status());
    }
}
