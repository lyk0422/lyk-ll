package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 受控量产并发 H2 真实多线程测试：
 * 两笔量产并发最多一笔使用同一线路占用版本；凭证核销不重复；
 * 备案注销与量产竞态存在唯一提交顺序且最终数据守恒。
 */
class LineConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService service;

    @Test
    void concurrentProductionsOnSameOccupancyVersionLeaveExactlyOneWinner() throws Exception {
        service.registerLine(Fixtures.line("L-C1"));
        long filingA = approvedAndFiled("P-C1-A", List.of());
        long filingB = approvedAndFiled("P-C1-B", List.of());

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (Long filingId : List.of(filingA, filingB)) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.controlledProduction(Fixtures.controlledProduction(
                            "L-C1", filingId, "B-C1-" + filingId, "5", 1, null));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.getCode()).isEqualTo("OCCUPANCY_VERSION_CONFLICT");
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(service.getLine("L-C1").occupancyVersion()).isEqualTo(2);
        assertThat(repository.countProductionRecords(filingA)
                + repository.countProductionRecords(filingB)).isEqualTo(1);
    }

    @Test
    void concurrentProductionsSharingVoucherConsumeItOnce() throws Exception {
        lineWithPreviousBatch("L-C2", List.of("MILK"));
        long filing = approvedAndFiled("P-C2-NEW", List.of());
        long voucherId = confirmVoucher("L-C2", List.of("MILK"), 2);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String batchNo = "B-C2-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.controlledProduction(Fixtures.controlledProduction(
                            "L-C2", filing, batchNo, "5", 2, voucherId));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    // 败者：占用版本已推进（409）；不得出现凭证重复核销类错误。
                    assertThat(e.getCode()).isEqualTo("OCCUPANCY_VERSION_CONFLICT");
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(service.getLine("L-C2").occupancyVersion()).isEqualTo(3);
        assertThat(repository.countProductionRecords(filing)).isEqualTo(1);
        assertThat(service.getVoucher(voucherId).status()).isEqualTo("CONSUMED");
    }

    @Test
    void cancelRacingWithProductionHasSingleSerializationOrder() throws Exception {
        // 重复若干轮，覆盖两种锁获取顺序：量产先提交成功 或 注销先提交量产被拒。
        for (int round = 0; round < 5; round++) {
            String lineCode = "L-C3-" + round;
            service.registerLine(Fixtures.line(lineCode));
            long filingId = approvedAndFiled("P-C3-" + round, List.of());

            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger productionOk = new AtomicInteger();
            AtomicInteger productionRejected = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();

            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.controlledProduction(Fixtures.controlledProduction(
                            lineCode, filingId, "B-C3", "5", 1, null));
                    productionOk.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.getCode()).isEqualTo("FILING_CANCELLED");
                    productionRejected.incrementAndGet();
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                service.cancelFiling(Fixtures.filingRef(filingId));
                return null;
            }));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // 唯一提交顺序：量产与注销恰有一个先发生，结果守恒。
            assertThat(productionOk.get() + productionRejected.get()).isEqualTo(1);
            assertThat(service.getFiling(filingId).status()).isEqualTo("CANCELLED");
            if (productionOk.get() == 1) {
                // 量产先提交：记录存在、占用版本已推进；注销随后终态。
                assertThat(repository.countProductionRecords(filingId)).isEqualTo(1);
                assertThat(service.getLine(lineCode).occupancyVersion()).isEqualTo(2);
            } else {
                // 注销先提交：量产拒绝，无记录、版本不推进。
                assertThat(repository.countProductionRecords(filingId)).isZero();
                assertThat(service.getLine(lineCode).occupancyVersion()).isEqualTo(1);
            }
        }
    }

    private void lineWithPreviousBatch(String lineCode, List<String> previousAllergens) {
        service.registerLine(Fixtures.line(lineCode));
        long prevFiling = approvedAndFiled("P-" + lineCode + "-PREV", previousAllergens);
        service.controlledProduction(
                Fixtures.controlledProduction(lineCode, prevFiling, "B-PREV", "10", 1, null));
    }

    private long confirmVoucher(String lineCode, List<String> allergens, int occupancyVersion) {
        return service.confirmCleaning(Fixtures.cleaning(lineCode, allergens,
                clock.nowMillis() - 60_000, occupancyVersion, "operator-1", "reviewer-1"))
                .body().voucherId();
    }

    private long approvedAndFiled(String productCode, List<String> allergens) {
        long formulaId = service.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        if (allergens != null) {
            service.upsertAllergens(Fixtures.allergens(formulaId, allergens, null));
        }
        return service.fileFiling(Fixtures.file(formulaId)).body().filingId();
    }
}
