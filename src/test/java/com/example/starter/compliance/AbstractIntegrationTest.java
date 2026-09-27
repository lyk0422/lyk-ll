package com.example.starter.compliance;

import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.time.ComplianceClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * H2 集成测试基类：每个场景前清理业务数据（保留合成目录）、限量标准重置为初始值并重置可控时钟，
 * 保证测试之间无顺序依赖。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:food_compliance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
public abstract class AbstractIntegrationTest {

    @Autowired
    protected ComplianceRepository repository;

    @Autowired
    protected ComplianceClock clock;

    @BeforeEach
    void cleanAndResetClock() {
        repository.deleteBusinessData();
        resetInitialLimits();
        clock.reset();
    }

    /** 重置合成初始限量（g/kg 终产品），与 SyntheticDataInitializer 保持一致。 */
    private void resetInitialLimits() {
        long now = System.currentTimeMillis();
        repository.updateLimit("E202", "BEV01", new BigDecimal("0.500000"), now);
        repository.updateLimit("E211", "BEV01", new BigDecimal("1.000000"), now);
        repository.updateLimit("E102", "BEV01", new BigDecimal("0.100000"), now);
        repository.updateLimit("E129", "BEV01", new BigDecimal("0.100000"), now);
        repository.updateLimit("E300", "BEV01", new BigDecimal("0.500000"), now);
        repository.updateLimit("E202", "BAK01", new BigDecimal("1.000000"), now);
        repository.updateLimit("E211", "BAK01", new BigDecimal("1.000000"), now);
        repository.updateLimit("E300", "BAK01", new BigDecimal("2.000000"), now);
        repository.updateLimit("E202", "MEAT01", new BigDecimal("1.500000"), now);
        repository.updateLimit("E300", "MEAT01", new BigDecimal("0.500000"), now);
    }

    @AfterEach
    void resetClockAfter() {
        clock.reset();
    }

    /** 将可控时钟设置到某 UTC 日期的正午，避免 UTC 日界抖动。 */
    protected void setUtcDate(LocalDate date) {
        clock.setFixed(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
    }

    protected void setInstant(Instant instant) {
        clock.setFixed(instant);
    }
}
