package com.example.starter.support;

import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.FuelComponentRequest;
import com.example.starter.api.dto.LicenseUpsertRequest;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试场景构造工厂与清表工具，全部为合成数据。
 */
public final class TestFixtures {

    public static final String KILN = "KILN-01";
    public static final String LICENSE = "LIC-TEST-001";

    private TestFixtures() {
    }

    /**
     * 按外键依赖顺序清空全部业务表，保证用例间无顺序依赖。
     */
    public static void cleanAll(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reduction_command");
        jdbc.update("DELETE FROM hour_evaluation_summary");
        jdbc.update("DELETE FROM hour_evaluation");
        jdbc.update("DELETE FROM late_reading_event");
        jdbc.update("DELETE FROM emission_reading");
        jdbc.update("DELETE FROM kiln_execution_slot");
        jdbc.update("DELETE FROM daily_ban");
        jdbc.update("DELETE FROM plan_fuel");
        jdbc.update("DELETE FROM co_firing_plan");
        jdbc.update("DELETE FROM hazardous_license");
    }

    public static LicenseUpsertRequest license(String licenseNo, LocalDate validFrom, LocalDate validTo) {
        return new LicenseUpsertRequest(licenseNo, "合成持证单位", validFrom, validTo, false);
    }

    public static FuelComponentRequest fuel(String name, boolean alternative,
                                            String feed, String heat, String chlorinePct, String sulfurPct) {
        return new FuelComponentRequest(name, alternative, new BigDecimal(feed), new BigDecimal(heat),
                new BigDecimal(chlorinePct), new BigDecimal(sulfurPct));
    }

    /**
     * 默认申报：替代率 0.40，加权氯 0.2 kg/h、硫 6 kg/h，均远低于限值。
     */
    public static DeclarePlanRequest declareRequest(String requestId, String kilnId, String heatRatio) {
        return new DeclarePlanRequest(requestId, kilnId, LICENSE, new BigDecimal(heatRatio),
                new BigDecimal("0.1000"), List.of(
                        fuel("煤粉", false, "1000", "5500", "0.01", "0.30"),
                        fuel("RDF 替代燃料", true, "1000", "3200", "0.01", "0.30")));
    }

    public static DeclarePlanRequest declareRequest(String requestId, String heatRatio) {
        return declareRequest(requestId, KILN, heatRatio);
    }

    public static ReadingBatchRequest singleReading(String point, String pollutant,
                                                     LocalDateTime sampledAt, String value) {
        return new ReadingBatchRequest(List.of(
                new ReadingItem(point, pollutant, sampledAt, new BigDecimal(value))));
    }

    public static ReadingBatchRequest readings(ReadingItem... items) {
        return new ReadingBatchRequest(List.of(items));
    }

    public static ReadingItem reading(String point, String pollutant,
                                      LocalDateTime sampledAt, String value) {
        return new ReadingItem(point, pollutant, sampledAt, new BigDecimal(value));
    }
}
