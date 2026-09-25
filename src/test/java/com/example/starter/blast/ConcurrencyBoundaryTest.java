package com.example.starter.blast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界：同一爆区同一计划起爆时刻并发申请后并发审批，只允许一个获批；
 * 同一“爆区＋测点＋采样时刻”的并发振动回传只落一条。
 */
class ConcurrencyBoundaryTest extends AbstractBlastIntegrationTest {

    private static final Instant PLANNED = Instant.parse("2026-09-26T04:00:00Z");

    @Test
    void concurrentApprovalsForSameSlotOnlyOneSucceeds() throws Exception {
        int threads = 8;
        List<Long> applicationIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            applicationIds.add(applyOk("conc-apply-" + i, "ZONE-A", PLANNED, new BigDecimal("180.000")));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Long applicationId : applicationIds) {
                long id = applicationId;
                futures.add(pool.submit(() -> {
                    awaitQuietly(start);
                    var resp = post("/api/blast/applications/" + id + "/approval",
                            new ApprovalBody("审批人乙", true, null));
                    int code = resp.getStatusCode().value();
                    if (code == 200) {
                        approved.incrementAndGet();
                    } else if (code == 409) {
                        conflicted.incrementAndGet();
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

        assertThat(approved.get()).as("同一爆区同一时刻只允许一个获批").isEqualTo(1);
        assertThat(approved.get() + conflicted.get()).isEqualTo(threads);

        Long approvedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_application WHERE zone_code = 'ZONE-A' "
                        + "AND planned_time = ? AND status = 'APPROVED'",
                Long.class, java.sql.Timestamp.from(PLANNED));
        assertThat(approvedCount).isEqualTo(1);
    }

    @Test
    void concurrentReadingSubmissionsDeduplicateByNaturalKey() throws Exception {
        long applicationId = applyOk("conc-read-app", "ZONE-A", PLANNED, new BigDecimal("180.000"));
        approveOk(applicationId);
        filingOk("conc-read-file", applicationId, Instant.parse("2026-09-26T04:05:00Z"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitQuietly(start);
                    var resp = post("/api/blast/readings", new ReadingBody(
                            applicationId, "ZONE-A", "P1",
                            Instant.parse("2026-09-26T04:05:01Z").toString(),
                            new BigDecimal("5.123")));
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody().get("accepted").asBoolean()) {
                        accepted.incrementAndGet();
                    } else if (resp.getStatusCode().is2xxSuccessful()
                            && resp.getBody().get("duplicate").asBoolean()) {
                        duplicates.incrementAndGet();
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

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vibration_reading WHERE application_id = ? AND point_code = 'P1' "
                        + "AND sample_time = ?",
                Long.class, applicationId, java.sql.Timestamp.from(Instant.parse("2026-09-26T04:05:01Z")));
        assertThat(rows).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
