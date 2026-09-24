package com.example.starter.carbon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.carbon.dto.ComplianceView;
import com.example.starter.carbon.dto.CreateEnterpriseRequest;
import com.example.starter.carbon.dto.IssueBatchRequest;
import com.example.starter.carbon.dto.SetEmissionsRequest;
import com.example.starter.carbon.dto.SubmitSurrenderRequest;
import com.example.starter.carbon.dto.SurrenderResponse;

/**
 * 清缴业务规则测试：5% 抵减边界、扣减顺序、组合原子性、幂等、截止口径与凭证稳定性。
 */
@SpringBootTest
class SurrenderServiceTest {

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
    private CarbonRepository repository;
    @Autowired
    private MutableClock clock;
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
        clock.setInstant(T0);
    }

    @Test
    void offsetExactlyFivePercentAccepted() {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        long offsetBatch = issueOffset(enterpriseId, 50, "2027-06-30");
        long allowanceBatch = issueAllowance(enterpriseId, 950, "2027-06-30");

        SurrenderResponse response = surrenderService.submit(
                new SubmitSurrenderRequest("req-5pct-ok", enterpriseId, YEAR, 950, 50));

        assertThat(response.certificateNo()).startsWith("CERT-");
        assertThat(response.items()).hasSize(2);
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.status()).isEqualTo("SURRENDERED");
        assertThat(view.surrenderedOffset()).isEqualTo(50);
        assertThat(view.surrenderedAllowance()).isEqualTo(950);
        assertThat(remaining("offset_credit_batch", offsetBatch)).isZero();
        assertThat(remaining("allowance_batch", allowanceBatch)).isZero();
    }

    @Test
    void offsetAboveFivePercentRejectedAndAccountUnchanged() {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        long offsetBatch = issueOffset(enterpriseId, 100, "2027-06-30");
        long allowanceBatch = issueAllowance(enterpriseId, 949, "2027-06-30");

        assertThatThrownBy(() -> surrenderService.submit(
                new SubmitSurrenderRequest("req-5pct-over", enterpriseId, YEAR, 949, 51)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("OFFSET_CAP_EXCEEDED");
                });

        // 整笔拒绝且账户无变动
        assertThat(remaining("offset_credit_batch", offsetBatch)).isEqualTo(100);
        assertThat(remaining("allowance_batch", allowanceBatch)).isEqualTo(949);
        assertThat(repository.findRequest("req-5pct-over")).isEmpty();
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.surrenderedAllowance()).isZero();
        assertThat(view.surrenderedOffset()).isZero();
    }

    @Test
    void deductionFollowsExpiryThenFifoOrder() {
        long enterpriseId = newEnterpriseWithEmissions(150);
        // 先签发但到期日晚；后两批到期日相同，按签发先后（主键）排序
        long lateBatch = issueAllowance(enterpriseId, 100, "2027-01-01");
        long earlyBatch1 = issueAllowance(enterpriseId, 100, "2026-06-01");
        long earlyBatch2 = issueAllowance(enterpriseId, 100, "2026-06-01");

        SurrenderResponse response = surrenderService.submit(
                new SubmitSurrenderRequest("req-order", enterpriseId, YEAR, 150, 0));

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).batchId()).isEqualTo(earlyBatch1);
        assertThat(response.items().get(0).quantity()).isEqualTo(100);
        assertThat(response.items().get(1).batchId()).isEqualTo(earlyBatch2);
        assertThat(response.items().get(1).quantity()).isEqualTo(50);
        assertThat(remaining("allowance_batch", earlyBatch1)).isZero();
        assertThat(remaining("allowance_batch", earlyBatch2)).isEqualTo(50);
        assertThat(remaining("allowance_batch", lateBatch)).isEqualTo(100);
    }

    @Test
    void comboDeductionIsAtomicWhenAllowanceShort() {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        long offsetBatch = issueOffset(enterpriseId, 50, "2027-06-30");
        long allowanceBatch = issueAllowance(enterpriseId, 900, "2027-06-30");

        // 抵消信用充足、配额不足：整笔拒绝，抵消信用不得被扣减
        assertThatThrownBy(() -> surrenderService.submit(
                new SubmitSurrenderRequest("req-atomic", enterpriseId, YEAR, 950, 50)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("INSUFFICIENT_BALANCE");
                });

        assertThat(remaining("offset_credit_batch", offsetBatch)).isEqualTo(50);
        assertThat(remaining("allowance_batch", allowanceBatch)).isEqualTo(900);
        assertThat(repository.findRequest("req-atomic")).isEmpty();
        assertThat(repository.findItems("req-atomic")).isEmpty();
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.surrenderedAllowance()).isZero();
        assertThat(view.surrenderedOffset()).isZero();
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        long enterpriseId = newEnterpriseWithEmissions(1000);
        issueAllowance(enterpriseId, 1000, "2027-06-30");
        SubmitSurrenderRequest request = new SubmitSurrenderRequest("req-replay", enterpriseId, YEAR, 1000, 0);

        SurrenderResponse first = surrenderService.submit(request);
        SurrenderResponse replay = surrenderService.submit(request);

        assertThat(replay.certificateNo()).isEqualTo(first.certificateNo());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.items()).isEqualTo(first.items());
        // 未重复扣减
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.surrenderedAllowance()).isEqualTo(1000);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndAccountUnchanged() {
        long enterpriseId = newEnterpriseWithEmissions(2000);
        issueAllowance(enterpriseId, 2000, "2027-06-30");
        issueOffset(enterpriseId, 100, "2027-06-30");
        surrenderService.submit(new SubmitSurrenderRequest("req-conflict", enterpriseId, YEAR, 2000, 0));

        assertThatThrownBy(() -> surrenderService.submit(
                new SubmitSurrenderRequest("req-conflict", enterpriseId, YEAR, 1900, 100)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("IDEMPOTENT_CONFLICT");
                });

        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.surrenderedAllowance()).isEqualTo(2000);
        assertThat(view.surrenderedOffset()).isZero();
    }

    @Test
    void surrenderAtExactDeadlineAccepted() {
        long enterpriseId = newEnterpriseWithEmissions(500);
        issueAllowance(enterpriseId, 500, "2027-06-30");
        clock.setInstant(DEADLINE);

        SurrenderResponse response = surrenderService.submit(
                new SubmitSurrenderRequest("req-at-deadline", enterpriseId, YEAR, 500, 0));

        assertThat(response.certificateNo()).isNotBlank();
        assertThat(carbonService.getCompliance(enterpriseId, YEAR).status()).isEqualTo("SURRENDERED");
    }

    @Test
    void surrenderAfterDeadlineRejectedAndMarkedNonCompliant() {
        long enterpriseId = newEnterpriseWithEmissions(500);
        long allowanceBatch = issueAllowance(enterpriseId, 500, "2027-06-30");
        clock.setInstant(DEADLINE.plusSeconds(1));

        assertThatThrownBy(() -> surrenderService.submit(
                new SubmitSurrenderRequest("req-overdue", enterpriseId, YEAR, 500, 0)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("SURRENDER_OVERDUE");
                });

        assertThat(remaining("allowance_batch", allowanceBatch)).isEqualTo(500);
        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.status()).isEqualTo("NON_COMPLIANT");
    }

    @Test
    void complianceMarkedNonCompliantOnQueryAfterDeadline() {
        long enterpriseId = newEnterpriseWithEmissions(800);
        issueAllowance(enterpriseId, 300, "2027-06-30");
        surrenderService.submit(new SubmitSurrenderRequest("req-partial", enterpriseId, YEAR, 300, 0));
        assertThat(carbonService.getCompliance(enterpriseId, YEAR).status()).isEqualTo("PENDING");

        clock.setInstant(DEADLINE.plusSeconds(1));

        ComplianceView view = carbonService.getCompliance(enterpriseId, YEAR);
        assertThat(view.status()).isEqualTo("NON_COMPLIANT");
        assertThat(view.surrenderedAllowance()).isEqualTo(300);
    }

    @Test
    void certificateStableOnRepeatedQuery() {
        long enterpriseId = newEnterpriseWithEmissions(600);
        issueAllowance(enterpriseId, 600, "2027-06-30");
        SurrenderResponse submitted = surrenderService.submit(
                new SubmitSurrenderRequest("req-cert", enterpriseId, YEAR, 600, 0));

        SurrenderResponse query1 = surrenderService.getSurrender("req-cert");
        SurrenderResponse query2 = surrenderService.getSurrender("req-cert");

        assertThat(query1).isEqualTo(query2);
        assertThat(query1.certificateNo()).isEqualTo(submitted.certificateNo());
        assertThat(query1.items()).isEqualTo(submitted.items());
    }

    @Test
    void surrenderWithoutEmissionsRecordRejected() {
        long enterpriseId = carbonService.createEnterprise(new CreateEnterpriseRequest("合成企业-未核定"));
        issueAllowance(enterpriseId, 100, "2027-06-30");

        assertThatThrownBy(() -> surrenderService.submit(
                new SubmitSurrenderRequest("req-no-account", enterpriseId, YEAR, 100, 0)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.code()).isEqualTo("COMPLIANCE_ACCOUNT_NOT_FOUND");
                });
    }

    private long newEnterpriseWithEmissions(long emissions) {
        long enterpriseId = carbonService.createEnterprise(new CreateEnterpriseRequest("合成企业-" + emissions));
        carbonService.setEmissions(enterpriseId, YEAR, new SetEmissionsRequest(emissions, DEADLINE));
        return enterpriseId;
    }

    private long issueAllowance(long enterpriseId, long quantity, String expiryDate) {
        return carbonService.issueAllowance(new IssueBatchRequest(
                enterpriseId, YEAR, quantity, LocalDate.parse(expiryDate)));
    }

    private long issueOffset(long enterpriseId, long quantity, String expiryDate) {
        return carbonService.issueOffset(new IssueBatchRequest(
                enterpriseId, YEAR, quantity, LocalDate.parse(expiryDate)));
    }

    private long remaining(String table, long batchId) {
        Long remaining = jdbc.queryForObject(
                "SELECT quantity_remaining FROM " + table + " WHERE id = ?", Long.class, batchId);
        return remaining == null ? -1 : remaining;
    }
}
