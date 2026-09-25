package com.example.starter.carbon;

import java.math.BigDecimal;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.service.SurrenderService;
import com.example.starter.carbon.support.DemoDataSeeder;
import com.example.starter.carbon.web.SurrenderRequest;
import com.example.starter.carbon.web.SurrenderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 Profile 启动冒烟：独立命名内存库，验证自动建表、合成数据种子、调度器装配，
 * 并通过真实业务入口完成一笔足额清缴与凭证查询，等价于本地启动后的核心调用链。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:carbon_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LocalStartupSmokeTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private SurrenderService service;
    @Autowired
    private CarbonLedgerRepository ledger;

    @Test
    @DisplayName("默认启动：合成台账已初始化，调度器装配，可完成950配额+50抵消清缴并查询凭证")
    void defaultStartupSeedsDemoDataAndSurrenderSucceeds() {
        assertThat(context.containsBean("demoDataSeeder")).isTrue();
        assertThat(context.containsBean("deadlineSchedulerConfig")).isTrue();

        var demoEnterprise = ledger.findEnterpriseByCode(DemoDataSeeder.DEMO_ENTERPRISE);
        assertThat(demoEnterprise).isNotNull();
        assertThat(ledger.remainingAmount(CarbonLedgerRepository.TYPE_ALLOWANCE, "AL-2025-A"))
                .isEqualByComparingTo("400");
        assertThat(ledger.remainingAmount(CarbonLedgerRepository.TYPE_OFFSET, "OFF-2025-A"))
                .isEqualByComparingTo("50");

        SurrenderResponse response = service.submit(new SurrenderRequest(
                "SMOKE-REQ-01", DemoDataSeeder.DEMO_ENTERPRISE, DemoDataSeeder.DEMO_YEAR,
                new BigDecimal("950.000"), new BigDecimal("50.000")));
        assertThat(response.status()).isEqualTo("RETIRED");
        assertThat(response.retirements()).extracting(item -> item.batchNo())
                .containsExactly("AL-2025-A", "AL-2025-B", "AL-2025-C", "OFF-2025-A");
        assertThat(ledger.assessmentStatus(demoEnterprise.id(), DemoDataSeeder.DEMO_YEAR))
                .isEqualTo("SURRENDERED");

        var certificate = service.getCertificate(response.certificateNo());
        assertThat(certificate.requestId()).isEqualTo("SMOKE-REQ-01");
        assertThat(certificate.enterpriseCode()).isEqualTo(DemoDataSeeder.DEMO_ENTERPRISE);
        assertThat(certificate.totalRetired()).isEqualByComparingTo("1000.000");
        // 凭证号稳定：再次查询完全一致。
        assertThat(service.getCertificate(response.certificateNo())).isEqualTo(certificate);
    }
}
