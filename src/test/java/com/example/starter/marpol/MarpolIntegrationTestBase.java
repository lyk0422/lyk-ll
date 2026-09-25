package com.example.starter.marpol;

import com.example.starter.marpol.support.TimeService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 集成测试基类：每个场景前按外键顺序清理业务表，避免顺序依赖；
 * 参考数据（港口、特殊区域）保留。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class,
        MutableClockConfiguration.class})
@ActiveProfiles("test")
public abstract class MarpolIntegrationTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TimeService timeService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM defect");
        jdbc.update("DELETE FROM inspection_item");
        jdbc.update("DELETE FROM inspection");
        jdbc.update("DELETE FROM emission_request");
        jdbc.update("DELETE FROM emission_record");
        jdbc.update("DELETE FROM voyage");
        jdbc.update("DELETE FROM ship");
        jdbc.update("INSERT INTO ship (ship_id, ship_name, created_at) "
                + "VALUES ('SHIP-001', '海峰轮（合成）', '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO voyage (ship_id, voyage_no, status, closed_at, created_at) "
                + "VALUES ('SHIP-001', 'V-2026-01', 'OPEN', NULL, '2026-01-01 00:00:00')");
        if (timeService instanceof MutableClockConfiguration.MutableTimeService mutable) {
            mutable.setInstant(java.time.Instant.parse("2026-09-10T00:00:00Z"));
        }
    }
}
