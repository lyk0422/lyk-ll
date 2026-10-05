package com.example.starter;

import com.example.starter.api.dto.CommandDtos;
import com.example.starter.api.dto.ReadingDtos;
import com.example.starter.api.dto.SetupDtos;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.service.AlarmService;
import com.example.starter.service.EnergyService;
import com.example.starter.service.EvaluationService;
import com.example.starter.service.ReadingService;
import com.example.starter.service.SetupService;
import com.example.starter.service.StrategyService;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例前清空数据，避免顺序依赖；
 * 不使用测试事务回滚，以保证并发用例中的跨线程真实提交。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final String ZONE = "Z1";
    protected static final String CROP = "TOMATO";
    protected static final String STAGE = "FRUIT";

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected SetupService setupService;
    @Autowired
    protected StrategyService strategyService;
    @Autowired
    protected AlarmService alarmService;
    @Autowired
    protected ReadingService readingService;
    @Autowired
    protected EnergyService energyService;
    @Autowired
    protected EvaluationService evaluationService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("idempotent_request", "daily_evaluation", "control_command",
                "energy_budget", "deviation_alarm", "late_reading_event", "sensor_reading",
                "strategy_rehearsal", "climate_strategy", "crop_stage", "crop", "zone_info")) {
            jdbcTemplate.update("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.update("SET REFERENTIAL_INTEGRITY TRUE");
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
    }

    protected void seedZone() {
        setupService.createZone(new SetupDtos.ZoneSetupRequest(ZONE, "一号番茄分区"));
    }

    /**
     * 合成番茄结果期窗口：白天 [20,28]℃，夜间 [14,20]℃，湿度 [60,80]%RH（边界均合法）。
     */
    protected void seedCrop() {
        setupService.upsertCrop(new SetupDtos.CropSetupRequest(CROP, "番茄", List.of(
                new SetupDtos.StageWindow(STAGE, "结果期",
                        bd("20.00"), bd("28.00"), bd("14.00"), bd("20.00"),
                        bd("60.00"), bd("80.00")))));
    }

    protected StrategyDtos.StrategyResponse draftStrategy(String code, String day, String night,
                                                           String humidity, String tempTol, String humTol) {
        return strategyService.draft(new StrategyDtos.DraftRequest(code, ZONE, CROP, STAGE,
                bd(day), bd(night), bd(humidity), bd(tempTol), bd(humTol)));
    }

    protected StrategyDtos.StrategyResponse dispatch(String code, String requestId, Instant effective) {
        return strategyService.dispatch(code, new StrategyDtos.DispatchRequest(requestId, effective));
    }

    protected CommandDtos.BudgetResponse allocateBudget(String date, String kwh) {
        return energyService.allocateBudget(
                new CommandDtos.BudgetRequest(ZONE, java.time.LocalDate.parse(date), bd(kwh)));
    }

    protected ReadingDtos.ReadingResponse ingest(Instant sampleTime, String temp, String humidity) {
        return readingService.ingest(
                new ReadingDtos.ReadingRequest(ZONE, sampleTime, bd(temp), bd(humidity)));
    }

    protected static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
