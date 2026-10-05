package com.example.starter.gas;

import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.SampleRequest;
import com.example.starter.gas.dto.SignOffRequest;
import com.example.starter.gas.dto.VerificationResponse;
import com.example.starter.gas.dto.VerificationSubmitRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.service.QualityService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 气质核验的真实线程并发测试（H2 事务与唯一/CAS 约束，不使用 mock）：
 * 同站同日并发核验只允许一个生效版本；并发签认只解除一次。
 */
class QualityConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 20);

    @Autowired
    private CustodyService custodyService;
    @Autowired
    private QualityService qualityService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String voucherNo;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("rd-1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"),
                new BigDecimal("10000"), new BigDecimal("40.000000")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("vc-1", "ST01", GAS_DAY));
        voucherNo = voucher.voucherNo();
    }

    @Test
    void concurrentFirstVerifications_onlyOneActiveVersion() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    start.await();
                    try {
                        qualityService.submitVerification(new VerificationSubmitRequest(
                                "qv-con-" + idx, "ST01", GAS_DAY, voucherNo,
                                List.of(new SampleRequest(Instant.parse("2026-09-20T08:00:00Z"),
                                        new BigDecimal("100"), Map.of("CH4", new BigDecimal("100"))))));
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        if ("VERIFICATION_VERSION_CONFLICT".equals(ex.getCode())) {
                            conflict.incrementAndGet();
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

        // 并发下后到者可能看到已提交的生效版本并走合法修订路径，故成功数 ≥1；
        // 数据库级保证：任意时刻仅一个生效版本，占位表指向生效行，版本链完整
        assertThat(success.get()).isGreaterThanOrEqualTo(1);
        assertThat(success.get() + conflict.get()).isEqualTo(threads);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification WHERE status='ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM station_day_verification", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification", Integer.class))
                .isEqualTo(success.get());
        String activeNo = jdbc.queryForObject(
                "SELECT verification_no FROM quality_verification WHERE status='ACTIVE'", String.class);
        String occupiedNo = jdbc.queryForObject(
                "SELECT verification_no FROM station_day_verification", String.class);
        assertThat(occupiedNo).isEqualTo(activeNo);
    }

    @Test
    void concurrentRevisions_onlyOneNewActiveVersion() throws Exception {
        qualityService.submitVerification(new VerificationSubmitRequest(
                "qv-v1", "ST01", GAS_DAY, voucherNo,
                List.of(new SampleRequest(Instant.parse("2026-09-20T08:00:00Z"),
                        new BigDecimal("100"), Map.of("CH4", new BigDecimal("100"))))));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    start.await();
                    try {
                        qualityService.submitVerification(new VerificationSubmitRequest(
                                "qv-rev-" + idx, "ST01", GAS_DAY, voucherNo,
                                List.of(new SampleRequest(Instant.parse("2026-09-20T10:00:00Z"),
                                        new BigDecimal("100"), Map.of("CH4", new BigDecimal("100"))))));
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        if ("VERIFICATION_VERSION_CONFLICT".equals(ex.getCode())) {
                            conflict.incrementAndGet();
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

        // 并发修订：基于同一生效版本的 CAS 只有一个成功；若某线程在他人提交后才读取，
        // 则构成对最新版本的合法串行修订。保证：仅一个生效版本、版本链连续、占位一致
        assertThat(success.get()).isGreaterThanOrEqualTo(1);
        assertThat(success.get() + conflict.get()).isEqualTo(threads);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification", Integer.class))
                .isEqualTo(1 + success.get());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_verification WHERE status='ACTIVE'", Integer.class))
                .isEqualTo(1);
        Integer activeVersion = jdbc.queryForObject(
                "SELECT version FROM quality_verification WHERE status='ACTIVE'", Integer.class);
        assertThat(activeVersion).isEqualTo(1 + success.get());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM station_day_verification", Integer.class)).isEqualTo(1);
        String activeNo = jdbc.queryForObject(
                "SELECT verification_no FROM quality_verification WHERE status='ACTIVE'", String.class);
        String occupiedNo = jdbc.queryForObject(
                "SELECT verification_no FROM station_day_verification", String.class);
        assertThat(occupiedNo).isEqualTo(activeNo);
    }

    @Test
    void concurrentSignOffs_singleResolutionAndPauseReleased() throws Exception {
        VerificationResponse diff = qualityService.submitVerification(
                new VerificationSubmitRequest("qv-diff", "ST01", GAS_DAY, voucherNo,
                        List.of(new SampleRequest(Instant.parse("2026-09-20T08:00:00Z"),
                                new BigDecimal("100"),
                                Map.of("CH4", new BigDecimal("89"), "C2H6", new BigDecimal("11"))))));
        assertThat(diff.confirmationPaused()).isTrue();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                final String party = (idx % 2 == 0) ? "A" : "B";
                futures[i] = pool.submit(() -> {
                    start.await();
                    qualityService.signOff(diff.verificationNo(),
                            new SignOffRequest("so-con-" + idx, party));
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

        // 双方各一条签认记录；差异恰好解除一次，暂停释放
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM quality_diff_signoff WHERE verification_no=?",
                Integer.class, diff.verificationNo())).isEqualTo(2);
        VerificationResponse resolved = qualityService.getVerification(diff.verificationNo());
        assertThat(resolved.diffStatus()).isEqualTo("RESOLVED");
        assertThat(resolved.confirmationPaused()).isFalse();

        // 暂停已解除：双方确认可生效
        custodyService.confirm(voucherNo, new ConfirmRequest("cf-a", "A"));
        VoucherResponse effective = custodyService.confirm(voucherNo, new ConfirmRequest("cf-b", "B"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
    }
}
