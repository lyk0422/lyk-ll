package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandActionRequest;
import com.example.starter.api.dto.ExecutePlanRequest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.api.dto.SealRequest;
import com.example.starter.service.CementService;
import com.example.starter.support.TestFixtures;

/**
 * 日结三项勾稽：实际替代率可由逐小时生效比例复算；超标时段与降掺记录一一对应。
 */
class DailySummaryReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    private CementService service;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private PlanStatusResponse start(String reqId) {
        PlanResultResponse p = service.submit(TestFixtures.submit(reqId, KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));
        assertThat(p.accepted()).isTrue();
        return service.execute(new ExecutePlanRequest(p.planNo()));
    }

    private void ingestHour(String planNo, int hour, String nox) {
        service.ingestReadings(TestFixtures.readings(planNo,
                TestFixtures.reading("P1", DAY.atTime(hour, 10), nox, "100", "10"),
                TestFixtures.reading("P2", DAY.atTime(hour, 40), nox, "100", "10")));
    }

    @Test
    void actual_rate_is_recomputable_from_effective_hours_and_matches_records() {
        PlanStatusResponse plan = start("REQ-REC-1");
        ingestHour(plan.planNo(), 8, "400"); // 恰达标
        ingestHour(plan.planNo(), 9, "460"); // 超标：触发+下发+确认 → 该小时起降至保底
        service.issueCommand(new CommandActionRequest("REQ-REC-ISSUE", plan.planNo(),
                DAY.atTime(9, 0).toString()));
        service.confirmCommand(new CommandActionRequest("REQ-REC-CONF", plan.planNo(),
                DAY.atTime(9, 0).toString()));
        ingestHour(plan.planNo(), 10, "300"); // 达标但降掺仍生效
        ingestHour(plan.planNo(), 11, "300");

        var summary = service.getDailySummary(plan.planNo(), DAY);
        assertThat(summary.effectiveHours()).hasSize(4);
        // 勾稽复算：8点0.4，9/10/11点0.1 → (0.4+0.1+0.1+0.1)/4 = 0.175
        BigDecimal recomputed = summary.effectiveHours().stream()
                .map(h -> h.effectiveRate())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(4), 4, java.math.RoundingMode.HALF_UP);
        assertThat(summary.actualSubstitutionRate()).isEqualByComparingTo(recomputed);
        assertThat(summary.actualSubstitutionRate()).isEqualByComparingTo("0.1750");

        assertThat(summary.exceededPeriods()).hasSize(1);
        assertThat(summary.exceededPeriods().get(0).hourStart()).isEqualTo(DAY.atTime(9, 0));
        assertThat(summary.exceededPeriods().get(0).pollutants()).contains("NOX");
        assertThat(summary.reductionRecords()).hasSize(1);
        assertThat(summary.reductionRecords().get(0).status()).isEqualTo("CONFIRMED");
        assertThat(summary.reductionRecords().get(0).targetRate()).isEqualByComparingTo("0.1000");
    }

    @Test
    void issued_but_unconfirmed_command_does_not_reduce_actual_rate_before_escalation() {
        PlanStatusResponse plan = start("REQ-REC-2");
        ingestHour(plan.planNo(), 8, "460");
        service.issueCommand(new CommandActionRequest("REQ-REC-IS2", plan.planNo(),
                DAY.atTime(8, 0).toString()));
        var summary = service.getDailySummary(plan.planNo(), DAY);
        // 仅下发未确认/升级：实际比例仍为申报 0.4
        assertThat(summary.actualSubstitutionRate()).isEqualByComparingTo("0.4000");
        assertThat(summary.reductionRecords()).hasSize(1);
        assertThat(summary.reductionRecords().get(0).status()).isEqualTo("ISSUED");
    }

    @Test
    void sealed_snapshot_is_frozen_and_repeatable() {
        PlanStatusResponse plan = start("REQ-REC-3");
        ingestHour(plan.planNo(), 8, "460");
        service.issueCommand(new CommandActionRequest("REQ-REC-IS3", plan.planNo(),
                DAY.atTime(8, 0).toString()));

        var sealed = service.seal(new SealRequest("REQ-REC-SEAL", plan.planNo(), DAY));
        assertThat(sealed.sealed()).isTrue();
        assertThat(sealed.reductionRecords()).hasSize(1);

        // 封存快照落库可复算
        String json = jdbc.queryForObject(
                "SELECT reduction_records FROM daily_summary WHERE plan_id = ?",
                String.class, plan.planId());
        assertThat(json).contains("ISSUED");
        Boolean sealedFlag = jdbc.queryForObject(
                "SELECT sealed FROM daily_summary WHERE plan_id = ?", Boolean.class, plan.planId());
        assertThat(sealedFlag).isTrue();
    }
}
