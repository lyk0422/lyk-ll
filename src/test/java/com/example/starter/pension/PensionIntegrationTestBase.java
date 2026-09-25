package com.example.starter.pension;

import com.example.starter.pension.api.ContributionRequest;
import com.example.starter.pension.api.MemberUpsertRequest;
import com.example.starter.pension.domain.ContributionType;
import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.service.PensionService;
import com.example.starter.pension.support.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * H2 集成测试基类：每个用例前清空全部业务表并固定 UTC 时钟，避免顺序依赖。
 */
@SpringBootTest
public abstract class PensionIntegrationTestBase {

    protected static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    @Autowired
    protected PensionService pensionService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TimeProvider timeProvider;

    @BeforeEach
    void cleanDatabaseAndFreezeClock() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of(
                "account_ledger", "payout_instruction", "payout_installment",
                "payout_plan", "contribution", "member")) {
            jdbcTemplate.update("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        timeProvider.setClock(Clock.fixed(
                Instant.parse("2026-09-25T10:15:30Z"), ZoneOffset.UTC));
    }

    /** 登记成员。 */
    protected void member(String memberNo, String name, LocalDate hireDate, MemberStatus status) {
        pensionService.upsertMember(new MemberUpsertRequest(memberNo, name, hireDate, status));
    }

    /** 录入缴费。 */
    protected void contribution(String memberNo, ContributionType type, long amountCents,
                                LocalDate contributionDate, String period) {
        pensionService.addContribution(new ContributionRequest(
                memberNo, type, amountCents, contributionDate, period));
    }
}
