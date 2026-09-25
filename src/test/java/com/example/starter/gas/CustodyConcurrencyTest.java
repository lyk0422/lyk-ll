package com.example.starter.gas;

import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DisputeRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.SealRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实线程并发下的唯一结果竞态测试（H2 事务与唯一/CAS 约束，不使用 mock）。
 */
class CustodyConcurrencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 20);

    @Autowired
    private CustodyService custodyService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
        addNomination(jdbc, GAS_DAY, "380000.00");
    }

    private String prepareEffectiveVoucher() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("r1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"), new BigDecimal("10000"), new BigDecimal("39.2")));
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        VoucherResponse voucher = custodyService.createVoucher(
                new VoucherCreateRequest("v1", "ST01", GAS_DAY));
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("c1", "A"));
        custodyService.confirm(voucher.voucherNo(), new ConfirmRequest("c2", "B"));
        return voucher.voucherNo();
    }

    @Test
    void concurrentVoucherCreation_onlyOneWins() throws Exception {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        custodyService.submitReading(new ReadingSubmitRequest("r1", "ST01",
                Instant.parse("2026-09-20T12:00:00Z"), new BigDecimal("10000"), new BigDecimal("39.2")));
        clock.setInstant(Instant.parse("2026-09-21T00:30:00Z"));

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
                        custodyService.createVoucher(new VoucherCreateRequest(
                                "vc-con-" + idx, "ST01", GAS_DAY));
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        if ("VOUCHER_ALREADY_EXISTS".equals(ex.getCode())) {
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

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        Integer voucherCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM custody_voucher WHERE station_code='ST01' AND gas_date=? "
                        + "AND voucher_type='ORIGINAL'", Integer.class, java.sql.Date.valueOf(GAS_DAY));
        assertThat(voucherCount).isEqualTo(1);
        Integer occupied = jdbc.queryForObject("SELECT COUNT(1) FROM station_day_voucher", Integer.class);
        assertThat(occupied).isEqualTo(1);
    }

    @Test
    void concurrentDisputesOnSeventhDay_onlyOneDisputeRecord() throws Exception {
        String voucherNo = prepareEffectiveVoucher();
        // 恰第 7 日（UTC）争议受理窗口内
        clock.setInstant(Instant.parse("2026-09-28T09:00:00Z"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger disputed = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    start.await();
                    VoucherResponse resp = custodyService.dispute(voucherNo,
                            new DisputeRequest("d-con-" + idx, "A", "并发争议" + idx));
                    if ("DISPUTED".equals(resp.status())) {
                        disputed.incrementAndGet();
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

        assertThat(disputed.get()).isEqualTo(threads);
        Integer disputeCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_dispute WHERE voucher_no=?", Integer.class, voucherNo);
        assertThat(disputeCount).isEqualTo(1);
        assertThat(custodyService.getVoucher(voucherNo).status()).isEqualTo("DISPUTED");
    }

    @Test
    void concurrentSealAndLateDispute_singleTerminalResult() throws Exception {
        String voucherNo = prepareEffectiveVoucher();
        // 争议期结束之后：封账成功、争议逾期被拒，最终唯一 SEALED
        clock.setInstant(Instant.parse("2026-09-29T00:01:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealedOk = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Future<?>[] futures = new Future<?>[4];
        try {
            for (int i = 0; i < 4; i++) {
                final boolean sealAction = (i % 2 == 0);
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    start.await();
                    try {
                        VoucherResponse resp;
                        if (sealAction) {
                            resp = custodyService.seal(voucherNo, new SealRequest("s-con-" + idx));
                        } else {
                            resp = custodyService.dispute(voucherNo,
                                    new DisputeRequest("d-con-" + idx, "B", "逾期争议" + idx));
                        }
                        if ("SEALED".equals(resp.status())) {
                            sealedOk.incrementAndGet();
                        }
                    } catch (ApiException ex) {
                        if ("DISPUTE_WINDOW_CLOSED".equals(ex.getCode())
                                || "VOUCHER_SEALED".equals(ex.getCode())) {
                            rejected.incrementAndGet();
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

        assertThat(sealedOk.get() + rejected.get()).isEqualTo(4);
        assertThat(sealedOk.get()).isGreaterThanOrEqualTo(1);
        assertThat(custodyService.getVoucher(voucherNo).status()).isEqualTo("SEALED");
        Integer disputeCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM voucher_dispute WHERE voucher_no=?", Integer.class, voucherNo);
        assertThat(disputeCount).isZero();
    }
}
