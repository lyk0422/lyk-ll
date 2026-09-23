package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.calibration.web.CalibrationController;

/**
 * 默认 profile 启动冒烟：不设置任何 DB_URL/DB_USER 时，
 * 应用以嵌入式 H2（MODE=MySQL）启动并自动建表，证明本地入口无需外部数据库。
 */
@SpringBootTest
class LocalStartupSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CalibrationController controller;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startsWithEmbeddedH2AndSchemaInitialized() throws Exception {
        assertThat(controller).isNotNull();
        String product = dataSource.getConnection().getMetaData().getDatabaseProductName();
        assertThat(product).isEqualTo("H2");
        // schema.sql 已自动执行，关键表均可查询
        for (String table : List.of(
                "calibration_version", "canary_release", "canary_vehicle",
                "active_canary_vehicle", "resolution_record")) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).isZero();
        }
    }
}
