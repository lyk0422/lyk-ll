package com.example.starter.museum;

import com.example.starter.museum.support.ControllableClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * 集成测试基类：每个用例前清空全部业务表、重置序列并重灌合成目录数据，
 * 使用独立内存库 museum_loan_test，避免用例间顺序依赖。
 * 统一在基类声明 MockMvc 自动配置，保证全部用例共用同一 Spring 上下文，
 * 避免命名内存库被重复执行建表脚本。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class MuseumTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ControllableClock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[]{
                "idempotent_request", "loan_event", "resume_review", "protection_work_order",
                "environment_alert", "environment_reading", "handover", "artifact_active_loan",
                "loan_contract", "showcase", "hall", "artifact"}) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("ALTER SEQUENCE domain_seq RESTART WITH 1");
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        seedCatalog();
        clock.reset();
    }

    private void seedCatalog() {
        jdbc.update("""
                INSERT INTO artifact (artifact_no, name, grade, material, created_at) VALUES
                 ('A001', '青铜夔纹鼎', 1, 'BRONZE', TIMESTAMP '2026-01-01 00:00:00'),
                 ('A002', '唐代丝织佛画', 1, 'SILK',   TIMESTAMP '2026-01-01 00:00:00'),
                 ('A003', '越窑青瓷瓶',   2, 'CERAMIC', TIMESTAMP '2026-01-01 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO hall (hall_no, name) VALUES ('H1', '青铜馆'), ('H2', '织绣馆')
                """);
        jdbc.update("""
                INSERT INTO showcase (showcase_no, hall_id, material, temp_min, temp_max,
                    hum_min, hum_max, frozen, created_at) VALUES
                 ('S001', 1, 'BRONZE',  15.00, 22.00, 35.00, 50.00, FALSE, TIMESTAMP '2026-01-01 00:00:00'),
                 ('S002', 2, 'SILK',    18.00, 22.00, 50.00, 60.00, FALSE, TIMESTAMP '2026-01-01 00:00:00'),
                 ('S003', 1, 'CERAMIC', 15.00, 25.00, 40.00, 60.00, FALSE, TIMESTAMP '2026-01-01 00:00:00')
                """);
    }

    protected void fixClock(String instant) {
        clock.setInstant(Instant.parse(instant));
    }
}
