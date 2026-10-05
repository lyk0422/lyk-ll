package com.example.starter.api;

import com.example.starter.api.dto.AlarmHandlingDtos;
import com.example.starter.api.dto.CommandDtos;
import com.example.starter.api.dto.ReadingDtos;
import com.example.starter.api.dto.RehearsalDtos;
import com.example.starter.api.dto.SetupDtos;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经真实 HTTP 入口验证策略冲突仲裁与预演留档查询：
 * 处置不兼容挂起（无副作用）→ 完结后重新下发；能耗超预算 422；预演留档可查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration.class)
class StrategyArbitrationHttpTest {

    private static final String ZONE = "ZN-ARB";
    private static final String CROP = "TOMATO-ARB";
    private static final LocalDate DAY = LocalDate.of(2026, 2, 10);
    private static final Instant T0 = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("idempotent_request", "strategy_rehearsal", "alarm_handling",
                "daily_evaluation", "control_command",
                "energy_budget", "deviation_alarm", "late_reading_event", "sensor_reading",
                "climate_strategy", "crop_stage", "crop", "zone_info")) {
            jdbcTemplate.update("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.update("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("HTTP 仲裁主流程：处置不兼容挂起→预演留档可查→完结后重新下发；超预算 422；无留档 404")
    void arbitrationFlowOverHttp() {
        // 造数：作物窗口与分区。
        restTemplate.postForEntity(url("/api/setup/crops"),
                new SetupDtos.CropSetupRequest(CROP, "番茄", List.of(new SetupDtos.StageWindow(
                        "FRUIT", "结果期", new BigDecimal("20"), new BigDecimal("28"),
                        new BigDecimal("14"), new BigDecimal("20"),
                        new BigDecimal("60"), new BigDecimal("80")))), Void.class);
        restTemplate.postForEntity(url("/api/setup/zones"),
                new SetupDtos.ZoneSetupRequest(ZONE, "仲裁验证区"), SetupDtos.ZoneResponse.class);

        // 基线策略生效并触发 ACTIVE 告警（连续 3 点 27℃ 偏离 [23,25] 带）。
        draft("ST-1", "24.00", "18.00", "70.00");
        ResponseEntity<StrategyDtos.StrategyResponse> base = restTemplate.postForEntity(
                url("/api/strategies/ST-1/dispatch"),
                new StrategyDtos.DispatchRequest("D-1", T0), StrategyDtos.StrategyResponse.class);
        assertThat(base.getBody().status()).isEqualTo("DISPATCHED");
        Long alarmId = null;
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<ReadingDtos.ReadingResponse> r = restTemplate.postForEntity(url("/api/readings"),
                    new ReadingDtos.ReadingRequest(ZONE, T0.plusSeconds(60L * i),
                            new BigDecimal("27.00"), new BigDecimal("70")),
                    ReadingDtos.ReadingResponse.class);
            alarmId = r.getBody().alarmId();
        }
        assertThat(alarmId).isNotNull();

        // 发起处置：温度窗口 [25,26]，新策略 24℃ 不兼容。
        ResponseEntity<AlarmHandlingDtos.HandlingResponse> handling = restTemplate.postForEntity(
                url("/api/alarm-handlings"),
                new AlarmHandlingDtos.StartRequest("H1", alarmId, "H-REQ-1",
                        new BigDecimal("25.00"), new BigDecimal("26.00"),
                        new BigDecimal("65.00"), new BigDecimal("75.00")),
                AlarmHandlingDtos.HandlingResponse.class);
        assertThat(handling.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(handling.getBody().status()).isEqualTo("IN_PROGRESS");

        // 新策略下发被挂起：200 + SUSPENDED，无生效时刻。
        draft("ST-2", "24.00", "18.00", "70.00");
        ResponseEntity<StrategyDtos.StrategyResponse> suspended = restTemplate.postForEntity(
                url("/api/strategies/ST-2/dispatch"),
                new StrategyDtos.DispatchRequest("D-2", T0.plusSeconds(30 * 60)),
                StrategyDtos.StrategyResponse.class);
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suspended.getBody().status()).isEqualTo("SUSPENDED");
        assertThat(suspended.getBody().effectiveTime()).isNull();

        // 预演留档查询：SUSPENDED 且记录冲突处置。
        ResponseEntity<RehearsalDtos.RehearsalResponse> rehearsal = restTemplate.getForEntity(
                url("/api/strategies/ST-2/rehearsal"), RehearsalDtos.RehearsalResponse.class);
        assertThat(rehearsal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rehearsal.getBody().verdict()).isEqualTo("SUSPENDED");
        assertThat(rehearsal.getBody().handlingConflict()).isEqualTo("H1");
        assertThat(rehearsal.getBody().modelVersion()).isEqualTo("GH-ENERGY-v1");

        // 完结处置后重新下发成功。
        ResponseEntity<AlarmHandlingDtos.HandlingResponse> completed = restTemplate.postForEntity(
                url("/api/alarm-handlings/H1/complete"),
                new AlarmHandlingDtos.CompleteRequest("H-REQ-1-DONE"),
                AlarmHandlingDtos.HandlingResponse.class);
        assertThat(completed.getBody().status()).isEqualTo("COMPLETED");

        ResponseEntity<StrategyDtos.StrategyResponse> dispatched = restTemplate.postForEntity(
                url("/api/strategies/ST-2/dispatch"),
                new StrategyDtos.DispatchRequest("D-2B", T0.plusSeconds(30 * 60)),
                StrategyDtos.StrategyResponse.class);
        assertThat(dispatched.getBody().status()).isEqualTo("DISPATCHED");
        ResponseEntity<RehearsalDtos.RehearsalResponse> passed = restTemplate.getForEntity(
                url("/api/strategies/ST-2/rehearsal"), RehearsalDtos.RehearsalResponse.class);
        assertThat(passed.getBody().verdict()).isEqualTo("PASS");

        // 能耗超预算：次日预算 0.500 < 预估 0.600（20/14/60 正午生效 12h×0.05kWh/h），422 拒绝。
        LocalDate nextDay = DAY.plusDays(1);
        restTemplate.postForEntity(url("/api/energy/budgets"),
                new CommandDtos.BudgetRequest(ZONE, nextDay, new BigDecimal("0.500")),
                CommandDtos.BudgetResponse.class);
        draft("ST-3", "20.00", "14.00", "60.00");
        ResponseEntity<Map> rejected = restTemplate.postForEntity(
                url("/api/strategies/ST-3/dispatch"),
                new StrategyDtos.DispatchRequest("D-3", nextDay.atStartOfDay(ZoneOffset.UTC)
                        .toInstant().plusSeconds(12 * 3600)), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // 拒绝的预演结论仍留档可查。
        ResponseEntity<RehearsalDtos.RehearsalResponse> rejectedRehearsal = restTemplate.getForEntity(
                url("/api/strategies/ST-3/rehearsal"), RehearsalDtos.RehearsalResponse.class);
        assertThat(rejectedRehearsal.getBody().verdict()).isEqualTo("REJECTED");
        assertThat(rejectedRehearsal.getBody().estimatedKwh()).isEqualByComparingTo("0.600");
        assertThat(rejectedRehearsal.getBody().remainingKwh()).isEqualByComparingTo("0.500");

        // 从未预演的策略查询留档 → 404。
        draft("ST-9", "24.00", "18.00", "70.00");
        ResponseEntity<Map> noRehearsal = restTemplate.getForEntity(
                url("/api/strategies/ST-9/rehearsal"), Map.class);
        assertThat(noRehearsal.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void draft(String code, String day, String night, String humidity) {
        ResponseEntity<StrategyDtos.StrategyResponse> response = restTemplate.postForEntity(
                url("/api/strategies"),
                new StrategyDtos.DraftRequest(code, ZONE, CROP, "FRUIT",
                        new BigDecimal(day), new BigDecimal(night), new BigDecimal(humidity),
                        new BigDecimal("1.0"), new BigDecimal("5")),
                StrategyDtos.StrategyResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
