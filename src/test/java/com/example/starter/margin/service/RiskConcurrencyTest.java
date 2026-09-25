package com.example.starter.margin.service;

import com.example.starter.margin.AbstractMarginH2Test;
import com.example.starter.margin.api.DepositRequest;
import com.example.starter.margin.api.DepositResponse;
import com.example.starter.margin.api.LiquidationResponse;
import com.example.starter.margin.api.RiskAssessmentResponse;
import com.example.starter.margin.domain.DepositResult;
import com.example.starter.margin.domain.JobStatus;
import com.example.starter.margin.po.JobPo;
import com.example.starter.margin.po.SnapshotPo;
import com.example.starter.margin.repo.RiskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 并发测试：入账与强平竞态由账户行锁串行化，先到者决定结果；
 * 同 requestId 并发仅入账一次；强平双重执行不产生重复指令。所有断言带超时。
 */
class RiskConcurrencyTest extends AbstractMarginH2Test {

    @Autowired
    private RiskService riskService;
    @Autowired
    private RiskRepository riskRepo;

    /**
     * 多轮并发：同一账户、同一时刻并发执行“达标入账”与“强平”。
     * 无论谁先，结果必须自洽，绝不允许既全额强平又保留足额保证金。
     */
    @Test
    void depositAndLiquidationRace_firstComerWins_invariantHolds() throws Exception {
        int iterations = 12;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String accountNo = "RACE-" + i;
                // 1080 / 1000 = 108%：直接 PENDING；320 元入账恰好补足到 140%
                setupAccount(accountNo, "1080.00", "CNY", new String[][]{{"L1", "1000.00"}});
                RiskAssessmentResponse assessed = riskService.assess(accountNo);
                long jobId = assessed.jobId();
                String requestId = "DEP-" + i;

                CountDownLatch start = new CountDownLatch(1);
                Future<DepositResponse> depositFuture = pool.submit(() -> {
                    start.await();
                    return riskService.deposit(new DepositRequest(requestId, accountNo,
                            new BigDecimal("320.00"), "CNY"));
                });
                Future<LiquidationResponse> liquidationFuture = pool.submit(() -> {
                    start.await();
                    return riskService.executeJob(jobId);
                });
                start.countDown();

                DepositResponse deposit = depositFuture.get(15, TimeUnit.SECONDS);
                LiquidationResponse liquidation = liquidationFuture.get(15, TimeUnit.SECONDS);

                JobPo job = riskRepo.findJobById(jobId);
                SnapshotPo snap = accountData.findLatestConfirmedSnapshot(accountNo);

                if (job.status() == JobStatus.CANCELLED) {
                    // 入账先到达标：撤销待发强平，负债分文未动，入账为普通 ACCEPTED
                    assertThat(deposit.result()).isEqualTo(DepositResult.ACCEPTED);
                    assertThat(deposit.jobCancelled()).isTrue();
                    assertThat(liquidation.status()).isEqualTo(JobStatus.CANCELLED.name());
                    assertThat(liquidation.items()).isEmpty();
                    assertThat(snap.totalAssets()).isEqualByComparingTo("1400.00");
                    assertThat(snap.totalLiabilities()).isEqualByComparingTo("1000.00");
                    assertThat(accountData.findOutstandingLiabilities(accountNo))
                            .hasSize(1);
                } else {
                    // 强平先到终态：迟到 320 计入余下负债重算，绝不是“全额强平+足额保证金并存”
                    assertThat(job.status()).isEqualTo(JobStatus.EXECUTED);
                    assertThat(deposit.result()).isEqualTo(DepositResult.APPLIED_TO_REMAINING);
                    assertThat(liquidation.status()).isEqualTo(JobStatus.EXECUTED.name());
                    // 强平单独结果：资产 280、负债 200（平 800）
                    assertThat(liquidation.finalRatio()).isEqualByComparingTo("1.4000");
                    // 迟到保证金计入后：资产 600、负债仍 200
                    assertThat(snap.totalAssets()).isEqualByComparingTo("600.00");
                    assertThat(snap.totalLiabilities()).isEqualByComparingTo("200.00");
                }

                // 资金守恒：最终资产 + 已偿还负债 = 初始资产 + 实际入账金额（1080 + 320）
                BigDecimal outstanding = accountData.findOutstandingLiabilities(accountNo).stream()
                        .map(l -> l.outstandingAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal repaid = new BigDecimal("1000.00").subtract(outstanding);
                assertThat(snap.totalAssets().add(repaid))
                        .isEqualByComparingTo("1400.00");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 同 requestId 同参并发：恰好一次 ACCEPTED，其余 REPLAYED，资产只增加一次。
     */
    @Test
    void concurrentSameRequestId_appliesExactlyOnce() throws Exception {
        setupAccount("IDEM", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        riskService.assess("IDEM");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DepositResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return riskService.deposit(new DepositRequest("REQ-X", "IDEM",
                            new BigDecimal("150.00"), "CNY"));
                }));
            }
            start.countDown();
            int accepted = 0;
            int replayed = 0;
            for (Future<DepositResponse> f : futures) {
                DepositResponse r = f.get(15, TimeUnit.SECONDS);
                if (r.replayed()) {
                    replayed++;
                    assertThat(r.result()).isEqualTo(DepositResult.ACCEPTED);
                } else {
                    accepted++;
                    assertThat(r.result()).isEqualTo(DepositResult.ACCEPTED);
                }
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(replayed).isEqualTo(threads - 1);
            // 仅入账一次：1250 + 150 = 1400
            assertThat(accountData.findLatestConfirmedSnapshot("IDEM").totalAssets())
                    .isEqualByComparingTo("1400.00");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 同一 PENDING 作业被两个线程同时执行：仅一方真正平仓，指令不重复。
     */
    @Test
    void concurrentExecuteOnlyLiquidatesOnce() throws Exception {
        setupAccount("DUP", "1080.00", "CNY", new String[][]{{"L1", "1000.00"}});
        long jobId = riskService.assess("DUP").jobId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<LiquidationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return riskService.executeJob(jobId);
                }));
            }
            start.countDown();
            for (Future<LiquidationResponse> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS).status())
                        .isEqualTo(JobStatus.EXECUTED.name());
            }
            // 指令只生成一次：偿还总额 800，剩余负债 200
            LiquidationResponse stored = riskService.getJob(jobId);
            assertThat(stored.items()).hasSize(1);
            assertThat(stored.items().get(0).repaidAmount()).isEqualByComparingTo("800.00");
            assertThat(accountData.findOutstandingLiabilities("DUP").get(0).outstandingAmount())
                    .isEqualByComparingTo("200.00");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
