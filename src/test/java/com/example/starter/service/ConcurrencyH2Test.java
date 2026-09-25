package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.domain.ConflictException;
import com.example.starter.persistence.VoucherPO;
import com.example.starter.persistence.VoucherRepository;
import com.example.starter.web.dto.ConfirmRequest;
import com.example.starter.web.dto.DisputeRequest;
import com.example.starter.web.dto.VoucherResponse;
import com.example.starter.web.dto.VoucherSubmitRequest;

/**
 * H2 真实并发测试：封账/争议条件更新互斥、同键并发提交幂等、并发争议唯一受理。
 */
class ConcurrencyH2Test extends AbstractH2IntegrationTest {

    @Autowired
    private VoucherRepository voucherRepository;
    @Autowired
    private TransactionTemplate txTemplate;

    /** 仓储层：同一 EFFECTIVE 行上并发“封账”和“置争议”，恰一方成功，终态唯一。 */
    @Test
    void concurrentSealAndDisputeConditionalUpdatesProduceSingleWinner() throws Exception {
        VoucherResponse voucher = effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");

        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Integer> sealRows = new AtomicReference<>();
        AtomicReference<Integer> disputeRows = new AtomicReference<>();
        Instant now = Instant.now(clock);
        try {
            Future<?> f1 = pool.submit(() -> txTemplate.executeWithoutResult(s -> {
                await(barrier);
                sealRows.set(voucherRepository.seal(voucher.voucherNo(), now, now));
            }));
            Future<?> f2 = pool.submit(() -> txTemplate.executeWithoutResult(s -> {
                await(barrier);
                disputeRows.set(voucherRepository.compareAndUpdateStatus(voucher.voucherNo(),
                        "EFFECTIVE", "DISPUTED", now));
            }));
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        int wins = sealRows.get() + disputeRows.get();
        assertThat(wins).as("两个条件更新中恰有一个成功").isEqualTo(1);
        VoucherPO finalState = voucherRepository.findByNo(voucher.voucherNo()).orElseThrow();
        if (sealRows.get() == 1) {
            assertThat(finalState.status()).isEqualTo("SEALED");
        } else {
            assertThat(finalState.status()).isEqualTo("DISPUTED");
        }
    }

    /** 服务层：争议期内两个并发争议，只受理一个，凭证唯一进入 DISPUTED。 */
    @Test
    void concurrentDisputesOnlyOneAccepted() throws Exception {
        VoucherResponse voucher = effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> failure1 = new AtomicReference<>();
        AtomicReference<Throwable> failure2 = new AtomicReference<>();
        try {
            Future<?> f1 = pool.submit(() -> {
                await(barrier);
                try {
                    service.dispute(new DisputeRequest("req-dispute-1", voucher.voucherNo(), "A",
                            "争议一"));
                } catch (ConflictException e) {
                    failure1.set(e);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                await(barrier);
                try {
                    service.dispute(new DisputeRequest("req-dispute-2", voucher.voucherNo(), "B",
                            "争议二"));
                } catch (ConflictException e) {
                    failure2.set(e);
                }
            });
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(voucher.voucherNo())).isEqualTo("DISPUTED");
        assertThat(count("SELECT COUNT(*) FROM dispute WHERE voucher_no = ? AND status = 'OPEN'",
                voucher.voucherNo())).isEqualTo(1);
        long conflicts = java.util.stream.Stream.of(failure1.get(), failure2.get())
                .filter(java.util.Objects::nonNull).count();
        assertThat(conflicts).as("两个并发争议中恰有一个被拒绝").isEqualTo(1);
    }

    /** 服务层：逾期后两个并发封账，均得到 SEALED，封账只发生一次且幂等。 */
    @Test
    void concurrentSealsAreIdempotentAndSingleSealedState() throws Exception {
        VoucherResponse voucher = effectiveVoucher("req-submit-1", "req-conf-a", "req-conf-b");
        clock.setInstant(Instant.parse("2026-09-19T00:00:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                await(barrier);
                service.seal(voucher.voucherNo(), "req-seal-1");
            });
            Future<?> f2 = pool.submit(() -> {
                await(barrier);
                service.seal(voucher.voucherNo(), "req-seal-2");
            });
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(voucher.voucherNo())).isEqualTo("SEALED");
        assertThat(count("SELECT COUNT(*) FROM voucher WHERE voucher_no = ? AND sealed_at IS NOT NULL",
                voucher.voucherNo())).isEqualTo(1);
    }

    /** 同 requestId 同参并发提交：只生成一张凭证，两个请求返回同一凭证号。 */
    @Test
    void concurrentSameRequestIdSubmitCreatesSingleVoucher() throws Exception {
        createStation();
        seedDayReadings();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> no1 = new AtomicReference<>();
        AtomicReference<String> no2 = new AtomicReference<>();
        try {
            Future<?> f1 = pool.submit(() -> {
                await(barrier);
                no1.set(service.submitVoucher(
                        new VoucherSubmitRequest("race-key", STATION, GAS_DAY)).body().voucherNo());
            });
            Future<?> f2 = pool.submit(() -> {
                await(barrier);
                no2.set(service.submitVoucher(
                        new VoucherSubmitRequest("race-key", STATION, GAS_DAY)).body().voucherNo());
            });
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(no1.get()).isEqualTo(no2.get());
        assertThat(count("SELECT COUNT(*) FROM voucher")).isEqualTo(1);
    }

    /** A、B 双方几乎同时确认：最终唯一生效，只写一次生效时刻。 */
    @Test
    void concurrentBothPartyConfirmationsEndEffectiveOnce() throws Exception {
        createStation();
        seedDayReadings();
        VoucherResponse voucher = submit("req-submit-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                await(barrier);
                service.confirm(new ConfirmRequest("req-conf-a", voucher.voucherNo(), "A", "张三"));
            });
            Future<?> f2 = pool.submit(() -> {
                await(barrier);
                service.confirm(new ConfirmRequest("req-conf-b", voucher.voucherNo(), "B", "李四"));
            });
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        VoucherPO finalState = voucherRepository.findByNo(voucher.voucherNo()).orElseThrow();
        assertThat(finalState.status()).isEqualTo("EFFECTIVE");
        assertThat(finalState.partyAConfirmed()).isTrue();
        assertThat(finalState.partyBConfirmed()).isTrue();
        assertThat(finalState.effectiveAt()).isNotNull();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
