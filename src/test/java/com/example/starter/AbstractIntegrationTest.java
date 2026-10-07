package com.example.starter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.config.SyntheticDataInitializer;
import com.example.starter.dto.PlanSegmentInput;
import com.example.starter.support.ServiceClock;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * H2 集成测试支撑：每场景前清空业务表并播种风场/机组，固定可控时钟。
 */
public abstract class AbstractIntegrationTest {

    protected static final String FARM = SyntheticDataInitializer.FARM_CODE;

    /** 测试用计划 UTC 日期。 */
    protected static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 27);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ServiceClock clock;

    /** 机组编码（播种顺序）。 */
    protected record Seed(String code, int ratedKw, int availableKw) {
    }

    protected List<Seed> defaultSeeds() {
        return List.of(
                new Seed("T-01", 2000, 2000),
                new Seed("T-02", 2000, 1600),
                new Seed("T-03", 3000, 2400));
    }

    protected void resetDb() {
        jdbc.update("DELETE FROM controlled_minute_confirm");
        jdbc.update("DELETE FROM controlled_plan_step");
        jdbc.update("DELETE FROM controlled_plan_turbine");
        jdbc.update("DELETE FROM controlled_occupation");
        jdbc.update("DELETE FROM controlled_plan");
        jdbc.update("DELETE FROM late_report_event");
        jdbc.update("DELETE FROM alert_event");
        jdbc.update("DELETE FROM execution_report");
        jdbc.update("DELETE FROM allocation");
        jdbc.update("DELETE FROM dispatch_cycle");
        jdbc.update("DELETE FROM curtail_order");
        jdbc.update("DELETE FROM day_ahead_plan_segment");
        jdbc.update("DELETE FROM day_ahead_plan_version");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM wind_turbine");
        jdbc.update("DELETE FROM wind_farm");
        seedFarm(defaultSeeds());
    }

    /**
     * 构造完整合法的 96 段 × 全场机组计划明细。
     *
     * @param valueAt 以（时段序号, 机组编码）给出核定出力（kW）
     */
    protected List<PlanSegmentInput> fullSegments(BiFunction<Integer, String, Integer> valueAt) {
        List<String> codes = defaultSeeds().stream().map(Seed::code).toList();
        List<PlanSegmentInput> segments = new java.util.ArrayList<>();
        for (int idx = 0; idx < 96; idx++) {
            for (String code : codes) {
                segments.add(new PlanSegmentInput(idx, code, valueAt.apply(idx, code)));
            }
        }
        return segments;
    }

    /** 全部段全部机组同一核定出力的完整 96 段计划。 */
    protected List<PlanSegmentInput> fullSegments(int samePowerKw) {
        return fullSegments((idx, code) -> samePowerKw);
    }

    /** 完整 96 段计划：仅指定段按 overrides 覆盖，其余段与机组取 otherPowerKw。 */
    protected List<PlanSegmentInput> fullSegmentsWith(int specialSegment,
                                                      Map<String, Integer> overrides,
                                                      int otherPowerKw) {
        return fullSegments((idx, code) ->
                idx == specialSegment && overrides.containsKey(code)
                        ? overrides.get(code) : otherPowerKw);
    }

    protected void seedFarm(List<Seed> seeds) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wind_farm(farm_code, farm_name, created_at) VALUES (?, ?, ?)",
                FARM, "测试合成风电场", now);
        for (Seed s : seeds) {
            jdbc.update("""
                    INSERT INTO wind_turbine
                        (farm_code, turbine_code, rated_power_kw, technical_min_kw,
                         available_power_kw, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    FARM, s.code(), s.ratedKw(), Math.round(s.ratedKw() * 0.15f),
                    s.availableKw(), now);
        }
    }

    protected void pinClock(int hour, int minute, int second) {
        clock.pinAt(LocalDateTime.of(2026, 9, 26, hour, minute, second)
                .toInstant(ZoneOffset.ofHours(8)));
    }

    protected Instant localInstant(int hour, int minute, int second) {
        return LocalDateTime.of(2026, 9, 26, hour, minute, second)
                .toInstant(ZoneOffset.ofHours(8));
    }
}
