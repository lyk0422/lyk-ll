package com.example.starter.emission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.HourEvaluationResponse;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReadingIngestResult;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.core.ApiException;
import com.example.starter.core.TestClockHelper;
import com.example.starter.domain.PlanStatus;
import com.example.starter.support.TestFixtures;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 读数接入与小时评估：去重、乱序归位、恰等限值达标、超标触发降掺、恰连续 3 次禁烧。
 */
class EmissionEvaluationIntegrationTest extends AbstractIntegrationTest {

    private Long seedExecutingPlan(String requestId) {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        PlanResponse declared = planService.declare(TestFixtures.declareRequest(requestId, "0.4000"));
        Long id = planService.review(declared.id(),
                new ReviewRequest(ReviewDecision.APPROVE, null)).id();
        planService.execute(id);
        // 将时钟推进到评估小时之后，保证指令下发时刻晚于被评估小时。
        clock.setTime(TestClockHelper.hour(10));
        return id;
    }

    @Test
    void readingsAreDeduplicatedByPointAndSampledAt() {
        Long planId = seedExecutingPlan("em-dedup");
        var first = emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(5), "410"));
        assertThat(first.accepted()).isEqualTo(1);
        // 同测点同采样时刻，即使数值不同也忽略。
        var duplicate = emissionService.ingest(planId, TestFixtures.readings(
                TestFixtures.reading("P1", "NOX", TestClockHelper.hour(9).plusMinutes(5), "999"),
                TestFixtures.reading("P1", "NOX", TestClockHelper.hour(9).plusMinutes(6), "420")));
        assertThat(duplicate.accepted()).isEqualTo(1);
        assertThat(duplicate.duplicated()).isEqualTo(1);
    }

    @Test
    void outOfOrderReadingsAreBucketedBySampledAt() {
        Long planId = seedExecutingPlan("em-order");
        // 乱序上报：先 09:50 后 09:10，均归位于 09 时自然小时。
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(50), "300"));
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(10), "500"));
        HourEvaluationResponse evaluation = emissionService.evaluate(planId, TestClockHelper.hour(9));
        assertThat(evaluation.pollutantAverages()).hasSize(1);
        var nox = evaluation.pollutantAverages().get(0);
        assertThat(nox.sampleCount()).isEqualTo(2);
        assertThat(nox.averageMgM3()).isEqualByComparingTo("400.000");
        // 均值恰 400（限值）判达标：不触发降掺。
        assertThat(nox.exceeded()).isFalse();
        assertThat(evaluation.exceeded()).isFalse();
        assertThat(evaluation.command()).isNull();
    }

    @Test
    void averageExactlyAtLimitPasses() {
        Long planId = seedExecutingPlan("em-eq");
        emissionService.ingest(planId, TestFixtures.readings(
                TestFixtures.reading("P1", "NOX", TestClockHelper.hour(9).plusMinutes(5), "380"),
                TestFixtures.reading("P1", "NOX", TestClockHelper.hour(9).plusMinutes(15), "420")));
        HourEvaluationResponse evaluation = emissionService.evaluate(planId, TestClockHelper.hour(9));
        assertThat(evaluation.exceeded()).isFalse();
        assertThat(evaluation.consecutiveExceeded()).isZero();
        Integer commandCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM reduction_command WHERE plan_id = ?", Integer.class, planId);
        assertThat(commandCount).isZero();
    }

    @Test
    void exceedanceTriggersReductionCommandAndStepsRatioDown() {
        Long planId = seedExecutingPlan("em-exceed");
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(20), "450"));
        HourEvaluationResponse evaluation = emissionService.evaluate(planId, TestClockHelper.hour(9));
        assertThat(evaluation.exceeded()).isTrue();
        assertThat(evaluation.consecutiveExceeded()).isEqualTo(1);
        assertThat(evaluation.command()).isNotNull();
        assertThat(evaluation.command().status()).isEqualTo("ISSUED");
        // 0.4 按步长 0.1 降至 0.3。
        assertThat(evaluation.command().targetHeatRatio()).isEqualByComparingTo("0.3000");
        assertThat(planService.get(planId).currentHeatRatio()).isEqualByComparingTo("0.3000");
    }

    @Test
    void threeConsecutiveExceededHoursBanTheDay() {
        Long planId = seedExecutingPlan("em-ban");
        for (int h = 9; h <= 11; h++) {
            emissionService.ingest(planId, TestFixtures.singleReading(
                    "P1", "NOX", TestClockHelper.hour(h).plusMinutes(20), "500"));
        }
        HourEvaluationResponse first = emissionService.evaluate(planId, TestClockHelper.hour(9));
        HourEvaluationResponse second = emissionService.evaluate(planId, TestClockHelper.hour(10));
        HourEvaluationResponse third = emissionService.evaluate(planId, TestClockHelper.hour(11));
        assertThat(first.consecutiveExceeded()).isEqualTo(1);
        assertThat(first.banned()).isFalse();
        assertThat(second.consecutiveExceeded()).isEqualTo(2);
        assertThat(second.banned()).isFalse();
        assertThat(third.consecutiveExceeded()).isEqualTo(3);
        assertThat(third.banned()).isTrue();

        assertThat(planService.get(planId).status()).isEqualTo(PlanStatus.BANNED.name());
        Integer banCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM daily_ban WHERE kiln_id = ? AND ban_date = ?",
                Integer.class, TestFixtures.KILN, LocalDate.of(2026, 9, 25));
        assertThat(banCount).isEqualTo(1);
        Integer slotCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM kiln_execution_slot WHERE kiln_id = ?",
                Integer.class, TestFixtures.KILN);
        assertThat(slotCount).isZero();
        // 禁烧后新小时评估被拒绝。
        assertThatThrownBy(() -> emissionService.evaluate(planId, TestClockHelper.hour(12)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("禁烧");
    }

    @Test
    void nonExceededHourBreaksConsecutiveCount() {
        Long planId = seedExecutingPlan("em-break");
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(20), "500"));
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(10).plusMinutes(20), "300"));
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(11).plusMinutes(20), "500"));
        emissionService.evaluate(planId, TestClockHelper.hour(9));
        emissionService.evaluate(planId, TestClockHelper.hour(10));
        HourEvaluationResponse third = emissionService.evaluate(planId, TestClockHelper.hour(11));
        // 第 10 时达标，连续计数中断，第 11 时重新从 1 起计，不禁烧。
        assertThat(third.consecutiveExceeded()).isEqualTo(1);
        assertThat(third.banned()).isFalse();
        assertThat(planService.get(planId).status()).isEqualTo(PlanStatus.EXECUTING.name());
    }

    @Test
    void repeatedEvaluationOfSameHourIsIdempotent() {
        Long planId = seedExecutingPlan("em-replay-hour");
        emissionService.ingest(planId, TestFixtures.singleReading(
                "P1", "NOX", TestClockHelper.hour(9).plusMinutes(20), "500"));
        HourEvaluationResponse first = emissionService.evaluate(planId, TestClockHelper.hour(9));
        // 即便时钟推进，重复评估同一小时回放首次结果，不重复下发指令。
        clock.advance(Duration.ofMinutes(30));
        HourEvaluationResponse replay = emissionService.evaluate(planId, TestClockHelper.hour(9));
        assertThat(replay.command().id()).isEqualTo(first.command().id());
        Integer commandCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM reduction_command WHERE plan_id = ?", Integer.class, planId);
        assertThat(commandCount).isEqualTo(1);
    }
}
