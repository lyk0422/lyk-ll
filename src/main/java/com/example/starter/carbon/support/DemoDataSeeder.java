package com.example.starter.carbon.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.repo.CarbonLedgerRepository.EnterpriseRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地演示合成台账：一家示例企业 2025 履约年度，核定排放 1000.000 吨，
 * 配额合计 1000 吨（三批，同获得日期按到期日近者优先），抵消信用 50 吨（恰为排放量5%）。
 *
 * <p>仅在非 test Profile 下执行；幂等，重复启动不重复造数。</p>
 */
@Component
@Profile("!test")
public class DemoDataSeeder implements ApplicationRunner {

    /** 示例企业编码。 */
    public static final String DEMO_ENTERPRISE = "E-DEMO-01";
    /** 示例履约年度。 */
    public static final int DEMO_YEAR = 2025;
    /** 示例核定排放量（吨CO2e）。 */
    public static final BigDecimal DEMO_EMISSION = new BigDecimal("1000.000");
    /** 示例履约截止时刻（UTC）。 */
    public static final Instant DEMO_DEADLINE = Instant.parse("2027-06-30T16:00:00Z");

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final CarbonLedgerRepository ledger;

    public DemoDataSeeder(CarbonLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (ledger.findEnterpriseByCode(DEMO_ENTERPRISE) != null) {
            return;
        }
        long enterpriseId = ledger.insertEnterprise(DEMO_ENTERPRISE, "示例钢铁有限公司（合成数据）");
        ledger.insertAssessment(enterpriseId, DEMO_YEAR, DEMO_EMISSION, DEMO_DEADLINE);

        // 三批配额均于 2025-01-01 获得，到期日依次变远：扣减顺序 AL-2025-A → AL-2025-B → AL-2025-C。
        ledger.insertBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, enterpriseId, "AL-2025-A",
                new BigDecimal("400.000"), LocalDate.parse("2025-01-01"), LocalDate.parse("2026-06-30"));
        ledger.insertBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, enterpriseId, "AL-2025-B",
                new BigDecimal("300.000"), LocalDate.parse("2025-01-01"), LocalDate.parse("2027-06-30"));
        ledger.insertBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, enterpriseId, "AL-2025-C",
                new BigDecimal("300.000"), LocalDate.parse("2025-06-01"), LocalDate.parse("2026-01-01"));

        // 核证抵消信用 50 吨，恰为核定排放量的 5%。
        ledger.insertBatch(CarbonLedgerRepository.TYPE_OFFSET, enterpriseId, "OFF-2025-A",
                new BigDecimal("50.000"), LocalDate.parse("2025-02-01"), LocalDate.parse("2026-12-31"));

        log.info("合成演示台账已就绪：企业={} 年度={} 核定排放={} 截止UTC={}",
                DEMO_ENTERPRISE, DEMO_YEAR, DEMO_EMISSION.toPlainString(), DEMO_DEADLINE);
    }
}
