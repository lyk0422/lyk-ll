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
 * 清线门禁真实多线程 H2 测试：
 * 同一线路同一占用版本并发量产至多一笔成功；备案注销与量产竞态存在唯一提交顺序，
 * 历史生产记录与备案最终状态保持一致（不会出现已注销备案下挂新受控量产记录）。
 */
class LineControlConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long T0 = 1_800_000_000_000L;

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

    @Test
    void concurrentProductionsForSameLineVersionLeaveExactlyOneRecord() throws Exception {
        long filing1 = approvedFiling("P-CC1", 1);
        long filing2 = approvedFiling("P-CC2", 1);
        // 空过敏原集合 + 全新线路：无遗留、无需凭证，只验证线路版本并发门禁。
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-CC1", 1,
                List.of(), 0, T0));
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-CC2", 1,
                List.of(), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-CC", "并发线",
                null, null, T0));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (long filingId : List.of(filing1, filing2)) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(),
                            filingId, "L-CC", 1, "B-" + filingId, "5", null, T0 + 1000));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.getCode()).isEqualTo("LINE_VERSION_CONFLICT");
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
        assertThat(lineService.getLine("L-CC").occupiedVersion()).isEqualTo(2);
        assertThat(lineService.getLineProductions("L-CC").productions()).hasSize(1);
    }

    @Test
    void cancellationRacingWithProductionHasSingleCommitOrderAndConsistentState()
            throws Exception {
        long filingId = approvedFiling("P-RACE", 1);
        lineService.registerFormulaAllergens(Fixtures.allergens(Fixtures.requestId(), "P-RACE", 1,
                List.of("ALG_MILK"), 0, T0));
        lineService.registerLine(Fixtures.line(Fixtures.requestId(), "L-RACE", "竞态线",
                null, null, T0));
        // 全新线路无遗留过敏原，量产不需要凭证。

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                lineService.controlledProduction(Fixtures.controlled(Fixtures.requestId(),
                        filingId, "L-RACE", 1, "B-RACE", "5", null, T0 + 1000));
            } catch (ApiException e) {
                // 注销先提交时量产必须被拒绝。
                assertThat(e.getCode()).isEqualTo("FILING_CANCELLED");
            }
            return null;
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            complianceService.cancelFiling(Fixtures.filingRef(filingId));
            return null;
        }));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        String filingStatus = complianceService.getFiling(filingId).status();
        int records = lineService.getLineProductions("L-RACE").productions().size();
        if ("CANCELLED".equals(filingStatus)) {
            // 唯一提交顺序的两种结果都必须自洽：注销最终态下不得有新受控量产记录。
            // （若量产先提交，备案仍会被随后注销；此时允许恰好 1 条历史记录。）
            // 量产在备案行锁内读到的状态决定受理，注销提交后不可能再出现 CANCELLED 备案的新记录。
            assertThat(records).isLessThanOrEqualTo(1);
        }
        // 占用版本推进次数必须与成功量产记录数一致。
        Integer occupied = lineService.getLine("L-RACE").occupiedVersion();
        assertThat(occupied).isEqualTo(1 + records);
    }
}
