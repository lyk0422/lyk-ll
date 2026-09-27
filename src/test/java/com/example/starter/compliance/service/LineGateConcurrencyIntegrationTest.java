package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.CleaningCertificateRow;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.ProductionLineRow;
import java.time.Instant;
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
 * 受控量产并发真实多线程 H2 测试：
 * 两笔量产并发最多一笔使用同一线路版本；备案暂停/注销与量产竞态存在唯一提交顺序，
 * 失败不核销凭证；同一 requestId 并发仅落一条生产记录并重放首次结果。
 */
class LineGateConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private LineGateService gateService;

    @Test
    void concurrentProductionsForSameLineVersionAcceptExactlyOne() throws Exception {
        setInstant(T0);
        gateService.registerLine(Fixtures.line("LC"));
        Product a = product("PC-A", "MILK");
        Product b = product("PC-B", "EGG");

        List<Outcome> outcomes = runRace(List.of(
                () -> gateService.acceptControlledProduction(Fixtures.controlled("LC", 1,
                        a.filingId(), null, "BC-A", "100", T0.toEpochMilli())),
                () -> gateService.acceptControlledProduction(Fixtures.controlled("LC", 1,
                        b.filingId(), null, "BC-B", "100", T0.toEpochMilli()))));

        long success = outcomes.stream().filter(Outcome::success).count();
        long conflict = outcomes.stream().filter(o -> !o.success()
                && o.code().equals("LINE_VERSION_CONFLICT")).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        ProductionLineRow line = gateRepository.findProductionLine("LC").orElseThrow();
        assertThat(line.occupancyVersion()).isEqualTo(2);
        assertThat(gateRepository.findLineProductionRecords("LC")).hasSize(1);
    }

    @Test
    void concurrentProductionsCompetingForCertificatesConsumeAtMostOne() throws Exception {
        setInstant(T0);
        gateService.registerLine(Fixtures.line("LC2"));
        Product milkEgg = product("PC2-A", "MILK", "EGG");
        Product milkOnly = product("PC2-B", "MILK");
        // 首产后线路占用版本为 2，最近配方含 MILK、EGG；切到仅含 MILK 需覆盖 EGG 的凭证。
        gateService.acceptControlledProduction(Fixtures.controlled("LC2", 1, milkEgg.filingId(),
                null, "B0", "100", T0.toEpochMilli()));
        long certA = gateService.confirmCleaning(Fixtures.cleaning("LC2", "op1", "rv1",
                T0.toEpochMilli(), List.of("EGG"))).body().certificateId();
        long certB = gateService.confirmCleaning(Fixtures.cleaning("LC2", "op2", "rv2",
                T0.toEpochMilli() + 1, List.of("EGG"))).body().certificateId();

        List<Outcome> outcomes = runRace(List.of(
                () -> gateService.acceptControlledProduction(Fixtures.controlled("LC2", 2,
                        milkOnly.filingId(), certA, "B-A", "100", T0.toEpochMilli() + 60_000)),
                () -> gateService.acceptControlledProduction(Fixtures.controlled("LC2", 2,
                        milkOnly.filingId(), certB, "B-B", "100", T0.toEpochMilli() + 60_000))));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(gateRepository.findLineProductionRecords("LC2")).hasSize(2);
        ProductionLineRow line = gateRepository.findProductionLine("LC2").orElseThrow();
        assertThat(line.occupancyVersion()).isEqualTo(3);
        // 失败方凭证不能被消耗（无生产记录引用）；先提交方按"后续生产先提交即作废"规则将其置 SUPERSEDED。
        List<CleaningCertificateRow> certs = List.of(
                gateRepository.findCertificateById(certA).orElseThrow(),
                gateRepository.findCertificateById(certB).orElseThrow());
        assertThat(certs).extracting(CleaningCertificateRow::status)
                .containsExactlyInAnyOrder("CONSUMED", "SUPERSEDED");
        assertThat(gateRepository.findLineProductionRecords("LC2").stream()
                .filter(r -> r.certificateId() != null)).hasSize(1);
    }

    @Test
    void cancellationRacesWithProductionInUniqueCommitOrder() throws Exception {
        setInstant(T0);
        gateService.registerLine(Fixtures.line("LC3"));
        Product milkEgg = product("PC3-A", "MILK", "EGG");

        List<Outcome> outcomes = runRace(List.of(
                () -> gateService.acceptControlledProduction(Fixtures.controlled("LC3", 1,
                        milkEgg.filingId(), null, "BR", "100", T0.toEpochMilli())),
                () -> complianceService.cancelFiling(Fixtures.filingRef(milkEgg.filingId()))));

        // 两个操作都给出确定结果；量产要么先提交（后被注销，存在 1 条记录），要么被先注销拒绝（0 条）。
        Outcome production = outcomes.get(0);
        long recordCount = gateRepository.findLineProductionRecords("LC3").size();
        if (production.success()) {
            assertThat(recordCount).isEqualTo(1);
        } else {
            assertThat(production.code()).isEqualTo("FILING_CANCELLED");
            assertThat(recordCount).isZero();
        }
        // 注销最终必然生效；若量产先提交，线路推进到 2；否则停在 1。
        FilingRow filing = repository.findFilingById(milkEgg.filingId()).orElseThrow();
        assertThat(filing.status()).isEqualTo("CANCELLED");
        int expectedVersion = production.success() ? 2 : 1;
        assertThat(gateRepository.findProductionLine("LC3").orElseThrow().occupancyVersion())
                .isEqualTo(expectedVersion);
        // 历史量产记录不被注销改动。
        assertThat(gateRepository.findLineProductionRecords("LC3")).hasSize((int) recordCount);
    }

    @Test
    void suspensionRacesWithProductionInUniqueCommitOrder() throws Exception {
        setInstant(T0);
        gateService.registerLine(Fixtures.line("LC4"));
        Product milkEgg = product("PC4-A", "MILK");

        List<Outcome> outcomes = runRace(List.of(
                () -> gateService.acceptControlledProduction(Fixtures.controlled("LC4", 1,
                        milkEgg.filingId(), null, "BS", "100", T0.toEpochMilli())),
                () -> {
                    // 与巡检/复审失败相同的暂停路径：独立事务暂停并释放有效位。
                    FilingRow row = repository.findFilingById(milkEgg.filingId()).orElseThrow();
                    springContext.getBean(FilingTransitions.class)
                            .suspendOverdueNewTransaction(row);
                    return null;
                }));

        Outcome production = outcomes.get(0);
        long recordCount = gateRepository.findLineProductionRecords("LC4").size();
        FilingRow filing = repository.findFilingById(milkEgg.filingId()).orElseThrow();
        assertThat(filing.status()).isEqualTo("SUSPENDED");
        if (production.success()) {
            assertThat(recordCount).isEqualTo(1);
            assertThat(gateRepository.findProductionLine("LC4").orElseThrow().occupancyVersion())
                    .isEqualTo(2);
        } else {
            assertThat(production.code()).isEqualTo("FILING_SUSPENDED");
            assertThat(recordCount).isZero();
            assertThat(gateRepository.findProductionLine("LC4").orElseThrow().occupancyVersion())
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentSameRequestIdProducesExactlyOneRecordAndReplays() throws Exception {
        setInstant(T0);
        gateService.registerLine(Fixtures.line("LC5"));
        Product milk = product("PC5-A", "MILK");
        String key = Fixtures.requestId();

        List<Outcome> outcomes = runRace(List.of(
                () -> gateService.acceptControlledProduction(Fixtures.controlled(key, "LC5", 1,
                        milk.filingId(), null, "BK", "100", T0.toEpochMilli())),
                () -> gateService.acceptControlledProduction(Fixtures.controlled(key, "LC5", 1,
                        milk.filingId(), null, "BK", "100", T0.toEpochMilli()))));

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.success()).isTrue();
            assertThat(production(o).body().recordId()).isNotNull();
        });
        long firstId = production(outcomes.get(0)).body().recordId();
        long secondId = production(outcomes.get(1)).body().recordId();
        assertThat(firstId).isEqualTo(secondId);
        AtomicInteger replayed = new AtomicInteger();
        outcomes.forEach(o -> {
            if (production(o).replayed()) {
                replayed.incrementAndGet();
            }
        });
        assertThat(replayed.get()).isEqualTo(1);
        assertThat(gateRepository.findLineProductionRecords("LC5")).hasSize(1);
        assertThat(gateRepository.findProductionLine("LC5").orElseThrow().occupancyVersion())
                .isEqualTo(2);
    }

    // ---------------- 辅助 ----------------

    @Autowired
    private org.springframework.context.ApplicationContext springContext;

    private record Outcome(boolean success, String code, Object value) {
    }

    private interface RaceCall {
        Object call();
    }

    /** 真实并发执行：所有线程就绪后同时放行，返回与入参同序的结果；业务异常转 success=false。 */
    private List<Outcome> runRace(List<RaceCall> calls) throws Exception {
        int threads = calls.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (RaceCall call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return new Outcome(true, null, call.call());
                } catch (ApiException e) {
                    return new Outcome(false, e.getCode(), null);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return outcomes;
    }

    @SuppressWarnings("unchecked")
    private static ServiceResult<Results.ControlledProductionView> production(Outcome outcome) {
        return (ServiceResult<Results.ControlledProductionView>) outcome.value();
    }

    private Product product(String productCode, String... allergens) {
        long formulaId = complianceService.submitFormula(Fixtures.formula(productCode, 1,
                List.of(Fixtures.additive("E300", "0.1")), List.of())).body().formulaId();
        complianceService.reviewFormula(Fixtures.ref(formulaId));
        complianceService.approveFormula(Fixtures.ref(formulaId));
        long filingId = complianceService.fileFiling(Fixtures.file(formulaId)).body().filingId();
        if (allergens.length > 0) {
            gateService.registerFormulaAllergens(
                    Fixtures.allergens(formulaId, 0, List.of(allergens)));
        }
        return new Product(formulaId, filingId);
    }

    private record Product(long formulaId, long filingId) {
    }
}
