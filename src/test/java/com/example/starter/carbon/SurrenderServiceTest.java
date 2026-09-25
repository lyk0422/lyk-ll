package com.example.starter.carbon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.carbon.ApiDtos.AllowanceGrantRequest;
import com.example.starter.carbon.ApiDtos.BatchView;
import com.example.starter.carbon.ApiDtos.CertificateView;
import com.example.starter.carbon.ApiDtos.ComplianceStatusView;
import com.example.starter.carbon.ApiDtos.EmissionAssessRequest;
import com.example.starter.carbon.ApiDtos.EnterpriseCreateRequest;
import com.example.starter.carbon.ApiDtos.OffsetCreditRequest;
import com.example.starter.carbon.ApiDtos.SurrenderApplyRequest;
import com.example.starter.carbon.ApiDtos.SurrenderResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 清缴业务核心测试：5% 抵减边界、FIFO+到期优先、组合原子性、幂等重放与冲突、
 * 并发串行生效、截止时刻口径、凭证稳定性。使用独立 H2 内存库与可控时钟。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:carbon_svc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "carbon.compliance.deadline=2030-06-30T16:00:00Z"
})
@Import(MutableClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SurrenderServiceTest {

    private static final Instant DEADLINE = Instant.parse("2030-06-30T16:00:00Z");
    private static final int YEAR = 2030;

    @Autowired
    private SurrenderService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(DEADLINE.minus(Duration.ofDays(30)));
        jdbc.update("DELETE FROM surrender_item");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM surrender_request");
        jdbc.update("DELETE FROM compliance_status");
        jdbc.update("DELETE FROM allowance_batch");
        jdbc.update("DELETE FROM offset_credit_batch");
        jdbc.update("DELETE FROM emission_assessment");
        jdbc.update("DELETE FROM enterprise");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    // ---------- 5% 抵减边界 ----------

    @Test
    void offsetExactlyFivePercentIsAccepted() {
        newEnterprise("E-CAP-OK");
        assess("E-CAP-OK", 1000);
        grantAllowance("E-CAP-OK", 950);
        issueOffset("E-CAP-OK", 50);

        SurrenderResponse resp = service.surrender(
                new SurrenderApplyRequest("REQ-CAP-OK", "E-CAP-OK", YEAR, 950L, 50L));

        assertThat(resp.status()).isEqualTo("COMPLETED");
        assertThat(resp.certificateNo()).isNotBlank();
        assertThat(resp.items()).allMatch(i -> i.status().equals("CANCELLED"));
        ComplianceStatusView status = service.getComplianceStatus("E-CAP-OK", YEAR);
        assertThat(status.status()).isEqualTo("COMPLIANT");
        assertThat(status.offsetUsed()).isEqualTo(50);
    }

    @Test
    void offsetAboveFivePercentRejectedAtomically() {
        newEnterprise("E-CAP-OVER");
        assess("E-CAP-OVER", 1000);
        BatchView allowance = grantAllowance("E-CAP-OVER", 949);
        BatchView offset = issueOffset("E-CAP-OVER", 51);

        assertThatThrownBy(() -> service.surrender(
                new SurrenderApplyRequest("REQ-CAP-OVER", "E-CAP-OVER", YEAR, 949L, 51L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("OFFSET_CAP_EXCEEDED");
                });

        // 整笔拒绝且账户无变动
        assertThat(allowanceRemaining(allowance.id())).isEqualTo(949);
        assertThat(offsetRemaining(offset.id())).isEqualTo(51);
        assertThat(countRows("surrender_request")).isZero();
        assertThat(countRows("surrender_item")).isZero();
    }

    // ---------- FIFO + 到期优先 ----------

    @Test
    void deductionPrefersNearestExpiryThenFirstInFirstOut() {
        newEnterprise("E-FIFO");
        assess("E-FIFO", 1000);
        // B1 先入库但到期日晚；B2 到期日最近；B3 与 B1 到期日相同但更晚入库
        BatchView b1 = grantAllowance("E-FIFO", 100, LocalDate.parse("2030-12-31"));
        BatchView b2 = grantAllowance("E-FIFO", 100, LocalDate.parse("2030-06-01"));
        BatchView b3 = grantAllowance("E-FIFO", 100, LocalDate.parse("2030-12-31"));

        SurrenderResponse resp = service.surrender(
                new SurrenderApplyRequest("REQ-FIFO", "E-FIFO", YEAR, 150L, 0L));

        assertThat(resp.items()).hasSize(2);
        // 先扣到期日最近的 B2（100），再扣同到期日中先入库的 B1（50）
        assertThat(resp.items().get(0).batchId()).isEqualTo(b2.id());
        assertThat(resp.items().get(0).amount()).isEqualTo(100);
        assertThat(resp.items().get(1).batchId()).isEqualTo(b1.id());
        assertThat(resp.items().get(1).amount()).isEqualTo(50);

        assertThat(allowanceRemaining(b2.id())).isZero();
        assertThat(allowanceRemaining(b1.id())).isEqualTo(50);
        assertThat(allowanceRemaining(b3.id())).isEqualTo(100);
    }

    // ---------- 组合原子性 ----------

    @Test
    void mixedCombinationRollsBackWhenOffsetInsufficient() {
        newEnterprise("E-ATOMIC");
        assess("E-ATOMIC", 1000);
        BatchView allowance = grantAllowance("E-ATOMIC", 100);
        BatchView offset = issueOffset("E-ATOMIC", 30);

        // 配额足够、抵消不足：整笔拒绝，配额账户不得发生变动
        assertThatThrownBy(() -> service.surrender(
                new SurrenderApplyRequest("REQ-ATOMIC", "E-ATOMIC", YEAR, 100L, 50L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("INSUFFICIENT_OFFSET");
                });

        assertThat(allowanceRemaining(allowance.id())).isEqualTo(100);
        assertThat(offsetRemaining(offset.id())).isEqualTo(30);
        assertThat(countRows("surrender_request")).isZero();
        assertThat(countRows("surrender_item")).isZero();
        assertThat(countRows("certificate")).isZero();
    }

    // ---------- 幂等：同键重放与异参冲突 ----------

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        newEnterprise("E-IDEM");
        assess("E-IDEM", 1000);
        BatchView allowance = grantAllowance("E-IDEM", 500);
        issueOffset("E-IDEM", 20);

        SurrenderApplyRequest req = new SurrenderApplyRequest("REQ-IDEM", "E-IDEM", YEAR, 480L, 20L);
        SurrenderResponse first = service.surrender(req);
        SurrenderResponse replay = service.surrender(req);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.certificateNo()).isEqualTo(first.certificateNo());
        assertThat(replay.items()).isEqualTo(first.items());
        // 只扣减一次
        assertThat(allowanceRemaining(allowance.id())).isEqualTo(20);
        assertThat(countRows("surrender_request")).isEqualTo(1);
        assertThat(countRows("certificate")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409WithoutChanges() {
        newEnterprise("E-CONFLICT");
        assess("E-CONFLICT", 1000);
        BatchView allowance = grantAllowance("E-CONFLICT", 500);

        service.surrender(new SurrenderApplyRequest("REQ-CONFLICT", "E-CONFLICT", YEAR, 100L, 0L));

        assertThatThrownBy(() -> service.surrender(
                new SurrenderApplyRequest("REQ-CONFLICT", "E-CONFLICT", YEAR, 200L, 0L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("REQUEST_ID_CONFLICT");
                });

        assertThat(allowanceRemaining(allowance.id())).isEqualTo(400);
        assertThat(countRows("surrender_request")).isEqualTo(1);
    }

    // ---------- 并发串行生效 ----------

    @Test
    void concurrentRequestsSerializedWithoutExceedingOffsetCap() throws Exception {
        newEnterprise("E-CONC-CAP");
        assess("E-CONC-CAP", 1000);
        BatchView allowance = grantAllowance("E-CONC-CAP", 1000);
        BatchView offset = issueOffset("E-CONC-CAP", 100);

        // 两笔各申请抵减 40（上限 50）：只能按提交顺序生效一笔
        List<ApiException> failures = new ArrayList<>();
        List<SurrenderResponse> successes = new ArrayList<>();
        runConcurrently(List.of(
                () -> service.surrender(new SurrenderApplyRequest("REQ-CC-1", "E-CONC-CAP", YEAR, 460L, 40L)),
                () -> service.surrender(new SurrenderApplyRequest("REQ-CC-2", "E-CONC-CAP", YEAR, 460L, 40L))
        ), successes, failures);

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).code()).isEqualTo("OFFSET_CAP_EXCEEDED");
        // 不存在合计超额抵减，也不存在重复扣减
        assertThat(offsetRemaining(offset.id())).isEqualTo(60);
        assertThat(allowanceRemaining(allowance.id())).isEqualTo(540);
        ComplianceStatusView status = service.getComplianceStatus("E-CONC-CAP", YEAR);
        assertThat(status.offsetUsed()).isEqualTo(40);
        assertThat(status.surrenderedTotal()).isEqualTo(500);
    }

    @Test
    void concurrentDistinctRequestsAllDeductExactlyOnce() throws Exception {
        newEnterprise("E-CONC-MANY");
        assess("E-CONC-MANY", 1000);
        BatchView allowance = grantAllowance("E-CONC-MANY", 1000);

        int threads = 8;
        List<ThrowingSupplier> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> service.surrender(
                    new SurrenderApplyRequest("REQ-CM-" + seq, "E-CONC-MANY", YEAR, 100L, 0L)));
        }
        List<SurrenderResponse> successes = new ArrayList<>();
        List<ApiException> failures = new ArrayList<>();
        runConcurrently(tasks, successes, failures);

        assertThat(failures).isEmpty();
        assertThat(successes).hasSize(threads);
        assertThat(successes.stream().map(SurrenderResponse::certificateNo).distinct())
                .hasSize(threads);
        // 总额恰好扣减 800，无重复扣减、无负余额
        assertThat(allowanceRemaining(allowance.id())).isEqualTo(200);
        Long itemSum = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM surrender_item",
                Long.class);
        assertThat(itemSum).isEqualTo(800);
    }

    @Test
    void concurrentSameRequestIdYieldsSingleDeduction() throws Exception {
        newEnterprise("E-CONC-IDEM");
        assess("E-CONC-IDEM", 1000);
        BatchView allowance = grantAllowance("E-CONC-IDEM", 500);

        int threads = 5;
        List<ThrowingSupplier> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.surrender(
                    new SurrenderApplyRequest("REQ-CI", "E-CONC-IDEM", YEAR, 100L, 0L)));
        }
        List<SurrenderResponse> successes = new ArrayList<>();
        List<ApiException> failures = new ArrayList<>();
        runConcurrently(tasks, successes, failures);

        assertThat(failures).isEmpty();
        assertThat(successes).hasSize(threads);
        assertThat(successes.stream().map(SurrenderResponse::certificateNo).distinct()).hasSize(1);
        assertThat(allowanceRemaining(allowance.id())).isEqualTo(400);
        assertThat(countRows("surrender_request")).isEqualTo(1);
    }

    // ---------- 截止时刻口径 ----------

    @Test
    void requestExactlyAtDeadlineIsAccepted() {
        newEnterprise("E-DL-EXACT");
        assess("E-DL-EXACT", 1000);
        grantAllowance("E-DL-EXACT", 100);

        clock.setInstant(DEADLINE);
        SurrenderResponse resp = service.surrender(
                new SurrenderApplyRequest("REQ-DL-EXACT", "E-DL-EXACT", YEAR, 100L, 0L));
        assertThat(resp.status()).isEqualTo("COMPLETED");
    }

    @Test
    void requestAfterDeadlineIsRejected() {
        newEnterprise("E-DL-LATE");
        assess("E-DL-LATE", 1000);
        BatchView allowance = grantAllowance("E-DL-LATE", 100);

        clock.setInstant(DEADLINE.plusMillis(1));
        assertThatThrownBy(() -> service.surrender(
                new SurrenderApplyRequest("REQ-DL-LATE", "E-DL-LATE", YEAR, 100L, 0L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("COMPLIANCE_DEADLINE_PASSED");
                });
        assertThat(allowanceRemaining(allowance.id())).isEqualTo(100);
    }

    @Test
    void nonCompliantMarkedAfterDeadlineOnly() {
        newEnterprise("E-MARK-PENDING");
        assess("E-MARK-PENDING", 1000);
        grantAllowance("E-MARK-PENDING", 100);
        newEnterprise("E-MARK-DONE");
        assess("E-MARK-DONE", 500);
        grantAllowance("E-MARK-DONE", 500);
        service.surrender(new SurrenderApplyRequest("REQ-MARK-DONE", "E-MARK-DONE", YEAR, 500L, 0L));

        // 截止前不标记
        assertThat(service.markNonCompliantIfDue()).isZero();
        assertThat(service.getComplianceStatus("E-MARK-PENDING", YEAR).status()).isEqualTo("PENDING");

        // 截止后：未足额企业标记“未履约”，已足额企业保持 COMPLIANT
        clock.setInstant(DEADLINE.plusSeconds(1));
        assertThat(service.markNonCompliantIfDue()).isEqualTo(1);
        assertThat(service.getComplianceStatus("E-MARK-PENDING", YEAR).status())
                .isEqualTo("NON_COMPLIANT");
        assertThat(service.getComplianceStatus("E-MARK-DONE", YEAR).status()).isEqualTo("COMPLIANT");
    }

    // ---------- 凭证稳定性 ----------

    @Test
    void certificateQueryIsStableAcrossRepeats() {
        newEnterprise("E-CERT");
        assess("E-CERT", 1000);
        grantAllowance("E-CERT", 200);

        SurrenderResponse resp = service.surrender(
                new SurrenderApplyRequest("REQ-CERT", "E-CERT", YEAR, 200L, 0L));
        CertificateView first = service.getCertificate(resp.certificateNo());
        CertificateView second = service.getCertificate(resp.certificateNo());

        assertThat(second).isEqualTo(first);
        assertThat(first.requestId()).isEqualTo("REQ-CERT");
        assertThat(first.enterpriseCode()).isEqualTo("E-CERT");
        assertThat(first.totalAmount()).isEqualTo(200);
        assertThat(first.offsetAmount()).isZero();
    }

    // ---------- 测试辅助 ----------

    private void newEnterprise(String code) {
        service.createEnterprise(new EnterpriseCreateRequest(code, "合成企业-" + code));
    }

    private void assess(String code, long emission) {
        service.assessEmission(new EmissionAssessRequest(code, YEAR, emission));
    }

    private BatchView grantAllowance(String code, long amount) {
        return grantAllowance(code, amount, LocalDate.parse("2031-12-31"));
    }

    private BatchView grantAllowance(String code, long amount, LocalDate expiry) {
        return service.grantAllowance(new AllowanceGrantRequest(code, YEAR, amount, expiry));
    }

    private BatchView issueOffset(String code, long amount) {
        return service.issueOffsetCredit(
                new OffsetCreditRequest(code, "CCER", amount, LocalDate.parse("2031-12-31")));
    }

    private long allowanceRemaining(long batchId) {
        return jdbc.queryForObject("SELECT remaining_amount FROM allowance_batch WHERE id = ?",
                Long.class, batchId);
    }

    private long offsetRemaining(long batchId) {
        return jdbc.queryForObject("SELECT remaining_amount FROM offset_credit_batch WHERE id = ?",
                Long.class, batchId);
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        SurrenderResponse run();
    }

    /**
     * 真实并发执行：所有任务在同一栅栏后同时提交，收集成功响应与业务异常。
     */
    private void runConcurrently(List<ThrowingSupplier> tasks, List<SurrenderResponse> successes,
                                 List<ApiException> failures) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<SurrenderResponse> syncSuccesses = java.util.Collections.synchronizedList(successes);
        List<ApiException> syncFailures = java.util.Collections.synchronizedList(failures);
        AtomicInteger completed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (ThrowingSupplier task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发栅栏超时");
                    }
                    syncSuccesses.add(task.run());
                } catch (ApiException e) {
                    syncFailures.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    completed.incrementAndGet();
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(completed.get()).isEqualTo(tasks.size());
    }
}
