package com.example.starter.gas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地启动冒烟：按主 application.yaml 的默认方式（嵌入式 H2 + 自动建表与种子数据）
 * 启动完整 Spring 上下文，不依赖外部 MySQL/Docker。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:local_boot_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always"
})
class LocalBootSmokeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextStarts_withH2AndSeedData() {
        Integer stations = jdbc.queryForObject(
                "SELECT COUNT(1) FROM meter_station WHERE station_code='ST01'", Integer.class);
        assertThat(stations).isEqualTo(1);
        Integer nominations = jdbc.queryForObject(
                "SELECT COUNT(1) FROM daily_nomination WHERE station_code='ST01'", Integer.class);
        assertThat(nominations).isEqualTo(6);
        for (String table : new String[]{"meter_station", "pipeline_contract",
                "daily_nomination", "meter_reading", "custody_voucher",
                "station_day_voucher", "voucher_confirmation", "voucher_dispute",
                "voucher_reading_diff", "daily_assessment", "idempotent_request"}) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.tables WHERE lower(table_name)=?",
                    Integer.class, table);
            assertThat(exists).as("表 %s 应存在", table).isEqualTo(1);
        }
    }
}
