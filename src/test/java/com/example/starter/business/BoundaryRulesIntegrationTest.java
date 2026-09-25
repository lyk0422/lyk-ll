package com.example.starter.business;

import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.dto.TemperatureReportResponse;
import com.example.starter.service.BalanceService;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 边界规则测试：温差恰 2℃、单次恰 10%、稳定恰 4 小时、24 小时恰 3 次、第 4 次转人工复核。
 */
class BoundaryRulesIntegrationTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-B";
    private static final List<String> UNITS = List.of("ST-B-U01", "ST-B-U02", "ST-B-U03");

    @Autowired
    private BalanceService balanceService;

    @Test
    void temperatureSpreadExactlyTwoDegrees_isBalanced() {
        createStation(STATION, 3);
        Instant t0 = clock.instant();
        // 回水温度 44.0 / 45.0 / 46.0：极差恰 2.0℃
        TemperatureReportResponse report = balanceService.reportTemperature(Requests.stationReadings(
                "r-temp-1", STATION, UNITS, List.of("44.000", "45.000", "46.000"), t0));
        assertThat(report.accepted()).isEqualTo(3);

        balanceService.adjust(Requests.adjust("r-adj-1", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "1")));

        EvaluationResponse judgment = balanceService.evaluate(
                Requests.evaluate("r-eval-1", STATION, "JUDGMENT"));
        assertThat(judgment.tempDiff()).isEqualByComparingTo("2.000");
        assertThat(judgment.balanced()).isTrue();
        assertThat(judgment.underFlow()).isEmpty();
        assertThat(judgment.overFlow()).isEmpty();
    }

    @Test
    void temperatureSpreadAboveTwoDegrees_isImbalanced_andClassified() {
        createStation(STATION, 3);
        balanceService.reportTemperature(Requests.stationReadings(
                "r-temp-1", STATION, UNITS, List.of("43.000", "45.000", "47.000"), clock.instant()));
        balanceService.adjust(Requests.adjust("r-adj-1", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "1")));

        EvaluationResponse judgment = balanceService.evaluate(
                Requests.evaluate("r-eval-1", STATION, "JUDGMENT"));
        assertThat(judgment.balanced()).isFalse();
        assertThat(judgment.tempDiff()).isEqualByComparingTo("4.000");
        // 回水温度低者欠流需开大，高者过流需关小
        assertThat(judgment.underFlow()).containsExactly(UNITS.get(0));
        assertThat(judgment.overFlow()).containsExactly(UNITS.get(2));
    }

    @Test
    void stepExactlyTenPercent_isLegal() {
        createStation(STATION, 3);
        AdjustResponse response = balanceService.adjust(Requests.adjust("r-adj-1", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "10.000")));
        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().get(0).delta()).isEqualByComparingTo("10.000");
        assertThat(response.steps().get(0).toOpening()).isEqualByComparingTo("60.000");
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("60.000");
    }

    @Test
    void stepAboveTenPercent_isRejected_andValveUnchanged() {
        createStation(STATION, 3);
        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("r-adj-bad", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "10.001"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10%");
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("50.000");
        // 被校验拒绝的请求未占用任务
        assertThat(count("adjustment_task")).isZero();
    }

    @Test
    void reEvalBeforeFourHours_isRejected_exactlyFourHoursAllowed() {
        createStation(STATION, 3);
        balanceService.adjust(Requests.adjust("r-adj-1", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "5")));
        balanceService.reportTemperature(Requests.stationReadings(
                "r-temp-1", STATION, UNITS, List.of("44.0", "45.0", "46.0"), clock.instant()));

        // 差 1 秒不满 4 小时：拒绝
        clock.advanceSeconds(DurationHolder.FOUR_HOURS_SECONDS - 1);
        assertThatThrownBy(() -> balanceService.evaluate(
                Requests.evaluate("r-eval-early", STATION, "RE_EVAL")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4 小时");

        // 恰满 4 小时：允许
        clock.advanceSeconds(1);
        EvaluationResponse reEval = balanceService.evaluate(
                Requests.evaluate("r-eval-ok", STATION, "RE_EVAL"));
        assertThat(reEval.balanced()).isTrue();
        assertThat(reEval.phase()).isEqualTo("RE_EVAL");
    }

    @Test
    void threeAdjustmentsWithin24HoursAllowed_fourthGoesManualReview() {
        createStation(STATION, 3);
        // 恰 3 次均合法（间隔推进，仍处于同一 24h 窗口内）
        for (int i = 1; i <= 3; i++) {
            AdjustResponse resp = balanceService.adjust(Requests.adjust("r-adj-" + i, STATION,
                    Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "2")));
            assertThat(resp.taskCreated()).isEqualTo(i == 1);
            clock.advanceSeconds(60);
        }
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("56.000");
        assertThat(count("adjustment_record")).isEqualTo(3);

        // 第 4 次：不执行，转人工复核
        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("r-adj-4", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "2"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("人工复核");
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("56.000");
        Long reviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manual_review mr JOIN heating_unit u ON u.id = mr.unit_id "
                        + "WHERE u.unit_code = ?", Long.class, UNITS.get(0));
        assertThat(reviews).isEqualTo(1);
        // 第 4 次未产生开度版本
        assertThat(count("valve_opening_version")).isEqualTo(3);
    }

    @Test
    void adjustmentAfterExactly24HoursOutsideWindow_isAllowedAgain() {
        createStation(STATION, 3);
        for (int i = 1; i <= 3; i++) {
            balanceService.adjust(Requests.adjust("r-adj-" + i, STATION,
                    Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "1")));
            clock.advanceSeconds(60);
        }
        // 距首次调节恰满 24 小时：首次记录滑出窗口，窗口内仅 2 次，可再调
        clock.setInstant(Instant.parse("2026-09-01T00:00:00Z").plusSeconds(24 * 3600));
        AdjustResponse fourth = balanceService.adjust(Requests.adjust("r-adj-window", STATION,
                Requests.step(UNITS.get(0), com.example.starter.domain.ValveAction.OPEN, "1")));
        assertThat(fourth.steps()).hasSize(1);
        assertThat(new BigDecimal("54.000").compareTo(valveOpening(STATION, UNITS.get(0)))).isZero();
    }

    private static final class DurationHolder {
        private static final long FOUR_HOURS_SECONDS = 4 * 3600;
    }
}
