package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.AbstractIntegrationTest;
import com.example.starter.compliance.Fixtures;
import com.example.starter.compliance.repo.FilingRow;
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
 * 并发真实多线程 H2 测试：同一产品同时刻只允许一个有效备案版本，并发备案仅一方成功。
 */
class FilingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService service;

    @Test
    void concurrentFilingsForSameProductLeaveExactlyOneActiveVersion() throws Exception {
        // 同一产品两个已批准版本（均未备案），随后并发备案，唯一有效位必须仅一方占用成功。
        long v1 = approve("P-CONCUR", 1);
        long v2 = approve("P-CONCUR", 2);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (Long formulaId : List.of(v1, v2)) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.fileFiling(Fixtures.file(formulaId));
                    success.incrementAndGet();
                } catch (RuntimeException e) {
                    // 唯一有效位冲突：active_filing 主键约束触发，整事务回滚。
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

        // 最终数据：仅一个有效备案版本，败者事务整体回滚不留备案行。
        List<FilingRow> effective =
                repository.findEffectiveFilingsByProduct("P-CONCUR");
        assertThat(effective).hasSize(1);
        assertThat(repository.findFilingsByProduct("P-CONCUR")).hasSize(1);
        assertThat(effective.get(0).status()).isEqualTo("ACTIVE");
    }

    private long approve(String productCode, int version) {
        long formulaId = service.submitFormula(Fixtures.formula(productCode, version,
                List.of(Fixtures.additive("E202", "0.2")), List.of())).body().formulaId();
        service.reviewFormula(Fixtures.ref(formulaId));
        service.approveFormula(Fixtures.ref(formulaId));
        return formulaId;
    }
}
