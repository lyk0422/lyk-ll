package com.example.starter.carbon;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.service.SurrenderRuleException;
import com.example.starter.carbon.service.SurrenderService;
import com.example.starter.carbon.web.SurrenderRequest;
import com.example.starter.carbon.web.SurrenderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一企业同一履约年度并发清缴的 H2 事务测试：
 * 串行生效、无合计超额抵减、无重复扣减；同键并发重放只扣一次。
 */
class SurrenderConcurrencyTest extends AbstractCarbonIntegrationTest {

    private static final String ENT = "E-CONC";
    private static final int YEAR = 2025;
    private static final String DEADLINE = "2026-06-30T16:00:00Z";

    @Autowired
    private SurrenderService service;

    /** 并发执行结果：成功响应或失败错误码二选一。 */
    private record Outcome(SurrenderResponse response, String errorCode) {
        boolean retired() {
            return response != null && "RETIRED".equals(response.status());
        }
    }

    @Test
    @DisplayName("并发6笔（每笔150配额+10抵消）：恰好5笔串行生效，第6笔超5%上限拒绝，无重复扣减")
    void concurrentRequestsSerializeWithoutExceedingOffsetCap() throws Exception {
        freezeAt("2026-01-01T00:00:00Z");
        long entId = createEnterprise(ENT, "并发测试企业");
        createAssessment(entId, YEAR, "1000.000", DEADLINE);
        // 配额充足：三批共 1200；抵消仅 50。
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "CA-A", "400",
                "2025-01-01", "2026-12-31");
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "CA-B", "400",
                "2025-02-01", "2027-06-30");
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "CA-C", "400",
                "2025-03-01", "2028-06-30");
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, entId, "CO-A", "50",
                "2025-01-01", "2026-12-31");

        List<Outcome> outcomes = runConcurrently(6, i -> service.submit(new SurrenderRequest(
                "CREQ-" + (i + 1), ENT, YEAR,
                new BigDecimal("150.000"), new BigDecimal("10.000"))));

        long retired = outcomes.stream().filter(Outcome::retired).count();
        List<String> errorCodes = outcomes.stream().filter(o -> o.errorCode() != null)
                .map(Outcome::errorCode).toList();
        assertThat(retired).isEqualTo(5);
        assertThat(errorCodes).containsExactly("OFFSET_CAP_EXCEEDED");

        // 串行生效后的最终账：配额扣 750、抵消扣 50，无批次被扣成负数，无重复扣减。
        assertThat(ledger.remainingAmount("ALLOWANCE", "CA-A")).isEqualByComparingTo("0");
        assertThat(ledger.remainingAmount("ALLOWANCE", "CA-B")).isEqualByComparingTo("50");
        assertThat(ledger.remainingAmount("ALLOWANCE", "CA-C")).isEqualByComparingTo("400");
        assertThat(ledger.remainingAmount("OFFSET", "CO-A")).isEqualByComparingTo("0");
        assertThat(ledger.batchStatus("OFFSET", "CO-A")).isEqualTo("RETIRED");
        // 累计 800 < 1000，年度保持 PENDING；5% 抵消恰好⽤满合法。
        assertThat(ledger.assessmentStatus(entId, YEAR)).isEqualTo("PENDING");

        var sums = jdbc.queryForMap(
                "SELECT (SELECT COALESCE(SUM(amount),0) FROM retirement_record WHERE account_type='ALLOWANCE') "
                        + "AS allowance_sum, "
                        + "(SELECT COALESCE(SUM(amount),0) FROM retirement_record WHERE account_type='OFFSET') "
                        + "AS offset_sum");
        assertThat((BigDecimal) sums.get("allowance_sum")).isEqualByComparingTo("750");
        assertThat((BigDecimal) sums.get("offset_sum")).isEqualByComparingTo("50");
        // 恰好 5 笔成功申请落库。
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM surrender_request WHERE enterprise_id = " + entId, Integer.class);
        assertThat(requestCount).isEqualTo(5);
    }

    @Test
    @DisplayName("同requestId同参4线程并发：仅一笔真实注销，其余重放同一凭证，只扣一次")
    void concurrentSameRequestIdDeductsOnce() throws Exception {
        freezeAt("2026-01-01T00:00:00Z");
        long entId = createEnterprise(ENT + "-2", "并发重放企业");
        createAssessment(entId, YEAR, "100.000", DEADLINE);
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "SA-A", "100",
                "2025-01-01", "2026-12-31");
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, entId, "SO-A", "5",
                "2025-01-01", "2026-12-31");

        String entCode = jdbc.queryForObject(
                "SELECT code FROM enterprise WHERE id = ?", String.class, entId);
        List<Outcome> outcomes = runConcurrently(4, i -> service.submit(new SurrenderRequest(
                "SAME-IDEM", entCode, YEAR,
                new BigDecimal("95.000"), new BigDecimal("5.000"))));

        assertThat(outcomes).allMatch(Outcome::retired);
        // 四笔响应的凭证号完全相同、清单规模相同（重放首次结果）。
        String expectedCertNo = outcomes.get(0).response().certificateNo();
        assertThat(expectedCertNo).isNotBlank();
        assertThat(outcomes).extracting(o -> o.response().certificateNo())
                .containsOnly(expectedCertNo);
        for (Outcome outcome : outcomes) {
            assertThat(outcome.response().retirements()).hasSize(2);
        }

        assertThat(ledger.remainingAmount("ALLOWANCE", "SA-A")).isEqualByComparingTo("5");
        assertThat(ledger.remainingAmount("OFFSET", "SO-A")).isEqualByComparingTo("0");
        assertThat(ledger.assessmentStatus(entId, YEAR)).isEqualTo("SURRENDERED");
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM surrender_request WHERE request_id = 'SAME-IDEM'", Integer.class);
        assertThat(requestCount).isEqualTo(1);
        Integer certificateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM surrender_certificate WHERE request_id = 'SAME-IDEM'", Integer.class);
        assertThat(certificateCount).isEqualTo(1);
    }

    @Test
    @DisplayName("截止扫描与清缴在核定行上互斥：清缴持锁提交为SURRENDERED后，过期扫描不覆盖结果")
    void overdueScanWaitsOnSurrenderRowLockAndKeepsSurrendered() throws Exception {
        freezeAt(DEADLINE);
        long entId = createEnterprise(ENT + "-RACE", "截止竞争企业");
        createAssessment(entId, YEAR, "100.000", DEADLINE);
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "RACE-AL", "100",
                "2025-01-01", "2026-12-31");

        CountDownLatch surrenderHoldsLock = new CountDownLatch(1);
        CountDownLatch scanStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 线程A：在事务内持有核定行 FOR UPDATE 锁（模拟清缴受理进行中），
        // 等扫描线程发起更新后，再将状态置为 SURRENDERED 并提交。
        Thread surrender = new Thread(() -> {
            try {
                new org.springframework.transaction.support.TransactionTemplate(txManager)
                        .executeWithoutResult(tx -> {
                            var assessment = ledger.findAssessment(entId, YEAR, true);
                            surrenderHoldsLock.countDown();
                            try {
                                assertThat(scanStarted.await(10, TimeUnit.SECONDS)).isTrue();
                                // 给扫描 UPDATE 留出时间真正阻塞在行锁上。
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            assertThat(ledger.markSurrendered(assessment.id())).isEqualTo(1);
                        });
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "surrender-tx");

        // 线程B：时钟拨到截止时刻之后10秒发起扫描；UPDATE 必须等A提交，届时状态已是
        // SURRENDERED，条件 status='PENDING' 不匹配，标记0行，唯一结果保持“已清缴”。
        Thread scan = new Thread(() -> {
            try {
                assertThat(surrenderHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();
                scanStarted.countDown();
                freezeAt("2026-06-30T16:00:10Z");
                assertThat(service.markOverdueAssessments()).isZero();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "deadline-scan");

        surrender.start();
        scan.start();
        scan.join(15000);
        surrender.join(15000);
        assertThat(surrender.isAlive()).isFalse();
        assertThat(scan.isAlive()).isFalse();
        if (failure.get() != null) {
            throw new AssertionError("截止竞争测试线程异常", failure.get());
        }
        assertThat(ledger.assessmentStatus(entId, YEAR)).isEqualTo("SURRENDERED");
    }

    /**
     * 使用发令闸协调 threads 个真实并发任务，超时30秒收集每个任务的成功响应或业务错误码；
     * 出现非预期异常类型时测试失败，不靠日志或休眠判定。
     */
    private List<Outcome> runConcurrently(int threads,
                                          java.util.function.IntFunction<SurrenderResponse> action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = IntStream.range(0, threads)
                    .<Callable<Outcome>>mapToObj(i -> () -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        try {
                            return new Outcome(action.apply(i), null);
                        } catch (SurrenderRuleException ex) {
                            return new Outcome(null, ex.getCode());
                        }
                    })
                    .map(pool::submit)
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Outcome> outcomes = new java.util.ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
