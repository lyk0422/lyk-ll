package com.example.starter.support;

import com.example.starter.repository.MasterDataRepository;
import com.example.starter.domain.Generator;
import com.example.starter.domain.GeneratorStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 本地启动时写入少量合成基站与油机，无需任何外部动环系统即可走通业务。
 * 测试 profile 下不执行，保证用例数据隔离。
 */
@Component
@Profile("!test")
@Order(0)
@ConditionalOnProperty(name = "app.sample-data.enabled", havingValue = "true", matchIfMissing = true)
public class SampleDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final MasterDataRepository masterDataRepository;

    public SampleDataInitializer(JdbcTemplate jdbc, MasterDataRepository masterDataRepository) {
        this.jdbc = jdbc;
        this.masterDataRepository = masterDataRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer stationCount = jdbc.queryForObject("SELECT COUNT(*) FROM base_station", Integer.class);
        if (stationCount != null && stationCount > 0) {
            return;
        }
        Instant now = Instant.now();
        // 额定 3000Wh，平均放电 2000W：满电约 90 分钟（低于 2 小时，便于演示派单）。
        masterDataRepository.insertStation(new com.example.starter.domain.BaseStation(
                null, "BS-001", "城东基站", new BigDecimal("3000"), new BigDecimal("2000"), null, now));
        // 额定 6000Wh，平均放电 2000W：满电 180 分钟（达到 2 小时以上，不派单）。
        masterDataRepository.insertStation(new com.example.starter.domain.BaseStation(
                null, "BS-002", "城西基站", new BigDecimal("6000"), new BigDecimal("2000"), null, now));
        masterDataRepository.insertGenerator(new Generator(
                null, "GEN-001", "1号油机", GeneratorStatus.IDLE, null, now));
        masterDataRepository.insertGenerator(new Generator(
                null, "GEN-002", "2号油机", GeneratorStatus.IDLE, null, now));
        masterDataRepository.insertGenerator(new Generator(
                null, "GEN-003", "3号油机", GeneratorStatus.IDLE, null, now));
    }
}
