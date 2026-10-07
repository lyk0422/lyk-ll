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
import org.springframework.http.HttpStatus;

/**
 * 返工料真实多线程 H2 测试：
 * 两批在不同线路争同一返工料时不得超用（行锁 + 条件扣减，恰有一笔成功、整笔零扣）；
 * 并发登记同一料号恰有一方成功。
 */
class ReworkConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long T0 = 1_800_000_000_000L;

    @Autowired
    private LineControlService lineService;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private ReworkService reworkService;

    private long approvedFiling(String productCode, int version) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, version,
                List.of(Fixtures.additive("E300", "0.2")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        return complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
    }

    @Test
    void concurrentBatchesCompetingForSameMaterialNeverOverConsume() throws Exception {
        // 来源量产记录 → 返工料 RW-C 可用 100 公斤。
        long sourceFiling = approvedFiling("P-CSRC", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-CSRC",
                1, List.of("ALG_MILK"), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-CSRC", "来源线",
                null, null, T0));
        long recordId = lineService.controlledProduction(Fixtures.controlled(
                Fixtures.requestId(), sourceFiling, "L-CSRC", 1, "B-CSRC", "50", null,
                T0 + 1000)).body().productionRecordId();
        reworkService.registerRework(Fixtures.rework(Fixtures.requestId(), "RW-C", recordId,
                "100", 0, T0 + 2000));

        // 两条线路、两个备案，均声明乳；各产 300 公斤并投 60 公斤返工料（恰等 20%），
        // 两笔合计 120 > 100 可用额度，恰有一笔成功。
        long filingA = approvedFiling("P-CA", 1);
        long filingB = approvedFiling("P-CB", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-CA",
                1, List.of("ALG_MILK"), 0, T0));
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-CB",
                1, List.of("ALG_MILK"), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-CA", "竞态线A",
                null, null, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-CB", "竞态线B",
                null, null, T0));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            long filingId = i == 0 ? filingA : filingB;
            String lineCode = i == 0 ? "L-CA" : "L-CB";
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    lineService.controlledProduction(Fixtures.controlledWithRework(
                            Fixtures.requestId(), filingId, lineCode, 1, "B-" + lineCode,
                            "300", null, T0 + 3000,
                            List.of(Fixtures.reworkUse("RW-C", "60"))));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("REWORK_BALANCE_INSUFFICIENT");
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

        // 不得超用：恰一笔成功，累计消费 60、余额 40，失败方整笔零扣。
        assertThat(success.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        ReworkResults.ReworkMaterialView material = reworkService.getMaterial("RW-C");
        assertThat(material.consumedKg()).isEqualTo("60");
        assertThat(material.balanceKg()).isEqualTo("40");
        assertThat(reworkService.getConsumptions("RW-C").consumptions()).hasSize(1);
        int records = lineService.getLineProductions("L-CA").productions().size()
                + lineService.getLineProductions("L-CB").productions().size();
        assertThat(records).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationOfSameMaterialCodeSucceedsExactlyOnce() throws Exception {
        long filingA = approvedFiling("P-RA1", 1);
        long filingB = approvedFiling("P-RB1", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-RA1",
                1, List.of("ALG_MILK"), 0, T0));
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-RB1",
                1, List.of("ALG_EGG"), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-RA1", "来源线A",
                null, null, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-RB1", "来源线B",
                null, null, T0));
        long recordA = lineService.controlledProduction(Fixtures.controlled(
                Fixtures.requestId(), filingA, "L-RA1", 1, "B-RA", "10", null, T0 + 1000))
                .body().productionRecordId();
        long recordB = lineService.controlledProduction(Fixtures.controlled(
                Fixtures.requestId(), filingB, "L-RB1", 1, "B-RB", "10", null, T0 + 1000))
                .body().productionRecordId();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            long recordId = i == 0 ? recordA : recordB;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    reworkService.registerRework(Fixtures.rework(Fixtures.requestId(), "RW-RACE",
                            recordId, "10", 0, T0 + 2000));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("REWORK_EXISTS");
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
        assertThat(reworkService.getMaterial("RW-RACE").version()).isEqualTo(1);
    }
}
