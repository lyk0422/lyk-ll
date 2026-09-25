package com.example.starter.api;

import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.api.dto.CommandDtos;
import com.example.starter.api.dto.EvaluationDtos;
import com.example.starter.api.dto.ReadingDtos;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经真实 HTTP 入口验证编制→下发→读数告警→指令能耗→日评估主流程与失败状态码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration.class)
class GreenhouseFlowHttpTest {

    private static final String ZONE = "ZN-HTTP";
    private static final String CROP = "CUCUMBER";
    private static final LocalDate DAY = LocalDate.of(2026, 2, 1);
    private static final Instant T0 = DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("idempotent_request", "daily_evaluation", "control_command",
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
    @DisplayName("HTTP 主流程：造数→编制→下发→连续3点告警→确认→预算与指令→日评估；失败语义 404/422/409")
    void fullFlowOverHttp() {
        // 造数
        ResponseEntity<Void> crop = restTemplate.postForEntity(url("/api/setup/crops"),
                new SetupDtos.CropSetupRequest(CROP, "黄瓜", List.of(new SetupDtos.StageWindow(
                        "VINE", "爬蔓期", new BigDecimal("21"), new BigDecimal("29"),
                        new BigDecimal("15"), new BigDecimal("21"), new BigDecimal("65"), new BigDecimal("85")))),
                Void.class);
        assertThat(crop.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<SetupDtos.ZoneResponse> zone = restTemplate.postForEntity(url("/api/setup/zones"),
                new SetupDtos.ZoneSetupRequest(ZONE, "黄瓜一号区"), SetupDtos.ZoneResponse.class);
        assertThat(zone.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 编制：越窗 422
        ResponseEntity<Map> bad = restTemplate.postForEntity(url("/api/strategies"),
                new StrategyDtos.DraftRequest("ST-BAD", ZONE, CROP, "VINE",
                        new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("70"),
                        new BigDecimal("1"), new BigDecimal("5")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 编制成功：昼夜差恰 8℃（29-21），且均在窗口边界
        ResponseEntity<StrategyDtos.StrategyResponse> drafted = restTemplate.postForEntity(
                url("/api/strategies"),
                new StrategyDtos.DraftRequest("ST-1", ZONE, CROP, "VINE",
                        new BigDecimal("29.00"), new BigDecimal("21.00"), new BigDecimal("70"),
                        new BigDecimal("1.0"), new BigDecimal("5")),
                StrategyDtos.StrategyResponse.class);
        assertThat(drafted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(drafted.getBody().status()).isEqualTo("VALIDATED");

        // 下发
        ResponseEntity<StrategyDtos.StrategyResponse> dispatched = restTemplate.postForEntity(
                url("/api/strategies/ST-1/dispatch"),
                new StrategyDtos.DispatchRequest("D-1", T0), StrategyDtos.StrategyResponse.class);
        assertThat(dispatched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dispatched.getBody().status()).isEqualTo("DISPATCHED");

        // 同键异参 409
        ResponseEntity<Map> conflict = restTemplate.postForEntity(
                url("/api/strategies/ST-1/dispatch"),
                new StrategyDtos.DispatchRequest("D-1", T0.plusSeconds(60)), Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 不存在策略 404
        ResponseEntity<Map> notFound = restTemplate.getForEntity(url("/api/strategies/NOPE"), Map.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 连续 3 点偏离（白天带 [28,30]，30.5 偏离）恰在第 3 点告警
        postReading(T0, "30.50", "70");
        postReading(T0.plusSeconds(60), "30.50", "70");
        ResponseEntity<ReadingDtos.ReadingResponse> third = postReading(T0.plusSeconds(120), "30.50", "70");
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(third.getBody().alarmId()).isNotNull();

        // 预算分配与指令：加热恰用尽预算
        ResponseEntity<CommandDtos.BudgetResponse> budget = restTemplate.postForEntity(
                url("/api/energy/budgets"),
                new CommandDtos.BudgetRequest(ZONE, DAY, new BigDecimal("5.000")),
                CommandDtos.BudgetResponse.class);
        assertThat(budget.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<CommandDtos.CommandResponse> heat = restTemplate.postForEntity(
                url("/api/energy/commands"),
                new CommandDtos.CommandRequest("CMD-1", ZONE, "HEAT", new BigDecimal("5.000"), T0.plusSeconds(30)),
                CommandDtos.CommandResponse.class);
        assertThat(heat.getBody().remainingKwh()).isEqualByComparingTo("0.000");

        ResponseEntity<Map> overBudget = restTemplate.postForEntity(url("/api/energy/commands"),
                new CommandDtos.CommandRequest("CMD-2", ZONE, "HEAT", new BigDecimal("0.001"), T0.plusSeconds(40)),
                Map.class);
        assertThat(overBudget.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<CommandDtos.CommandResponse> vent = restTemplate.postForEntity(
                url("/api/energy/commands"),
                new CommandDtos.CommandRequest("CMD-3", ZONE, "VENTILATE", null, T0.plusSeconds(50)),
                CommandDtos.CommandResponse.class);
        assertThat(vent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(vent.getBody().energyKwh()).isEqualByComparingTo("0.000");

        // 告警确认
        ResponseEntity<AlarmDtos.AlarmResponse> ack = restTemplate.postForEntity(url("/api/alarms/ack"),
                new AlarmDtos.AckRequest(third.getBody().alarmId(), "ACK-1"), AlarmDtos.AlarmResponse.class);
        assertThat(ack.getBody().status()).isEqualTo("ACKNOWLEDGED");

        // 日评估封存与幂等重放
        ResponseEntity<EvaluationDtos.EvaluationResponse> eval1 = restTemplate.postForEntity(
                url("/api/evaluations"),
                new EvaluationDtos.EvaluationRequest(ZONE, DAY), EvaluationDtos.EvaluationResponse.class);
        assertThat(eval1.getBody().status()).isEqualTo("SEALED");
        assertThat(eval1.getBody().replayed()).isFalse();
        assertThat(eval1.getBody().totalCount()).isEqualTo(3);
        assertThat(eval1.getBody().consumedKwh()).isEqualByComparingTo("5.000");

        ResponseEntity<EvaluationDtos.EvaluationResponse> eval2 = restTemplate.postForEntity(
                url("/api/evaluations"),
                new EvaluationDtos.EvaluationRequest(ZONE, DAY), EvaluationDtos.EvaluationResponse.class);
        assertThat(eval2.getBody().replayed()).isTrue();
        assertThat(eval2.getBody().id()).isEqualTo(eval1.getBody().id());

        // 封存后迟到读数
        ResponseEntity<ReadingDtos.ReadingResponse> late = postReading(T0.plusSeconds(600), "5", "5");
        assertThat(late.getBody().late()).isTrue();
    }

    private ResponseEntity<ReadingDtos.ReadingResponse> postReading(Instant t, String temp, String humidity) {
        return restTemplate.postForEntity(url("/api/readings"),
                new ReadingDtos.ReadingRequest(ZONE, t, new BigDecimal(temp), new BigDecimal(humidity)),
                ReadingDtos.ReadingResponse.class);
    }
}
