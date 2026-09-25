package com.example.starter.gas;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证本地启动资源 schema.sql 与 data.sql 可在全新 H2（MODE=MySQL）上
 * 完成自动建表与合成种子数据初始化（无需外部 MySQL）。
 */
class LocalInitScriptsTest {

    @Test
    void mainSchemaAndSeedData_runOnFreshH2() throws Exception {
        String url = "jdbc:h2:mem:init_script_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("data.sql"));
            // 再次执行验证幂等（MERGE）
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("data.sql"));

            try (Statement st = connection.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT station_name, party_a_name, party_b_name FROM meter_station "
                                + "WHERE station_code='ST01'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("station_name")).isEqualTo("门东交接计量站");
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT penalty_unit_price FROM pipeline_contract WHERE contract_no='C-2026-001'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("0.5000"));
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(1) FROM daily_nomination WHERE station_code='ST01'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(6);
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT nominated_energy FROM daily_nomination "
                                + "WHERE station_code='ST01' AND gas_date=DATE '2026-09-20'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("10000.00");
                }
                // 全部业务表建表成功（11 张）
                for (String table : new String[]{"meter_station", "pipeline_contract",
                        "daily_nomination", "meter_reading", "custody_voucher",
                        "station_day_voucher", "voucher_confirmation", "voucher_dispute",
                        "voucher_reading_diff", "daily_assessment", "idempotent_request"}) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT COUNT(1) FROM information_schema.tables "
                                    + "WHERE lower(table_name)='" + table + "'")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).as("表 %s 应存在", table).isEqualTo(1);
                    }
                }
            }
        }
    }
}
