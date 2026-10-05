package com.example.starter.gas;

import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DifferenceSignOffRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.VerificationResponse;
import com.example.starter.gas.dto.VerificationSampleRequest;
import com.example.starter.gas.dto.VerificationSubmitRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.service.QualityVerificationService;
import com.example.starter.gas.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 气质核验并发测试（H2 真实库、真实线程）：
 * 同站同日并发核验只允许一个生效版本；同键并发幂等；并发签认唯一解除。
 */
class QualityConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 20);

    @Autowired
    private CustodyService custodyService;
    @Autowired
    private QualityVerificationService verificationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        addNomination(jdbc, GAS_DAY, "400000.00");
    }

    private String prepareDraftVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("qr-1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"),
                new BigDecimal("10000"), new BigDecimal("40.000000")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        return custodyService.createVoucher(new VoucherCreateRequest("qv-1", "ST01", GAS_DAY))
                .voucherNo();
    }

    private VerificationSubmitRequest request(String requestId, int c2h6Percent) {
        return new VerificationSubmitRequest(requestId, "ST01", GAS_DAY,
                List.of(new VerificationSampleRequest(Instant.parse("2026-09-20T10:00:00Z"),
                        new BigDecimal("500"),
                        Map.of("CH4", new BigDecimal(100 - c2h6Percent),
                                "C2H6", new BigDecimal(c2h6Percent)))));
    }

    @Test
    void concurrentVerification_sameStationDay_onlyOneEffectiveVersion() throws Exception {
        prepareDraftVoucher();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger versionConflict = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    start.await();
                    try {
                        verificationService.submitVerification(request("qc-" + idx, 2));
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        if ("VERIFICATION_VERSION_CONFLICT".equals(ex.getCode())) {
                            versionConflict.incrementAndGet();
                        } else {
                            throw new AssertionError("意外错误码: " + ex.getCode(), ex);
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 并发下只允许一个生效版本：每次提交要么成功追加唯一版本，要么 409 冲突；
        // 成功数与落库版本数一致，版本号自 1 连续无分叉
        assertThat(success.get()).isGreaterThanOrEqualTo(1);
        assertThat(success.get() + versionConflict.get()).isEqualTo(threads);
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification WHERE station_code='ST01' AND gas_date=?",
                Integer.class, java.sql.Date.valueOf(GAS_DAY));
        assertThat(versions).isEqualTo(success.get());
        Integer distinctVersions = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT version) FROM quality_verification "
                        + "WHERE station_code='ST01' AND gas_date=?",
                Integer.class, java.sql.Date.valueOf(GAS_DAY));
        assertThat(distinctVersions).isEqualTo(success.get());
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(version) FROM quality_verification "
                        + "WHERE station_code='ST01' AND gas_date=?",
                Integer.class, java.sql.Date.valueOf(GAS_DAY));
        assertThat(maxVersion).isEqualTo(success.get());
        // 版本序列与最新版本一致：任意时刻只有一个生效版本
        Integer currentVersion = jdbc.queryForObject(
                "SELECT current_version FROM station_day_verification "
                        + "WHERE station_code='ST01' AND gas_date=?",
                Integer.class, java.sql.Date.valueOf(GAS_DAY));
        assertThat(currentVersion).isEqualTo(success.get());
    }

    @Test
    void concurrentVerification_sameRequestId_singleVersionAndSameResult() throws Exception {
        prepareDraftVoucher();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<VerificationResponse> responses = new ConcurrentLinkedQueue<>();
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    start.await();
                    responses.add(verificationService.submitVerification(request("qc-same", 2)));
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses).hasSize(threads);
        assertThat(responses.stream().map(VerificationResponse::verificationNo).distinct())
                .hasSize(1);
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification", Integer.class);
        assertThat(versions).isEqualTo(1);
        Integer idempotent = jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id='qc-same'", Integer.class);
        assertThat(idempotent).isEqualTo(1);
    }

    @Test
    void concurrentSignOff_bothParties_resolveOnceAndReleasePause() throws Exception {
        String voucherNo = prepareDraftVoucher();
        custodyService.confirm(voucherNo, new ConfirmRequest("qc-a", "A"));
        // 偏差 1.5% > 1% → 气质差异并暂停确认
        VerificationResponse difference = verificationService.submitVerification(request("q-diff", 3));
        assertThat(difference.confirmPaused()).isTrue();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<VerificationResponse> responses = new ConcurrentLinkedQueue<>();
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final String party = (i % 2 == 0) ? "A" : "B";
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    start.await();
                    responses.add(verificationService.signOffDifference(difference.verificationNo(),
                            new DifferenceSignOffRequest("qs-" + party + "-" + idx, party)));
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses).hasSize(threads);
        // 每方仅一条签认记录，结论唯一解除，暂停释放
        Integer signOffs = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_difference_signoff WHERE verification_no=?",
                Integer.class, difference.verificationNo());
        assertThat(signOffs).isEqualTo(2);
        VerificationResponse resolved = verificationService.getVerification(difference.verificationNo());
        assertThat(resolved.status()).isEqualTo("DIFFERENT_RESOLVED");
        assertThat(resolved.confirmPaused()).isFalse();
        Integer activePauses = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_confirm_pause WHERE voucher_no=? AND released_at IS NULL",
                Integer.class, voucherNo);
        assertThat(activePauses).isZero();

        // 暂停解除后 B 方确认生效
        VoucherResponse effective = custodyService.confirm(voucherNo, new ConfirmRequest("qc-b", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
    }
}
