package com.example.starter.config;

import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 合成数据初始化：本地 H2 启动后写入一个风场与三台机组（仅 JVM 生命周期内保留）。
 * 幂等：已有数据时跳过。可用出力初始等于额定容量，技术下限为额定的 15%。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    public static final String FARM_CODE = "WF-DEMO";

    private record SeedTurbine(String code, int ratedKw, int availableKw) {
    }

    private static final SeedTurbine[] TURBINES = {
            new SeedTurbine("T-01", 2000, 2000),
            new SeedTurbine("T-02", 2000, 1600),
            new SeedTurbine("T-03", 3000, 2400)
    };

    private final JdbcTemplate jdbc;

    public SyntheticDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wind_farm", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wind_farm(farm_code, farm_name, created_at) VALUES (?, ?, ?)",
                FARM_CODE, "合成示范风电场", now);
        for (SeedTurbine t : TURBINES) {
            int technicalMin = Math.round(t.ratedKw() * 0.15f);
            jdbc.update("""
                    INSERT INTO wind_turbine
                        (farm_code, turbine_code, rated_power_kw, technical_min_kw, available_power_kw, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    FARM_CODE, t.code(), t.ratedKw(), technicalMin, t.availableKw(), now);
        }
    }
}
