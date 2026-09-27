package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 受控量产并发 H2 测试（真实多线程，非 mock）：
 * 同一线路同一占用版本两笔并发量产最多一笔成功；备案注销与量产竞态存在唯一提交顺序、不产生脏记录。
 */
class ClearanceConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private ClearanceService clearanceService;

    private long[] approvedFiledWithAllergen(String product, int version,
            List<String> allergens) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(product, version,
                List.of(Fixtures.additive("E202", "0.3")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        long filingId = complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
        clearanceService.registerFormulaAllergens(
                Fixtures.allergensReg(formulaId, null, allergens));
        return new long[] {formulaId, filingId};
    }

    @Test
    void concurrentProductionsOnSameOccupiedVersionLeaveExactlyOneRecord() throws Exception {
        long[] v1 = approvedFiledWithAllergen("P-CC", 1, List.of("AL_PEANUT"));
        clearanceService.registerLine(Fixtures.line("L-CC", "并发线"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (String batch : List.of("B-A", "B-B")) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    clearanceService.declareControlledProduction(Fixtures.controlled(
                            Fixtures.requestId(), "L-CC", v1[1], batch, "10", 1,
                            java.time.Instant.parse("2026-06-01T00:00:00Z").toEpochMilli(), null));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    // 败者：占用版本已被先提交事务推进，422 整事务回滚。
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
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
        // 仅一笔记录；占用版本恰好推进一次（1 → 2）；最近量产版本固定为该配方。
        assertThat(clearanceService.listControlledProductions("L-CC")).hasSize(1);
        assertThat(repository.findProductionLine("L-CC").orElseThrow().occupiedVersion())
                .isEqualTo(2);
        assertThat(repository.findProductionLine("L-CC").orElseThrow().lastFormulaId())
                .isEqualTo(v1[0]);
    }

    @Test
    void cancelRacingWithProductionHasSingleSerializationOrderAndNoDirtyRecord() throws Exception {
        long[] v1 = approvedFiledWithAllergen("P-CANCEL-RACE", 1, List.of("AL_PEANUT"));
        clearanceService.registerLine(Fixtures.line("L-CR", "竞态线"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        // 线程 1：注销备案；线程 2：受控量产受理。两者由备案行锁决出唯一提交顺序。
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            complianceService.cancelFiling(Fixtures.filingRef(v1[1]));
            cancelled.incrementAndGet();
            return null;
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                clearanceService.declareControlledProduction(Fixtures.controlled(
                        Fixtures.requestId(), "L-CR", v1[1], "B-RACE", "10", 1,
                        java.time.Instant.parse("2026-06-01T00:00:00Z").toEpochMilli(), null));
                produced.incrementAndGet();
            } catch (ApiException e) {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getCode()).isEqualTo("FILING_NOT_EFFECTIVE");
            }
            return null;
        }));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(cancelled.get()).isEqualTo(1);
        // 唯一顺序：要么注销先（量产拒绝、零记录），要么量产先（恰一记录，随后注销）。
        assertThat(produced.get()).isBetween(0, 1);
        assertThat(clearanceService.listControlledProductions("L-CR")).hasSize(produced.get());
        assertThat(complianceService.getFiling(v1[1]).status()).isEqualTo("CANCELLED");
    }
}
