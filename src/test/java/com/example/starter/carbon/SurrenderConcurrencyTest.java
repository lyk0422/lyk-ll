package com.example.starter.carbon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.carbon.dto.ComplianceView;
import com.example.starter.carbon.dto.CreateEnterpriseRequest;
import com.example.starter.carbon.dto.IssueBatchRequest;
import com.example.starter.carbon.dto.SetEmissionsRequest;
import com.example.starter.carbon.dto.SubmitSurrenderRequest;
import com.example.starter.carbon.dto.SurrenderResponse;

/**
 * 并发与幂等边界测试：同一企业同一履约年度的并发申请按提交顺序串行生效，
 * 不出现合计超额抵减、不重复扣减同一配额。
 */
@SpringBootTest
class SurrenderConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-12-31T00:00:00Z");
    private static final int YEAR = 2026;

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private SurrenderService surrenderService;
    @Autowired
    private CarbonService carbonService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM surrender_item");
        jdbc.update("DELETE FROM surrender_request");
        jdbc.update("DELETE FROM offset_credit_batch");
        jdbc.update("DELETE FROM allowance_batch");
        jdbc.update("DELETE FROM compliance_account");
        jdbc.update("DELETE FROM enterprise");
    }

    @Test
    void concurrentSurrendersSerializeWithoutOverDeduction() throws Exception {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        for (int i = 0; i < 4; i++) {
            issueAllowance(enterpriseId, 250, "2027-0" + (i + 1) + "-01");
        }

        List<Outcome> outcomes = runConcurrently(5, 30,
                i -> () -> surrenderService.submit(
                        new SubmitSurrenderRequest("req-par-" + i, enterpriseId, YEAR, 200, 0)));

        assertThat(outcomes).allMatch(Outcome::success);
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.status()).isEqualTo("SURRENDERED");
        assertThat(view.surrenderedAllowance()).isEqualTo(1000);
        // 注销明细总量等于账户扣减总量，无重复扣减
        Long itemSum = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM surrender_item",
                Long.class);
        assertThat(itemSum).isEqualTo(1000);
        Long remainingSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity_remaining), 0) FROM allowance_batch", Long.class);
        assertThat(remainingSum).isZero();
        // 凭证号全局唯一
        Long distinctCerts = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT certificate_no) FROM surrender_request", Long.class);
        assertThat(distinctCerts).isEqualTo(5);
    }

    @Test
    void concurrentOffsetCapEnforcedSerially() throws Exception {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        issueOffset(enterpriseId, 100, "2027-06-30");
        issueAllowance(enterpriseId, 2000, "2027-06-30");

        // 两笔各抵减 30，累计 60 超过 5% 上限（50）：串行生效后只能成功一笔
        List<Outcome> outcomes = runConcurrently(2, 30,
                i -> () -> surrenderService.submit(
                        new SubmitSurrenderRequest("req-cap-" + i, enterpriseId, YEAR, 485, 30)));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(o -> !o.success() && "OFFSET_CAP_EXCEEDED".equals(o.errorCode()))
                .count()).isEqualTo(1);
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.surrenderedOffset()).isEqualTo(30);
        assertThat(view.surrenderedAllowance()).isEqualTo(485);
    }

    @Test
    void concurrentSameRequestIdSameParamsDeductsOnce() throws Exception {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        issueAllowance(enterpriseId, 1000, "2027-06-30");
        SubmitSurrenderRequest request =
                new SubmitSurrenderRequest("req-same", enterpriseId, YEAR, 200, 0);

        List<Outcome> outcomes = runConcurrently(4, 30, i -> () -> surrenderService.submit(request));

        assertThat(outcomes).allMatch(Outcome::success);
        List<String> certs = outcomes.stream().map(o -> o.response().certificateNo()).distinct().toList();
        assertThat(certs).hasSize(1);
        // 只扣减一次
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.surrenderedAllowance()).isEqualTo(200);
        Long requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM surrender_request WHERE request_id = 'req-same'", Long.class);
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdDifferentParamsSingleWinner() throws Exception {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        issueAllowance(enterpriseId, 1000, "2027-06-30");

        List<Callable<SurrenderResponse>> tasks = List.of(
                () -> surrenderService.submit(
                        new SubmitSurrenderRequest("req-race", enterpriseId, YEAR, 300, 0)),
                () -> surrenderService.submit(
                        new SubmitSurrenderRequest("req-race", enterpriseId, YEAR, 400, 0)));
        List<Outcome> outcomes = runConcurrently(tasks, 30);

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(outcomes.stream()
                .filter(o -> !o.success() && "IDEMPOTENT_CONFLICT".equals(o.errorCode()))
                .count()).isEqualTo(1);
        // 账户只反映胜出一笔
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.surrenderedAllowance()).isIn(300L, 400L);
        Long requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM surrender_request WHERE request_id = 'req-race'", Long.class);
        assertThat(requestCount).isEqualTo(1);
    }

    private List<Outcome> runConcurrently(int threads, int timeoutSeconds,
            java.util.function.IntFunction<Callable<SurrenderResponse>> taskFactory)
            throws InterruptedException {
        List<Callable<SurrenderResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(taskFactory.apply(i));
        }
        return runConcurrently(tasks, timeoutSeconds);
    }

    private List<Outcome> runConcurrently(List<Callable<SurrenderResponse>> calls, int timeoutSeconds)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = calls.stream()
                    .<Callable<Outcome>>map(call -> () -> {
                        gate.await();
                        try {
                            return Outcome.ok(call.call());
                        } catch (ApiException ex) {
                            return Outcome.error(ex.code());
                        }
                    })
                    .map(pool::submit)
                    .toList();
            gate.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(timeoutSeconds, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("并发任务未在超时时间内完成", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record Outcome(SurrenderResponse response, String errorCode) {

        static Outcome ok(SurrenderResponse response) {
            return new Outcome(response, null);
        }

        static Outcome error(String code) {
            return new Outcome(null, code);
        }

        boolean success() {
            return response != null;
        }
    }

    private long newEnterpriseWithEmissions(long emissions) {
        long enterpriseId = carbonService.createEnterprise(new CreateEnterpriseRequest("合成企业-并发"));
        carbonService.setEmissions(enterpriseId, YEAR, new SetEmissionsRequest(emissions, DEADLINE));
        return enterpriseId;
    }

    private void issueAllowance(long enterpriseId, long quantity, String expiryDate) {
        carbonService.issueAllowance(new IssueBatchRequest(
                enterpriseId, YEAR, quantity, LocalDate.parse(expiryDate)));
    }

    private void issueOffset(long enterpriseId, long quantity, String expiryDate) {
        carbonService.issueOffset(new IssueBatchRequest(
                enterpriseId, YEAR, quantity, LocalDate.parse(expiryDate)));
    }
}
