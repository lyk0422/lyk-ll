package com.example.starter.gas.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 测试数据准备：场景间清理业务表并写入合成基础数据。
 */
public final class DatabaseFixtures {

    private DatabaseFixtures() {
    }

    /** 清理全部业务表并写入计量站 ST01、合同单价 0.5 的合成数据。 */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM daily_assessment");
        jdbc.update("DELETE FROM voucher_reading_diff");
        jdbc.update("DELETE FROM voucher_dispute");
        jdbc.update("DELETE FROM voucher_confirmation");
        jdbc.update("DELETE FROM station_day_voucher");
        jdbc.update("DELETE FROM custody_voucher");
        jdbc.update("DELETE FROM meter_reading");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM daily_nomination");
        jdbc.update("DELETE FROM pipeline_contract");
        jdbc.update("DELETE FROM meter_station");
        jdbc.update("INSERT INTO meter_station (station_code, station_name, party_a_name, party_b_name) "
                + "VALUES ('ST01', '门东交接计量站', '甲方销售公司', '乙方燃气公司')");
        jdbc.update("INSERT INTO pipeline_contract (contract_no, station_code, penalty_unit_price) "
                + "VALUES ('C-T-001', 'ST01', 0.5000)");
    }

    /** 写入某日指定能量。 */
    public static void addNomination(JdbcTemplate jdbc, LocalDate gasDate, String nominatedEnergy) {
        jdbc.update("INSERT INTO daily_nomination (station_code, gas_date, nominated_energy) "
                        + "VALUES ('ST01', ?, ?)",
                java.sql.Date.valueOf(gasDate), new BigDecimal(nominatedEnergy));
    }
}
