package com.example.starter.cems;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.RegisterOutletRequest;

/**
 * 测试支持：场景间清空 H2 数据、登记合成排口，保证用例无顺序依赖。
 */
final class CemsTestSupport {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 30, 0);

    private CemsTestSupport() {
    }

    static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM outlet_event");
        jdbc.update("DELETE FROM production_cut");
        jdbc.update("DELETE FROM restriction_order");
        jdbc.update("DELETE FROM data_gap");
        jdbc.update("DELETE FROM exceedance_mark");
        jdbc.update("DELETE FROM overflow_adjustment");
        jdbc.update("DELETE FROM overflow_anomaly");
        jdbc.update("DELETE FROM overflow_daily_snapshot");
        jdbc.update("DELETE FROM overflow_hourly");
        jdbc.update("DELETE FROM overflow_permit");
        jdbc.update("DELETE FROM monitor_reading");
        jdbc.update("DELETE FROM discharge_outlet");
    }

    static void register(CemsService service, String code, String dailyLimit, String hourlyLimit) {
        service.registerOutlet(new RegisterOutletRequest(
                "reg-" + code, code, "合成排口-" + code,
                new BigDecimal(dailyLimit), new BigDecimal(hourlyLimit), "mg/L"));
    }
}
