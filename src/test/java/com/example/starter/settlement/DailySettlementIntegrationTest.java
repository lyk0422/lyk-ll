package com.example.starter.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.DailySettlementResponse;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReadingIngestResult;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.core.ApiException;
import com.example.starter.core.TestClockHelper;
import com.example.starter.domain.PlanStatus;
import com.example.starter.support.TestFixtures;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 日结封存：终态幂等、封存后迟到读数只记事件、实际替代率/超标时段/降掺记录三项勾稽。
 */
class DailySettlementIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private Long seedExecutingPlan(String requestId) {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31)));
        PlanResponse declared = planService.declare(TestFixtures.declareRequest(requestId, "0.4000"));
        Long id = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null)).id();
        planService.execute(id);
        clock.setTime(TestClockHelper.hour(10));
        return id;
    }

    @Test
    void sealIsTerminalAndRepeatedSealIsIdempotent() {
        Long planId = seedExecutingPlan("seal-idem");
        DailySettlementResponse first = settlementService.seal(planId, DAY);
        assertThat(first.sealed()).isTrue();
        assertThat(planService.get(planId).status()).isEqualTo(PlanStatus.SEALED.name());

        DailySettlementResponse replay = settlementService.seal(planId, DAY);
        assertThat(replay.sealed()).isTrue();
        assertThat(replay.actualHeatRatio()).isEqualTo(first.actualHeatRatio());
        assertThat(replay.exceedancePeriods()).hasSameSizeAs(first.exceedancePeriods());
        Integer sealedCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM co_firing_plan WHERE id = ? AND status = 'SEALED'",
                Integer.class, planId);
        assertThat(sealedCount).isEqualTo(1);
    }

    @Test
    void lateReadingsAfterSealOnlyRecordEventsAndDoNotChangeSettlement() {
        Long planId = seedExecutingPlan("seal-late");
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(20), "500"));
        DailySettlementResponse beforeSeal = settlementService.seal(planId, DAY);
        int periodsBefore = beforeSeal.exceedancePeriods().size();

        // 封存后的读数（含迟到的历史小时数据）只记迟到事件。
        ReadingIngestResult ingest = emissionService.ingest(planId, TestFixtures.readings(
                TestFixtures.reading("P1", "NOX", TestClockHelper.hour(9).plusMinutes(40), "600"),
                TestFixtures.reading("P2", "SO2", TestClockHelper.hour(15), "10")));
        assertThat(ingest.accepted()).isZero();
        assertThat(ingest.late()).isEqualTo(2);
        Integer lateCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_reading_event WHERE plan_id = ?", Integer.class, planId);
        assertThat(lateCount).isEqualTo(2);
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM emission_reading WHERE plan_id = ?", Integer.class, planId);
        assertThat(readingCount).isEqualTo(1);

        DailySettlementResponse replay = settlementService.seal(planId, DAY);
        assertThat(replay.exceedancePeriods()).hasSize(periodsBefore);
        // 封存后不得再评估。
        assertThatThrownBy(() -> emissionService.evaluate(planId, TestClockHelper.hour(9)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void settlementThreeItemsAreReconcilable() {
        Long planId = seedExecutingPlan("seal-reconcile");
        // 09 时超标（生效率快照 0.4，触发降掺至 0.3），10 时达标（生效率快照 0.3）。
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(20), "500"));
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(10).plusMinutes(20), "300"));
        var hour9 = emissionService.evaluate(planId, TestClockHelper.hour(9));
        var hour10 = emissionService.evaluate(planId, TestClockHelper.hour(10));
        assertThat(hour9.exceeded()).isTrue();
        assertThat(hour10.exceeded()).isFalse();

        DailySettlementResponse settlement = settlementService.seal(planId, DAY);
        assertThat(settlement.evaluatedHours()).isEqualTo(2);
        // 实际替代率 = (0.4 + 0.3) / 2 = 0.35，可由小时生效率快照复算。
        assertThat(settlement.actualHeatRatio()).isEqualByComparingTo("0.3500");
        assertThat(settlement.exceedancePeriods()).hasSize(1);
        assertThat(settlement.exceedancePeriods().get(0).hourStart())
                .isEqualTo(TestClockHelper.hour(9).toString());
        assertThat(settlement.reductionCommands()).hasSize(1);
        assertThat(settlement.reductionCommands().get(0).id())
                .isEqualTo(hour9.command().id());
        // 超标时段数与降掺记录数一一对应。
        assertThat(settlement.exceedancePeriods()).hasSameSizeAs(settlement.reductionCommands());
    }

    @Test
    void declaredPlanCannotBeSettled() {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31)));
        PlanResponse declared = planService.declare(TestFixtures.declareRequest("seal-declared", "0.4000"));
        assertThatThrownBy(() -> settlementService.seal(declared.id(), DAY))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("日结");
    }
}
