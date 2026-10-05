package com.example.starter.gas;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * H2 集成测试基类：独立命名内存库（application.yaml 中 test 配置），
 * 每个场景前清理业务表并写入一套合成基础数据（计量站 ST01、合同单价 0.5）。
 */
@SpringBootTest
@Import(com.example.starter.gas.support.TestClockConfiguration.class)
abstract class AbstractH2IntegrationTest {

    void resetDatabase(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM daily_assessment");
        jdbc.update("DELETE FROM voucher_reading_diff");
        jdbc.update("DELETE FROM voucher_dispute");
        jdbc.update("DELETE FROM voucher_confirmation");
        jdbc.update("DELETE FROM station_day_voucher");
        jdbc.update("DELETE FROM custody_voucher");
        jdbc.update("DELETE FROM meter_reading");
        jdbc.update("DELETE FROM quality_diff_signoff");
        jdbc.update("DELETE FROM quality_sample");
        jdbc.update("DELETE FROM station_day_verification");
        jdbc.update("DELETE FROM quality_verification");
        jdbc.update("DELETE FROM component_heating_value");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM daily_nomination");
        jdbc.update("DELETE FROM pipeline_contract");
        jdbc.update("DELETE FROM meter_station");
        jdbc.update("INSERT INTO meter_station (station_code, station_name, party_a_name, party_b_name) "
                + "VALUES ('ST01', '门东交接计量站', '甲方销售公司', '乙方燃气公司')");
        jdbc.update("INSERT INTO pipeline_contract (contract_no, station_code, penalty_unit_price) "
                + "VALUES ('C-T-001', 'ST01', 0.5000)");
        seedComponentHeatingValues(jdbc);
    }

    /** 合成组分热值表（虚构数据）：与 data.sql 保持一致。 */
    static void seedComponentHeatingValues(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO component_heating_value (component_code, component_name, heating_value) "
                + "VALUES ('CH4', '甲烷', 40.000000), ('C2H6', '乙烷', 44.000000), "
                + "('C3H8', '丙烷', 50.000000), ('N2', '氮气', 0.000000), ('CO2', '二氧化碳', 0.000000)");
    }

    void addNomination(JdbcTemplate jdbc, LocalDate gasDate, String nominatedEnergy) {
        jdbc.update("INSERT INTO daily_nomination (station_code, gas_date, nominated_energy) "
                        + "VALUES ('ST01', ?, ?)",
                java.sql.Date.valueOf(gasDate), new BigDecimal(nominatedEnergy));
    }

    /** 执行期望抛出 {@link ApiException} 的动作并返回异常，未抛出则断言失败。 */
    static ApiException catchApi(Runnable action) {
        try {
            action.run();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException ex) {
            return ex;
        }
    }
}
