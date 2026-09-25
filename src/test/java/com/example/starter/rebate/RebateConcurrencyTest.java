package com.example.starter.rebate;

import com.example.starter.rebate.api.BatchResponse;
import com.example.starter.rebate.api.DocumentResponse;
import com.example.starter.rebate.api.RefundResponse;
import com.example.starter.rebate.service.RebateService;
import com.example.starter.rebate.support.BusinessConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实并发边界测试：线程闸门协调并发提交，设置超时，断言结果与 H2 最终数据。
 */
class RebateConcurrencyTest extends RebateServiceTestBase {

    private static final String HS = "1234567890";
    private static final LocalDate DECLARE_DATE = LocalDate.parse("2026-06-30");

    @Autowired
    private RebateService service;

    private void seedRates() {
        service.saveRate("rate-1", HS, LocalDate.parse("2026-01-01"), new BigDecimal("0.13"));
    }

    @Test
    void sameRequestIdConcurrent_sameParams_singleEffect() throws Exception {
        seedRates();
        long docId = service.collect("doc-c", "DECL-C", "INV-C", HS,
                new BigDecimal("1000"), new BigDecimal("26")).id();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BatchResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.createBatch("same-key", "B-CONC", DECLARE_DATE, List.of(docId));
                }));
            }
            start.countDown();
            for (Future<BatchResponse> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS).id()).isEqualTo(1L);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rebate_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id='same-key'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM declaration_registration", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameDeclarationConcurrentBatches_exactlyOneWins() throws Exception {
        seedRates();
        // 同一报关单号、不同发票号的两张单证
        DocumentResponse docA = service.collect("ca-1", "DECL-SAME", "INV-A", HS,
                new BigDecimal("1000"), new BigDecimal("26"));
        DocumentResponse docB = service.collect("ca-2", "DECL-SAME", "INV-B", HS,
                new BigDecimal("1000"), new BigDecimal("26"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BatchResponse> f1 = pool.submit(() -> {
                start.await();
                return service.createBatch("key-a", "B-A", DECLARE_DATE, List.of(docA.id()));
            });
            Future<BatchResponse> f2 = pool.submit(() -> {
                start.await();
                return service.createBatch("key-b", "B-B", DECLARE_DATE, List.of(docB.id()));
            });
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<BatchResponse> f : List.of(f1, f2)) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    assertThat(e).rootCause().isInstanceOf(BusinessConflictException.class);
                    conflict++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 最终只有一个有效批次、一条占用；另一张单证未被挂批次
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM rebate_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM declaration_registration", Integer.class)).isEqualTo(1);
        Integer attached = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rebate_document WHERE batch_id IS NOT NULL", Integer.class);
        assertThat(attached).isEqualTo(1);
    }

    @Test
    void concurrentDeclare_exactlyOneFreeze() throws Exception {
        seedRates();
        long docId = service.collect("cd-1", "DECL-CD", "INV-CD", HS,
                new BigDecimal("1000"), new BigDecimal("26")).id();
        BatchResponse batch = service.createBatch("cd-batch", "B-CD", DECLARE_DATE, List.of(docId));

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "declare-key-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    service.declare(batch.id(), key, List.of());
                    return null;
                }));
            }
            start.countDown();
            int success = 0;
            int conflict = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    assertThat(e).rootCause().isInstanceOf(BusinessConflictException.class);
                    conflict++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM rebate_batch WHERE id=" + batch.id(), String.class)).isEqualTo("DECLARED");
    }

    @Test
    void concurrentRefundSameSerial_singleRegistration() throws Exception {
        seedRates();
        long docId = service.collect("cr-1", "DECL-CR", "INV-CR", HS,
                new BigDecimal("1000"), new BigDecimal("26")).id();
        BatchResponse batch = service.createBatch("cr-batch", "B-CR", DECLARE_DATE, List.of(docId));
        service.declare(batch.id(), "cr-declare", List.of());
        service.approve(batch.id());

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RefundResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "refund-key-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.refund(batch.id(), key, "TS-SAME", new BigDecimal("130.00"));
                }));
            }
            start.countDown();
            for (Future<RefundResponse> future : futures) {
                RefundResponse response = future.get(20, TimeUnit.SECONDS);
                assertThat(response.treasurySerialNo()).isEqualTo("TS-SAME");
                assertThat(response.status()).isEqualTo("CONSISTENT");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM refund_registration", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM rebate_batch WHERE id=" + batch.id(), String.class)).isEqualTo("REFUNDED");
    }
}
