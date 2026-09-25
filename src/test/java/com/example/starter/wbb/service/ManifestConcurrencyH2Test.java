package com.example.starter.wbb.service;

import com.example.starter.wbb.AbstractH2IntegrationTest;
import com.example.starter.wbb.config.SyntheticDataSeeder;
import com.example.starter.wbb.dto.CheckinRequest;
import com.example.starter.wbb.dto.LmcRequest;
import com.example.starter.wbb.dto.LoadConfirmRequest;
import com.example.starter.wbb.dto.ManifestResponse;
import com.example.starter.wbb.dto.PayloadItemDto;
import com.example.starter.wbb.dto.PreplanRequest;
import com.example.starter.wbb.dto.ReviewRequest;
import com.example.starter.wbb.exception.BusinessRuleException;
import com.example.starter.wbb.exception.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实并发与幂等边界测试：
 * 同岗位并发复核唯一成功；同 requestId 异参 409 且不产生新版本。
 */
class ManifestConcurrencyH2Test extends AbstractH2IntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 26);

    @org.springframework.beans.factory.annotation.Autowired
    private ManifestService service;

    private PayloadItemDto item(String category, String zone, int pieces, String weight, String arm) {
        return new PayloadItemDto(category, zone, pieces, new BigDecimal(weight), new BigDecimal(arm));
    }

    private List<PayloadItemDto> baseline() {
        return List.of(
                item("PAX", "CABIN", 100, "8000", "16.0"),
                item("CARGO", "HOLD1", 20, "2000", "16.0"));
    }

    private void prepareLoaded(String flight, String prefix) {
        service.preplan(new PreplanRequest(prefix + "-pre", flight, DATE,
                SyntheticDataSeeder.AIRCRAFT_TYPE, "B-9999", new BigDecimal("5000"), null, baseline()));
        service.checkin(flight + "-" + DATE, new CheckinRequest(prefix + "-ci", baseline()));
        service.loadConfirm(flight + "-" + DATE, new LoadConfirmRequest(prefix + "-load"));
    }

    @Test
    void concurrentSameRoleReviewsOnlyOneSucceeds() throws Exception {
        prepareLoaded("CA2001", "cc");
        String key = "CA2001-" + DATE;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        service.review(key, new ReviewRequest("cc-rwbo-" + idx, "WBO", "user" + idx));
                        ok.incrementAndGet();
                    } catch (BusinessRuleException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        // 唯一约束 + 状态判定：WBO 岗仅一条签字，舱单未冻结。
        Integer reviewRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_review WHERE review_role = 'WBO'", Integer.class);
        assertThat(reviewRows).isEqualTo(1);
        assertThat(service.getActive(key).frozen()).isFalse();
        assertThat(service.getActive(key).wboSigned()).isTrue();
        assertThat(service.getActive(key).dspSigned()).isFalse();
    }

    @Test
    void sameRequestIdDifferentParamsConflicts409WithoutNewVersion() {
        prepareLoaded("CA2002", "cf");
        String key = "CA2002-" + DATE;
        ManifestResponse first = service.lmc(key,
                new LmcRequest("same-id", List.of(item("PAX", "CABIN", 1, "80", "16.0"))));
        assertThat(first.lmcDeltaPax()).isEqualTo(1);

        // 同键异参：409，且不产生任何新版本/新业载行。
        assertThatThrownBy(() -> service.lmc(key,
                new LmcRequest("same-id", List.of(item("PAX", "CABIN", 2, "160", "16.0")))))
                .isInstanceOf(IdempotencyConflictException.class);
        ManifestResponse active = service.getActive(key);
        assertThat(active.id()).isEqualTo(first.id());
        assertThat(active.versionNo()).isEqualTo(1);
        assertThat(active.lmcDeltaPax()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payload_item WHERE manifest_id = ?", Integer.class, first.id()))
                .isEqualTo(3);
    }

    @Test
    void concurrentPreplanSameFlightOnlyOneActiveManifest() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        service.preplan(new PreplanRequest("cp-" + idx, "CA2003", DATE,
                                SyntheticDataSeeder.AIRCRAFT_TYPE, "B-9999",
                                new BigDecimal("5000"), null, baseline()));
                        ok.incrementAndGet();
                    } catch (BusinessRuleException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        String key = "CA2003-" + DATE;
        assertThat(service.versions(key)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_active_manifest WHERE flight_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdSameParamsExecutesOnceAndOthersReplay() throws Exception {
        prepareLoaded("CA2004", "cid");
        String key = "CA2004-" + DATE;
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<Long>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.lmc(key, new LmcRequest("shared-same-id",
                            List.of(item("PAX", "CABIN", 2, "160", "16.0")))).id();
                }));
            }
            start.countDown();
            Long firstId = null;
            for (Future<Long> f : futures) {
                Long id = f.get(30, TimeUnit.SECONDS);
                if (firstId == null) {
                    firstId = id;
                }
                assertThat(id).isEqualTo(firstId);
            }
        } finally {
            pool.shutdownNow();
        }
        // 业务只执行一次：增量明细仅一行，累计 +2 人/+160kg，版本仍为 1。
        ManifestResponse active = service.getActive(key);
        assertThat(active.versionNo()).isEqualTo(1);
        assertThat(active.lmcDeltaPax()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payload_item WHERE manifest_id = ?",
                Integer.class, active.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'shared-same-id'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdDifferentParamsOneSucceedsOthersConflict409() throws Exception {
        prepareLoaded("CA2005", "cid2");
        String key = "CA2005-" + DATE;
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int pax = i + 1;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        service.lmc(key, new LmcRequest("shared-diff-id",
                                List.of(item("PAX", "CABIN", pax, String.valueOf(80L * pax), "16.0"))));
                        ok.incrementAndGet();
                    } catch (IdempotencyConflictException e) {
                        conflict.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        // 只有一种参数落库，无版本重打。
        assertThat(service.versions(key)).hasSize(1);
    }
}
