package com.example.starter.crane;

import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：真实嵌入式 H2（MySQL 模式）+ 固定 UTC 时钟（{@link TestClockConfig}）；
 * 每个用例前按外键逆序清表并重建合成基础数据，保证场景间无顺序依赖。
 */
@SpringBootTest
public abstract class AbstractCraneIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM event_correction");
        jdbc.update("DELETE FROM intrusion_warning");
        jdbc.update("DELETE FROM brake_command");
        jdbc.update("DELETE FROM pose_reading");
        jdbc.update("DELETE FROM zone_authorization");
        jdbc.update("DELETE FROM overlap_zone");
        jdbc.update("DELETE FROM tower_crane");
        Timestamp now = Timestamp.from(TestClockConfig.FIXED_NOW);
        jdbc.update("INSERT INTO tower_crane (crane_code, rated_load_kg, created_at) VALUES ('C-101', 5000.00, ?)",
                now);
        jdbc.update("INSERT INTO tower_crane (crane_code, rated_load_kg, created_at) VALUES ('C-102', 3000.00, ?)",
                now);
        jdbc.update("INSERT INTO overlap_zone (zone_code, description, created_at) VALUES ('Z-A1', '合成重叠区', ?)",
                now);
    }
}
