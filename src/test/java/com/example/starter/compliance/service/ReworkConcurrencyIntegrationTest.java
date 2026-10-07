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
 * 返工料真实多线程 H2 并发测试：
 * 两批争同料不得超用（恰好一笔成功，余额与版本一致）；
 * 备案撤销与投返工料量产按同一事务次序判定，最终状态自洽。
 */
class ReworkConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String MILK = "ALG_MILK";

    @Autowired
    private LineControlService lineService;

    @Autowired
    private ComplianceService complianceService;

    private long approvedFiling(String productCode, int version) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, version,
                List.of(Fixtures.additive("E300", "0.2")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        return complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
    }

    /** 来源量产记录 + 返工料登记，返回料号。 */
    private String registeredLot(String lotCode, String productCode, String lineCode,
            String availableKg) {
        long filingId = approvedFiling(productCode, 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), productCode,
                1, List.of(MILK), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), lineCode, lineCode + "-线",
                null, null, T0));
        long recordId = lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(),
                filingId, lineCode, 1, "B-SRC", "50", null, T0 + 1000))
                .body().productionRecordId();
        lineService.registerReworkLot(Fixtures.reworkLot(Fixtures.requestId(), lotCode, recordId,
                productCode, availableKg, T0));
        return lotCode;
    }

    @Test
    void concurrentConsumptionOfSameLotNeverExceedsQuota() throws Exception {
        registeredLot("RW-CC", "P-CC-SRC", "L-CC-SRC", "10");
        // 目标配方声明乳；两条全新线路各提交一笔 8 公斤投料（合计 16 > 10）。
        long targetFiling = approvedFiling("P-CC-T", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-CC-T",
                1, List.of(MILK), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-CC-A", "并发线A",
                null, null, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-CC-B", "并发线B",
                null, null, T0));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (String lineCode : List.of("L-CC-A", "L-CC-B")) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    lineService.controlledProduction(Fixtures.controlledWithRework(
                            Fixtures.requestId(), targetFiling, lineCode, 1, "B-" + lineCode,
                            "100", null, T0 + 2000,
                            List.of(Fixtures.reworkInput("RW-CC", "8"))));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.getCode()).isEqualTo("REWORK_QUOTA_INSUFFICIENT");
                    insufficient.incrementAndGet();
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

        // 两批争同料：恰好一笔成功，消费 8 不超可用 10，料账版本只加 1。
        assertThat(success.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        LineControlResults.ReworkLotLedgerView ledger = lineService.getReworkLotLedger("RW-CC");
        assertThat(ledger.lot().consumedKg()).isEqualTo("8");
        assertThat(ledger.lot().balanceKg()).isEqualTo("2");
        assertThat(ledger.lot().version()).isEqualTo(2);
        assertThat(ledger.consumptions()).hasSize(1);
    }

    @Test
    void cancellationRacingWithReworkProductionHasConsistentFinalState() throws Exception {
        registeredLot("RW-RC", "P-RC-SRC", "L-RC-SRC", "10");
        long targetFiling = approvedFiling("P-RC-T", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-RC-T",
                1, List.of(MILK), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-RC", "竞态线",
                null, null, T0));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                lineService.controlledProduction(Fixtures.controlledWithRework(
                        Fixtures.requestId(), targetFiling, "L-RC", 1, "B-RC", "100", null,
                        T0 + 2000, List.of(Fixtures.reworkInput("RW-RC", "5"))));
            } catch (ApiException e) {
                // 注销先提交时量产必须被拒绝且零扣。
                assertThat(e.getCode()).isEqualTo("FILING_CANCELLED");
            }
            return null;
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            complianceService.cancelFiling(Fixtures.filingRef(targetFiling));
            return null;
        }));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 唯一事务次序：成功则消费 5 且版本 2、有一条量产记录；失败则零扣、无记录。
        int records = lineService.getLineProductions("L-RC").productions().size();
        LineControlResults.ReworkLotLedgerView ledger = lineService.getReworkLotLedger("RW-RC");
        if (records == 1) {
            assertThat(ledger.lot().consumedKg()).isEqualTo("5");
            assertThat(ledger.lot().version()).isEqualTo(2);
            assertThat(ledger.consumptions()).hasSize(1);
        } else {
            assertThat(ledger.lot().consumedKg()).isEqualTo("0");
            assertThat(ledger.lot().version()).isEqualTo(1);
            assertThat(ledger.consumptions()).isEmpty();
        }
        assertThat(lineService.getLine("L-RC").occupiedVersion()).isEqualTo(1 + records);
    }
}
